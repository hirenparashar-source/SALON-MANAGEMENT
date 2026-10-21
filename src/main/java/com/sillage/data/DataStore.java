package com.sillage.data;

import com.sillage.core.Json;
import com.sillage.core.JsonMapper;
import com.sillage.core.Log;
import com.sillage.core.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The document store: one JSON file per collection under {@code data/}, plus snapshots under
 * {@code data/backups/}.
 *
 * <p>A collection is registered once at boot with {@link #repo(String, Class, Function)}. From then
 * on every {@link Repo#put} asks for a save, saves are debounced by {@value #DEBOUNCE_MILLIS} ms and
 * coalesced onto a single daemon writer thread, and the bytes always go through
 * {@link AtomicFiles} — temp file, then rename. A crash therefore costs at most the last quarter
 * second of edits and can never truncate a book.
 *
 * <p>There is no schema and no migration step: a document is whatever the record looked like, and
 * {@link JsonMapper} fills in defaults for keys an older file does not have. Adding a field to a
 * model is the whole upgrade procedure.
 */
public final class DataStore implements AutoCloseable {

    /** How long a burst of writes is collected before it hits the disk. */
    private static final long DEBOUNCE_MILLIS = 250;

    /** Snapshots bigger than this are described from their filename instead of being parsed. */
    private static final long METADATA_PARSE_LIMIT = 4L * 1024 * 1024;

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path directory;
    private final Path backupDirectory;
    private final Instant openedAt = Instant.now();

    private final Map<String, Repo<?>> byName = new ConcurrentHashMap<>();
    private final List<Repo<?>> ordered = new CopyOnWriteArrayList<>();
    private final Map<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> snapshotCache = new ConcurrentHashMap<>();

    private final ScheduledExecutorService writer;
    private volatile boolean closed;

    public DataStore(Path directory) {
        this.directory = directory == null ? Path.of("data") : directory.toAbsolutePath().normalize();
        this.backupDirectory = this.directory.resolve("backups");
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "sillage-store");
            thread.setDaemon(true);
            return thread;
        };
        this.writer = Executors.newSingleThreadScheduledExecutor(factory);
        try {
            Files.createDirectories(this.directory);
            Files.createDirectories(this.backupDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("cannot open the data directory " + this.directory + ": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ collections

    /**
     * Registers a collection (idempotent: an existing name is returned unchanged) and wires it to
     * the debounced saver.
     */
    public synchronized <T> Repo<T> repo(String name, Class<T> type, Function<T, String> idOf) {
        Repo<?> existing = byName.get(name);
        if (existing != null) {
            if (!type.equals(existing.type())) {
                throw new IllegalStateException("collection " + name + " is already registered as "
                        + existing.type().getSimpleName() + ", not " + type.getSimpleName());
            }
            return cast(existing, type);
        }
        Repo<T> repo = new Repo<>(name, type, idOf, this::scheduleSave);
        byName.put(name, repo);
        ordered.add(repo);
        return repo;
    }

    @SuppressWarnings("unchecked")
    private <T> Repo<T> cast(Repo<?> repo, Class<T> type) { return (Repo<T>) repo; }

    public Repo<?> repoOrNull(String name) { return name == null ? null : byName.get(name); }

    public List<String> collectionNames() {
        List<String> out = new ArrayList<>(ordered.size());
        for (Repo<?> repo : ordered) out.add(repo.name());
        return out;
    }

    public Path fileFor(String collection) { return directory.resolve(collection + ".json"); }

    public Path directory() { return directory; }

    public Path backupDirectory() { return backupDirectory; }

    public Instant openedAt() { return openedAt; }

    /** collection name -> record count; the shape the studio's data room renders. */
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Repo<?> repo : ordered) out.put(repo.name(), repo.size());
        return out;
    }

    public int documents() {
        int total = 0;
        for (Repo<?> repo : ordered) total += repo.size();
        return total;
    }

    public long diskUsageBytes() { return AtomicFiles.directoryBytes(directory); }

    // ------------------------------------------------------------------ load

    /** Reads every registered collection from disk. Missing files simply mean an empty book. */
    public void load() {
        int total = 0;
        int files = 0;
        for (Repo<?> repo : ordered) {
            Path file = fileFor(repo.name());
            if (!Files.isRegularFile(file)) continue;
            try {
                String text = AtomicFiles.readString(file);
                if (text == null || text.isBlank()) continue;
                int count = loadInto(repo, documentsOf(Json.parse(text), repo.name()));
                total += count;
                files++;
            } catch (RuntimeException | IOException e) {
                Path quarantine = directory.resolve(repo.name() + ".corrupt-" + System.currentTimeMillis() + ".json");
                Log.error("store: %s is unreadable (%s) — keeping it aside as %s and starting that collection empty",
                        file.getFileName(), e.getMessage(), quarantine.getFileName());
                try {
                    Files.move(file, quarantine, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // leave the damaged file where it is; nothing more we can safely do
                }
            }
        }
        if (files > 0) {
            Log.info("store: loaded %s records from %d collections in %s", total, files, directory);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> documentsOf(Object parsed, String collection) {
        if (parsed instanceof Collection<?> list) return new ArrayList<>((Collection<Object>) list);
        Map<String, Object> root = JsonMapper.asMap(parsed);
        Object docs = root.get("documents");
        if (docs == null) docs = root.get(collection);
        if (docs == null) {
            throw new IllegalArgumentException("no \"documents\" array in " + collection + ".json");
        }
        return JsonMapper.asList(docs);
    }

    private <T> int loadInto(Repo<T> repo, List<Object> documents) {
        List<T> items = new ArrayList<>(documents.size());
        for (Object document : documents) items.add(JsonMapper.from(document, repo.type(), null));
        repo.replaceAll(items);
        repo.markSaved();
        return items.size();
    }

    // ------------------------------------------------------------------ save

    /** Called by {@link Repo} after a mutation: coalesce into one write a quarter second later. */
    void scheduleSave(Repo<?> repo) {
        if (closed) return;
        String name = repo.name();
        synchronized (pending) {
            if (closed) return;
            ScheduledFuture<?> previous = pending.get(name);
            if (previous != null) previous.cancel(false);
            try {
                pending.put(name, writer.schedule(() -> flushQuietly(name), DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS));
            } catch (RuntimeException e) {
                pending.remove(name);
                Log.warn("store: cannot schedule a save of %s (%s)", name, e.getMessage());
            }
        }
    }

    private void flushQuietly(String name) {
        pending.remove(name);
        Repo<?> repo = byName.get(name);
        if (repo == null) return;
        try {
            writeCollection(repo);
        } catch (IOException | RuntimeException e) {
            // Stays dirty, so the next write (or shutdown) tries again.
            Log.error("store: could not save %s: %s", name, e.getMessage());
        }
    }

    /** Writes every collection that is ahead of the disk (and any file that does not exist yet). */
    public int flushAll() {
        synchronized (pending) {
            for (ScheduledFuture<?> task : pending.values()) task.cancel(false);
            pending.clear();
        }
        int written = 0;
        int failed = 0;
        for (Repo<?> repo : ordered) {
            Path file = fileFor(repo.name());
            if (!repo.dirty() && Files.isRegularFile(file)) continue;
            try {
                writeCollection(repo);
                written++;
            } catch (IOException | RuntimeException e) {
                failed++;
                Log.error("store: could not save %s: %s", repo.name(), e.getMessage());
            }
        }
        if (failed > 0) Log.error("store: %d of %d collections could not be written", failed, ordered.size());
        return written;
    }

    private void writeCollection(Repo<?> repo) throws IOException {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("collection", repo.name());
        document.put("savedAt", Instant.now().toString());
        document.put("documents", repo.documents());
        AtomicFiles.write(fileFor(repo.name()), Json.writePretty(document));
        repo.markSaved();
    }

    // ------------------------------------------------------------------ snapshots

    /** Copies every collection into {@code data/backups/sillage-<timestamp>.json}. */
    public Path snapshot(String label) throws IOException {
        flushAll();
        Files.createDirectories(backupDirectory);

        Map<String, Object> counts = new LinkedHashMap<>();
        Map<String, Object> collections = new LinkedHashMap<>();
        for (Repo<?> repo : ordered) {
            List<Map<String, Object>> documents = repo.documents();
            counts.put(repo.name(), documents.size());
            collections.put(repo.name(), documents);
        }

        String stamp = LocalDateTime.now().format(FILE_STAMP);
        String name = "sillage-" + stamp + ".json";
        Path file = backupDirectory.resolve(name);
        for (int suffix = 2; Files.exists(file); suffix++) {
            name = "sillage-" + stamp + "-" + suffix + ".json";
            file = backupDirectory.resolve(name);
        }

        Instant takenAt = Instant.now();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("snapshot", name);
        root.put("label", label == null || label.isBlank() ? "manual" : label.trim());
        root.put("takenAt", takenAt.toString());
        root.put("app", "SILLAGE");
        root.put("version", 1);
        root.put("counts", counts);
        root.put("collections", collections);
        AtomicFiles.write(file, Json.writePretty(root));
        snapshotCache.remove(cacheKey(file));
        return file;
    }

    /** Newest first: name, when it was taken, how big it is and what it holds. */
    public List<Map<String, Object>> snapshots() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(backupDirectory)) return out;
        List<Path> files;
        try (Stream<Path> walk = Files.list(backupDirectory)) {
            files = walk.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException | RuntimeException e) {
            Log.warn("store: cannot list snapshots (%s)", e.getMessage());
            return out;
        }
        for (Path file : files) out.add(snapshotMetadata(file));
        if (snapshotCache.size() > 128) snapshotCache.clear();
        return out;
    }

    private Map<String, Object> snapshotMetadata(Path file) {
        String key = cacheKey(file);
        Map<String, Object> cached = snapshotCache.get(key);
        if (cached != null) return cached;

        String name = file.getFileName().toString();
        long bytes = AtomicFiles.sizeOf(file);
        Instant at = stampFromName(name);
        if (at == null) at = Instant.ofEpochMilli(file.toFile().lastModified());
        String label = "";
        int documents = 0;
        int collections = 0;

        if (bytes > 0 && bytes <= METADATA_PARSE_LIMIT) {
            try {
                Map<String, Object> root = JsonMapper.asMap(Json.parse(AtomicFiles.readString(file)));
                label = JsonMapper.asText(root.get("label"));
                Map<String, Object> counts = JsonMapper.asMap(root.get("counts"));
                Object body = root.get("collections");
                if (counts.isEmpty() && body instanceof Map<?, ?> map) {
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        counts.put(String.valueOf(entry.getKey()), JsonMapper.asList(entry.getValue()).size());
                    }
                }
                collections = counts.size();
                for (Object value : counts.values()) documents += JsonMapper.asInt(value, 0);
                Instant taken = JsonMapper.asInstant(root.get("takenAt"));
                if (taken != null) at = taken;
            } catch (IOException | RuntimeException e) {
                Log.warn("store: snapshot %s is unreadable (%s)", name, e.getMessage());
            }
        }

        Map<String, Object> meta = Util.map("name", name, "file", name, "at", at.toString(),
                "label", label, "bytes", bytes, "human", humanBytes(bytes),
                "collections", collections, "documents", documents);
        snapshotCache.put(key, meta);
        return meta;
    }

    private static String cacheKey(Path file) {
        return file.getFileName() + ":" + AtomicFiles.sizeOf(file) + ":" + file.toFile().lastModified();
    }

    private static Instant stampFromName(String name) {
        try {
            String body = name.substring("sillage-".length(), name.length() - ".json".length());
            int dash = body.lastIndexOf('-');
            if (dash > 0 && body.substring(dash + 1).chars().allMatch(Character::isDigit)) body = body.substring(0, dash);
            return LocalDateTime.parse(body, FILE_STAMP).atZone(ZoneId.systemDefault()).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Replays a snapshot: every collection it contains is replaced wholesale. Collections that no
     * longer exist in this build are skipped, and the caller rebuilds the search index.
     *
     * @return collection name -> records restored
     */
    public Map<String, Integer> restore(String name) throws IOException {
        if (name == null || name.isBlank()) throw new IOException("no snapshot name given");
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IOException("not a snapshot name: " + name);
        }
        Path file = backupDirectory.resolve(name).normalize();
        if (!file.startsWith(backupDirectory)) throw new IOException("snapshots must live in " + backupDirectory);
        if (!Files.isRegularFile(file)) throw new IOException("no such snapshot: " + name);

        Map<String, Object> root = JsonMapper.asMap(Json.parse(AtomicFiles.readString(file)));
        Map<String, Object> collections = new LinkedHashMap<>(JsonMapper.asMap(root.get("collections")));
        if (collections.isEmpty() && root.get("documents") != null) {
            String single = JsonMapper.asText(root.get("collection"));
            if (!single.isBlank()) collections.put(single, root.get("documents"));
        }
        if (collections.isEmpty()) throw new IOException(name + " holds no collections");

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : collections.entrySet()) {
            Repo<?> repo = byName.get(entry.getKey());
            if (repo == null) {
                Log.warn("store: snapshot %s mentions unknown collection %s — skipped", name, entry.getKey());
                continue;
            }
            counts.put(entry.getKey(), restoreInto(repo, JsonMapper.asList(entry.getValue())));
        }
        flushAll();
        snapshotCache.remove(cacheKey(file));
        return counts;
    }

    private <T> int restoreInto(Repo<T> repo, List<Object> documents) {
        List<T> items = new ArrayList<>(documents.size());
        for (Object document : documents) items.add(JsonMapper.from(document, repo.type(), null));
        repo.replaceAll(items);
        return items.size();
    }

    // ------------------------------------------------------------------ wipe & close

    /** Empties every collection and deletes its file. Snapshots and {@code receipt.key} survive. */
    public void wipe() {
        for (Repo<?> repo : ordered) {
            repo.replaceAll(List.of());
            repo.markSaved();
            AtomicFiles.deleteQuietly(fileFor(repo.name()));
        }
        synchronized (pending) {
            for (ScheduledFuture<?> task : pending.values()) task.cancel(false);
            pending.clear();
        }
        Log.warn("store: every collection cleared (%s)", directory);
    }

    /** Flushes and stops the writer thread; safe to call twice. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        flushAll();
        writer.shutdown();
        try {
            if (!writer.awaitTermination(3, TimeUnit.SECONDS)) writer.shutdownNow();
        } catch (InterruptedException e) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"KB", "MB", "GB"};
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
        return Math.round(value * 10) / 10.0 + " " + units[Math.max(0, unit)];
    }
}
