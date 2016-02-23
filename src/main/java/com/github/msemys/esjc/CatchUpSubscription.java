package com.github.msemys.esjc;


import com.github.msemys.esjc.event.ClientConnected;
import com.github.msemys.esjc.util.Strings;
import com.github.msemys.esjc.util.Subscriptions.DropData;
import com.github.msemys.esjc.util.concurrent.ResettableLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.msemys.esjc.util.Numbers.isPositive;
import static com.github.msemys.esjc.util.Preconditions.checkArgument;
import static com.github.msemys.esjc.util.Preconditions.checkNotNull;
import static com.github.msemys.esjc.util.Strings.defaultIfEmpty;
import static com.github.msemys.esjc.util.Strings.isNullOrEmpty;
import static com.github.msemys.esjc.util.Subscriptions.DROP_SUBSCRIPTION_EVENT;
import static com.github.msemys.esjc.util.Subscriptions.UNKNOWN_DROP_DATA;

/**
 * Catch-up subscription.
 */
public abstract class CatchUpSubscription {
    protected final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * The name of the stream to which the subscription is subscribed (empty if subscribed to $all stream).
     */
    public final String streamId;

    private final EventStore eventstore;
    private final boolean resolveLinkTos;
    private final UserCredentials userCredentials;
    protected final CatchUpSubscriptionListener listener;
    protected final int readBatchSize;
    protected final int maxPushQueueSize;
    private final Executor executor;

    private final Queue<ResolvedEvent> liveQueue = new ConcurrentLinkedQueue<>();
    private Subscription subscription;
    private final AtomicReference<DropData> dropData = new AtomicReference<>();
    private volatile boolean allowProcessing;
    private final AtomicBoolean isProcessing = new AtomicBoolean();
    protected volatile boolean shouldStop;
    private final AtomicBoolean isDropped = new AtomicBoolean();
    private final ResettableLatch stopped = new ResettableLatch(true);

    private final EventStoreListener reconnectionHook;

    protected CatchUpSubscription(EventStore eventstore,
                                  String streamId,
                                  boolean resolveLinkTos,
                                  CatchUpSubscriptionListener listener,
                                  UserCredentials userCredentials,
                                  int readBatchSize,
                                  int maxPushQueueSize,
                                  Executor executor) {
        checkNotNull(eventstore, "eventstore");
        checkNotNull(listener, "listener");
        checkNotNull(listener, "executor");
        checkArgument(isPositive(readBatchSize), "readBatchSize should be positive");
        checkArgument(readBatchSize < EventStore.MAX_READ_SIZE, "Read batch size should be less than %d. For larger reads you should page.", EventStore.MAX_READ_SIZE);
        checkArgument(isPositive(maxPushQueueSize), "maxPushQueueSize should be positive");

        this.eventstore = eventstore;
        this.streamId = defaultIfEmpty(streamId, Strings.EMPTY);
        this.resolveLinkTos = resolveLinkTos;
        this.listener = listener;
        this.userCredentials = userCredentials;
        this.readBatchSize = readBatchSize;
        this.maxPushQueueSize = maxPushQueueSize;
        this.executor = executor;

        reconnectionHook = event -> {
            if (event instanceof ClientConnected) {
                onReconnect();
            }
        };
    }

    protected abstract void readEventsTill(EventStore eventstore,
                                           boolean resolveLinkTos,
                                           UserCredentials userCredentials,
                                           Long lastCommitPosition,
                                           Integer lastEventNumber) throws Exception;

    protected abstract void tryProcess(ResolvedEvent event);

    void start() {
        logger.trace("Catch-up Subscription to {}: starting...", streamId());
        runSubscription();
    }

    public void stop(Duration timeout) throws TimeoutException {
        stop();
        logger.trace("Waiting on subscription to stop");
        if (!stopped.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new TimeoutException(String.format("Could not stop %s in time.", getClass().getSimpleName()));
        }
    }

    public void stop() {
        logger.trace("Catch-up Subscription to {}: requesting stop...", streamId());

        logger.trace("Catch-up Subscription to {}: unhooking from connection. Connected.", streamId());
        eventstore.removeListener(reconnectionHook);

        shouldStop = true;
        enqueueSubscriptionDropNotification(SubscriptionDropReason.UserInitiated, null);
    }

    private void onReconnect() {
        logger.trace("Catch-up Subscription to {}: recovering after reconnection.", streamId());

        logger.trace("Catch-up Subscription to {}: unhooking from connection. Connected.", streamId());
        eventstore.removeListener(reconnectionHook);

        runSubscription();
    }

    private void runSubscription() {
        executor.execute(() -> {
            logger.trace("Catch-up Subscription to {}: running...", streamId());

            stopped.reset();

            try {
                if (!shouldStop) {
                    logger.trace("Catch-up Subscription to {}: pulling events...", streamId());
                    readEventsTill(eventstore, resolveLinkTos, userCredentials, null, null);
                }

                if (!shouldStop) {
                    logger.trace("Catch-up Subscription to {}: subscribing...", streamId());

                    VolatileSubscriptionListener subscriptionListener = new VolatileSubscriptionListener() {
                        @Override
                        public void onEvent(Subscription s, ResolvedEvent event) {
                            logger.trace("Catch-up Subscription to {}: event appeared ({}, {}, {} @ {}).",
                                streamId(), event.originalStreamId(), event.originalEventNumber(),
                                event.originalEvent().eventType, event.originalPosition);

                            if (liveQueue.size() >= maxPushQueueSize) {
                                enqueueSubscriptionDropNotification(SubscriptionDropReason.ProcessingQueueOverflow, null);
                                subscription.unsubscribe();
                            } else {
                                liveQueue.offer(event);
                                if (allowProcessing) {
                                    ensureProcessingPushQueue();
                                }
                            }
                        }

                        @Override
                        public void onClose(Subscription s, SubscriptionDropReason reason, Exception exception) {
                            enqueueSubscriptionDropNotification(reason, exception);
                        }
                    };

                    subscription = isSubscribedToAll() ?
                        eventstore.subscribeToAll(resolveLinkTos, subscriptionListener, userCredentials).get() :
                        eventstore.subscribeToStream(streamId, resolveLinkTos, subscriptionListener, userCredentials).get();

                    logger.trace("Catch-up Subscription to {}: pulling events (if left)...", streamId());
                    readEventsTill(eventstore, resolveLinkTos, userCredentials, subscription.lastCommitPosition, subscription.lastEventNumber);
                }
            } catch (Exception e) {
                dropSubscription(SubscriptionDropReason.CatchUpError, e);
                return;
            }

            if (shouldStop) {
                dropSubscription(SubscriptionDropReason.UserInitiated, null);
                return;
            }

            logger.trace("Catch-up Subscription to {}: processing live events...", streamId());
            listener.onLiveProcessingStarted(this);

            logger.trace("Catch-up Subscription to {}: hooking to connection. Connected", streamId());
            eventstore.addListener(reconnectionHook);

            allowProcessing = true;
            ensureProcessingPushQueue();
        });
    }

    private void enqueueSubscriptionDropNotification(SubscriptionDropReason reason, Exception exception) {
        // if drop data was already set -- no need to enqueue drop again, somebody did that already
        if (dropData.compareAndSet(null, new DropData(reason, exception))) {
            liveQueue.offer(DROP_SUBSCRIPTION_EVENT);
            if (allowProcessing) {
                ensureProcessingPushQueue();
            }
        }
    }

    private void ensureProcessingPushQueue() {
        if (isProcessing.compareAndSet(false, true)) {
            executor.execute(this::processLiveQueue);
        }
    }

    private void processLiveQueue() {
        do {
            ResolvedEvent event;
            while ((event = liveQueue.poll()) != null) {
                // drop subscription artificial ResolvedEvent
                if (event.equals(DROP_SUBSCRIPTION_EVENT)) {
                    DropData previousDropData = dropData.getAndAccumulate(UNKNOWN_DROP_DATA,
                        (current, update) -> (current == null) ? update : current);

                    if (previousDropData == null) {
                        previousDropData = UNKNOWN_DROP_DATA;
                    }

                    dropSubscription(previousDropData.reason, previousDropData.exception);
                    isProcessing.compareAndSet(true, false);
                    return;
                }

                try {
                    tryProcess(event);
                } catch (Exception e) {
                    dropSubscription(SubscriptionDropReason.EventHandlerException, e);
                    return;
                }
            }
            isProcessing.compareAndSet(true, false);
        } while (!liveQueue.isEmpty() && isProcessing.compareAndSet(false, true));
    }

    private void dropSubscription(SubscriptionDropReason reason, Exception exception) {
        if (isDropped.compareAndSet(false, true)) {
            logger.trace("Catch-up Subscription to {}: dropping subscription, reason: {}.", streamId(), reason, exception);

            if (subscription != null) {
                subscription.unsubscribe();
            }

            listener.onClose(this, reason, exception);

            stopped.release();
        }
    }

    /**
     * Determines whether or not this subscription is to $all stream or to a specific stream.
     *
     * @return {@code true} if this subscription is to $all stream, otherwise {@code false}
     */
    public boolean isSubscribedToAll() {
        return isNullOrEmpty(streamId);
    }

    protected String streamId() {
        return defaultIfEmpty(streamId, "<all>");
    }

}
