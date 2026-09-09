package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.AbstractReference;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.Client;
import org.hongxi.jaws.transport.TransportFactory;
import org.hongxi.jaws.transport.TransportResolver;
import org.hongxi.jaws.transport.http2.Http2Constants;
import org.hongxi.jaws.transport.http2.StreamType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wire protocol reference. Delegates to {@link WireClient} for gRPC
 * transmission using typed protobuf {@link Message} arguments directly.
 * The response {@code Message} passes through so that the Jaws proxy returns
 * the typed protobuf object to the caller.
 * <p>
 * The protobuf request/response types are extracted from the service interface
 * via {@link WireProtoTypes}.
 *
 * @author shenhongxi
 */
public class WireReference<T> extends AbstractReference<T> {

    private static final Logger log = LoggerFactory.getLogger(WireReference.class);

    private final WireProtoTypes protoTypes;
    private final Client client;
    private final TransportFactory transportFactory;

    /** Guards against releasing the shared client more than once. */
    private final AtomicBoolean destroyed = new AtomicBoolean(false);

    public WireReference(Class<T> interfaceClass, URL url) {
        super(interfaceClass, url);
        this.protoTypes = WireProtoTypes.fromServiceInterface(interfaceClass);

        transportFactory = TransportResolver.resolve(url);
        client = transportFactory.createClient(url);
    }

    @Override
    protected boolean doInit() {
        return client.open();
    }

    @Override
    protected Response doCall(Request request) {
        request.setAttachment(UrlParam.Identity.GROUP.getName(), url.getGroup());
        WireProtoTypes.MethodInfo methodInfo = protoTypes.getMethodInfo(request.getMethodName());
        WireClient wireClient = (WireClient) client;
        return wireClient.request(request, methodInfo.responseParser());
    }

    @Override
    public Flow.Publisher<Object> callStream(Request request, Flow.Publisher<Object> requestStream) {
        if (!isAvailable()) {
            throw new JawsServiceException(
                    "WireReference callStream failed: endpoint is not available, url=" + url.getUri());
        }
        request.setAttachment(UrlParam.Identity.GROUP.getName(), url.getGroup());

        WireProtoTypes.MethodInfo methodInfo = protoTypes.getMethodInfo(request.getMethodName());
        WireClient wireClient = (WireClient) client;

        if (requestStream == null) {
            // Server-streaming
            Object[] args = request.getArguments();
            if (args == null || args.length == 0 || !(args[0] instanceof Message requestMessage)) {
                throw new JawsServiceException(
                        "WireReference callStream failed: argument must be a protobuf Message, url="
                                + url.getUri());
            }
            DefaultRequest streamRequest = buildWireRequest(request, new Object[]{requestMessage});
            return wireClient.requestStream(streamRequest, methodInfo.responseParser());
        } else {
            // Client-streaming or bidi-streaming: route via the unified requestStream
            String streamingHeader = request.getAttachments().get(Http2Constants.HEADER_STREAMING);
            StreamType streamType = StreamType.fromValue(streamingHeader);
            if (streamType == StreamType.CLIENT) {
                DefaultRequest clientStreamRequest = buildWireRequest(request, request.getArguments());
                return wireClient.requestStream(clientStreamRequest, requestStream, methodInfo.responseParser());
            }
            // Bidi-streaming
            DefaultRequest biStreamRequest = buildWireRequest(request, request.getArguments());
            return wireClient.requestBiStream(biStreamRequest, requestStream, methodInfo.responseParser());
        }
    }

    private DefaultRequest buildWireRequest(Request request, Object[] arguments) {
        DefaultRequest wireRequest = new DefaultRequest();
        wireRequest.setInterfaceName(request.getInterfaceName());
        wireRequest.setMethodName(request.getMethodName());
        wireRequest.setParamDesc(request.getParamDesc());
        wireRequest.setArguments(arguments);
        wireRequest.setRequestId(request.getRequestId());
        for (var entry : request.getAttachments().entrySet()) {
            wireRequest.setAttachment(entry.getKey(), entry.getValue());
        }
        return wireRequest;
    }

    @Override
    public boolean isAvailable() {
        return client.isAvailable();
    }

    @Override
    public void destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }
        transportFactory.releaseClient(client);
        log.info("WireReference destroy: url={}", url);
    }
}
