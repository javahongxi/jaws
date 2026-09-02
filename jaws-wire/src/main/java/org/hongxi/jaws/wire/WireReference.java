package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.AbstractReference;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.Client;
import org.hongxi.jaws.transport.TransportFactory;
import org.hongxi.jaws.transport.TransportResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wire protocol reference. Converts protobuf {@link Message} arguments to raw
 * bytes for transmission via {@link WireClient}. The response {@code Message}
 * passes through directly so that the Jaws proxy returns the typed protobuf
 * object to the caller.
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

        Object[] args = request.getArguments();
        if (args == null || args.length == 0 || !(args[0] instanceof Message requestMessage)) {
            throw new JawsServiceException(
                    "WireReference doCall failed: argument must be a protobuf Message, url="
                            + url.getUri());
        }

        // Convert Message to raw protobuf bytes for the wire transport
        byte[] requestBytes = requestMessage.toByteArray();

        WireProtoTypes.MethodInfo methodInfo = protoTypes.getMethodInfo(request.getMethodName());
        WireClient wireClient = (WireClient) client;
        return wireClient.sendRawBytes(
                request, requestBytes, methodInfo.responseParser());
    }

    @Override
    public Flow.Publisher<Object> callStream(Request request) {
        if (!isAvailable()) {
            throw new JawsServiceException(
                    "WireReference callStream failed: endpoint is not available, url=" + url.getUri());
        }
        request.setAttachment(UrlParam.Identity.GROUP.getName(), url.getGroup());

        Object[] args = request.getArguments();
        if (args == null || args.length == 0 || !(args[0] instanceof Message requestMessage)) {
            throw new JawsServiceException(
                    "WireReference callStream failed: argument must be a protobuf Message, url="
                            + url.getUri());
        }

        WireProtoTypes.MethodInfo methodInfo = protoTypes.getMethodInfo(request.getMethodName());

        // Build a request with the typed Message as argument for WireClient.requestStream
        DefaultRequest streamRequest = new DefaultRequest();
        streamRequest.setInterfaceName(request.getInterfaceName());
        streamRequest.setMethodName(request.getMethodName());
        streamRequest.setParamDesc(request.getParamDesc());
        streamRequest.setArguments(new Object[]{requestMessage});
        streamRequest.setRequestId(request.getRequestId());
        for (var entry : request.getAttachments().entrySet()) {
            streamRequest.setAttachment(entry.getKey(), entry.getValue());
        }

        WireClient wireClient = (WireClient) client;
        return wireClient.requestStream(streamRequest, methodInfo.responseParser());
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
