package com.diliupengyun.differential_create.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/** Weak observations never expose a live iterator to rendering callbacks. */
public final class RenderObservationCache<K> {
    private final Set<K> entries = Collections.newSetFromMap(new WeakHashMap<>());

    public synchronized void observe(K entry) {
        entries.add(entry);
    }

    /** Pin this frame's live entries, then release the lock before rendering. */
    public synchronized List<K> snapshot(Predicate<? super K> removed) {
        entries.removeIf(removed);
        return new ArrayList<>(entries);
    }

    public synchronized void clear() {
        entries.clear();
    }
}
