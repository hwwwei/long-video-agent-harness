package io.github.hwwwei.video.upload;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** File backed chunks: no request body or merged video is held in memory. */
public final class ChunkStorage {
    private final Path root;

    public ChunkStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path chunkPath(String uploadId, int index) {
        if (!uploadId.matches("[A-Za-z0-9-]{1,64}") || index < 0) throw new IllegalArgumentException("invalid upload identifier");
        return root.resolve("chunks").resolve(uploadId).resolve(index + ".part");
    }

    public boolean hasChunk(String uploadId, ChunkLayout layout, int index) throws IOException {
        return Files.isRegularFile(chunkPath(uploadId, index)) && Files.size(chunkPath(uploadId, index)) == layout.expectedLength(index);
    }

    public synchronized boolean put(String uploadId, ChunkLayout layout, int index, InputStream input, String expectedSha) throws IOException {
        long expectedLength = layout.expectedLength(index);
        String canonicalHash = checkedHash(expectedSha);
        Path destination = chunkPath(uploadId, index);
        if (Files.exists(destination)) {
            if (Files.size(destination) != expectedLength || !hash(destination).equals(canonicalHash)) throw new IllegalStateException("chunk already exists with different content");
            return false;
        }
        Files.createDirectories(destination.getParent());
        Path temporary = destination.resolveSibling(index + "." + UUID.randomUUID() + ".tmp");
        try {
            MessageDigest digest = sha256();
            long copied = 0;
            try (OutputStream output = Files.newOutputStream(temporary); DigestInputStream source = new DigestInputStream(input, digest)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = source.read(buffer)) != -1) {
                    copied += read;
                    if (copied > expectedLength) throw new IllegalArgumentException("chunk is larger than expected");
                    output.write(buffer, 0, read);
                }
            }
            if (copied != expectedLength || !HexFormat.of().formatHex(digest.digest()).equals(canonicalHash)) throw new IllegalArgumentException("chunk length or SHA-256 mismatch");
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
                return true;
            } catch (java.nio.file.FileAlreadyExistsException race) {
                if (Files.size(destination) == expectedLength && hash(destination).equals(canonicalHash)) return false;
                throw new IllegalStateException("chunk already exists with different content", race);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public synchronized Path complete(String uploadId, ChunkLayout layout, String expectedSha) throws IOException {
        String canonicalHash = checkedHash(expectedSha);
        Path destination = root.resolve("videos").resolve(canonicalHash + ".bin");
        Files.createDirectories(destination.getParent());
        Path temporary = destination.resolveSibling(canonicalHash + "." + UUID.randomUUID() + ".tmp");
        try {
            MessageDigest digest = sha256();
            long copied = 0;
            try (OutputStream output = Files.newOutputStream(temporary)) {
                for (int index = 0; index < layout.count(); index++) {
                    Path chunk = chunkPath(uploadId, index);
                    if (!hasChunk(uploadId, layout, index)) throw new IllegalStateException("missing or invalid chunk " + index);
                    try (InputStream source = Files.newInputStream(chunk)) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = source.read(buffer)) != -1) {
                            digest.update(buffer, 0, read);
                            output.write(buffer, 0, read);
                            copied += read;
                        }
                    }
                }
            }
            if (copied != layout.size() || !HexFormat.of().formatHex(digest.digest()).equals(canonicalHash)) throw new IllegalArgumentException("complete file SHA-256 mismatch");
            if (!Files.exists(destination)) {
                try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE); }
                catch (java.nio.file.FileAlreadyExistsException ignored) { /* identical content is already stored */ }
            }
            return destination;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String checkedHash(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("SHA-256 must be 64 hex characters");
        return value.toLowerCase();
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static String hash(Path path) throws IOException {
        MessageDigest digest = sha256();
        try (DigestInputStream input = new DigestInputStream(Files.newInputStream(path), digest)) { input.transferTo(OutputStream.nullOutputStream()); }
        return HexFormat.of().formatHex(digest.digest());
    }
}
