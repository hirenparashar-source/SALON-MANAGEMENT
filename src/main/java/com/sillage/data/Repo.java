package com.sillage.data;

import com.sillage.core.JsonMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One collection of the document store: an id-keyed map of immutable records plus any number of
 * secondary indexes, guarded by a read/write lock.
 *
 * <p>Records are Java records, so a "write" is always a full replacement — {@link #put(Object)}
 * stores the new version of the entity and re-points the indexes at it. Reads never block each
 * other; a write takes the exclusive lock, bumps a mutation counter and asks the owning
 * {@link DataStore} for a debounced save.
 *
 * <p>Indexes are plain functions of the record ({@code c -> lower(c.email())}). They exist for the
 * lookups the salon actually performs — phone, code, invoice number, staff member, session owner —
 * and they are the reason a customer search on 30 000 clients is still a map hit rather than a scan.
 *
 * @param <T> the record type stored in this collection
 */
public final class Repo<T> {

    /** Called after a mutation so the store can schedule a debounced, coalesced save. */
    @FunctionalInterface
    public interface Saver {
        void scheduleSave(Repo<?> repo);
    }

    private final String name;
    private final Class<T> type;
    private final Function<T, String> idOf;

    private final Map<String, T> rows = new LinkedHashMap<>();
    private final Map<String, Function<T, String>> indexers = new LinkedHashMap<>();
    /** index name -> key -> ids (insertion ordered, so a tie keeps book order). */
    private final Map<String, Map<String, List<String>>> postings = new LinkedHashMap<>();
    /** index name -> id -> key currently held, used to un-index the previous version. */
    private final Map<String, Map<String, String>> keysById = new LinkedHashMap<>();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicLong mutations = new AtomicLong();
    private final Saver saver;
    private volatile boolean dirty;

    public Repo(String name, Class<T> type, Function<T, String> idOf) {
        this(name, type, idOf, null);
    }

    public Repo(String name, Class<T> type, Function<T, String> idOf, Saver saver) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("a collection needs a name");
        if (type == null) throw new IllegalArgumentException(name + ": a collection needs a record type");
        if (idOf == null) throw new IllegalArgumentException(name + ": a collection needs an id extractor");
        this.name = name;
        this.type = type;
        this.idOf = idOf;
        this.saver = saver;
    }

    // ------------------------------------------------------------------ shape

    public String name() { return name; }

    public Class<T> type() { return type; }

    /** Total number of writes this collection has seen since the JVM started. */
    public long mutations() { return mutations.get(); }

    /** True while the in-memory state is ahead of what is on disk. */
    public boolean dirty() { return dirty; }

    public void markSaved() { dirty = false; }

    // ------------------------------------------------------------------ indexes

    /**
     * Registers (or replaces) a secondary index. Existing rows are indexed immediately, so an
     * index may be declared after a load. Returns this repo, which is how {@code Salon} chains
     * {@code repo("customers", ...).index("phone", ...)}.
     */
    public Repo<T> index(String field, Function<T, String> keyOf) {
        if (field == null || field.isBlank() || keyOf == null) {
            throw new IllegalArgumentException(name + ": an index needs a field name and a key function");
        }
        lock.writeLock().lock();
        try {
            indexers.put(field, keyOf);
            Map<String, List<String>> table = new LinkedHashMap<>();
            Map<String, String> seen = new LinkedHashMap<>();
            postings.put(field, table);
            keysById.put(field, seen);
            for (Map.Entry<String, T> row : rows.entrySet()) {
                String key = safeKey(keyOf, row.getValue());
                if (key == null || key.isEmpty()) continue;
                table.computeIfAbsent(key, k -> new ArrayList<>()).add(row.getKey());
                seen.put(row.getKey(), key);
            }
            return this;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<String> indexNames() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(indexers.keySet());
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Every record whose indexed {@code field} equals {@code key}. Never null. */
    public List<T> byIndex(String field, String key) {
        List<T> out = new ArrayList<>();
        if (field == null || key == null || key.isEmpty()) return out;
        lock.readLock().lock();
        try {
            Map<String, List<String>> table = postings.get(field);
            if (table == null) return fallbackScan(field, key, out);
            List<String> ids = table.get(key);
            if (ids == null || ids.isEmpty()) return out;
            for (String id : ids) {
                T row = rows.get(id);
                if (row != null) out.add(row);
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<T> firstByIndex(String field, String key) {
        List<T> hits = byIndex(field, key);
        return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
    }

    /** An index registered after rows existed still answers; scan rather than lie. */
    private List<T> fallbackScan(String field, String key, List<T> out) {
        Function<T, String> keyOf = indexers.get(field);
        if (keyOf == null) return out;
        for (T row : rows.values()) {
            if (key.equals(safeKey(keyOf, row))) out.add(row);
        }
        return out;
    }

    // ------------------------------------------------------------------ writes

    /**
     * Stores {@code entity}, replacing any record with the same id, and asks the store for a save.
     *
     * @return the entity that was stored (records are immutable, so it is the same object)
     */
    public T put(T entity) {
        if (entity == null) throw new IllegalArgumentException(name + ": cannot store a null record");
        String id = idOf.apply(entity);
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(name + ": a record needs an id before it can be stored");
        }
        lock.writeLock().lock();
        try {
            rows.put(id, entity);
            reindex(id, entity);
            mutations.incrementAndGet();
            dirty = true;
        } finally {
            lock.writeLock().unlock();
        }
        notifyChanged();
        return entity;
    }

    /** Removes the record with {@code id}; true if something was actually removed. */
    public boolean remove(String id) {
        if (id == null || id.isBlank()) return false;
        boolean removed;
        lock.writeLock().lock();
        try {
            removed = rows.remove(id) != null;
            if (removed) {
                unindex(id);
                mutations.incrementAndGet();
                dirty = true;
            }
        } finally {
            lock.writeLock().unlock();
        }
        if (removed) notifyChanged();
        return removed;
    }

    /**
     * Replaces the whole collection in one go. Used by {@link DataStore} for a load and for a
     * restore; it marks the repo dirty but does <em>not</em> schedule a save, because the caller
     * decides whether the new state should go back to disk.
     */
    public void replaceAll(Collection<? extends T> items) {
        lock.writeLock().lock();
        try {
            rows.clear();
            for (Map<String, List<String>> table : postings.values()) table.clear();
            for (Map<String, String> seen : keysById.values()) seen.clear();
            if (items != null) {
                for (T item : items) {
                    if (item == null) continue;
                    String id = idOf.apply(item);
                    if (id == null || id.isBlank()) continue;
                    rows.put(id, item);
                    reindex(id, item);
                    mutations.incrementAndGet();
                }
            }
            dirty = true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Empties the collection and schedules a save, so the file on disk follows. */
    public void clear() {
        boolean hadRows;
        lock.writeLock().lock();
        try {
            hadRows = !rows.isEmpty();
            rows.clear();
            for (Map<String, List<String>> table : postings.values()) table.clear();
            for (Map<String, String> seen : keysById.values()) seen.clear();
            mutations.incrementAndGet();
            dirty = true;
        } finally {
            lock.writeLock().unlock();
        }
        if (hadRows) notifyChanged();
    }

    private void reindex(String id, T entity) {
        for (Map.Entry<String, Function<T, String>> entry : indexers.entrySet()) {
            String field = entry.getKey();
            String key = safeKey(entry.getValue(), entity);
            Map<String, String> seen = keysById.get(field);
            Map<String, List<String>> table = postings.get(field);
            if (seen == null || table == null) continue;

            String previous = seen.remove(id);
            if (previous != null) {
                List<String> ids = table.get(previous);
                if (ids != null) {
                    ids.remove(id);
                    if (ids.isEmpty()) table.remove(previous);
                }
            }
            if (key != null && !key.isEmpty()) {
                table.computeIfAbsent(key, k -> new ArrayList<>()).add(id);
                seen.put(id, key);
            }
        }
    }

    private void unindex(String id) {
        for (String field : indexers.keySet()) {
            Map<String, String> seen = keysById.get(field);
            Map<String, List<String>> table = postings.get(field);
            if (seen == null || table == null) continue;
            String previous = seen.remove(id);
            if (previous == null) continue;
            List<String> ids = table.get(previous);
            if (ids != null) {
                ids.remove(id);
                if (ids.isEmpty()) table.remove(previous);
            }
        }
    }

    private static <T> String safeKey(Function<T, String> keyOf, T entity) {
        try {
            return keyOf.apply(entity);
        } catch (RuntimeException e) {
            // A half-filled record must not stop a write; it simply is not indexed on that field.
            return null;
        }
    }

    private void notifyChanged() {
        Saver callback = saver;
        if (callback == null) return;
        try {
            callback.scheduleSave(this);
        } catch (RuntimeException e) {
            // Persistence problems are logged by the store; never fail the caller's write.
        }
    }

    // ------------------------------------------------------------------ reads

    /** The record with this id, or {@code null}. */
    public T get(String id) {
        if (id == null || id.isBlank()) return null;
        lock.readLock().lock();
        try {
            return rows.get(id);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<T> find(String id) {
        return Optional.ofNullable(get(id));
    }

    public boolean contains(String id) {
        return get(id) != null;
    }

    /** A mutable snapshot of every record, in book order. Safe to sort. */
    public List<T> all() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(rows.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<T> where(Predicate<T> test) {
        List<T> out = new ArrayList<>();
        if (test == null) return out;
        lock.readLock().lock();
        try {
            for (T row : rows.values()) {
                if (matches(test, row)) out.add(row);
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<T> firstWhere(Predicate<T> test) {
        if (test == null) return Optional.empty();
        lock.readLock().lock();
        try {
            for (T row : rows.values()) {
                if (matches(test, row)) return Optional.of(row);
            }
            return Optional.empty();
        } finally {
            lock.readLock().unlock();
        }
    }

    public long countWhere(Predicate<T> test) {
        if (test == null) return 0L;
        lock.readLock().lock();
        try {
            long count = 0;
            for (T row : rows.values()) if (matches(test, row)) count++;
            return count;
        } finally {
            lock.readLock().unlock();
        }
    }

    private static <T> boolean matches(Predicate<T> test, T row) {
        try {
            return test.test(row);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return rows.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean isEmpty() { return size() == 0; }

    /** Plain JSON documents, in book order — what goes on disk and into a snapshot. */
    public List<Map<String, Object>> documents() {
        lock.readLock().lock();
        try {
            List<Map<String, Object>> out = new ArrayList<>(rows.size());
            for (T row : rows.values()) {
                try {
                    out.add(JsonMapper.toMap(row));
                } catch (RuntimeException e) {
                    throw new IllegalStateException(name + ": cannot serialise record " + idOf.apply(row), e);
                }
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public String toString() {
        return "Repo[" + name + " " + size() + " records" + (dirty() ? ", dirty" : "") + "]";
    }
}
