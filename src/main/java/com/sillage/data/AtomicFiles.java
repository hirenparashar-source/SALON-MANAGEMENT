package com.sillage.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Crash-safe file writes.
 *
 * <p>Nothing in SILLAGE ever writes straight onto a book: the bytes go to a sibling temp file
 * first and are then renamed over the target. A rename is atomic on every filesystem we care
 * about, so a crash mid-save leaves either the old document or the new one — never a truncated
 * half-line that would take the whole collection down on the next boot.
 */
public final class AtomicFiles {

    private AtomicFiles() {}

    /** Writes UTF-8 text atomically, creating parent directories as needed. */
    public static void write(Path target, String text) throws IOException {
        write(target, text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8));
    }

    /** Writes raw bytes atomically, creating parent directories as needed. */
    public static void write(Path target, byte[] bytes) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);

        Path tmp = Files.createTempFile(parent, absolute.getFileName() + ".", ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Network shares and some containers refuse ATOMIC_MOVE; a plain replace is
                // still far better than writing in place.
                Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            deleteQuietly(tmp);
            throw e;
        }
    }

    public static String readString(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    public static boolean exists(Path file) {
        return file != null && Files.isRegularFile(file);
    }

    public static void deleteQuietly(Path file) {
        try {
            if (file != null) Files.deleteIfExists(file);
        } catch (IOException | RuntimeException ignored) {
            // Nothing useful to do: a leftover temp file is harmless.
        }
    }

    public static long sizeOf(Path file) {
        try {
            return file != null && Files.isRegularFile(file) ? Files.size(file) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    /** Total size of every regular file under {@code dir} (recursive), 0 if it does not exist. */
    public static long directoryBytes(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return 0L;
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(AtomicFiles::sizeOf).sum();
        } catch (IOException | RuntimeException e) {
            return 0L;
        }
    }
}
