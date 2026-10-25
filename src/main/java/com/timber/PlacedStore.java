package com.timber;

import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Guarda quais troncos foram colocados por jogadores.
 * Os dados ficam dentro do proprio chunk (PersistentDataContainer),
 * entao sobrevivem a reinicios e nao precisam de arquivo separado.
 */
class PlacedStore {

    private final NamespacedKey key;
    private final Map<String, Set<Long>> cache = new HashMap<>();

    PlacedStore(NamespacedKey key) {
        this.key = key;
    }

    private static String id(Chunk c) {
        return c.getWorld().getUID() + ":" + c.getX() + ":" + c.getZ();
    }

    // posicao dentro do chunk (x e z de 0 a 15) + altura
    private static long pack(Block b) {
        return ((long) (b.getY() + 2048) << 8)
                | ((long) (b.getX() & 15) << 4)
                | (long) (b.getZ() & 15);
    }

    private Set<Long> load(Chunk c) {
        return cache.computeIfAbsent(id(c), k -> {
            Set<Long> set = new HashSet<>();
            long[] arr = c.getPersistentDataContainer().get(key, PersistentDataType.LONG_ARRAY);
            if (arr != null) {
                for (long v : arr) set.add(v);
            }
            return set;
        });
    }

    private void save(Chunk c, Set<Long> set) {
        PersistentDataContainer pdc = c.getPersistentDataContainer();
        if (set.isEmpty()) {
            pdc.remove(key);
        } else {
            pdc.set(key, PersistentDataType.LONG_ARRAY,
                    set.stream().mapToLong(Long::longValue).toArray());
        }
    }

    boolean isPlaced(Block b) {
        return load(b.getChunk()).contains(pack(b));
    }

    void mark(Block b) {
        Chunk c = b.getChunk();
        Set<Long> set = load(c);
        if (set.add(pack(b))) save(c, set);
    }

    void unmark(Block b) {
        Chunk c = b.getChunk();
        Set<Long> set = load(c);
        if (set.remove(pack(b))) save(c, set);
    }

    void unload(Chunk c) {
        cache.remove(id(c));
    }
}
