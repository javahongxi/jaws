package org.hongxi.jaws.transport.grpc;

import io.grpc.stub.StreamObserver;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.RpcContext;
import org.hongxi.jaws.serialization.Serialization;
import org.hongxi.jaws.transport.Channel;
import org.hongxi.jaws.transport.MessageHandler;
import org.hongxi.jaws.transport.grpc.proto.ByteRequest;
import org.hongxi.jaws.transport.grpc.proto.ByteResponse;
import org.hongxi.jaws.transport.grpc.proto.JawsRpcServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * gRPC service implementation that bridges gRPC invocations to the Jaws
 * {@link MessageHandler} pipeline (which dispatches to {@link Provider}s).
 * <p>
 * Call flow:
 * <pre>
 * gRPC ByteRequest → deserialize payload → DefaultRequest
 *   → messageHandler.handleAsync() → CompletableFuture&lt;Object&gt;
 *   → serialize Response → ByteResponse → gRPC response
 * </pre>
 *
 * @author shenhongxi
 */
class GrpcServerService extends JawsRpcServiceGrpc.JawsRpcServiceImplBase {
    private static final Logger log = LoggerFactory.getLogger(GrpcServerService.class);

    private final MessageHandler messageHandler;
    private final Channel serverChannel;
    private final Serialization serialization;

    GrpcServerService(MessageHandler messageHandler, Channel serverChannel, Serialization serialization) {
        this.messageHandler = messageHandler;
        this.serverChannel = serverChannel;
        this.serialization = serialization;
    }

    @Override
    public void invoke(ByteRequest grpcRequest, StreamObserver<ByteResponse> responseObserver) {
        long startTime = System.currentTimeMillis();
        try {
            // Deserialize gRPC payload → Jaws Request
            DefaultRequest request = GrpcPayloadCodec.decodeRequest(
                    grpcRequest.getPayload().toByteArray(), serialization);

            // Initialize RpcContext for this invocation
            RpcContext.init(request);

            // Dispatch to the Jaws message handler (Provider pipeline)
            CompletableFuture<Object> future = messageHandler.handleAsync(serverChannel, request);

            future.whenComplete((result, throwable) -> {
                try {
                    RpcContext.init(request);
                    DefaultResponse response;
                    if (throwable != null) {
                        log.error("gRPC invoke failed: {}", request, throwable);
                        response = new DefaultResponse(request.getRequestId());
                        response.setException(new RuntimeException(
                                "process request failed: " + throwable.getMessage(), throwable));
                    } else if (result instanceof DefaultResponse dr) {
                        response = dr;
                    } else if (result instanceof Response r) {
                        response = new DefaultResponse(r);
                    } else {
                        response = new DefaultResponse(result);
                    }
                    response.setRequestId(request.getRequestId());
                    response.setProcessTime(System.currentTimeMillis() - startTime);

                    // Serialize Response → gRPC ByteResponse
                    byte[] payload = GrpcPayloadCodec.encodeResponse(response, serialization);
                    ByteResponse grpcResponse = ByteResponse.newBuilder()
                            .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
                            .build();

                    responseObserver.onNext(grpcResponse);
                    responseObserver.onCompleted();
                } catch (Exception e) {
                    log.error("gRPC response serialization failed: requestId={}", request.getRequestId(), e);
                    responseObserver.onError(io.grpc.Status.INTERNAL
                            .withDescription("Response serialization failed: " + e.getMessage())
                            .asRuntimeException());
                } finally {
                    RpcContext.destroy();
                }
            });
        } catch (Exception e) {
            log.error("gRPC request deserialization failed", e);
            responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription("Request deserialization failed: " + e.getMessage())
                    .asRuntimeException());
        }
    }
}
