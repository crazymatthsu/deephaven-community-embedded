package io.github.cmh.dhc.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.deephaven.proto.backplane.grpc.CreateDirectoryRequest;
import io.deephaven.proto.backplane.grpc.DeleteItemRequest;
import io.deephaven.proto.backplane.grpc.FetchFileRequest;
import io.deephaven.proto.backplane.grpc.ItemInfo;
import io.deephaven.proto.backplane.grpc.ItemType;
import io.deephaven.proto.backplane.grpc.ListItemsRequest;
import io.deephaven.proto.backplane.grpc.ListItemsResponse;
import io.deephaven.proto.backplane.grpc.MoveItemRequest;
import io.deephaven.proto.backplane.grpc.SaveFileRequest;
import io.deephaven.proto.backplane.grpc.SaveFileResponse;
import org.junit.jupiter.api.Test;

/**
 * T1: the rewriting of storage paths under Windows' rules (finding F11), which no Linux or macOS
 * machine applies by itself. {@link StorageServiceTest} asks a running instance, and is the test
 * that fails on a Windows machine without the interceptor.
 */
class StoragePathsTest {

    private final StoragePaths windows = new StoragePaths('\\');
    private final StoragePaths elsewhere = new StoragePaths('/');

    @Test
    void aRequestLosesItsLeadingSlash() {
        assertEquals("layouts", windows.request(ListItemsRequest.newBuilder().setPath("/layouts").build()).getPath());
        assertEquals("layouts/main.json",
                windows.request(FetchFileRequest.newBuilder().setPath("/layouts/main.json").build()).getPath());
        assertEquals("notebooks/a.groovy",
                windows.request(SaveFileRequest.newBuilder().setPath("/notebooks/a.groovy").build()).getPath());
        assertEquals("notebooks/sub",
                windows.request(CreateDirectoryRequest.newBuilder().setPath("/notebooks/sub").build()).getPath());
        assertEquals("notebooks/a.groovy",
                windows.request(DeleteItemRequest.newBuilder().setPath("/notebooks/a.groovy").build()).getPath());

        MoveItemRequest moved = windows.request(
                MoveItemRequest.newBuilder().setOldPath("/notebooks/a.groovy").setNewPath("/notebooks/b.groovy").build());
        assertEquals("notebooks/a.groovy", moved.getOldPath());
        assertEquals("notebooks/b.groovy", moved.getNewPath());
    }

    @Test
    void theRestOfARequestIsKept() {
        SaveFileRequest request = SaveFileRequest.newBuilder()
                .setPath("/layouts/main.json")
                .setAllowOverwrite(true)
                .setContents(com.google.protobuf.ByteString.copyFromUtf8("{}"))
                .build();

        SaveFileRequest rewritten = windows.request(request);

        assertEquals(request.toBuilder().setPath("layouts/main.json").build(), rewritten);
    }

    @Test
    void theRootAndAPathWithoutASlashStayWhatTheyMean() {
        assertEquals("", windows.request(ListItemsRequest.newBuilder().setPath("/").build()).getPath());
        assertEquals("layouts", windows.request(ListItemsRequest.newBuilder().setPath("layouts").build()).getPath());
    }

    @Test
    void onlyOneSlashIsDroppedSoThatDeephavenStillRefusesTheRest() {
        // Deephaven drops one separator and refuses what then leaves the storage directory. A
        // second slash is not ours to forgive.
        assertEquals("/etc", windows.request(ListItemsRequest.newBuilder().setPath("//etc").build()).getPath());
        assertEquals("../outside",
                windows.request(FetchFileRequest.newBuilder().setPath("/../outside").build()).getPath());
    }

    @Test
    void aListingIsAnsweredWithForwardSlashes() {
        ListItemsResponse response = ListItemsResponse.newBuilder()
                .setCanonicalPath("/notebooks\\sub")
                .addItems(ItemInfo.newBuilder().setPath("/notebooks\\sub\\a.groovy").setType(ItemType.FILE).setSize(3)
                        .setEtag("abc"))
                .addItems(ItemInfo.newBuilder().setPath("/notebooks\\sub\\deeper").setType(ItemType.DIRECTORY))
                .build();

        ListItemsResponse rewritten = windows.response(response);

        assertEquals("/notebooks/sub", rewritten.getCanonicalPath());
        assertEquals("/notebooks/sub/a.groovy", rewritten.getItems(0).getPath());
        assertEquals(ItemType.FILE, rewritten.getItems(0).getType());
        assertEquals(3, rewritten.getItems(0).getSize());
        assertEquals("abc", rewritten.getItems(0).getEtag());
        assertEquals("/notebooks/sub/deeper", rewritten.getItems(1).getPath());
        assertEquals(ItemType.DIRECTORY, rewritten.getItems(1).getType());
    }

    @Test
    void anAnswerWithoutPathsIsPassedOn() {
        SaveFileResponse response = SaveFileResponse.newBuilder().setEtag("abc").build();

        assertSame(response, windows.response(response));
    }

    @Test
    void whereTheSeparatorIsASlashNothingIsTouched() {
        ListItemsRequest request = ListItemsRequest.newBuilder().setPath("/layouts").build();
        ListItemsResponse response = ListItemsResponse.newBuilder().setCanonicalPath("/layouts").build();

        assertSame(request, elsewhere.request(request));
        assertSame(response, elsewhere.response(response));
    }
}
