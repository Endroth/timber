package com.timber;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class TimberPlugin extends JavaPlugin implements Listener {

    private static final Tag<Material> LOGS = Tag.LOGS_THAT_BURN;
    private static final BlockFace[] FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
            BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private PlacedStore store;
    // jogadores cujo corte em cadeia esta em andamento (evita repetir o evento)
    private final Set<UUID> busy = new HashSet<>();

    private int maxLogs;
    private boolean requireAxe;
    private boolean sneakDisables;
    private boolean wearTool;
    private boolean removeLeaves;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        maxLogs = Math.max(1, getConfig().getInt("max-logs", 500));
        requireAxe = getConfig().getBoolean("exigir-machado", true);
        sneakDisables = getConfig().getBoolean("agachar-desativa", true);
        wearTool = getConfig().getBoolean("gastar-durabilidade", true);
        removeLeaves = getConfig().getBoolean("folhas-somem-na-hora", true);

        store = new PlacedStore(new NamespacedKey(this, "placed"));
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    // ---------- Registro de troncos colocados por jogadores ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (LOGS.isTagged(b.getType())) store.mark(b);
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        store.unload(e.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        for (Block b : e.blockList()) forget(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        for (Block b : e.blockList()) forget(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        forget(e.getBlock());
    }

    // arvore crescida de muda e arvore natural
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(StructureGrowEvent e) {
        for (BlockState s : e.getBlocks()) {
            if (LOGS.isTagged(s.getType())) store.unmark(s.getBlock());
        }
    }

    private void forget(Block b) {
        if (LOGS.isTagged(b.getType())) store.unmark(b);
    }

    // ---------- Quebra da arvore ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block origin = e.getBlock();
        if (!LOGS.isTagged(origin.getType())) return;
        if (busy.contains(p.getUniqueId())) return;

        // tronco colocado por jogador: quebra normal, sem cortar a arvore
        boolean wasPlaced = store.isPlaced(origin);
        store.unmark(origin);
        if (wasPlaced) return;

        if (!p.hasPermission("timber.use")) return;
        if (p.getGameMode() == GameMode.CREATIVE) return;
        if (sneakDisables && p.isSneaking()) return;

        ItemStack tool = p.getInventory().getItemInMainHand();
        if (requireAxe && !tool.getType().name().endsWith("_AXE")) return;

        List<Block> tree = findTree(origin);
        if (tree.isEmpty()) return;
        if (!hasNaturalLeaves(origin, tree)) return; // nao parece uma arvore

        chop(p, tool, origin, tree);
    }

    /** Acha os troncos ligados ao tronco quebrado, da mesma especie e nao colocados por jogador. */
    private List<Block> findTree(Block origin) {
        String species = species(origin.getType());
        World world = origin.getWorld();

        Set<Block> visited = new HashSet<>();
        List<Block> tree = new ArrayList<>();
        Deque<Block> queue = new ArrayDeque<>();
        visited.add(origin);
        queue.add(origin);

        while (!queue.isEmpty() && tree.size() < maxLogs) {
            Block cur = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        int x = cur.getX() + dx;
                        int y = cur.getY() + dy;
                        int z = cur.getZ() + dz;
                        if (y < world.getMinHeight() || y >= world.getMaxHeight()) continue;
                        if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;

                        Block n = world.getBlockAt(x, y, z);
                        if (!visited.add(n)) continue;
                        if (!LOGS.isTagged(n.getType())) continue;
                        if (!species(n.getType()).equals(species)) continue;
                        if (store.isPlaced(n)) continue;

                        tree.add(n);
                        queue.add(n);
                    }
                }
            }
        }

        if (tree.size() > maxLogs) return new ArrayList<>(tree.subList(0, maxLogs));
        return tree;
    }

    /** Folhas de arvore natural nao sao "persistentes"; folhas colocadas por jogador sao. */
    private boolean hasNaturalLeaves(Block origin, List<Block> tree) {
        if (touchesNaturalLeaves(origin)) return true;
        for (Block b : tree) {
            if (touchesNaturalLeaves(b)) return true;
        }
        return false;
    }

    private boolean touchesNaturalLeaves(Block b) {
        for (BlockFace f : FACES) {
            Block n = b.getRelative(f);
            if (Tag.LEAVES.isTagged(n.getType())
                    && n.getBlockData() instanceof Leaves leaves
                    && !leaves.isPersistent()) {
                return true;
            }
        }
        return false;
    }

    private void chop(Player p, ItemStack tool, Block origin, List<Block> tree) {
        List<Block> removed = new ArrayList<>();
        busy.add(p.getUniqueId());
        try {
            for (Block log : tree) {
                if (!LOGS.isTagged(log.getType())) continue;
                if (wearTool && nearlyBroken(tool)) break;

                // dispara um evento de quebra para cada tronco:
                // respeita protecoes e faz a scoreboard contar cada um
                BlockBreakEvent ev = new BlockBreakEvent(log, p);
                Bukkit.getPluginManager().callEvent(ev);
                if (ev.isCancelled()) continue;

                if (ev.isDropItems()) {
                    log.breakNaturally(tool);
                } else {
                    log.setType(Material.AIR);
                }
                removed.add(log);
                if (wearTool) damage(tool);
            }
        } finally {
            busy.remove(p.getUniqueId());
        }
        p.getInventory().setItemInMainHand(tool);

        if (removeLeaves) clearLeaves(origin, removed);
    }

    private static final int DECAY_RANGE = 6;     // folha a ate 6 passos de um tronco fica viva
    private static final int SUPPORT_RANGE = 12;  // regiao analisada para achar outras arvores

    /**
     * Faz as folhas que iriam apodrecer sumirem na hora, como o decaimento normal
     * (mesmos drops de mudas e gravetos). Folhas que ainda estao ligadas a outra
     * arvore, e folhas colocadas por jogadores, continuam no lugar.
     */
    private void clearLeaves(Block origin, List<Block> removed) {
        // o tronco quebrado pelo jogador ainda existe neste momento, entao conta como removido
        Set<Block> gone = new HashSet<>(removed);
        gone.add(origin);

        // 1) folhas ao redor dos troncos cortados (depth = passos desde um tronco cortado)
        Map<Block, Integer> depth = new HashMap<>();
        Deque<Block> queue = new ArrayDeque<>();
        for (Block log : gone) {
            for (BlockFace f : FACES) {
                Block n = log.getRelative(f);
                if (Tag.LEAVES.isTagged(n.getType()) && !depth.containsKey(n)) {
                    depth.put(n, 1);
                    queue.add(n);
                }
            }
        }
        while (!queue.isEmpty()) {
            Block cur = queue.poll();
            int d = depth.get(cur);
            if (d >= SUPPORT_RANGE) continue;
            for (BlockFace f : FACES) {
                Block n = cur.getRelative(f);
                if (depth.containsKey(n) || !Tag.LEAVES.isTagged(n.getType())) continue;
                depth.put(n, d + 1);
                queue.add(n);
            }
        }

        // 2) quais folhas ainda tem um tronco por perto (regra do jogo: ate 6 passos)
        Map<Block, Integer> dist = new HashMap<>();
        for (Block leaf : depth.keySet()) {
            for (BlockFace f : FACES) {
                Block n = leaf.getRelative(f);
                if (Tag.LOGS.isTagged(n.getType()) && !gone.contains(n)) {
                    dist.put(leaf, 1);
                    queue.add(leaf);
                    break;
                }
            }
        }
        while (!queue.isEmpty()) {
            Block cur = queue.poll();
            int d = dist.get(cur);
            if (d >= DECAY_RANGE) continue;
            for (BlockFace f : FACES) {
                Block n = cur.getRelative(f);
                if (depth.containsKey(n) && !dist.containsKey(n)) {
                    dist.put(n, d + 1);
                    queue.add(n);
                }
            }
        }

        // 3) remove as folhas naturais que ficaram sem tronco
        for (Map.Entry<Block, Integer> en : depth.entrySet()) {
            if (en.getValue() > DECAY_RANGE) continue;
            Block leaf = en.getKey();
            if (dist.containsKey(leaf)) continue;
            if (!(leaf.getBlockData() instanceof Leaves data) || data.isPersistent()) continue;

            LeavesDecayEvent ev = new LeavesDecayEvent(leaf);
            Bukkit.getPluginManager().callEvent(ev);
            if (!ev.isCancelled()) leaf.breakNaturally();
        }
    }

    private boolean nearlyBroken(ItemStack tool) {
        if (tool.getItemMeta() instanceof Damageable d) {
            return tool.getType().getMaxDurability() - d.getDamage() <= 1;
        }
        return false;
    }

    private void damage(ItemStack tool) {
        ItemMeta meta = tool.getItemMeta();
        if (meta instanceof Damageable d) {
            d.setDamage(d.getDamage() + 1);
            tool.setItemMeta(meta);
        }
    }

    /** OAK_LOG, STRIPPED_OAK_LOG, OAK_WOOD... -> "OAK" */
    private static String species(Material m) {
        String n = m.name();
        if (n.startsWith("STRIPPED_")) n = n.substring(9);
        if (n.endsWith("_LOG")) return n.substring(0, n.length() - 4);
        if (n.endsWith("_WOOD")) return n.substring(0, n.length() - 5);
        return n;
    }
}
