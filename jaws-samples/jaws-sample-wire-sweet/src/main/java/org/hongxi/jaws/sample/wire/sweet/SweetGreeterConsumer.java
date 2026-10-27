package org.hongxi.jaws.sample.wire.sweet;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ReferenceConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.rpc.RpcContext;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.sample.wire.sweet.proto.GreetReply;
import org.hongxi.jaws.sample.wire.sweet.proto.GreetRequest;
import org.hongxi.jaws.stream.StreamObserver;
import org.hongxi.jaws.stream.StreamSource;
import org.hongxi.jaws.transport.StreamSubject;
import org.hongxi.jaws.wire.StatusException;
import sweet.example.v1.Greeter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Calls a grpc-go service that jaws did not register, discovered through Nacos.
 * <p>
 * The provider is {@code sweet}'s own example — a Go process that registers itself
 * with the Nacos naming protocol by hand, under {@code DEFAULT_GROUP} and with the
 * bare service name {@code sweet.example.v1.Greeter}. Nothing on that side is
 * configured for jaws: no jaws metadata, no namespace prefix. Everything here adapts:
 * <ol>
 *   <li>{@code registryNamespace=} (empty) drops the {@code jaws/} prefix from the
 *       service name the Nacos leg subscribes to — see {@code NacosPathUtils}.</li>
 *   <li>{@code group=DEFAULT_GROUP} matches the group that provider registered under;
 *       jaws' own default is {@code default_rpc}.</li>
 *   <li>The typed proxy is {@link Greeter}, whose package and method names are the
 *       proto's {@code package.Service} and {@code Method} verbatim, because that is
 *       what the outbound {@code :path} is built from.</li>
 * </ol>
 * <p>
 * All four gRPC shapes go through the pipeline (cluster → load balance → filter chain
 * → WireReference → WireClient), so this is the registry-driven counterpart of
 * {@code jaws-sample-wire-interop}'s direct-address demos.
 * <p>
 * Start sweet's server first; the command line is in README.md.
 */
public class SweetGreeterConsumer {

    private static final String SERVICE_NAME = "sweet.example.v1.Greeter";

    public static void main(String[] args) throws Exception {
        String name = arg(args, "-name", "jaws");
        int times = Integer.parseInt(arg(args, "-times", "3"));
        String[] names = arg(args, "-names", "alice,bob,charlie").split(",");
        int holdSeconds = Integer.parseInt(arg(args, "-hold", "0"));

        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName("wire");
        protocolConfig.setId("wire");
        protocolConfig.setTransportFactory("wire");
        // The other half of the namespace story: this key reaches the reference URL
        // verbatim because ProtocolConfig#setParameter does not filter blank values
        // the way BaseConfig#putIfPresent does. Empty means "no prefix".
        protocolConfig.setParameter(UrlParam.Identity.REGISTRY_NAMESPACE.getName(), "");

        RegistryConfig registryConfig = new RegistryConfig();
        registryConfig.setProtocol(JawsConstants.REGISTRY_PROTOCOL_NACOS);
        registryConfig.setId("defaultRegistry");
        registryConfig.setAddress("127.0.0.1");
        registryConfig.setPort(8848);

        ReferenceConfig<Greeter> ref = new ReferenceConfig<>();
        ref.setInterface(Greeter.class);
        ref.setApplication("sample-wire-sweet");
        ref.setModule("sample-wire");
        // This is the group the Go provider registered under, not a jaws choice.
        ref.setGroup("DEFAULT_GROUP");
        ref.setCheck(false);
        ref.setRequestTimeout(5000);
        ref.setProtocol(protocolConfig);
        ref.setRegistry(registryConfig);

        Greeter greeter = ref.getRef();
        System.out.println("resolving " + SERVICE_NAME + " through nacos, group=DEFAULT_GROUP, namespace=(none)");

        unary(greeter, name);
        serverStreaming(greeter, name, times);
        clientStreaming(greeter, names);
        bidirectional(greeter, names);

        if (holdSeconds > 0) {
            System.out.println("\n--- holding " + holdSeconds + "s: kill the Go server now to watch the instance list empty out ---");
            Thread.sleep(TimeUnit.SECONDS.toMillis(holdSeconds));
            System.out.println("--- calling again after the hold ---");
            unary(greeter, name);
        }

        ref.destroy();
        System.out.println("done.");
    }

    private static void unary(Greeter greeter, String name) {
        try {
            GreetReply reply = greeter.Greet(GreetRequest.newBuilder().setName(name).build());
            System.out.println("Greet          => " + reply.getMessage() + "   " + dialedServer());
        } catch (Exception e) {
            System.out.println("Greet          => failed: " + failureMessage(e));
        }
    }

    private static void serverStreaming(Greeter greeter, String name, int times) throws Exception {
        List<String> got = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        Throwable[] failure = new Throwable[1];
        greeter.GreetStream(GreetRequest.newBuilder().setName(name).setTimes(times).build())
                .subscribe(new StreamObserver<>() {
                    @Override
                    public void onNext(GreetReply item) {
                        got.add(item.getMessage());
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        failure[0] = throwable;
                        latch.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        latch.countDown();
                    }
                });
        await(latch);
        report("GreetStream    ", got, failure[0]);
    }

    /**
     * Client streaming: the request stream has to be fed from another thread, because
     * the proxy call itself blocks until the single reply arrives.
     */
    private static void clientStreaming(Greeter greeter, String[] names) throws Exception {
        StreamSubject<GreetRequest> requests = new StreamSubject<>();
        Thread feeder = new Thread(() -> {
            for (String n : names) {
                requests.onNext(GreetRequest.newBuilder().setName(n).build());
            }
            requests.onCompleted();
        }, "greet-all-feeder");
        feeder.setDaemon(true);
        feeder.start();

        try {
            GreetReply reply = greeter.GreetAll(requests);
            System.out.println("GreetAll       => " + reply.getMessage() + "   " + dialedServer());
        } catch (Exception e) {
            System.out.println("GreetAll       => failed: " + failureMessage(e));
        }
        feeder.join(TimeUnit.SECONDS.toMillis(5));
    }

    private static void bidirectional(Greeter greeter, String[] names) throws Exception {
        List<String> got = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        Throwable[] failure = new Throwable[1];

        StreamSubject<GreetRequest> requests = new StreamSubject<>();
        StreamSource<GreetReply> responses = greeter.GreetChat(requests);
        responses.subscribe(new StreamObserver<>() {
            @Override
            public void onNext(GreetReply item) {
                got.add(item.getMessage());
            }

            @Override
            public void onError(Throwable throwable) {
                failure[0] = throwable;
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        });

        for (String n : names) {
            requests.onNext(GreetRequest.newBuilder().setName(n).build());
        }
        requests.onCompleted();
        await(latch);
        report("GreetChat      ", got, failure[0]);
    }

    private static void report(String label, List<String> got, Throwable failure) {
        if (failure != null) {
            System.out.println(label + "=> failed: " + failureMessage(failure));
            return;
        }
        System.out.println(label + "=> " + got + "   " + dialedServer());
    }

    /**
     * A failed unary call reaches us wrapped by the cluster layer, with the gRPC status
     * the Go server returned sitting in the cause chain; streaming failures surface that
     * status directly. Unwrapping keeps both shapes' output comparable — the point of the
     * {@code -name ""} run documented in README.md.
     */
    private static String failureMessage(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof StatusException status) {
                return cause == thrown
                        ? status.getMessage()
                        : status.getMessage() + "   (从 " + thrown.getClass().getSimpleName() + " 的 cause 链里取出)";
            }
        }
        return thrown.getMessage();
    }

    /**
     * Which provider URL the cluster layer actually dialed — the evidence that the
     * address came out of Nacos rather than a command line host:port.
     */
    private static String dialedServer() {
        URL serverUrl = RpcContext.getContext().getServerUrl();
        return serverUrl == null ? "server=(unknown)" : "server=" + serverUrl.getHost() + ":" + serverUrl.getPort();
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("stream did not finish within 10s");
        }
    }

    private static String arg(String[] args, String key, String defaultValue) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (key.equals(args[i])) {
                return args[i + 1];
            }
        }
        return defaultValue;
    }
}
