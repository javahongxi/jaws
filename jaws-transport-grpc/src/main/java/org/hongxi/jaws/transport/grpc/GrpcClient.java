package org.hongxi.jaws.transport.grpc;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.hongxi.jaws.common.ChannelState;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.DefaultResponseFuture;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.serialization.Serialization;
import org.hongxi.jaws.transport.Channel;
import org.hongxi.jaws.transport.Client;
import org.hongxi.jaws.transport.grpc.proto.ByteRequest;
import org.hongxi.jaws.transport.grpc.proto.ByteResponse;
import org.hongxi.jaws.transport.grpc.proto.JawsRpcServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * gRPC-based {@link Client} implementation.
 * <p>
 * Sends Jaws RPC requests through a gRPC channel. The request/response objects
 * are serialized to/from byte arrays using the configured Jaws {@link Serialization},
 * then wrapped in gRPC's generic {@code ByteRequest}/{@code ByteResponse} messages.
 * <p>
 * The {@link #request(Request)} method returns a {@link ResponseFuture} immediately;
 * the actual gRPC call completes asynchronously via the async stub.
 *
 * @author shenhongxi
 */
public class GrpcClient implements Client {
    private static final Logger log = LoggerFactory.getLogger(GrpcClient.class);

    private final URL url;
    private final Serialization serialization;

    // volatile: written under the instance lock in open()/close(), read lock-free in request()
    private volatile ManagedChannel managedChannel;
    private volatile JawsRpcServiceGrpc.JawsRpcServiceStub asyncStub;
    private volatile ChannelState state = ChannelState.UNINIT;

    public GrpcClient(URL url) {
        this.url = url;
        this.serialization = GrpcPayloadCodec.resolveSerialization(url);
    }

    @Override
    public Response request(Request request) {
        if (!isAvailable()) {
            throw new JawsServiceException("gRPC channel is not available: url=" + url.getUri());
        }

        int timeout = url.getMethodParameter(
                request.getMethodName(), request.getParamDesc(),
                UrlParam.Transport.REQUEST_TIMEOUT.getName(), UrlParam.Transport.REQUEST_TIMEOUT.intValue());

        DefaultResponseFuture responseFuture = new DefaultResponseFuture(request, timeout, url);

        try {
            // Serialize Jaws Request → gRPC ByteRequest
            byte[] payload = GrpcPayloadCodec.encodeRequest(request, serialization);
            ByteRequest grpcRequest = ByteRequest.newBuilder()
                    .setPayload(ByteString.copyFrom(payload))
                    .build();

            // Async gRPC call
            asyncStub.withDeadlineAfter(timeout, TimeUnit.MILLISECONDS)
                    .invoke(grpcRequest, new StreamObserver<ByteResponse>() {
                        @Override
                        public void onNext(ByteResponse grpcResponse) {
                            try {
                                DefaultResponse response = GrpcPayloadCodec.decodeResponse(
                                        grpcResponse.getPayload().toByteArray(), serialization);
                                if (response.getException() != null) {
                                    responseFuture.onFailure(response);
                                } else {
                                    responseFuture.onSuccess(response);
                                }
                            } catch (Exception e) {
                                log.error("gRPC response deserialization failed: requestId={}",
                                        request.getRequestId(), e);
                                DefaultResponse errorResponse = new DefaultResponse(request.getRequestId());
                                errorResponse.setException(new JawsServiceException(
                                        "Response deserialization failed", e));
                                responseFuture.onFailure(errorResponse);
                            }
                        }

                        @Override
                        public void onError(Throwable t) {
                            log.error("gRPC call failed: requestId={} url={}",
                                    request.getRequestId(), url.getUri(), t);
                            DefaultResponse errorResponse = new DefaultResponse(request.getRequestId());
                            errorResponse.setException(new JawsServiceException(
                                    "gRPC call failed: " + t.getMessage(), t));
                            responseFuture.onFailure(errorResponse);
                        }

                        @Override
                        public void onCompleted() {
                            // For unary calls, onCompleted follows onNext.
                            // The actual result is already handled in onNext.
                        }
                    });
        } catch (Exception e) {
            log.error("gRPC request failed: url={} requestId={}", url.getUri(), request.getRequestId(), e);
            DefaultResponse errorResponse = new DefaultResponse(request.getRequestId());
            errorResponse.setException(new JawsServiceException("gRPC request error", e));
            responseFuture.onFailure(errorResponse);
        }

        return responseFuture;
    }

    @Override
    public synchronized boolean open() {
        if (isAvailable()) {
            return true;
        }

        int timeout = url.getIntParameter(UrlParam.Transport.CONNECT_TIMEOUT);
        if (timeout <= 0) {
            throw new JawsFrameworkException("gRPC client init failed: connect timeout must be positive but was " + timeout);
        }

        try {
            managedChannel = NettyChannelBuilder
                    .forAddress(url.getHost(), url.getPort())
                    .usePlaintext()
                    .build();

            asyncStub = JawsRpcServiceGrpc.newStub(managedChannel);
            state = ChannelState.ALIVE;
            log.info("gRPC client opened: url={}", url);
        } catch (Exception e) {
            throw new JawsServiceException("Failed to open gRPC client: url=" + url.getUri(), e);
        }

        return true;
    }

    @Override
    public synchronized void close() {
        close(0);
    }

    @Override
    public synchronized void close(int timeout) {
        if (state.isCloseState()) return;

        try {
            if (managedChannel != null) {
                managedChannel.shutdown();
                int waitSec = timeout > 0 ? timeout / 1000 : 5;
                if (!managedChannel.awaitTermination(waitSec, TimeUnit.SECONDS)) {
                    managedChannel.shutdownNow();
                }
                managedChannel = null;
            }
            state = ChannelState.CLOSE;
            log.info("gRPC client closed: url={}", url);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (managedChannel != null) {
                managedChannel.shutdownNow();
            }
            state = ChannelState.CLOSE;
        }
    }

    @Override
    public boolean isAvailable() {
        return state.isAliveState() && managedChannel != null && !managedChannel.isShutdown();
    }

    @Override
    public URL getUrl() {
        return url;
    }
}
