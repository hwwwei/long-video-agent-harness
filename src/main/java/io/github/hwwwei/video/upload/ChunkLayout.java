package io.github.hwwwei.video.upload;

/** Streaming upload geometry; long arithmetic avoids GB-size overflow. */
public record ChunkLayout(long size, int chunkSize) {
    public static final long DEFAULT_MAX_SIZE = 2L * 1024 * 1024 * 1024;
    public static final int DEFAULT_CHUNK_SIZE = 8 * 1024 * 1024;

    public ChunkLayout {
        if (size <= 0 || size > DEFAULT_MAX_SIZE || chunkSize <= 0) {
            throw new IllegalArgumentException("invalid upload size or chunk size");
        }
    }

    public int count() {
        return Math.toIntExact((size + chunkSize - 1) / chunkSize);
    }

    public long expectedLength(int index) {
        if (index < 0 || index >= count()) {
            throw new IllegalArgumentException("chunk index outside upload");
        }
        return Math.min((long) chunkSize, size - (long) index * chunkSize);
    }
}
