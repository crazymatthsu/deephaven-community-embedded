package io.github.cmh.dhc.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import io.deephaven.client.impl.ClientConfig;
import io.deephaven.client.impl.FlightSession;
import io.deephaven.client.impl.FlightSessionFactoryConfig;
import io.deephaven.proto.backplane.grpc.CreateDirectoryRequest;
import io.deephaven.proto.backplane.grpc.DeleteItemRequest;
import io.deephaven.proto.backplane.grpc.FetchFileRequest;
import io.deephaven.proto.backplane.grpc.ItemInfo;
import io.deephaven.proto.backplane.grpc.ItemType;
import io.deephaven.proto.backplane.grpc.ListItemsRequest;
import io.deephaven.proto.backplane.grpc.ListItemsResponse;
import io.deephaven.proto.backplane.grpc.MoveItemRequest;
import io.deephaven.proto.backplane.grpc.SaveFileRequest;
import io.deephaven.proto.backplane.grpc.StorageServiceGrpc;
import io.deephaven.proto.backplane.grpc.StorageServiceGrpc.StorageServiceBlockingStub;
import io.deephaven.uri.DeephavenTarget;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T2: the web IDE's saved layouts and notebooks, asked for the way the web IDE asks, over the
 * network and with its paths: a leading slash and forward slashes (finding F11).
 *
 * <p>On Linux and macOS this is Deephaven's own code and passes without us. On Windows every
 * request below is refused with "Invalid path" unless {@link StoragePaths} is in front of the
 * storage service, so there this test is the proof that it is, and that it is enough.
 */
class StorageServiceTest {

    private static final String DIRECTORY = "/notebooks/dhc-test";
    private static final String FILE = DIRECTORY + "/first.groovy";
    private static final String MOVED = DIRECTORY + "/second.groovy";
    private static final ByteString CONTENTS = ByteString.copyFromUtf8("println 'kept by the web IDE'\n");

    @Test
    void layoutsAndNotebooksAreStoredUnderTheWebIdesPaths(@TempDir Path workDir) throws Exception {
        EmbeddedDeephaven deephaven = EmbeddedDeephaven.builder()
                .settings(EmbedSettings.builder("storage")
                        .httpPort(0)
                        .authMode(EmbedSettings.AuthMode.ANONYMOUS)
                        .workDir(workDir)
                        .build())
                .sideEffects(SideEffectPolicy.hostKeepsControl())
                .build();
        deephaven.start();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "storage-test-client");
            thread.setDaemon(true);
            return thread;
        });
        try (BufferAllocator allocator = new RootAllocator()) {
            FlightSessionFactoryConfig.Factory factory = FlightSessionFactoryConfig.builder()
                    .clientConfig(ClientConfig.builder()
                            .target(DeephavenTarget.of(URI.create("dh+plain://localhost:" + deephaven.httpPort())))
                            .build())
                    .allocator(allocator)
                    .scheduler(scheduler)
                    .build()
                    .factory();
            FlightSession session = factory.newFlightSession();
            try {
                // The session's own channel: it carries the login, as the web IDE's requests do.
                check(StorageServiceGrpc.newBlockingStub(session.session().channel().channel()), workDir);
            } finally {
                session.close();
                factory.managedChannel().shutdownNow();
            }
        } finally {
            scheduler.shutdownNow();
            deephaven.stop();
        }
    }

    private static void check(StorageServiceBlockingStub storage, Path workDir) {
        // What the web IDE asks first, on every load. Both directories exist from the start.
        assertEquals("/layouts", list(storage, "/layouts", null).getCanonicalPath());
        assertEquals("/notebooks", list(storage, "/notebooks", null).getCanonicalPath());
        assertEquals(List.of("/layouts", "/notebooks"),
                list(storage, "/", null).getItemsList().stream().map(ItemInfo::getPath).sorted().toList());

        storage.createDirectory(CreateDirectoryRequest.newBuilder().setPath(DIRECTORY).build());
        String etag = storage.saveFile(SaveFileRequest.newBuilder().setPath(FILE).setContents(CONTENTS).build())
                .getEtag();

        // It is a file below this instance's own directory (F10), and nowhere else.
        Path stored = workDir.resolve("data").resolve("storage").resolve("notebooks").resolve("dhc-test")
                .resolve("first.groovy");
        assertTrue(Files.isRegularFile(stored), "expected the saved file at " + stored);

        // A listing names it by the path it can be asked for again.
        ListItemsResponse listing = list(storage, DIRECTORY, null);
        assertEquals(DIRECTORY, listing.getCanonicalPath());
        assertEquals(1, listing.getItemsCount());
        assertEquals(FILE, listing.getItems(0).getPath());
        assertEquals(ItemType.FILE, listing.getItems(0).getType());
        assertEquals(CONTENTS.size(), listing.getItems(0).getSize());
        assertEquals(etag, listing.getItems(0).getEtag());
        assertEquals(List.of(ItemType.DIRECTORY),
                list(storage, "/notebooks", null).getItemsList().stream().map(ItemInfo::getType).toList());
        assertEquals(DIRECTORY, list(storage, "/notebooks", null).getItems(0).getPath());

        assertEquals(1, list(storage, DIRECTORY, "*.groovy").getItemsCount());
        assertEquals(0, list(storage, DIRECTORY, "*.json").getItemsCount());

        assertEquals(CONTENTS, storage.fetchFile(FetchFileRequest.newBuilder().setPath(FILE).build()).getContents());

        storage.moveItem(MoveItemRequest.newBuilder().setOldPath(FILE).setNewPath(MOVED).build());
        assertEquals(MOVED, list(storage, DIRECTORY, null).getItems(0).getPath());
        assertEquals(CONTENTS, storage.fetchFile(FetchFileRequest.newBuilder().setPath(MOVED).build()).getContents());
        assertEquals(Status.Code.FAILED_PRECONDITION, code(
                () -> storage.fetchFile(FetchFileRequest.newBuilder().setPath(FILE).build())));

        // What leaves the storage directory is still refused, by Deephaven's own check.
        assertEquals(Status.Code.INVALID_ARGUMENT, code(
                () -> storage.fetchFile(FetchFileRequest.newBuilder().setPath("/../outside.txt").build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(
                () -> storage.saveFile(SaveFileRequest.newBuilder().setPath("/../outside.txt").setContents(CONTENTS)
                        .build())));
        assertFalse(Files.exists(workDir.resolve("data").resolve("outside.txt")));

        storage.deleteItem(DeleteItemRequest.newBuilder().setPath(MOVED).build());
        storage.deleteItem(DeleteItemRequest.newBuilder().setPath(DIRECTORY).build());
        assertEquals(0, list(storage, "/notebooks", null).getItemsCount());
        assertFalse(Files.exists(stored.getParent()));
    }

    private static ListItemsResponse list(StorageServiceBlockingStub storage, String path, String glob) {
        ListItemsRequest.Builder request = ListItemsRequest.newBuilder().setPath(path);
        if (glob != null) {
            request.setFilterGlob(glob);
        }
        return storage.listItems(request.build());
    }

    private static Status.Code code(Runnable call) {
        return assertThrows(StatusRuntimeException.class, call::run).getStatus().getCode();
    }
}
