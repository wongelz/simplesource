package io.simplesource.kafka.internal.client;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * ExpiringMap is a Map type that allows you to
 * 1. Create a map entry at a particular time
 * 1. Modify any existing map entries as long as they are present
 * 2. Expire all map entries at a certain point, calling the supplied cleanup function
 *
 * @param <K> Key type
 * @param <V> Value type
 */
final class ExpiringMap<K, V> {

    private final ConcurrentHashMap<Long, ConcurrentHashMap<K, V>> outerMap = new ConcurrentHashMap<>();
    private final long retentionInSeconds;
    private final Clock clock;

    ExpiringMap(long retentionInSeconds, Clock clock) {
        this.retentionInSeconds = retentionInSeconds;
        this.clock = clock;
    }

    final V createEntry(K k, Supplier<V> lazyV) {
        long outerKey = Instant.now(clock).getEpochSecond() / retentionInSeconds;
        ConcurrentHashMap<K, V> innerMap = outerMap.computeIfAbsent(outerKey, oKey -> new ConcurrentHashMap<>());
        return innerMap.computeIfAbsent(k, ik -> lazyV.get());
    }

    final V computeIfPresent(K k, Function<V, V> vToV) {
        for (ConcurrentHashMap<K, V> inner: outerMap.values()) {
            V newV = inner.computeIfPresent(k, (ik, v) -> vToV.apply(v));
            if (newV != null)
                return newV;
        }
        return null;
    }

    final void removeStale(Consumer<V> consumeV)  {
        if (outerMap.size() < 3) return;
        new Thread(() -> {
            long outerKey = Instant.now(clock).getEpochSecond() / retentionInSeconds;
            outerMap.keySet().forEach(k -> {
                if (k + 1 < outerKey) {
                    outerMap.values().forEach(innerMap -> {
                        innerMap.values().forEach(consumeV);
                    });
                    outerMap.remove(k);
                }
            });
        }).start();
    }
}