package org.hongxi.jaws.sample.wire.interop;

import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;

import java.util.concurrent.TimeUnit;

/**
 * Standalone grpc-java server for the wire-interop sample module.
 * <p>
 * Starts a standard gRPC {@link GreeterGrpc.GreeterImplBase} on port 50060
 * with a metadata interceptor that echoes {@code x-trace-id} and a deadline-
 * aware slow-path for names prefixed with {@code "slow:"}.
 * <p>
 * Run this class first, then execute {@link WireCallGrpcDemo} or point
 * {@code grpcurl} at {@code localhost:50060}.
 * <p>
 * Run:
 * <pre>
 *   ./mvnw -q compile exec:java -pl jaws-samples/jaws-sample-wire-interop -am \
 *       -Dexec.mainClass="org.hongxi.jaws.sample.wire.interop.GrpcServerMain"
 * </pre>
 */
public class GrpcServerMain {

    private static final int GRPC_PORT = 50060;

    private static final Metadata.Key<String> TRACE_ID_KEY =
            Metadata.Key.of("x-trace-id", Metadata.ASCII_STRING_MARSHALLER);

    public static void main(String[] args) throws Exception {
        Server grpcServer = ServerBuilder.forPort(GRPC_PORT)
                .addService(new GreeterGrpc.GreeterImplBase() {
                    @Override
                    public void sayHello(HelloRequest request,
                                         StreamObserver<HelloReply> responseObserver) {
                        System.out.println("[grpc-java server] Received: " + request.getName());

                        if (request.getName().startsWith("slow:")) {
                            Deadline deadline = Context.current().getDeadline();
                            System.out.println("[grpc-java server] deadline propagated via grpc-timeout, remaining="
                                    + (deadline == null ? "none"
                                    : deadline.timeRemaining(TimeUnit.MILLISECONDS) + "ms"));
                            Context.current().addListener(
                                    context -> System.out.println(
                                            "[grpc-java server] call canceled (client RST_STREAM or deadline expired)"),
                                    Runnable::run);
                            try {
                                Thread.sleep(1500);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }

                        HelloReply reply = HelloReply.newBuilder()
                                .setMessage("Hello, " + request.getName() + "! (from standard grpc-java)")
                                .build();
                        responseObserver.onNext(reply);
                        responseObserver.onCompleted();
                    }
                })
                .intercept(new ServerInterceptor() {
                    @Override
                    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                            ServerCall<ReqT, RespT> call, Metadata headers,
                            ServerCallHandler<ReqT, RespT> next) {
                        String traceId = headers.get(TRACE_ID_KEY);
                        if (traceId != null) {
                            System.out.println("[grpc-java server] metadata x-trace-id = " + traceId);
                        }
                        return next.startCall(call, headers);
                    }
                })
                .build()
                .start();

        System.out.println("grpc-java server started on port " + GRPC_PORT);
        System.out.println("Press Ctrl+C to stop.");

        // Add shutdown hook for graceful termination
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down grpc-java server...");
            grpcServer.shutdown();
        }));

        grpcServer.awaitTermination();
    }
}
