package io.github.cmh.dhc.embed;

import io.deephaven.proto.backplane.grpc.CreateDirectoryRequest;
import io.deephaven.proto.backplane.grpc.DeleteItemRequest;
import io.deephaven.proto.backplane.grpc.FetchFileRequest;
import io.deephaven.proto.backplane.grpc.ItemInfo;
import io.deephaven.proto.backplane.grpc.ListItemsRequest;
import io.deephaven.proto.backplane.grpc.ListItemsResponse;
import io.deephaven.proto.backplane.grpc.MoveItemRequest;
import io.deephaven.proto.backplane.grpc.SaveFileRequest;
import io.deephaven.proto.backplane.grpc.StorageServiceGrpc;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.io.File;

/**
 * Makes the web IDE's saved layouts and notebooks work where the file separator is not
 * {@code /}, which is Windows (finding F11).
 *
 * <p>The web IDE names every stored file with a leading slash and forward slashes:
 * {@code /layouts/main.json}. Deephaven 42.4's storage service drops the leading slash only when
 * the slash is the platform's file separator, and answers with paths built from the platform's
 * separator. On Windows a request for {@code /layouts} therefore points outside the storage
 * directory and is refused ("Invalid path: /layouts"), and a listing would name its files
 * {@code /layouts\main.json}, which the IDE could not ask for again.
 *
 * <p>Nothing of Deephaven is patched. This interceptor sits in front of the storage service and
 * gives it paths without the leading slash, and gives the client answers with forward slashes.
 * Where the separator is {@code /} it does nothing at all, so Linux and macOS run Deephaven's code
 * unchanged.
 */
final class StoragePaths implements ServerInterceptor {

    private final char separator;

    StoragePaths() {
        this(File.separatorChar);
    }

    /** For tests: the rules of another platform. */
    StoragePaths(char separator) {
        this.separator = separator;
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
            ServerCallHandler<Q, R> next) {
        if (separator == '/' || !StorageServiceGrpc.SERVICE_NAME.equals(call.getMethodDescriptor().getServiceName())) {
            return next.startCall(call, headers);
        }
        ServerCall<Q, R> answers = new SimpleForwardingServerCall<>(call) {
            @Override
            public void sendMessage(R message) {
                super.sendMessage(response(message));
            }
        };
        return new SimpleForwardingServerCallListener<>(next.startCall(answers, headers)) {
            @Override
            public void onMessage(Q message) {
                super.onMessage(request(message));
            }
        };
    }

    /** A request as the storage service understands it on this platform. */
    @SuppressWarnings("unchecked")
    <Q> Q request(Q message) {
        if (separator == '/') {
            return message;
        }
        Object rewritten = message;
        if (message instanceof ListItemsRequest request) {
            rewritten = request.toBuilder().setPath(incoming(request.getPath())).build();
        } else if (message instanceof FetchFileRequest request) {
            rewritten = request.toBuilder().setPath(incoming(request.getPath())).build();
        } else if (message instanceof SaveFileRequest request) {
            rewritten = request.toBuilder().setPath(incoming(request.getPath())).build();
        } else if (message instanceof MoveItemRequest request) {
            rewritten = request.toBuilder()
                    .setOldPath(incoming(request.getOldPath()))
                    .setNewPath(incoming(request.getNewPath()))
                    .build();
        } else if (message instanceof CreateDirectoryRequest request) {
            rewritten = request.toBuilder().setPath(incoming(request.getPath())).build();
        } else if (message instanceof DeleteItemRequest request) {
            rewritten = request.toBuilder().setPath(incoming(request.getPath())).build();
        }
        return (Q) rewritten;
    }

    /** An answer as the web IDE understands it. Only a listing carries paths. */
    @SuppressWarnings("unchecked")
    <R> R response(R message) {
        if (separator == '/' || !(message instanceof ListItemsResponse response)) {
            return message;
        }
        ListItemsResponse.Builder builder = response.toBuilder()
                .setCanonicalPath(outgoing(response.getCanonicalPath()));
        for (int i = 0; i < response.getItemsCount(); i++) {
            ItemInfo item = response.getItems(i);
            builder.setItems(i, item.toBuilder().setPath(outgoing(item.getPath())));
        }
        return (R) builder.build();
    }

    /**
     * Drops the one leading slash, as Deephaven does where the slash is the separator. Anything
     * else is left to Deephaven's own check, which refuses what leaves the storage directory.
     */
    private static String incoming(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private String outgoing(String path) {
        return path.replace(separator, '/');
    }
}
