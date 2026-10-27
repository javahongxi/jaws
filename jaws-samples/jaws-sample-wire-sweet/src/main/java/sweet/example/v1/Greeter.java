// The one file in this repository that is deliberately not under org.hongxi.
//
// jaws derives the gRPC :path from the Java interface itself —
// ReferenceInvocationHandler#invoke passes interfaceClass.getName() as the service
// segment and method.getName() verbatim as the method segment, and WireClient then
// sends "/" + interfaceName + "/" + methodName. grpc-go, on the other end, only ever
// answers /sweet.example.v1.Greeter/Greet, which is the proto package joined to the
// service name. So this interface's fully qualified name and its method names are
// wire-visible identifiers, not Java naming choices: renaming the package to something
// tidier, or lower-casing a method to match Java conventions, turns every call into
// UNIMPLEMENTED on the Go side.
//
// Method names are PascalCase for the same reason — there is no case conversion
// anywhere on the outbound path.
package sweet.example.v1;

import org.hongxi.jaws.sample.wire.sweet.proto.GreetReply;
import org.hongxi.jaws.sample.wire.sweet.proto.GreetRequest;
import org.hongxi.jaws.stream.StreamSource;

/**
 * Typed view of sweet's Greeter service (see src/main/proto/greeter.proto, vendored
 * from github.com/javahongxi/sweet example/greeter).
 * <p>
 * Request and response types are the protoc-generated messages, so
 * {@code WireProtoTypes} can read both parsers off this interface; the
 * {@link StreamSource} parameters and return values are what select the streaming
 * shape on the way out.
 */
public interface Greeter {

    /**
     * Unary: {@code /sweet.example.v1.Greeter/Greet}.
     */
    GreetReply Greet(GreetRequest request);

    /**
     * Server-streaming: one reply per {@code times}, ending in grpc-status.
     */
    StreamSource<GreetReply> GreetStream(GreetRequest request);

    /**
     * Client-streaming: names go out one by one, one reply comes back after the
     * request stream completes.
     */
    GreetReply GreetAll(StreamSource<GreetRequest> names);

    /**
     * Bidirectional: each name answered as it arrives.
     */
    StreamSource<GreetReply> GreetChat(StreamSource<GreetRequest> names);
}
