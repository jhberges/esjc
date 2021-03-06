package com.github.msemys.esjc;

import com.github.msemys.esjc.event.Event;
import com.github.msemys.esjc.node.NodeEndpoints;
import com.github.msemys.esjc.operation.*;
import com.github.msemys.esjc.operation.manager.OperationManager;
import com.github.msemys.esjc.ssl.CommonNameTrustManagerFactory;
import com.github.msemys.esjc.subscription.MaximumSubscribersReachedException;
import com.github.msemys.esjc.subscription.PersistentSubscriptionDeletedException;
import com.github.msemys.esjc.subscription.manager.SubscriptionManager;
import com.github.msemys.esjc.tcp.TcpPackage;
import com.github.msemys.esjc.tcp.TcpPackageDecoder;
import com.github.msemys.esjc.tcp.TcpPackageEncoder;
import com.github.msemys.esjc.tcp.handler.AuthenticationHandler;
import com.github.msemys.esjc.tcp.handler.AuthenticationHandler.AuthenticationStatus;
import com.github.msemys.esjc.tcp.handler.HeartbeatHandler;
import com.github.msemys.esjc.tcp.handler.OperationHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;

import static com.github.msemys.esjc.util.Preconditions.checkNotNull;
import static com.github.msemys.esjc.util.Strings.toBytes;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

abstract class AbstractEventStore {
    private static final int MAX_FRAME_LENGTH = 64 * 1024 * 1024;

    protected enum ConnectionState {INIT, CONNECTING, CONNECTED, CLOSED}

    protected enum ConnectingPhase {INVALID, RECONNECTING, ENDPOINT_DISCOVERY, CONNECTION_ESTABLISHING, AUTHENTICATION, CONNECTED}

    protected final EventLoopGroup group = new NioEventLoopGroup(0, new DefaultThreadFactory("esio"));
    protected final Bootstrap bootstrap;
    protected final OperationManager operationManager;
    protected final SubscriptionManager subscriptionManager;
    public final Settings settings;

    protected volatile Channel connection;
    protected volatile ConnectingPhase connectingPhase = ConnectingPhase.INVALID;

    private final Set<EventStoreListener> listeners = new CopyOnWriteArraySet<>();

    protected AbstractEventStore(Settings settings) {
        checkNotNull(settings, "settings");

        bootstrap = new Bootstrap()
            .option(ChannelOption.SO_KEEPALIVE, settings.tcpSettings.keepAlive)
            .option(ChannelOption.TCP_NODELAY, settings.tcpSettings.tcpNoDelay)
            .option(ChannelOption.SO_SNDBUF, settings.tcpSettings.sendBufferSize)
            .option(ChannelOption.SO_RCVBUF, settings.tcpSettings.receiveBufferSize)
            .option(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, settings.tcpSettings.writeBufferLowWaterMark)
            .option(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, settings.tcpSettings.writeBufferHighWaterMark)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) settings.tcpSettings.connectTimeout.toMillis())
            .group(group)
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) throws Exception {
                    ChannelPipeline pipeline = ch.pipeline();

                    if (settings.sslSettings.useSslConnection) {
                        SslContext sslContext = SslContextBuilder.forClient()
                            .trustManager(settings.sslSettings.validateServerCertificate ?
                                new CommonNameTrustManagerFactory(settings.sslSettings.certificateCommonName) :
                                InsecureTrustManagerFactory.INSTANCE)
                            .build();
                        pipeline.addLast("ssl", sslContext.newHandler(ch.alloc()));
                    }

                    // decoder
                    pipeline.addLast("frame-decoder", new LengthFieldBasedFrameDecoder(LITTLE_ENDIAN, MAX_FRAME_LENGTH, 0, 4, 0, 4, true));
                    pipeline.addLast("package-decoder", new TcpPackageDecoder());

                    // encoder
                    pipeline.addLast("frame-encoder", new LengthFieldPrepender(LITTLE_ENDIAN, 4, 0, false));
                    pipeline.addLast("package-encoder", new TcpPackageEncoder());

                    // logic
                    pipeline.addLast("idle-state-handler", new IdleStateHandler(0, settings.heartbeatInterval.toMillis(), 0, MILLISECONDS));
                    pipeline.addLast("heartbeat-handler", new HeartbeatHandler(settings.heartbeatTimeout));
                    pipeline.addLast("authentication-handler", new AuthenticationHandler(settings.userCredentials, settings.operationTimeout)
                        .whenComplete(AbstractEventStore.this::onAuthenticationCompleted));
                    pipeline.addLast("operation-handler", new OperationHandler(operationManager, subscriptionManager)
                        .whenBadRequest(AbstractEventStore.this::onBadRequest)
                        .whenChannelError(AbstractEventStore.this::onChannelError)
                        .whenReconnect(AbstractEventStore.this::onReconnect));
                }
            });

        operationManager = new OperationManager(settings);
        subscriptionManager = new SubscriptionManager(settings);

        this.settings = settings;
    }

    /**
     * Deletes a stream from the Event Store asynchronously using soft-deletion mode and default user credentials.
     *
     * @param stream          the name of the stream to delete.
     * @param expectedVersion the expected version that the streams should have when being deleted.
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link WrongExpectedVersionException},
     * {@link StreamDeletedException}, {@link InvalidTransactionException}, {@link CommandNotExpectedException},
     * {@link NotAuthenticatedException}, {@link AccessDeniedException} or {@link ServerErrorException}
     * on exceptional completion.
     * @see AbstractEventStore#deleteStream(String, ExpectedVersion, boolean, UserCredentials)
     */
    public CompletableFuture<DeleteResult> deleteStream(String stream,
                                                        ExpectedVersion expectedVersion) {
        return deleteStream(stream, expectedVersion, false, null);
    }

    /**
     * Deletes a stream from the Event Store asynchronously using soft-deletion mode.
     *
     * @param stream          the name of the stream to delete.
     * @param expectedVersion the expected version that the streams should have when being deleted.
     * @param userCredentials user credentials to be used for this operation (use {@code null} for default user credentials).
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link WrongExpectedVersionException},
     * {@link StreamDeletedException}, {@link InvalidTransactionException}, {@link CommandNotExpectedException},
     * {@link NotAuthenticatedException}, {@link AccessDeniedException} or {@link ServerErrorException}
     * on exceptional completion.
     * @see AbstractEventStore#deleteStream(String, ExpectedVersion, boolean, UserCredentials)
     */
    public CompletableFuture<DeleteResult> deleteStream(String stream,
                                                        ExpectedVersion expectedVersion,
                                                        UserCredentials userCredentials) {
        return deleteStream(stream, expectedVersion, false, userCredentials);
    }

    /**
     * Deletes a stream from the Event Store asynchronously using default user credentials. There are two available deletion modes:
     * <ul>
     * <li>hard delete - streams can never be recreated</li>
     * <li>soft delete - streams can be written to again, but the event number sequence will not start from 0</li>
     * </ul>
     *
     * @param stream          the name of the stream to delete.
     * @param expectedVersion the expected version that the streams should have when being deleted.
     * @param hardDelete      use {@code true} for "hard delete" or {@code false} for "soft delete" mode.
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link WrongExpectedVersionException},
     * {@link StreamDeletedException}, {@link InvalidTransactionException}, {@link CommandNotExpectedException},
     * {@link NotAuthenticatedException}, {@link AccessDeniedException} or {@link ServerErrorException}
     * on exceptional completion.
     * @see AbstractEventStore#deleteStream(String, ExpectedVersion, boolean, UserCredentials)
     */
    public CompletableFuture<DeleteResult> deleteStream(String stream,
                                                        ExpectedVersion expectedVersion,
                                                        boolean hardDelete) {
        return deleteStream(stream, expectedVersion, hardDelete, null);
    }

    /**
     * Deletes a stream from the Event Store asynchronously. There are two available deletion modes:
     * <ul>
     * <li>hard delete - streams can never be recreated</li>
     * <li>soft delete - streams can be written to again, but the event number sequence will not start from 0</li>
     * </ul>
     *
     * @param stream          the name of the stream to delete.
     * @param expectedVersion the expected version that the streams should have when being deleted.
     * @param hardDelete      use {@code true} for "hard delete" or {@code false} for "soft delete" mode.
     * @param userCredentials user credentials to be used for this operation (use {@code null} for default user credentials).
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link WrongExpectedVersionException},
     * {@link StreamDeletedException}, {@link InvalidTransactionException}, {@link CommandNotExpectedException},
     * {@link NotAuthenticatedException}, {@link AccessDeniedException} or {@link ServerErrorException}
     * on exceptional completion.
     */
    public abstract CompletableFuture<DeleteResult> deleteStream(String stream,
                                                                 ExpectedVersion expectedVersion,
                                                                 boolean hardDelete,
                                                                 UserCredentials userCredentials);

    /**
     * Appends events to a stream asynchronously using default user credentials.
     *
     * @param stream          the name of the stream to append events to.
     * @param expectedVersion the version at which we currently expect the stream to be, in order that an optimistic concurrency check can be performed.
     * @param events          the events to append.
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link WrongExpectedVersionException},
     * {@link StreamDeletedException}, {@link InvalidTransactionException}, {@link CommandNotExpectedException},
     * {@link NotAuthenticatedException}, {@link AccessDeniedException} or {@link ServerErrorException}
     * on exceptional completion.
     * @see #appendToStream(String, ExpectedVersion, Iterable, UserCredentials)
     */
    public CompletableFuture<WriteResult> appendToStream(String stream,
                                                         ExpectedVersion expectedVersion,
                                                         Iterable<EventData> events) {
        return appendToStream(stream, expectedVersion, events, null);
    }

    /**
     * Appends events to a stream asynchronously.
     *
     * @param stream          the name of the stream to append events to.
     * @param expectedVersion the version at which we currently expect the stream to be, in order that an optimistic concurrency check can be performed.
     * @param events          the events to append.
     * @param userCredentials user credentials to be used for this operation (use {@code null} for default user credentials).
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link WrongExpectedVersionException},
     * {@link StreamDeletedException}, {@link InvalidTransactionException}, {@link CommandNotExpectedException},
     * {@link NotAuthenticatedException}, {@link AccessDeniedException} or {@link ServerErrorException}
     * on exceptional completion.
     */
    public abstract CompletableFuture<WriteResult> appendToStream(String stream,
                                                                  ExpectedVersion expectedVersion,
                                                                  Iterable<EventData> events,
                                                                  UserCredentials userCredentials);

    /**
     * Starts a transaction in the Event Store on a given stream asynchronously using default user credentials.
     *
     * @param stream          the stream to start a transaction on.
     * @param expectedVersion the expected version of the stream at the time of starting the transaction.
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link WrongExpectedVersionException},
     * {@link StreamDeletedException}, {@link InvalidTransactionException}, {@link CommandNotExpectedException},
     * {@link NotAuthenticatedException}, {@link AccessDeniedException} or {@link ServerErrorException}
     * on exceptional completion.
     * @see #startTransaction(String, ExpectedVersion, UserCredentials)
     */
    public CompletableFuture<Transaction> startTransaction(String stream,
                                                           ExpectedVersion expectedVersion) {
        return startTransaction(stream, expectedVersion, null);
    }

    /**
     * Starts a transaction in the Event Store on a given stream asynchronously.
     *
     * @param stream          the stream to start a transaction on.
     * @param expectedVersion the expected version of the stream at the time of starting the transaction.
     * @param userCredentials user credentials to be used for this operation (use {@code null} for default user credentials).
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link WrongExpectedVersionException},
     * {@link StreamDeletedException}, {@link InvalidTransactionException}, {@link CommandNotExpectedException},
     * {@link NotAuthenticatedException}, {@link AccessDeniedException} or {@link ServerErrorException}
     * on exceptional completion.
     */
    public abstract CompletableFuture<Transaction> startTransaction(String stream,
                                                                    ExpectedVersion expectedVersion,
                                                                    UserCredentials userCredentials);

    /**
     * Continues transaction by the specified transaction ID using default user credentials.
     *
     * @param transactionId the transaction ID that needs to be continued.
     * @return transaction
     * @see #continueTransaction(long, UserCredentials)
     */
    public Transaction continueTransaction(long transactionId) {
        return continueTransaction(transactionId, null);
    }

    /**
     * Continues transaction by the specified transaction ID.
     *
     * @param transactionId   the transaction ID that needs to be continued.
     * @param userCredentials user credentials to be used for this operation (use {@code null} for default user credentials).
     * @return transaction
     */
    public abstract Transaction continueTransaction(long transactionId,
                                                    UserCredentials userCredentials);

    /**
     * Reads a single event from a stream asynchronously using default user credentials.
     *
     * @param stream         the stream to read from.
     * @param eventNumber    the event number to read (use {@link StreamPosition#END} to read the last event in the stream).
     * @param resolveLinkTos whether to resolve link events automatically.
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link CommandNotExpectedException},
     * {@link NotAuthenticatedException}, {@link AccessDeniedException} or {@link ServerErrorException}
     * on exceptional completion.
     * @see #readEvent(String, int, boolean, UserCredentials)
     */
    public CompletableFuture<EventReadResult> readEvent(String stream,
                                                        int eventNumber,
                                                        boolean resolveLinkTos) {
        return readEvent(stream, eventNumber, resolveLinkTos, null);
    }

    /**
     * Reads a single event from a stream asynchronously.
     *
     * @param stream          the stream to read from.
     * @param eventNumber     the event number to read (use {@link StreamPosition#END} to read the last event in the stream).
     * @param resolveLinkTos  whether to resolve link events automatically.
     * @param userCredentials user credentials to be used for this operation (use {@code null} for default user credentials).
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link CommandNotExpectedException},
     * {@link NotAuthenticatedException}, {@link AccessDeniedException} or {@link ServerErrorException}
     * on exceptional completion.
     */
    public abstract CompletableFuture<EventReadResult> readEvent(String stream,
                                                                 int eventNumber,
                                                                 boolean resolveLinkTos,
                                                                 UserCredentials userCredentials);

    /**
     * Reads count events from a stream forwards (e.g. oldest to newest) starting from the
     * specified start position asynchronously using default user credentials.
     *
     * @param stream         the stream to read from.
     * @param start          the starting point to read from.
     * @param count          the count of events to read.
     * @param resolveLinkTos whether to resolve link events automatically.
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link CommandNotExpectedException},
     * {@link NotAuthenticatedException}, {@link AccessDeniedException} or {@link ServerErrorException}
     * on exceptional completion.
     * @see #readStreamEventsForward(String, int, int, boolean, UserCredentials)
     */
    public CompletableFuture<StreamEventsSlice> readStreamEventsForward(String stream,
                                                                        int start,
                                                                        int count,
                                                                        boolean resolveLinkTos) {
        return readStreamEventsForward(stream, start, count, resolveLinkTos, null);
    }

    /**
     * Reads count events from a stream forwards (e.g. oldest to newest) starting from the
     * specified start position asynchronously.
     *
     * @param stream          the stream to read from.
     * @param start           the starting point to read from.
     * @param count           the count of events to read.
     * @param resolveLinkTos  whether to resolve link events automatically.
     * @param userCredentials user credentials to be used for this operation (use {@code null} for default user credentials).
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link CommandNotExpectedException},
     * {@link NotAuthenticatedException}, {@link AccessDeniedException} or {@link ServerErrorException}
     * on exceptional completion.
     */
    public abstract CompletableFuture<StreamEventsSlice> readStreamEventsForward(String stream,
                                                                                 int start,
                                                                                 int count,
                                                                                 boolean resolveLinkTos,
                                                                                 UserCredentials userCredentials);

    /**
     * Reads count events from a stream backwards (e.g. newest to oldest) from the
     * specified start position asynchronously using default user credentials.
     *
     * @param stream         the stream to read from.
     * @param start          the starting point to read from.
     * @param count          the count of events to read.
     * @param resolveLinkTos whether to resolve link events automatically.
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link CommandNotExpectedException},
     * {@link NotAuthenticatedException}, {@link AccessDeniedException} or {@link ServerErrorException}
     * on exceptional completion.
     * @see #readStreamEventsBackward(String, int, int, boolean, UserCredentials)
     */
    public CompletableFuture<StreamEventsSlice> readStreamEventsBackward(String stream,
                                                                         int start,
                                                                         int count,
                                                                         boolean resolveLinkTos) {
        return readStreamEventsBackward(stream, start, count, resolveLinkTos, null);
    }

    /**
     * Reads count events from a stream backwards (e.g. newest to oldest) from the
     * specified start position asynchronously.
     *
     * @param stream          the stream to read from.
     * @param start           the starting point to read from.
     * @param count           the count of events to read.
     * @param resolveLinkTos  whether to resolve link events automatically.
     * @param userCredentials user credentials to be used for this operation (use {@code null} for default user credentials).
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link CommandNotExpectedException},
     * {@link NotAuthenticatedException}, {@link AccessDeniedException} or {@link ServerErrorException}
     * on exceptional completion.
     */
    public abstract CompletableFuture<StreamEventsSlice> readStreamEventsBackward(String stream,
                                                                                  int start,
                                                                                  int count,
                                                                                  boolean resolveLinkTos,
                                                                                  UserCredentials userCredentials);

    /**
     * Reads all events in the node forward (e.g. beginning to end) asynchronously using default user credentials.
     *
     * @param position       the position to start reading from.
     * @param maxCount       the maximum count to read.
     * @param resolveLinkTos whether to resolve link events automatically.
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link CommandNotExpectedException},
     * {@link NotAuthenticatedException}, {@link AccessDeniedException} or {@link ServerErrorException}
     * on exceptional completion.
     * @see #readAllEventsForward(Position, int, boolean, UserCredentials)
     */
    public CompletableFuture<AllEventsSlice> readAllEventsForward(Position position,
                                                                  int maxCount,
                                                                  boolean resolveLinkTos) {
        return readAllEventsForward(position, maxCount, resolveLinkTos, null);
    }

    /**
     * Reads all events in the node forward (e.g. beginning to end) asynchronously.
     *
     * @param position        the position to start reading from.
     * @param maxCount        the maximum count to read.
     * @param resolveLinkTos  whether to resolve link events automatically.
     * @param userCredentials user credentials to be used for this operation (use {@code null} for default user credentials).
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link CommandNotExpectedException},
     * {@link NotAuthenticatedException}, {@link AccessDeniedException} or {@link ServerErrorException}
     * on exceptional completion.
     */
    public abstract CompletableFuture<AllEventsSlice> readAllEventsForward(Position position,
                                                                           int maxCount,
                                                                           boolean resolveLinkTos,
                                                                           UserCredentials userCredentials);

    /**
     * Reads all events in the node backwards (e.g. end to beginning) asynchronously using default user credentials.
     *
     * @param position       the position to start reading from.
     * @param maxCount       the maximum count to read.
     * @param resolveLinkTos whether to resolve link events automatically.
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link CommandNotExpectedException},
     * {@link NotAuthenticatedException}, {@link AccessDeniedException} or {@link ServerErrorException}
     * on exceptional completion.
     * @see #readAllEventsBackward(Position, int, boolean, UserCredentials)
     */
    public CompletableFuture<AllEventsSlice> readAllEventsBackward(Position position,
                                                                   int maxCount,
                                                                   boolean resolveLinkTos) {
        return readAllEventsBackward(position, maxCount, resolveLinkTos, null);
    }

    /**
     * Reads all events in the node backwards (e.g. end to beginning) asynchronously.
     *
     * @param position        the position to start reading from.
     * @param maxCount        the maximum count to read.
     * @param resolveLinkTos  whether to resolve link events automatically.
     * @param userCredentials user credentials to be used for this operation (use {@code null} for default user credentials).
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link CommandNotExpectedException},
     * {@link NotAuthenticatedException}, {@link AccessDeniedException} or {@link ServerErrorException}
     * on exceptional completion.
     */
    public abstract CompletableFuture<AllEventsSlice> readAllEventsBackward(Position position,
                                                                            int maxCount,
                                                                            boolean resolveLinkTos,
                                                                            UserCredentials userCredentials);

    /**
     * Subscribes to a stream asynchronously using default user credentials. New events written to the stream
     * while the subscription is active will be pushed to the client.
     *
     * @param stream         the stream to subscribe to.
     * @param resolveLinkTos whether to resolve link events automatically.
     * @param listener       subscription listener.
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link IllegalArgumentException},
     * {@link CommandNotExpectedException}, {@link NotAuthenticatedException}, {@link AccessDeniedException}
     * or {@link ServerErrorException} on exceptional completion.
     * @see #subscribeToStream(String, boolean, VolatileSubscriptionListener, UserCredentials)
     */
    public CompletableFuture<Subscription> subscribeToStream(String stream,
                                                             boolean resolveLinkTos,
                                                             VolatileSubscriptionListener listener) {
        return subscribeToStream(stream, resolveLinkTos, listener, null);
    }

    /**
     * Subscribes to a stream asynchronously. New events written to the stream while the subscription is active
     * will be pushed to the client.
     *
     * @param stream          the stream to subscribe to.
     * @param resolveLinkTos  whether to resolve link events automatically.
     * @param listener        subscription listener.
     * @param userCredentials user credentials to be used for this operation (use {@code null} for default user credentials).
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link IllegalArgumentException},
     * {@link CommandNotExpectedException}, {@link NotAuthenticatedException}, {@link AccessDeniedException}
     * or {@link ServerErrorException} on exceptional completion.
     */
    public abstract CompletableFuture<Subscription> subscribeToStream(String stream,
                                                                      boolean resolveLinkTos,
                                                                      VolatileSubscriptionListener listener,
                                                                      UserCredentials userCredentials);

    /**
     * Subscribes to the $all stream asynchronously using default user credentials. New events written to the stream
     * while the subscription is active will be pushed to the client.
     *
     * @param resolveLinkTos whether to resolve link events automatically.
     * @param listener       subscription listener.
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link IllegalArgumentException},
     * {@link CommandNotExpectedException}, {@link NotAuthenticatedException}, {@link AccessDeniedException}
     * or {@link ServerErrorException} on exceptional completion.
     * @see #subscribeToAll(boolean, VolatileSubscriptionListener, UserCredentials)
     */
    public CompletableFuture<Subscription> subscribeToAll(boolean resolveLinkTos,
                                                          VolatileSubscriptionListener listener) {
        return subscribeToAll(resolveLinkTos, listener, null);
    }

    /**
     * Subscribes to the $all stream asynchronously. New events written to the stream while the subscription is active
     * will be pushed to the client.
     *
     * @param resolveLinkTos  whether to resolve link events automatically.
     * @param listener        subscription listener.
     * @param userCredentials user credentials to be used for this operation (use {@code null} for default user credentials).
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link IllegalArgumentException},
     * {@link CommandNotExpectedException}, {@link NotAuthenticatedException}, {@link AccessDeniedException}
     * or {@link ServerErrorException} on exceptional completion.
     */
    public abstract CompletableFuture<Subscription> subscribeToAll(boolean resolveLinkTos,
                                                                   VolatileSubscriptionListener listener,
                                                                   UserCredentials userCredentials);

    /**
     * Subscribes to a stream from the specified event number (exclusive) asynchronously using default user credentials.
     * Existing events from {@code fromEventNumberExclusive} onwards are read from the stream and presented to the user
     * by invoking subscription listener {@code .onEvent()} method as if they had been pushed.
     * Once the end of the stream is read, the subscription is transparently (to the user)
     * switched to push new events as they are written.
     * <p>
     * If events have already been received and resubscription from the same point is desired,
     * use the event number of the last event processed which appeared on the subscription.
     * </p>
     * <p>
     * <u>NOTE</u>: using {@link StreamPosition#START} for {@code fromEventNumberExclusive} will result in missing
     * the first event in the stream.
     * </p>
     *
     * @param stream                   the stream to subscribe to.
     * @param fromEventNumberExclusive the event number (exclusive) from which to start (use {@code null} to receive all events).
     * @param settings                 subscription settings.
     * @param listener                 subscription listener.
     * @return catch-up subscription
     * @see #subscribeToStreamFrom(String, Integer, CatchUpSubscriptionSettings, CatchUpSubscriptionListener, UserCredentials)
     */
    public CatchUpSubscription subscribeToStreamFrom(String stream,
                                                     Integer fromEventNumberExclusive,
                                                     CatchUpSubscriptionSettings settings,
                                                     CatchUpSubscriptionListener listener) {
        return subscribeToStreamFrom(stream, fromEventNumberExclusive, settings, listener, null);
    }

    /**
     * Subscribes to a stream from the specified event number (exclusive) asynchronously
     * using default catch-up subscription settings and default user credentials.
     * Existing events from {@code fromEventNumberExclusive} onwards are read from the stream and presented to the user
     * by invoking subscription listener {@code .onEvent()} method as if they had been pushed.
     * Once the end of the stream is read, the subscription is transparently (to the user)
     * switched to push new events as they are written.
     * <p>
     * If events have already been received and resubscription from the same point is desired,
     * use the event number of the last event processed which appeared on the subscription.
     * </p>
     * <p>
     * <u>NOTE</u>: using {@link StreamPosition#START} for {@code fromEventNumberExclusive} will result in missing
     * the first event in the stream.
     * </p>
     *
     * @param stream                   the stream to subscribe to.
     * @param fromEventNumberExclusive the event number (exclusive) from which to start (use {@code null} to receive all events).
     * @param listener                 subscription listener.
     * @return catch-up subscription
     * @see #subscribeToStreamFrom(String, Integer, CatchUpSubscriptionSettings, CatchUpSubscriptionListener, UserCredentials)
     */
    public CatchUpSubscription subscribeToStreamFrom(String stream,
                                                     Integer fromEventNumberExclusive,
                                                     CatchUpSubscriptionListener listener) {
        return subscribeToStreamFrom(stream, fromEventNumberExclusive, CatchUpSubscriptionSettings.DEFAULT, listener, null);
    }

    /**
     * Subscribes to a stream from the specified event number (exclusive) asynchronously using default catch-up subscription settings.
     * Existing events from {@code fromEventNumberExclusive} onwards are read from the stream and presented to the user
     * by invoking subscription listener {@code .onEvent()} method as if they had been pushed.
     * Once the end of the stream is read, the subscription is transparently (to the user)
     * switched to push new events as they are written.
     * <p>
     * If events have already been received and resubscription from the same point is desired,
     * use the event number of the last event processed which appeared on the subscription.
     * </p>
     * <p>
     * <u>NOTE</u>: using {@link StreamPosition#START} for {@code fromEventNumberExclusive} will result in missing
     * the first event in the stream.
     * </p>
     *
     * @param stream                   the stream to subscribe to.
     * @param fromEventNumberExclusive the event number (exclusive) from which to start (use {@code null} to receive all events).
     * @param listener                 subscription listener.
     * @param userCredentials          user credentials to be used for this operation (use {@code null} for default user credentials).
     * @return catch-up subscription
     * @see #subscribeToStreamFrom(String, Integer, CatchUpSubscriptionSettings, CatchUpSubscriptionListener, UserCredentials)
     */
    public CatchUpSubscription subscribeToStreamFrom(String stream,
                                                     Integer fromEventNumberExclusive,
                                                     CatchUpSubscriptionListener listener,
                                                     UserCredentials userCredentials) {
        return subscribeToStreamFrom(stream, fromEventNumberExclusive, CatchUpSubscriptionSettings.DEFAULT, listener, userCredentials);
    }

    /**
     * Subscribes to a stream from the specified event number (exclusive) asynchronously.
     * Existing events from {@code fromEventNumberExclusive} onwards are read from the stream and presented to the user
     * by invoking subscription listener {@code .onEvent()} method as if they had been pushed.
     * Once the end of the stream is read, the subscription is transparently (to the user)
     * switched to push new events as they are written.
     * <p>
     * If events have already been received and resubscription from the same point is desired,
     * use the event number of the last event processed which appeared on the subscription.
     * </p>
     * <p>
     * <u>NOTE</u>: using {@link StreamPosition#START} for {@code fromEventNumberExclusive} will result in missing
     * the first event in the stream.
     * </p>
     *
     * @param stream                   the stream to subscribe to.
     * @param fromEventNumberExclusive the event number (exclusive) from which to start (use {@code null} to receive all events).
     * @param settings                 subscription settings.
     * @param listener                 subscription listener.
     * @param userCredentials          user credentials to be used for this operation (use {@code null} for default user credentials).
     * @return catch-up subscription
     */
    public abstract CatchUpSubscription subscribeToStreamFrom(String stream,
                                                              Integer fromEventNumberExclusive,
                                                              CatchUpSubscriptionSettings settings,
                                                              CatchUpSubscriptionListener listener,
                                                              UserCredentials userCredentials);


    /**
     * Subscribes to the $all stream from the specified event position (exclusive) asynchronously using
     * default catch-up subscription settings and default user credentials.
     * Existing events from {@code fromPositionExclusive} onwards are read from the stream and presented to the user
     * by invoking subscription listener {@code .onEvent()} method as if they had been pushed.
     * Once the end of the stream is read, the subscription is transparently (to the user)
     * switched to push new events as they are written.
     * <p>
     * If events have already been received and resubscription from the same point is desired,
     * use the position representing the last event processed which appeared on the subscription.
     * </p>
     * <p>
     * <u>NOTE</u>: using {@link Position#START} for {@code fromPositionExclusive} will result in missing
     * the first event in the stream.
     * </p>
     *
     * @param fromPositionExclusive the position (exclusive) from which to start (use {@code null} to receive all events).
     * @param listener              subscription listener.
     * @return catch-up subscription
     * @see AbstractEventStore#subscribeToAllFrom(Position, CatchUpSubscriptionSettings, CatchUpSubscriptionListener, UserCredentials)
     */
    public CatchUpSubscription subscribeToAllFrom(Position fromPositionExclusive,
                                                  CatchUpSubscriptionListener listener) {
        return subscribeToAllFrom(fromPositionExclusive, CatchUpSubscriptionSettings.DEFAULT, listener, null);
    }

    /**
     * Subscribes to the $all stream from the specified event position (exclusive) asynchronously using default user credentials.
     * Existing events from {@code fromPositionExclusive} onwards are read from the stream and presented to the user
     * by invoking subscription listener {@code .onEvent()} method as if they had been pushed.
     * Once the end of the stream is read, the subscription is transparently (to the user)
     * switched to push new events as they are written.
     * <p>
     * If events have already been received and resubscription from the same point is desired,
     * use the position representing the last event processed which appeared on the subscription.
     * </p>
     * <p>
     * <u>NOTE</u>: using {@link Position#START} for {@code fromPositionExclusive} will result in missing
     * the first event in the stream.
     * </p>
     *
     * @param fromPositionExclusive the position (exclusive) from which to start (use {@code null} to receive all events).
     * @param settings              subscription settings.
     * @param listener              subscription listener.
     * @return catch-up subscription
     * @see AbstractEventStore#subscribeToAllFrom(Position, CatchUpSubscriptionSettings, CatchUpSubscriptionListener, UserCredentials)
     */
    public CatchUpSubscription subscribeToAllFrom(Position fromPositionExclusive,
                                                  CatchUpSubscriptionSettings settings,
                                                  CatchUpSubscriptionListener listener) {
        return subscribeToAllFrom(fromPositionExclusive, settings, listener, null);
    }

    /**
     * Subscribes to the $all stream from the specified event position (exclusive) asynchronously
     * using default catch-up subscription settings.
     * Existing events from {@code fromPositionExclusive} onwards are read from the stream and presented to the user
     * by invoking subscription listener {@code .onEvent()} method as if they had been pushed.
     * Once the end of the stream is read, the subscription is transparently (to the user)
     * switched to push new events as they are written.
     * <p>
     * If events have already been received and resubscription from the same point is desired,
     * use the position representing the last event processed which appeared on the subscription.
     * </p>
     * <p>
     * <u>NOTE</u>: using {@link Position#START} for {@code fromPositionExclusive} will result in missing
     * the first event in the stream.
     * </p>
     *
     * @param fromPositionExclusive the position (exclusive) from which to start (use {@code null} to receive all events).
     * @param listener              subscription listener.
     * @param userCredentials       user credentials to be used for this operation (use {@code null} for default user credentials).
     * @return catch-up subscription
     * @see AbstractEventStore#subscribeToAllFrom(Position, CatchUpSubscriptionSettings, CatchUpSubscriptionListener, UserCredentials)
     */
    public CatchUpSubscription subscribeToAllFrom(Position fromPositionExclusive,
                                                  CatchUpSubscriptionListener listener,
                                                  UserCredentials userCredentials) {
        return subscribeToAllFrom(fromPositionExclusive, CatchUpSubscriptionSettings.DEFAULT, listener, userCredentials);
    }

    /**
     * Subscribes to the $all stream from the specified event position (exclusive) asynchronously.
     * Existing events from {@code fromPositionExclusive} onwards are read from the stream and presented to the user
     * by invoking subscription listener {@code .onEvent()} method as if they had been pushed.
     * Once the end of the stream is read, the subscription is transparently (to the user)
     * switched to push new events as they are written.
     * <p>
     * If events have already been received and resubscription from the same point is desired,
     * use the position representing the last event processed which appeared on the subscription.
     * </p>
     * <p>
     * <u>NOTE</u>: using {@link Position#START} for {@code fromPositionExclusive} will result in missing
     * the first event in the stream.
     * </p>
     *
     * @param fromPositionExclusive the position (exclusive) from which to start (use {@code null} to receive all events).
     * @param settings              subscription settings.
     * @param listener              subscription listener.
     * @param userCredentials       user credentials to be used for this operation (use {@code null} for default user credentials).
     * @return catch-up subscription
     */
    public abstract CatchUpSubscription subscribeToAllFrom(Position fromPositionExclusive,
                                                           CatchUpSubscriptionSettings settings,
                                                           CatchUpSubscriptionListener listener,
                                                           UserCredentials userCredentials);

    /**
     * Subscribes to a persistent subscription asynchronously using default buffer size, auto-ack setting and default user credentials.
     * <p>
     * This will connect you to a persistent subscription group for a stream. The subscription group must first be created.
     * Many connections can connect to the same group and they will be treated as competing consumers within the group.
     * If one connection dies, work will be balanced across the rest of the consumers in the group.
     * If you attempt to connect to a group that does not exist you will be given an exception.
     * </p>
     * <p>
     * When auto-ack is disabled, the receiver is required to explicitly acknowledge messages through the subscription.
     * </p>
     *
     * @param stream    the stream to subscribe to.
     * @param groupName the subscription group to connect to.
     * @param listener  subscription listener.
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link IllegalArgumentException},
     * {@link PersistentSubscriptionDeletedException}, {@link MaximumSubscribersReachedException},
     * {@link CommandNotExpectedException}, {@link NotAuthenticatedException}, {@link AccessDeniedException}
     * or {@link ServerErrorException} on exceptional completion.
     * @see #subscribeToPersistent(String, String, PersistentSubscriptionListener, UserCredentials, int, boolean)
     */
    public CompletableFuture<PersistentSubscription> subscribeToPersistent(String stream,
                                                                           String groupName,
                                                                           PersistentSubscriptionListener listener) {
        return subscribeToPersistent(stream, groupName, listener, null, settings.persistentSubscriptionBufferSize, settings.persistentSubscriptionAutoAckEnabled);
    }

    /**
     * Subscribes to a persistent subscription asynchronously using default buffer size and auto-ack setting.
     * <p>
     * This will connect you to a persistent subscription group for a stream. The subscription group must first be created.
     * Many connections can connect to the same group and they will be treated as competing consumers within the group.
     * If one connection dies, work will be balanced across the rest of the consumers in the group.
     * If you attempt to connect to a group that does not exist you will be given an exception.
     * </p>
     * <p>
     * When auto-ack is disabled, the receiver is required to explicitly acknowledge messages through the subscription.
     * </p>
     *
     * @param stream          the stream to subscribe to.
     * @param groupName       the subscription group to connect to.
     * @param listener        subscription listener.
     * @param userCredentials user credentials to be used for this operation (use {@code null} for default user credentials).
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link IllegalArgumentException},
     * {@link PersistentSubscriptionDeletedException}, {@link MaximumSubscribersReachedException},
     * {@link CommandNotExpectedException}, {@link NotAuthenticatedException}, {@link AccessDeniedException}
     * or {@link ServerErrorException} on exceptional completion.
     * @see #subscribeToPersistent(String, String, PersistentSubscriptionListener, UserCredentials, int, boolean)
     */
    public CompletableFuture<PersistentSubscription> subscribeToPersistent(String stream,
                                                                           String groupName,
                                                                           PersistentSubscriptionListener listener,
                                                                           UserCredentials userCredentials) {
        return subscribeToPersistent(stream, groupName, listener, userCredentials, settings.persistentSubscriptionBufferSize, settings.persistentSubscriptionAutoAckEnabled);
    }

    /**
     * Subscribes to a persistent subscription asynchronously using default user credentials.
     * <p>
     * This will connect you to a persistent subscription group for a stream. The subscription group must first be created.
     * Many connections can connect to the same group and they will be treated as competing consumers within the group.
     * If one connection dies, work will be balanced across the rest of the consumers in the group.
     * If you attempt to connect to a group that does not exist you will be given an exception.
     * </p>
     * <p>
     * When auto-ack is disabled, the receiver is required to explicitly acknowledge messages through the subscription.
     * </p>
     *
     * @param stream     the stream to subscribe to.
     * @param groupName  the subscription group to connect to.
     * @param listener   subscription listener.
     * @param bufferSize the buffer size to use for the persistent subscription.
     * @param autoAck    whether the subscription should automatically acknowledge messages processed.
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link IllegalArgumentException},
     * {@link PersistentSubscriptionDeletedException}, {@link MaximumSubscribersReachedException},
     * {@link CommandNotExpectedException}, {@link NotAuthenticatedException}, {@link AccessDeniedException}
     * or {@link ServerErrorException} on exceptional completion.
     * @see #subscribeToPersistent(String, String, PersistentSubscriptionListener, UserCredentials, int, boolean)
     */
    public CompletableFuture<PersistentSubscription> subscribeToPersistent(String stream,
                                                                           String groupName,
                                                                           PersistentSubscriptionListener listener,
                                                                           int bufferSize,
                                                                           boolean autoAck) {
        return subscribeToPersistent(stream, groupName, listener, null, bufferSize, autoAck);
    }

    /**
     * Subscribes to a persistent subscription asynchronously.
     * <p>
     * This will connect you to a persistent subscription group for a stream. The subscription group must first be created.
     * Many connections can connect to the same group and they will be treated as competing consumers within the group.
     * If one connection dies, work will be balanced across the rest of the consumers in the group.
     * If you attempt to connect to a group that does not exist you will be given an exception.
     * </p>
     * <p>
     * When auto-ack is disabled, the receiver is required to explicitly acknowledge messages through the subscription.
     * </p>
     *
     * @param stream          the stream to subscribe to.
     * @param groupName       the subscription group to connect to.
     * @param listener        subscription listener.
     * @param userCredentials user credentials to be used for this operation (use {@code null} for default user credentials).
     * @param bufferSize      the buffer size to use for the persistent subscription.
     * @param autoAck         whether the subscription should automatically acknowledge messages processed.
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link IllegalArgumentException},
     * {@link PersistentSubscriptionDeletedException}, {@link MaximumSubscribersReachedException},
     * {@link CommandNotExpectedException}, {@link NotAuthenticatedException}, {@link AccessDeniedException}
     * or {@link ServerErrorException} on exceptional completion.
     */
    public abstract CompletableFuture<PersistentSubscription> subscribeToPersistent(String stream,
                                                                                    String groupName,
                                                                                    PersistentSubscriptionListener listener,
                                                                                    UserCredentials userCredentials,
                                                                                    int bufferSize,
                                                                                    boolean autoAck);

    /**
     * Creates a persistent subscription group on a stream asynchronously using
     * default persistent subscription settings and default user credentials.
     *
     * @param stream    the name of the stream to create the persistent subscription on.
     * @param groupName the name of the group to create.
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link IllegalStateException},
     * {@link CommandNotExpectedException}, {@link NotAuthenticatedException}, {@link AccessDeniedException}
     * or {@link ServerErrorException} on exceptional completion.
     * @see #createPersistentSubscription(String, String, PersistentSubscriptionSettings, UserCredentials)
     */
    public CompletableFuture<PersistentSubscriptionCreateResult> createPersistentSubscription(String stream,
                                                                                              String groupName) {
        return createPersistentSubscription(stream, groupName, PersistentSubscriptionSettings.DEFAULT, null);
    }

    /**
     * Creates a persistent subscription group on a stream asynchronously using default persistent subscription settings.
     *
     * @param stream          the name of the stream to create the persistent subscription on.
     * @param groupName       the name of the group to create.
     * @param userCredentials user credentials to be used for this operation (use {@code null} for default user credentials).
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link IllegalStateException},
     * {@link CommandNotExpectedException}, {@link NotAuthenticatedException}, {@link AccessDeniedException}
     * or {@link ServerErrorException} on exceptional completion.
     * @see #createPersistentSubscription(String, String, PersistentSubscriptionSettings, UserCredentials)
     */
    public CompletableFuture<PersistentSubscriptionCreateResult> createPersistentSubscription(String stream,
                                                                                              String groupName,
                                                                                              UserCredentials userCredentials) {
        return createPersistentSubscription(stream, groupName, PersistentSubscriptionSettings.DEFAULT, userCredentials);
    }

    /**
     * Creates a persistent subscription group on a stream asynchronously using default user credentials.
     *
     * @param stream    the name of the stream to create the persistent subscription on.
     * @param groupName the name of the group to create.
     * @param settings  persistent subscription settings.
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link IllegalStateException},
     * {@link CommandNotExpectedException}, {@link NotAuthenticatedException}, {@link AccessDeniedException}
     * or {@link ServerErrorException} on exceptional completion.
     * @see #createPersistentSubscription(String, String, PersistentSubscriptionSettings, UserCredentials)
     */
    public CompletableFuture<PersistentSubscriptionCreateResult> createPersistentSubscription(String stream,
                                                                                              String groupName,
                                                                                              PersistentSubscriptionSettings settings) {
        return createPersistentSubscription(stream, groupName, settings, null);
    }

    /**
     * Creates a persistent subscription on a stream asynchronously.
     *
     * @param stream          the name of the stream to create the persistent subscription on.
     * @param groupName       the name of the group to create.
     * @param settings        persistent subscription settings.
     * @param userCredentials user credentials to be used for this operation (use {@code null} for default user credentials).
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link IllegalStateException},
     * {@link CommandNotExpectedException}, {@link NotAuthenticatedException}, {@link AccessDeniedException}
     * or {@link ServerErrorException} on exceptional completion.
     */
    public abstract CompletableFuture<PersistentSubscriptionCreateResult> createPersistentSubscription(String stream,
                                                                                                       String groupName,
                                                                                                       PersistentSubscriptionSettings settings,
                                                                                                       UserCredentials userCredentials);

    /**
     * Updates a persistent subscription on a stream asynchronously using default user credentials.
     *
     * @param stream    the name of the stream to update the persistent subscription on.
     * @param groupName the name of the group to update.
     * @param settings  persistent subscription settings.
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link IllegalStateException},
     * {@link CommandNotExpectedException}, {@link NotAuthenticatedException}, {@link AccessDeniedException}
     * or {@link ServerErrorException} on exceptional completion.
     * @see #updatePersistentSubscription(String, String, PersistentSubscriptionSettings, UserCredentials)
     */
    public CompletableFuture<PersistentSubscriptionUpdateResult> updatePersistentSubscription(String stream,
                                                                                              String groupName,
                                                                                              PersistentSubscriptionSettings settings) {
        return updatePersistentSubscription(stream, groupName, settings, null);
    }

    /**
     * Updates a persistent subscription on a stream asynchronously.
     *
     * @param stream          the name of the stream to update the persistent subscription on.
     * @param groupName       the name of the group to update.
     * @param settings        persistent subscription settings.
     * @param userCredentials user credentials to be used for this operation (use {@code null} for default user credentials).
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link IllegalStateException},
     * {@link CommandNotExpectedException}, {@link NotAuthenticatedException}, {@link AccessDeniedException}
     * or {@link ServerErrorException} on exceptional completion.
     */
    public abstract CompletableFuture<PersistentSubscriptionUpdateResult> updatePersistentSubscription(String stream,
                                                                                                       String groupName,
                                                                                                       PersistentSubscriptionSettings settings,
                                                                                                       UserCredentials userCredentials);

    /**
     * Deletes a persistent subscription on a stream asynchronously using default user credentials.
     *
     * @param stream    the name of the stream to delete the persistent subscription on.
     * @param groupName the name of the group to delete.
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link IllegalStateException},
     * {@link CommandNotExpectedException}, {@link NotAuthenticatedException}, {@link AccessDeniedException}
     * or {@link ServerErrorException} on exceptional completion.
     * @see #deletePersistentSubscription(String, String, UserCredentials)
     */
    public CompletableFuture<PersistentSubscriptionDeleteResult> deletePersistentSubscription(String stream,
                                                                                              String groupName) {
        return deletePersistentSubscription(stream, groupName, null);
    }

    /**
     * Deletes a persistent subscription on a stream asynchronously.
     *
     * @param stream          the name of the stream to delete the persistent subscription on.
     * @param groupName       the name of the group to delete.
     * @param userCredentials user credentials to be used for this operation (use {@code null} for default user credentials).
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link IllegalStateException},
     * {@link CommandNotExpectedException}, {@link NotAuthenticatedException}, {@link AccessDeniedException}
     * or {@link ServerErrorException} on exceptional completion.
     */
    public abstract CompletableFuture<PersistentSubscriptionDeleteResult> deletePersistentSubscription(String stream,
                                                                                                       String groupName,
                                                                                                       UserCredentials userCredentials);

    /**
     * Sets the metadata for a stream asynchronously using default user credentials.
     *
     * @param stream                    the name of the stream for which to set metadata.
     * @param expectedMetastreamVersion the expected version for the write to the metadata stream.
     * @param metadata                  metadata to set.
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link WrongExpectedVersionException},
     * {@link StreamDeletedException}, {@link InvalidTransactionException}, {@link CommandNotExpectedException},
     * {@link NotAuthenticatedException}, {@link AccessDeniedException} or {@link ServerErrorException}
     * on exceptional completion.
     * @see #setStreamMetadata(String, ExpectedVersion, byte[], UserCredentials)
     */
    public CompletableFuture<WriteResult> setStreamMetadata(String stream,
                                                            ExpectedVersion expectedMetastreamVersion,
                                                            StreamMetadata metadata) {
        checkNotNull(metadata, "metadata");
        return setStreamMetadata(stream, expectedMetastreamVersion, toBytes(metadata.toJson()), null);
    }

    /**
     * Sets the metadata for a stream asynchronously.
     *
     * @param stream                    the name of the stream for which to set metadata.
     * @param expectedMetastreamVersion the expected version for the write to the metadata stream.
     * @param metadata                  metadata to set.
     * @param userCredentials           user credentials to be used for this operation (use {@code null} for default user credentials).
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link WrongExpectedVersionException},
     * {@link StreamDeletedException}, {@link InvalidTransactionException}, {@link CommandNotExpectedException},
     * {@link NotAuthenticatedException}, {@link AccessDeniedException} or {@link ServerErrorException}
     * on exceptional completion.
     * @see #setStreamMetadata(String, ExpectedVersion, byte[], UserCredentials)
     */
    public CompletableFuture<WriteResult> setStreamMetadata(String stream,
                                                            ExpectedVersion expectedMetastreamVersion,
                                                            StreamMetadata metadata,
                                                            UserCredentials userCredentials) {
        checkNotNull(metadata, "metadata");
        return setStreamMetadata(stream, expectedMetastreamVersion, toBytes(metadata.toJson()), userCredentials);
    }

    /**
     * Sets the metadata for a stream asynchronously using default user credentials.
     *
     * @param stream                    the name of the stream for which to set metadata.
     * @param expectedMetastreamVersion the expected version for the write to the metadata stream.
     * @param metadata                  metadata to set.
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link WrongExpectedVersionException},
     * {@link StreamDeletedException}, {@link InvalidTransactionException}, {@link CommandNotExpectedException},
     * {@link NotAuthenticatedException}, {@link AccessDeniedException} or {@link ServerErrorException}
     * on exceptional completion.
     * @see #setStreamMetadata(String, ExpectedVersion, byte[], UserCredentials)
     */
    public CompletableFuture<WriteResult> setStreamMetadata(String stream,
                                                            ExpectedVersion expectedMetastreamVersion,
                                                            byte[] metadata) {
        return setStreamMetadata(stream, expectedMetastreamVersion, metadata, null);
    }

    /**
     * Sets the metadata for a stream asynchronously.
     *
     * @param stream                    the name of the stream for which to set metadata.
     * @param expectedMetastreamVersion the expected version for the write to the metadata stream.
     * @param metadata                  metadata to set.
     * @param userCredentials           user credentials to be used for this operation (use {@code null} for default user credentials).
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link WrongExpectedVersionException},
     * {@link StreamDeletedException}, {@link InvalidTransactionException}, {@link CommandNotExpectedException},
     * {@link NotAuthenticatedException}, {@link AccessDeniedException} or {@link ServerErrorException}
     * on exceptional completion.
     */
    public abstract CompletableFuture<WriteResult> setStreamMetadata(String stream,
                                                                     ExpectedVersion expectedMetastreamVersion,
                                                                     byte[] metadata,
                                                                     UserCredentials userCredentials);

    /**
     * Gets the metadata for a stream asynchronously using default user credentials.
     *
     * @param stream the name of the stream for which to read metadata.
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link CommandNotExpectedException},
     * {@link NotAuthenticatedException}, {@link AccessDeniedException} or {@link ServerErrorException}
     * on exceptional completion.
     * @see #getStreamMetadata(String, UserCredentials)
     */
    public CompletableFuture<StreamMetadataResult> getStreamMetadata(String stream) {
        return getStreamMetadata(stream, null);
    }

    /**
     * Gets the metadata for a stream asynchronously.
     *
     * @param stream          the name of the stream for which to read metadata.
     * @param userCredentials user credentials to be used for this operation (use {@code null} for default user credentials).
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link CommandNotExpectedException},
     * {@link NotAuthenticatedException}, {@link AccessDeniedException} or {@link ServerErrorException}
     * on exceptional completion.
     */
    public abstract CompletableFuture<StreamMetadataResult> getStreamMetadata(String stream, UserCredentials userCredentials);

    /**
     * Gets the metadata for a stream as a byte array asynchronously using default user credentials.
     *
     * @param stream the name of the stream for which to read metadata.
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link CommandNotExpectedException},
     * {@link NotAuthenticatedException}, {@link AccessDeniedException} or {@link ServerErrorException}
     * on exceptional completion.
     * @see #getStreamMetadataAsRawBytes(String, UserCredentials)
     */
    public CompletableFuture<RawStreamMetadataResult> getStreamMetadataAsRawBytes(String stream) {
        return getStreamMetadataAsRawBytes(stream, null);
    }

    /**
     * Gets the metadata for a stream as a byte array asynchronously.
     *
     * @param stream          the name of the stream for which to read metadata.
     * @param userCredentials user credentials to be used for this operation (use {@code null} for default user credentials).
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link CommandNotExpectedException},
     * {@link NotAuthenticatedException}, {@link AccessDeniedException} or {@link ServerErrorException}
     * on exceptional completion.
     */
    public abstract CompletableFuture<RawStreamMetadataResult> getStreamMetadataAsRawBytes(String stream,
                                                                                           UserCredentials userCredentials);

    /**
     * Sets the global settings for the server or cluster asynchronously using default user credentials.
     *
     * @param settings system settings to apply.
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link WrongExpectedVersionException},
     * {@link StreamDeletedException}, {@link InvalidTransactionException}, {@link CommandNotExpectedException},
     * {@link NotAuthenticatedException}, {@link AccessDeniedException} or {@link ServerErrorException}
     * on exceptional completion.
     * @see #setSystemSettings(SystemSettings, UserCredentials)
     */
    public CompletableFuture<WriteResult> setSystemSettings(SystemSettings settings) {
        return setSystemSettings(settings, null);
    }

    /**
     * Sets the global settings for the server or cluster asynchronously.
     *
     * @param settings        system settings to apply.
     * @param userCredentials user credentials to be used for this operation (use {@code null} for default user credentials).
     * @return a {@code CompletableFuture} representing the result of this operation. The future's methods
     * {@code get} and {@code join} can throw an exception with cause {@link WrongExpectedVersionException},
     * {@link StreamDeletedException}, {@link InvalidTransactionException}, {@link CommandNotExpectedException},
     * {@link NotAuthenticatedException}, {@link AccessDeniedException} or {@link ServerErrorException}
     * on exceptional completion.
     */
    public abstract CompletableFuture<WriteResult> setSystemSettings(SystemSettings settings, UserCredentials userCredentials);

    /**
     * Adds the specified listener to this client.
     *
     * @param listener client event listener.
     */
    public void addListener(EventStoreListener listener) {
        listeners.add(listener);
    }

    /**
     * Removes the specified listener from this client.
     *
     * @param listener client event listener.
     */
    public void removeListener(EventStoreListener listener) {
        listeners.remove(listener);
    }

    protected Executor executor() {
        return settings.executor;
    }

    protected void fireEvent(Event event) {
        executor().execute(() -> listeners.forEach(l -> l.onEvent(event)));
    }

    protected abstract void onAuthenticationCompleted(AuthenticationStatus status);

    protected abstract void onBadRequest(TcpPackage tcpPackage);

    protected abstract void onChannelError(Throwable throwable);

    protected abstract void onReconnect(NodeEndpoints nodeEndpoints);

    protected ConnectionState connectionState() {
        if (connection == null) {
            return ConnectionState.INIT;
        } else if (connection.isOpen()) {
            return (connection.isActive() && (connectingPhase == ConnectingPhase.CONNECTED)) ?
                ConnectionState.CONNECTED : ConnectionState.CONNECTING;
        } else {
            return ConnectionState.CLOSED;
        }
    }

}
