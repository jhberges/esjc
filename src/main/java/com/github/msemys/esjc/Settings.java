package com.github.msemys.esjc;

import com.github.msemys.esjc.node.cluster.ClusterNodeSettings;
import com.github.msemys.esjc.node.static_.StaticNodeSettings;
import com.github.msemys.esjc.ssl.SslSettings;
import com.github.msemys.esjc.tcp.TcpSettings;
import com.github.msemys.esjc.util.concurrent.DefaultThreadFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.github.msemys.esjc.util.Numbers.isPositive;
import static com.github.msemys.esjc.util.Preconditions.checkArgument;

/**
 * Client settings
 */
public class Settings {

    /**
     * TCP settings.
     */
    public final TcpSettings tcpSettings;

    /**
     * Static node settings (optional).
     */
    public final Optional<StaticNodeSettings> staticNodeSettings;

    /**
     * Cluster node settings (optional).
     */
    public final Optional<ClusterNodeSettings> clusterNodeSettings;

    /**
     * SSL settings.
     */
    public final SslSettings sslSettings;

    /**
     * The amount of time to delay before attempting to reconnect.
     */
    public final Duration reconnectionDelay;

    /**
     * The interval at which to send heartbeat messages.
     * <p>
     * <u>NOTE</u>: heartbeat request will be sent only if connection is idle (no writes) for the specified time.
     * </p>
     */
    public final Duration heartbeatInterval;

    /**
     * The interval after which an unacknowledged heartbeat will cause
     * the connection to be considered faulted and disconnect.
     */
    public final Duration heartbeatTimeout;

    /**
     * Whether or not to require Event Store to refuse serving read or write request if it is not master.
     */
    public final boolean requireMaster;

    /**
     * The default user credentials to use for operations where other user credentials are not explicitly supplied.
     */
    public final Optional<UserCredentials> userCredentials;

    /**
     * The amount of time before an operation is considered to have timed out.
     */
    public final Duration operationTimeout;

    /**
     * The amount of time that timeouts are checked in the system.
     */
    public final Duration operationTimeoutCheckInterval;

    /**
     * The maximum number of outstanding items allowed in the operation queue.
     */
    public final int maxOperationQueueSize;

    /**
     * The maximum number of allowed asynchronous operations to be in process.
     */
    public final int maxConcurrentOperations;

    /**
     * The maximum number of operation retry attempts.
     */
    public final int maxOperationRetries;

    /**
     * The maximum number of times to allow for reconnection.
     */
    public final int maxReconnections;

    /**
     * The default buffer size to use for the persistent subscription.
     */
    public final int persistentSubscriptionBufferSize;

    /**
     * Whether the subscription should automatically acknowledge messages processed.
     */
    public final boolean persistentSubscriptionAutoAckEnabled;

    /**
     * Whether or not to raise an error if no response is received from the server for an operation.
     */
    public final boolean failOnNoServerResponse;

    /**
     * The executor to execute client internal tasks (such as establish-connection, start-operation) and run subscriptions.
     */
    public final Executor executor;

    private Settings(Builder builder) {
        tcpSettings = builder.tcpSettings;
        staticNodeSettings = Optional.ofNullable(builder.staticNodeSettings);
        clusterNodeSettings = Optional.ofNullable(builder.clusterNodeSettings);
        sslSettings = builder.sslSettings;
        reconnectionDelay = builder.reconnectionDelay;
        heartbeatInterval = builder.heartbeatInterval;
        heartbeatTimeout = builder.heartbeatTimeout;
        requireMaster = builder.requireMaster;
        userCredentials = Optional.ofNullable(builder.userCredentials);
        operationTimeout = builder.operationTimeout;
        operationTimeoutCheckInterval = builder.operationTimeoutCheckInterval;
        maxOperationQueueSize = builder.maxOperationQueueSize;
        maxConcurrentOperations = builder.maxConcurrentOperations;
        maxOperationRetries = builder.maxOperationRetries;
        maxReconnections = builder.maxReconnections;
        persistentSubscriptionBufferSize = builder.persistentSubscriptionBufferSize;
        persistentSubscriptionAutoAckEnabled = builder.persistentSubscriptionAutoAckEnabled;
        failOnNoServerResponse = builder.failOnNoServerResponse;
        executor = builder.executor;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Settings{");
        sb.append("tcpSettings=").append(tcpSettings);
        sb.append(", staticNodeSettings=").append(staticNodeSettings);
        sb.append(", clusterNodeSettings=").append(clusterNodeSettings);
        sb.append(", sslSettings=").append(sslSettings);
        sb.append(", reconnectionDelay=").append(reconnectionDelay);
        sb.append(", heartbeatInterval=").append(heartbeatInterval);
        sb.append(", heartbeatTimeout=").append(heartbeatTimeout);
        sb.append(", requireMaster=").append(requireMaster);
        sb.append(", userCredentials=").append(userCredentials);
        sb.append(", operationTimeout=").append(operationTimeout);
        sb.append(", operationTimeoutCheckInterval=").append(operationTimeoutCheckInterval);
        sb.append(", maxOperationQueueSize=").append(maxOperationQueueSize);
        sb.append(", maxConcurrentOperations=").append(maxConcurrentOperations);
        sb.append(", maxOperationRetries=").append(maxOperationRetries);
        sb.append(", maxReconnections=").append(maxReconnections);
        sb.append(", persistentSubscriptionBufferSize=").append(persistentSubscriptionBufferSize);
        sb.append(", persistentSubscriptionAutoAckEnabled=").append(persistentSubscriptionAutoAckEnabled);
        sb.append(", failOnNoServerResponse=").append(failOnNoServerResponse);
        sb.append(", executor=").append(executor);
        sb.append('}');
        return sb.toString();
    }

    /**
     * Creates a new client settings builder.
     *
     * @return client settings builder
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * Client settings builder.
     */
    public static class Builder {
        private TcpSettings tcpSettings;
        private StaticNodeSettings staticNodeSettings;
        private ClusterNodeSettings clusterNodeSettings;
        private SslSettings sslSettings;
        private Duration reconnectionDelay;
        private Duration heartbeatInterval;
        private Duration heartbeatTimeout;
        private Boolean requireMaster;
        private UserCredentials userCredentials;
        private Duration operationTimeout;
        private Duration operationTimeoutCheckInterval;
        private Integer maxOperationQueueSize;
        private Integer maxConcurrentOperations;
        private Integer maxOperationRetries;
        private Integer maxReconnections;
        private Integer persistentSubscriptionBufferSize;
        private Boolean persistentSubscriptionAutoAckEnabled;
        private Boolean failOnNoServerResponse;
        private Executor executor;

        private Builder() {
        }

        /**
         * Sets TCP settings.
         *
         * @param tcpSettings TCP settings.
         * @return the builder reference
         */
        public Builder tcpSettings(TcpSettings tcpSettings) {
            this.tcpSettings = tcpSettings;
            return this;
        }

        /**
         * Sets static node settings.
         *
         * @param staticNodeSettings static node settings.
         * @return the builder reference
         */
        public Builder nodeSettings(StaticNodeSettings staticNodeSettings) {
            this.staticNodeSettings = staticNodeSettings;
            return this;
        }

        /**
         * Sets cluster node settings.
         *
         * @param clusterNodeSettings cluster node settings.
         * @return the builder reference
         */
        public Builder nodeSettings(ClusterNodeSettings clusterNodeSettings) {
            this.clusterNodeSettings = clusterNodeSettings;
            return this;
        }

        /**
         * Sets SSL settings.
         *
         * @param sslSettings ssl settings.
         * @return the builder reference
         */
        public Builder sslSettings(SslSettings sslSettings) {
            this.sslSettings = sslSettings;
            return this;
        }

        /**
         * Sets the amount of time to delay before attempting to reconnect.
         *
         * @param duration the amount of time to delay before attempting to reconnect.
         * @return the builder reference
         */
        public Builder reconnectionDelay(Duration duration) {
            this.reconnectionDelay = duration;
            return this;
        }

        /**
         * Sets the interval at which to send heartbeat messages.
         * <p>
         * <u>NOTE</u>: heartbeat request will be sent only if connection is idle (no writes) for the specified time.
         * </p>
         *
         * @param heartbeatInterval the interval at which to send heartbeat messages.
         * @return the builder reference
         */
        public Builder heartbeatInterval(Duration heartbeatInterval) {
            this.heartbeatInterval = heartbeatInterval;
            return this;
        }

        /**
         * Sets the interval after which an unacknowledged heartbeat will cause
         * the connection to be considered faulted and disconnect.
         *
         * @param heartbeatTimeout heartbeat timeout.
         * @return the builder reference
         */
        public Builder heartbeatTimeout(Duration heartbeatTimeout) {
            this.heartbeatTimeout = heartbeatTimeout;
            return this;
        }

        /**
         * Sets whether or not to require Event Store to refuse serving read or write request if it is not master.
         *
         * @param requireMaster {@code true} to require master.
         * @return the builder reference
         */
        public Builder requireMaster(boolean requireMaster) {
            this.requireMaster = requireMaster;
            return this;
        }

        /**
         * Sets the default user credentials to be used for operations.
         * If user credentials are not given for an operation, these credentials will be used.
         *
         * @param username user name.
         * @param password user password.
         * @return the builder reference
         */
        public Builder userCredentials(String username, String password) {
            this.userCredentials = new UserCredentials(username, password);
            return this;
        }

        /**
         * Sets the amount of time before an operation is considered to have timed out.
         *
         * @param operationTimeout the amount of time before an operation is considered to have timed out.
         * @return the builder reference
         */
        public Builder operationTimeout(Duration operationTimeout) {
            this.operationTimeout = operationTimeout;
            return this;
        }

        /**
         * Sets the amount of time that timeouts are checked in the system.
         *
         * @param operationTimeoutCheckInterval the amount of time that timeouts are checked in the system.
         * @return the builder reference
         */
        public Builder operationTimeoutCheckInterval(Duration operationTimeoutCheckInterval) {
            this.operationTimeoutCheckInterval = operationTimeoutCheckInterval;
            return this;
        }

        /**
         * Sets the maximum number of outstanding items allowed in the operation queue.
         *
         * @param maxOperationQueueSize the maximum number of outstanding items allowed in the operation queue.
         * @return the builder reference
         */
        public Builder maxOperationQueueSize(int maxOperationQueueSize) {
            this.maxOperationQueueSize = maxOperationQueueSize;
            return this;
        }

        /**
         * Sets the maximum number of allowed asynchronous operations to be in process.
         *
         * @param maxConcurrentOperations the maximum number of allowed asynchronous operations to be in process.
         * @return the builder reference
         */
        public Builder maxConcurrentOperations(int maxConcurrentOperations) {
            this.maxConcurrentOperations = maxConcurrentOperations;
            return this;
        }

        /**
         * Sets the maximum number of operation retry attempts.
         *
         * @param maxOperationRetries the maximum number of operation retry attempts (use {@code -1} for unlimited).
         * @return the builder reference
         */
        public Builder maxOperationRetries(int maxOperationRetries) {
            this.maxOperationRetries = maxOperationRetries;
            return this;
        }

        /**
         * Sets the maximum number of times to allow for reconnection.
         *
         * @param maxReconnections the maximum number of times to allow for reconnection (use {@code -1} for unlimited).
         * @return the builder reference
         */
        public Builder maxReconnections(int maxReconnections) {
            this.maxReconnections = maxReconnections;
            return this;
        }

        /**
         * Sets the default buffer size to use for the persistent subscription.
         *
         * @param persistentSubscriptionBufferSize the default buffer size to use for the persistent subscription.
         * @return the builder reference
         */
        public Builder persistentSubscriptionBufferSize(int persistentSubscriptionBufferSize) {
            this.persistentSubscriptionBufferSize = persistentSubscriptionBufferSize;
            return this;
        }

        /**
         * Sets whether or not the subscription should automatically acknowledge messages processed.
         *
         * @param persistentSubscriptionAutoAckEnabled {@code true} to enable auto-acknowledge.
         * @return the builder reference
         */
        public Builder persistentSubscriptionAutoAckEnabled(boolean persistentSubscriptionAutoAckEnabled) {
            this.persistentSubscriptionAutoAckEnabled = persistentSubscriptionAutoAckEnabled;
            return this;
        }

        /**
         * Sets whether or not to raise an error if no response is received from the server for an operation.
         *
         * @param failOnNoServerResponse {@code true} to raise an error if no response is received from the server for an operation.
         * @return the builder reference
         */
        public Builder failOnNoServerResponse(boolean failOnNoServerResponse) {
            this.failOnNoServerResponse = failOnNoServerResponse;
            return this;
        }

        /**
         * Sets the executor to execute client internal tasks (such as establish-connection, start-operation) and run subscriptions.
         *
         * @param executor the executor to execute client internal tasks and run subscriptions.
         * @return the builder reference
         */
        public Builder executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        /**
         * Builds a client settings.
         *
         * @return client settings
         */
        public Settings build() {
            checkArgument(staticNodeSettings != null || clusterNodeSettings != null, "Missing node settings");
            checkArgument(staticNodeSettings == null || clusterNodeSettings == null, "Usage of 'static' and 'cluster' settings at once is not allowed");

            if (tcpSettings == null) {
                tcpSettings = TcpSettings.newBuilder().build();
            }

            if (sslSettings == null) {
                sslSettings = SslSettings.noSsl();
            }

            if (reconnectionDelay == null) {
                reconnectionDelay = Duration.ofSeconds(1);
            }

            if (heartbeatInterval == null) {
                heartbeatInterval = Duration.ofMillis(500);
            }

            if (heartbeatTimeout == null) {
                heartbeatTimeout = Duration.ofMillis(1500);
            }

            if (requireMaster == null) {
                requireMaster = true;
            }

            if (operationTimeout == null) {
                operationTimeout = Duration.ofSeconds(7);
            }

            if (operationTimeoutCheckInterval == null) {
                operationTimeoutCheckInterval = Duration.ofSeconds(1);
            }

            if (maxOperationQueueSize == null) {
                maxOperationQueueSize = 5000;
            } else {
                checkArgument(isPositive(maxOperationQueueSize), "maxOperationQueueSize should be positive");
            }

            if (maxConcurrentOperations == null) {
                maxConcurrentOperations = 5000;
            } else {
                checkArgument(isPositive(maxConcurrentOperations), "maxConcurrentOperations should be positive");
            }

            if (maxOperationRetries == null) {
                maxOperationRetries = 10;
            } else {
                checkArgument(maxOperationRetries >= -1, "maxOperationRetries value is out of range: %d. Allowed range: [-1, infinity].", maxOperationRetries);
            }

            if (maxReconnections == null) {
                maxReconnections = 10;
            } else {
                checkArgument(maxReconnections >= -1, "maxReconnections value is out of range: %d. Allowed range: [-1, infinity].", maxReconnections);
            }

            if (persistentSubscriptionBufferSize == null) {
                persistentSubscriptionBufferSize = 10;
            } else {
                checkArgument(isPositive(persistentSubscriptionBufferSize), "persistentSubscriptionBufferSize should be positive");
            }

            if (persistentSubscriptionAutoAckEnabled == null) {
                persistentSubscriptionAutoAckEnabled = true;
            }

            if (failOnNoServerResponse == null) {
                failOnNoServerResponse = false;
            }

            if (executor == null) {
                executor = new ThreadPoolExecutor(2, Integer.MAX_VALUE,
                    60L, TimeUnit.SECONDS,
                    new SynchronousQueue<>(),
                    new DefaultThreadFactory("es"));
            }

            return new Settings(this);
        }
    }

}
