package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A standalone gRPC channel that manages connections to multiple backend
 * addresses and load-balances calls across them, analogous to grpc-java's
 * {@code ManagedChannel}.
 * <p>
 * Unlike the Jaws Cluster pipeline (which relies on {@code ReferenceConfig},
 * {@code ConsumerCoordinator}, and the full framework infrastructure), this
 * channel is self-contained: it creates one {@link WireClient} per address,
 * applies a {@link LoadBalancePolicy} to pick a client for each call, and
 * optionally falls over to the next address on failure.
 * <p>
 * Typical usage for calling external gRPC services:
 * <pre>{@code
 * try (ManagedChannel channel = ManagedChannel.builder()
 *         .addAddress("10.0.0.1:50051")
 *         .addAddress("10.0.0.2:50051")
 *         .addAddress("10.0.0.3:50051")
 *         .build()) {
 *
 *     Response response = channel.unaryCall(
 *         "greeter.Greeter", "SayHello",
 *         HelloRequest.newBuilder().setName("World").build(),
 *         HelloReply.parser());
 *     HelloReply reply = (HelloReply) response.getValue();
 * }
 * }</pre>
 * <p>
 * Supported load balance policies:
 * <ul>
 *   <li>{@link LoadBalancePolicy#ROUND_ROBIN} — cycles through all addresses
 *       sequentially, distributing calls evenly</li>
 *   <li>{@link LoadBalancePolicy#PICK_FIRST} — sticks to the first available
 *       address; on failure, tries the next one in order</li>
 * </ul>
 *
 * @author shenhongxi
 * @see WireClient
 */
public class ManagedChannel implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(ManagedChannel.class);

    private final List<WireClient> clients;
    private final LoadBalancePolicy policy;

    /** Round-robin counter for ROUND_ROBIN policy. */
    private final AtomicInteger counter = new AtomicInteger(0);

    /** Tracks the current preferred index for PICK_FIRST policy. */
    private volatile int pickFirstIndex = 0;

    ManagedChannel(List<WireClient> clients, LoadBalancePolicy policy) {
        this.clients = clients;
        this.policy = policy;
    }

    /**
     * Create a new {@link Builder} for configuring a {@link ManagedChannel}.
     */
    public static Builder builder() {
        return new Builder();
    }

    // ========================================================================
    // Unary calls
    // ========================================================================

    /**
     * Send a unary gRPC call to the specified service method, load-balanced
     * across the configured addresses.
     *
     * @param serviceName    the fully-qualified gRPC service name (e.g. {@code greeter.Greeter})
     * @param methodName     the RPC method name (e.g. {@code SayHello})
     * @param request        the protobuf request message
     * @param responseParser the parser for the expected response type
     * @param <Req>          request message type
     * @param <Resp>         response message type
     * @return the full response with message value and trailer attachments
     */
    public <Req extends Message, Resp extends Message> Response unaryCall(
            String serviceName, String methodName,
            Req request, Parser<Resp> responseParser) {
        return unaryCall(serviceName, methodName, request, responseParser, null);
    }

    /**
     * Send a unary gRPC call with per-call metadata (gRPC custom headers).
     *
     * @param serviceName    the fully-qualified gRPC service name
     * @param methodName     the RPC method name
     * @param request        the protobuf request message
     * @param responseParser the parser for the expected response type
     * @param metadata       per-call metadata entries sent as custom HTTP/2 headers
     * @param <Req>          request message type
     * @param <Resp>         response message type
     * @return the full response with message value and trailer attachments
     */
    public <Req extends Message, Resp extends Message> Response unaryCall(
            String serviceName, String methodName,
            Req request, Parser<Resp> responseParser,
            Map<String, String> metadata) {
        return doUnaryCall(serviceName, methodName, request, responseParser, metadata);
    }

    // ========================================================================
    // Streaming calls
    // ========================================================================

    /**
     * Send a server-streaming gRPC call and return a {@link Flow.Publisher}
     * that emits each response message.
     *
     * @param serviceName    the fully-qualified gRPC service name
     * @param methodName     the RPC method name
     * @param request        the protobuf request message
     * @param responseParser the parser for the expected response type
     * @param <Req>          request message type
     * @param <Resp>         response message type
     * @return a publisher emitting streamed response messages
     */
    public <Req extends Message, Resp extends Message> Flow.Publisher<Resp> streamingCall(
            String serviceName, String methodName,
            Req request, Parser<Resp> responseParser) {
        return streamingCall(serviceName, methodName, request, responseParser, null);
    }

    /**
     * Send a server-streaming gRPC call with per-call metadata.
     */
    public <Req extends Message, Resp extends Message> Flow.Publisher<Resp> streamingCall(
            String serviceName, String methodName,
            Req request, Parser<Resp> responseParser,
            Map<String, String> metadata) {
        //noinspection unchecked
        return (Flow.Publisher<Resp>) (Flow.Publisher<?>) doStreamingCall(
                serviceName, methodName, request, responseParser, metadata);
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    /**
     * @return the number of backend addresses managed by this channel
     */
    public int size() {
        return clients.size();
    }

    /**
     * @return true if at least one backend client is available
     */
    public boolean isAvailable() {
        for (WireClient client : clients) {
            if (client.isAvailable()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        for (WireClient client : clients) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("Failed to close WireClient: {}", client.getUrl(), e);
            }
        }
        log.info("ManagedChannel closed, {} backend(s) released", clients.size());
    }

    // ========================================================================
    // Internal implementation
    // ========================================================================

    private Response doUnaryCall(String serviceName, String methodName,
                                Message request, Parser<? extends Message> responseParser,
                                Map<String, String> metadata) {
        Request jawsRequest = buildRequest(serviceName, methodName, request, metadata);

        if (policy == LoadBalancePolicy.PICK_FIRST) {
            return doPickFirstUnary(jawsRequest, responseParser);
        }

        // ROUND_ROBIN: try the selected client, fail over to others
        int startIdx = Math.abs(counter.getAndIncrement() % clients.size());
        Exception lastException = null;

        for (int i = 0; i < clients.size(); i++) {
            int idx = (startIdx + i) % clients.size();
            WireClient client = clients.get(idx);
            if (!client.isAvailable()) {
                continue;
            }
            try {
                return client.request(jawsRequest, responseParser);
            } catch (Exception e) {
                lastException = e;
                log.warn("ManagedChannel round-robin call failed: address={}:{}, error={}",
                        client.getUrl().getHost(), client.getUrl().getPort(), e.getMessage());
            }
        }

        throw new JawsServiceException(
                "ManagedChannel all " + clients.size() + " backend(s) failed for "
                        + serviceName + "/" + methodName, lastException);
    }

    private Response doPickFirstUnary(Request jawsRequest, Parser<? extends Message> responseParser) {
        // Try the current preferred index first, then fall over to others
        for (int i = 0; i < clients.size(); i++) {
            int idx = (pickFirstIndex + i) % clients.size();
            WireClient client = clients.get(idx);
            if (!client.isAvailable()) {
                continue;
            }
            try {
                Response response = client.request(jawsRequest, responseParser);
                // Success: update preferred index to this client
                pickFirstIndex = idx;
                return response;
            } catch (Exception e) {
                log.warn("ManagedChannel pick-first call failed: address={}:{}, error={}",
                        client.getUrl().getHost(), client.getUrl().getPort(), e.getMessage());
            }
        }

        throw new JawsServiceException(
                "ManagedChannel all " + clients.size() + " backend(s) failed for "
                        + jawsRequest.getInterfaceName() + "/" + jawsRequest.getMethodName());
    }

    private Flow.Publisher<Object> doStreamingCall(String serviceName, String methodName,
                                                    Message request, Parser<? extends Message> responseParser,
                                                    Map<String, String> metadata) {
        Request jawsRequest = buildRequest(serviceName, methodName, request, metadata);

        // For streaming, pick one client (no fail-over mid-stream)
        WireClient client = selectClient();
        return client.requestStream(jawsRequest, responseParser);
    }

    private WireClient selectClient() {
        if (policy == LoadBalancePolicy.PICK_FIRST) {
            // Use the preferred index
            for (int i = 0; i < clients.size(); i++) {
                int idx = (pickFirstIndex + i) % clients.size();
                WireClient client = clients.get(idx);
                if (client.isAvailable()) {
                    return client;
                }
            }
        } else {
            // ROUND_ROBIN
            int idx = Math.abs(counter.getAndIncrement() % clients.size());
            for (int i = 0; i < clients.size(); i++) {
                int actual = (idx + i) % clients.size();
                WireClient client = clients.get(actual);
                if (client.isAvailable()) {
                    return client;
                }
            }
        }
        throw new JawsServiceException("ManagedChannel: no available backend address");
    }

    private Request buildRequest(String serviceName, String methodName,
                                 Message request, java.util.Map<String, String> metadata) {
        DefaultRequest jawsRequest = new DefaultRequest();
        jawsRequest.setInterfaceName(serviceName);
        jawsRequest.setMethodName(methodName);
        jawsRequest.setArguments(new Object[]{request});
        if (metadata != null && !metadata.isEmpty()) {
            for (var entry : metadata.entrySet()) {
                jawsRequest.setAttachment(entry.getKey(), entry.getValue());
            }
        }
        return jawsRequest;
    }

    // ========================================================================
    // Builder
    // ========================================================================

    /**
     * Builder for configuring and creating a {@link ManagedChannel}.
     * <p>
     * At least one address must be provided. All other settings are optional
     * and have sensible defaults.
     */
    public static final class Builder {
        private final List<String> addresses = new ArrayList<>();
        private LoadBalancePolicy policy = LoadBalancePolicy.ROUND_ROBIN;
        private int requestTimeout = 5000;
        private int connectTimeout = 3000;
        private int maxInboundMessageSize = 4 * 1024 * 1024;
        private String compression = WireConstants.ENCODING_IDENTITY;

        private Builder() {
        }

        /**
         * Add a backend gRPC server address in {@code host:port} format.
         *
         * @param address the address (e.g. {@code "10.0.0.1:50051"})
         * @return this builder
         */
        public Builder addAddress(String address) {
            if (address == null || address.isBlank()) {
                throw new IllegalArgumentException("address must not be blank");
            }
            addresses.add(address.trim());
            return this;
        }

        /**
         * Set the load balance policy. Defaults to {@link LoadBalancePolicy#ROUND_ROBIN}.
         *
         * @param policy the load balance policy
         * @return this builder
         */
        public Builder loadBalancePolicy(LoadBalancePolicy policy) {
            this.policy = policy;
            return this;
        }

        /**
         * Use round-robin load balancing. Equivalent to
         * {@code loadBalancePolicy(LoadBalancePolicy.ROUND_ROBIN)}.
         *
         * @return this builder
         */
        public Builder roundRobin() {
            this.policy = LoadBalancePolicy.ROUND_ROBIN;
            return this;
        }

        /**
         * Use pick-first load balancing. Equivalent to
         * {@code loadBalancePolicy(LoadBalancePolicy.PICK_FIRST)}.
         *
         * @return this builder
         */
        public Builder pickFirst() {
            this.policy = LoadBalancePolicy.PICK_FIRST;
            return this;
        }

        /**
         * Set the request timeout in milliseconds. Defaults to 5000ms.
         *
         * @param timeoutMs timeout in milliseconds
         * @return this builder
         */
        public Builder requestTimeout(int timeoutMs) {
            this.requestTimeout = timeoutMs;
            return this;
        }

        /**
         * Set the connect timeout in milliseconds. Defaults to 3000ms.
         *
         * @param timeoutMs connect timeout in milliseconds
         * @return this builder
         */
        public Builder connectTimeout(int timeoutMs) {
            this.connectTimeout = timeoutMs;
            return this;
        }

        /**
         * Set the maximum inbound message size in bytes. Defaults to 4MiB.
         *
         * @param maxBytes max inbound message size
         * @return this builder
         */
        public Builder maxInboundMessageSize(int maxBytes) {
            this.maxInboundMessageSize = maxBytes;
            return this;
        }

        /**
         * Set the compression encoding for outbound messages ({@code "identity"}
         * or {@code "gzip"}). Defaults to {@code "identity"}.
         *
         * @param compression the compression encoding
         * @return this builder
         */
        public Builder compression(String compression) {
            this.compression = compression;
            return this;
        }

        /**
         * Build and open the {@link ManagedChannel}. Each address gets its own
         * {@link WireClient} connection; all connections are established eagerly.
         *
         * @return a ready-to-use ManagedChannel
         * @throws IllegalArgumentException if no addresses were provided
         * @throws JawsServiceException     if any connection fails to establish
         */
        public ManagedChannel build() {
            if (addresses.isEmpty()) {
                throw new IllegalArgumentException("At least one address must be provided");
            }

            List<WireClient> clients = new ArrayList<>(addresses.size());
            try {
                for (String address : addresses) {
                    String[] hostPort = address.split(":");
                    String host = hostPort[0].trim();
                    int port = hostPort.length > 1 ? Integer.parseInt(hostPort[1].trim()) : 50051;

                    URL url = new URL("wire", host, port, "wire");
                    url.addParameter(UrlParam.Transport.REQUEST_TIMEOUT.getName(),
                            String.valueOf(requestTimeout));
                    url.addParameter(UrlParam.Transport.CONNECT_TIMEOUT.getName(),
                            String.valueOf(connectTimeout));
                    url.addParameter(UrlParam.Transport.MAX_INBOUND_MESSAGE_SIZE.getName(),
                            String.valueOf(maxInboundMessageSize));
                    url.addParameter(UrlParam.Transport.COMPRESSION.getName(), compression);

                    WireClient client = new WireClient(url);
                    client.open();
                    clients.add(client);
                }

                log.info("ManagedChannel created: {} backend(s), policy={}, timeout={}ms",
                        clients.size(), policy, requestTimeout);

                return new ManagedChannel(clients, policy);
            } catch (Exception e) {
                // Clean up any already-opened clients on failure
                for (WireClient client : clients) {
                    try {
                        client.close();
                    } catch (Exception ex) {
                        log.warn("Failed to close WireClient during cleanup", ex);
                    }
                }
                if (e instanceof JawsServiceException) {
                    throw e;
                }
                throw new JawsServiceException("ManagedChannel build failed", e);
            }
        }
    }
}
