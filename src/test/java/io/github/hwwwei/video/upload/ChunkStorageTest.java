package io.github.hwwwei.video.upload;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkStorageTest {
    private final Path root = Path.of("target", "test-upload-" + UUID.randomUUID());

    @Test void duplicateChunkIsIdempotentButChangedBytesConflict() throws Exception {
        ChunkStorage storage = new ChunkStorage(root);
        ChunkLayout layout = new ChunkLayout(5, 3);
        assertTrue(storage.put("upload-1", layout, 0, new ByteArrayInputStream("abc".getBytes()), sha("abc")));
        assertFalse(storage.put("upload-1", layout, 0, new ByteArrayInputStream("abc".getBytes()), sha("abc")));
        assertThrows(IllegalStateException.class, () -> storage.put("upload-1", layout, 0, new ByteArrayInputStream("xyz".getBytes()), sha("xyz")));
        assertEquals(3, java.nio.file.Files.size(storage.chunkPath("upload-1", 0)));
    }

    @Test void completionStreamsAndDeduplicatesByContentHash() throws Exception {
        ChunkStorage storage = new ChunkStorage(root);
        ChunkLayout layout = new ChunkLayout(5, 3);
        storage.put("one", layout, 0, new ByteArrayInputStream("abc".getBytes()), sha("abc"));
        storage.put("one", layout, 1, new ByteArrayInputStream("de".getBytes()), sha("de"));
        Path first = storage.complete("one", layout, sha("abcde"));
        assertEquals("abcde", java.nio.file.Files.readString(first));
        storage.put("two", layout, 0, new ByteArrayInputStream("abc".getBytes()), sha("abc"));
        storage.put("two", layout, 1, new ByteArrayInputStream("de".getBytes()), sha("de"));
        assertEquals(first, storage.complete("two", layout, sha("abcde")));
        assertThrows(IllegalArgumentException.class, () -> storage.complete("two", layout, sha("wrong")));
    }

    static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes()));
    }
}
