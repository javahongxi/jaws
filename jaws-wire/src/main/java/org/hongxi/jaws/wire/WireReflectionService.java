package org.hongxi.jaws.wire;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Parser;
import org.hongxi.jaws.wire.reflection.ErrorResponse;
import org.hongxi.jaws.wire.reflection.FileDescriptorResponse;
import org.hongxi.jaws.wire.reflection.ListServiceResponse;
import org.hongxi.jaws.wire.reflection.ServerReflectionRequest;
import org.hongxi.jaws.wire.reflection.ServerReflectionResponse;
import org.hongxi.jaws.wire.reflection.ServiceResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Standard gRPC server reflection service
 * ({@code grpc.reflection.v1.ServerReflection},
 * <a href="https://github.com/grpc/grpc/blob/master/doc/server-reflection.md">
 * server-reflection.md</a>).
 * <p>
 * Enables tools like {@code grpcurl} to discover services and their descriptors
 * without a local {@code .proto} file. {@link WireServer} creates and registers
 * a {@code WireReflectionService} automatically in both operating modes.
 * <p>
 * Supported request types:
 * <ul>
 *   <li>{@code list_services} — returns all registered service names</li>
 *   <li>{@code file_containing_symbol} — returns the file descriptor(s) for a
 *       fully-qualified symbol (service / message / enum), including transitive
 *       dependencies</li>
 *   <li>{@code file_by_filename} — returns the file descriptor(s) for a given
 *       {@code .proto} file name</li>
 * </ul>
 * {@code file_containing_extension} and {@code all_extension_numbers_of_type}
 * return {@code UNIMPLEMENTED} per the gRPC reflection spec.
 *
 * @author shenhongxi
 */
public class WireReflectionService {

    private static final Logger log = LoggerFactory.getLogger(WireReflectionService.class);

    /** Fully-qualified service name used in the gRPC path. */
    public static final String SERVICE_NAME = "grpc.reflection.v1.ServerReflection";

    /** The gRPC path for the ServerReflectionInfo method. */
    static final String REFLECTION_PATH = "/" + SERVICE_NAME + "/ServerReflectionInfo";

    private static final Parser<ServerReflectionRequest> REQUEST_PARSER =
            ServerReflectionRequest.parser();

    /** Lazily evaluated set of service names (supplied by the registry or static map). */
    private final Supplier<Set<String>> serviceNamesSupplier;
    /** Lazily evaluated set of FileDescriptors (supplied by handlers or proto types). */
    private final Supplier<Set<Descriptors.FileDescriptor>> fileDescriptorsSupplier;

    /**
     * Create a reflection service with lazy suppliers for service names and
     * file descriptors. The suppliers are invoked on every request so that
     * dynamically registered services are visible.
     */
    WireReflectionService(Supplier<Set<String>> serviceNamesSupplier,
                          Supplier<Set<Descriptors.FileDescriptor>> fileDescriptorsSupplier) {
        this.serviceNamesSupplier = serviceNamesSupplier;
        this.fileDescriptorsSupplier = fileDescriptorsSupplier;
    }

    /**
     * @return the protobuf parser for {@link ServerReflectionRequest}
     */
    static Parser<ServerReflectionRequest> getRequestParser() {
        return REQUEST_PARSER;
    }

    /**
     * Handle a single {@link ServerReflectionRequest} and produce the
     * corresponding {@link ServerReflectionResponse}.
     */
    ServerReflectionResponse handleRequest(ServerReflectionRequest request) {
        ServerReflectionResponse.Builder responseBuilder = ServerReflectionResponse.newBuilder()
                .setOriginalRequest(request);

        switch (request.getMessageRequestCase()) {
            case LIST_SERVICES -> handleListServices(responseBuilder);
            case FILE_CONTAINING_SYMBOL -> handleFileContainingSymbol(
                    responseBuilder, request.getFileContainingSymbol());
            case FILE_BY_FILENAME -> handleFileByFilename(
                    responseBuilder, request.getFileByFilename());
            case FILE_CONTAINING_EXTENSION -> handleUnsupported(
                    responseBuilder, request, "file_containing_extension");
            case ALL_EXTENSION_NUMBERS_OF_TYPE -> handleUnsupported(
                    responseBuilder, request, "all_extension_numbers_of_type");
            case MESSAGEREQUEST_NOT_SET -> handleUnsupported(
                    responseBuilder, request, "empty request");
        }

        return responseBuilder.build();
    }

    // ---- Request handlers ----

    private void handleListServices(ServerReflectionResponse.Builder responseBuilder) {
        Set<String> names = serviceNamesSupplier.get();
        ListServiceResponse.Builder listBuilder = ListServiceResponse.newBuilder();
        for (String name : names) {
            // Skip internal protocol services from the listing
            if (!name.startsWith("grpc.")) {
                listBuilder.addService(ServiceResponse.newBuilder().setName(name).build());
            }
        }
        responseBuilder.setListServicesResponse(listBuilder.build());
    }

    private void handleFileContainingSymbol(ServerReflectionResponse.Builder responseBuilder,
                                             String symbol) {
        Set<Descriptors.FileDescriptor> allFiles = fileDescriptorsSupplier.get();

        // Search for the symbol across all known file descriptors
        for (Descriptors.FileDescriptor fd : allFiles) {
            if (containsSymbol(fd, symbol)) {
                responseBuilder.setFileDescriptorResponse(
                        buildFileDescriptorResponse(fd, allFiles));
                return;
            }
        }

        setErrorResponse(responseBuilder, WireConstants.STATUS_NOT_FOUND,
                "Symbol not found: " + symbol);
    }

    private void handleFileByFilename(ServerReflectionResponse.Builder responseBuilder,
                                       String filename) {
        Set<Descriptors.FileDescriptor> allFiles = fileDescriptorsSupplier.get();

        for (Descriptors.FileDescriptor fd : allFiles) {
            if (fd.getName().equals(filename)) {
                responseBuilder.setFileDescriptorResponse(
                        buildFileDescriptorResponse(fd, allFiles));
                return;
            }
        }

        setErrorResponse(responseBuilder, WireConstants.STATUS_NOT_FOUND,
                "File not found: " + filename);
    }

    private void handleUnsupported(ServerReflectionResponse.Builder responseBuilder,
                                    ServerReflectionRequest request, String methodName) {
        log.debug("Reflection method not implemented: {}", methodName);
        setErrorResponse(responseBuilder, WireConstants.STATUS_UNIMPLEMENTED,
                "Method not implemented: " + methodName);
    }

    // ---- Helpers ----

    /**
     * Check whether a file descriptor (or its messages/enums/services) contains
     * the given fully-qualified symbol.
     */
    private static boolean containsSymbol(Descriptors.FileDescriptor fd, String symbol) {
        for (Descriptors.ServiceDescriptor sd : fd.getServices()) {
            if (sd.getFullName().equals(symbol)) {
                return true;
            }
        }
        for (Descriptors.Descriptor md : fd.getMessageTypes()) {
            if (containsMessageSymbol(md, symbol)) {
                return true;
            }
        }
        for (Descriptors.EnumDescriptor ed : fd.getEnumTypes()) {
            if (ed.getFullName().equals(symbol)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsMessageSymbol(Descriptors.Descriptor md, String symbol) {
        if (md.getFullName().equals(symbol)) {
            return true;
        }
        for (Descriptors.Descriptor nested : md.getNestedTypes()) {
            if (containsMessageSymbol(nested, symbol)) {
                return true;
            }
        }
        for (Descriptors.EnumDescriptor ed : md.getEnumTypes()) {
            if (ed.getFullName().equals(symbol)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Build a {@link FileDescriptorResponse} containing the target file and all
     * its transitive dependencies as serialized {@code FileDescriptorProto} bytes.
     */
    private static FileDescriptorResponse buildFileDescriptorResponse(
            Descriptors.FileDescriptor target,
            Set<Descriptors.FileDescriptor> allKnownFiles) {
        Set<Descriptors.FileDescriptor> collected = new LinkedHashSet<>();
        collectFileDescriptorRecursive(target, collected);

        // Also include any known files that might be needed as dependencies
        // but are not in the transitive closure (shouldn't happen normally)
        FileDescriptorResponse.Builder builder = FileDescriptorResponse.newBuilder();
        for (Descriptors.FileDescriptor fd : collected) {
            builder.addFileDescriptorProto(fd.toProto().toByteString());
        }
        return builder.build();
    }

    private static void collectFileDescriptorRecursive(Descriptors.FileDescriptor fd,
                                                        Set<Descriptors.FileDescriptor> collected) {
        if (!collected.add(fd)) {
            return;
        }
        for (Descriptors.FileDescriptor dep : fd.getDependencies()) {
            collectFileDescriptorRecursive(dep, collected);
        }
    }

    private static void setErrorResponse(ServerReflectionResponse.Builder responseBuilder,
                                          int errorCode, String errorMessage) {
        responseBuilder.setErrorResponse(ErrorResponse.newBuilder()
                .setErrorCode(errorCode)
                .setErrorMessage(errorMessage)
                .build());
    }
}
