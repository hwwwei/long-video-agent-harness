package io.github.hwwwei.video.upload;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkLayoutTest {
    @Test void computesLastChunkWithoutLoadingFile() {
        ChunkLayout layout = new ChunkLayout(20, 8);
        assertEquals(3, layout.count());
        assertEquals(8, layout.expectedLength(0));
        assertEquals(4, layout.expectedLength(2));
        assertThrows(IllegalArgumentException.class, () -> layout.expectedLength(3));
    }

    @Test void rejectsFilesOverConfiguredLimit() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkLayout(2_147_483_649L, 8_388_608));
    }
}
