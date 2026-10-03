package bm.minecraft.tnt.plus;

import org.bukkit.ChatColor;
import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Extends TNT explosions to a cubic Chebyshev range. */
public final class BmMinecraftTntPlusPlugin extends JavaPlugin implements Listener {
    private static final int MAX_EXPLOSION_RADIUS = 128;
    private static final float BREAK_INTENSITY = 4.0F;
    private final Map<String, UUID> placedTnt = new HashMap<>();
    private final Map<String, UUID> pendingOwners = new HashMap<>();
    private final Map<UUID, UUID> tntOwners = new HashMap<>();
    private final Map<UUID, UUID> explosionOwners = new HashMap<>();
    private YamlConfiguration language;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadLanguage();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand command = getCommand("bm-minecraft-tnt-plus");
        if (command == null) throw new IllegalStateException("Missing command in plugin.yml: bm-minecraft-tnt-plus");
        command.setExecutor(this::runCommand);
        command.setTabCompleter((sender, ignored, label, args) -> args.length == 1 ? List.of("0", "1", "reload", "info", "status", "set").stream().filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT))).toList() : List.of());
        getLogger().info(console("enabled").replace("{version}", getPluginMeta().getVersion()));
    }

    @Override
    public void onDisable() {
        if (language != null) {
            getLogger().info(console("disabled"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() != Material.TNT) return;
        placedTnt.put(blockKey(event.getBlockPlaced()), event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        placedTnt.remove(blockKey(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (event.getEntityType() != EntityType.TNT_MINECART || event.getPlayer() == null) return;
        tntOwners.put(event.getEntity().getUniqueId(), event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTntPrime(TNTPrimeEvent event) {
        UUID owner = placedTnt.remove(blockKey(event.getBlock()));
        if (owner == null && event.getPrimingEntity() != null) owner = findOwnerId(event.getPrimingEntity());
        if (owner != null) pendingOwners.put(blockKey(event.getBlock()), owner);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof TNTPrimed primed)) return;
        UUID owner = pendingOwners.remove(blockKey(primed.getLocation().getBlock()));
        if (owner == null) owner = findOwnerId(primed.getSource());
        if (owner != null) tntOwners.put(primed.getUniqueId(), owner);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveEvent event) {
        UUID entityId = event.getEntity().getUniqueId();
        if (!tntOwners.containsKey(entityId)) return;
        getServer().getScheduler().runTask(this, () -> tntOwners.remove(entityId));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        if (!isFeatureEnabled() || !isTntExplosion(event.getEntity())) return;
        event.setRadius((float) (getExplosionRadius() * Math.sqrt(3.0D)));
        event.setFire(false);
        UUID owner = findOwnerId(event.getEntity());
        if (owner != null) explosionOwners.put(event.getEntity().getUniqueId(), owner);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!isFeatureEnabled() || !isTntExplosion(event.getEntity())) return;
        ExplosionResult result = event.getExplosionResult();
        if (result == ExplosionResult.KEEP || result == ExplosionResult.TRIGGER_BLOCK) return;
        int radius = getExplosionRadius();
        Set<Block> explodedBlocks = findExplodedBlocks(event.getLocation(), radius);
        event.blockList().clear();
        event.blockList().addAll(explodedBlocks);
        getServer().getScheduler().runTask(this, () -> {
            for (Block block : explodedBlocks) {
                if (canExplode(block)) block.setType(Material.AIR, false);
            }
        });
        notifyExplosion(event.getEntity(), explodedBlocks.size());
    }

    private Set<Block> findExplodedBlocks(Location origin, int radius) {
        Set<Block> explodedBlocks = new HashSet<>();
        World world = origin.getWorld();
        if (world == null) return explodedBlocks;
        Block center = origin.getBlock();
        for (int offsetX = -radius; offsetX <= radius; offsetX++) {
            for (int offsetY = -radius; offsetY <= radius; offsetY++) {
                for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
                    Block block = center.getRelative(offsetX, offsetY, offsetZ);
                    if (canExplode(block)) explodedBlocks.add(block);
                }
            }
        }
        return explodedBlocks;
    }

    private boolean canExplode(Block block) {
        Material material = block.getType();
        if (material.isAir() || material.getHardness() < 0.0F) return false;
        return (material.getBlastResistance() + 0.3F) * 0.3F < BREAK_INTENSITY;
    }

    private boolean isTntExplosion(Entity entity) {
        if (entity == null) return false;
        EntityType type = entity.getType();
        return type == EntityType.TNT || type == EntityType.TNT_MINECART;
    }

    private void notifyExplosion(Entity entity, int count) {
        UUID ownerId = explosionOwners.remove(entity.getUniqueId());
        if (ownerId == null) ownerId = findOwnerId(entity);
        if (ownerId == null) return;
        Player player = getServer().getPlayer(ownerId);
        if (player == null) return;
        send(player, "exploded", Map.of("count", Integer.toString(count)));
    }

    private UUID findOwnerId(Entity entity) {
        Set<UUID> seen = new HashSet<>();
        Entity current = entity;
        while (current != null && seen.add(current.getUniqueId())) {
            UUID tracked = tntOwners.get(current.getUniqueId());
            if (tracked != null) return tracked;
            if (current instanceof Player player) return player.getUniqueId();
            if (current instanceof TNTPrimed primed) {
                current = primed.getSource();
                continue;
            }
            return null;
        }
        return null;
    }

    private String blockKey(Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private boolean runCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { help(sender); return true; }
        if (!canManage(sender)) { send(sender, "no-permission", Map.of()); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "0", "1" -> { boolean enabled = args[0].equals("1"); getConfig().set("enabled", enabled); saveConfig(); send(sender, enabled ? "enabled" : "disabled", Map.of()); }
            case "reload" -> { reloadConfig(); reloadLanguage(); send(sender, "reloaded", Map.of()); }
            case "info" -> send(sender, "info", Map.of("version", getPluginMeta().getVersion()));
            case "status" -> send(sender, "status", Map.of("enabled", isFeatureEnabled() ? "ON" : "OFF", "radius", Integer.toString(getExplosionRadius()), "diameter", Integer.toString(getExplosionRadius() * 2 + 1)));
            case "set" -> setRadius(sender, args);
            default -> help(sender);
        }
        return true;
    }

    private void setRadius(CommandSender sender, String[] args) {
        if (args.length != 2) {
            send(sender, "usage-set", Map.of());
            return;
        }
        try { int radius = Integer.parseInt(args[1]); if (radius < 1 || radius > MAX_EXPLOSION_RADIUS) throw new NumberFormatException(); getConfig().set("explosion-radius", radius); saveConfig(); send(sender, "radius-set", Map.of("radius", Integer.toString(radius), "diameter", Integer.toString(radius * 2 + 1))); }
        catch (NumberFormatException exception) { send(sender, "invalid-radius", Map.of()); }
    }

    private void help(CommandSender sender) { for (String key : List.of("help-header", "help-toggle", "help-reload", "help-info", "help-status", "help-set", "help-footer")) send(sender, key, Map.of()); }
    private boolean isFeatureEnabled() { return getConfig().getBoolean("enabled", true); }
    private int getExplosionRadius() { return Math.clamp(getConfig().getInt("explosion-radius", 3), 1, MAX_EXPLOSION_RADIUS); }
    private boolean canManage(CommandSender sender) { return !(sender instanceof Player) || !getConfig().getBoolean("admin-require-op", true) || sender.isOp() || sender.hasPermission("bm-minecraft-tnt-plus.admin"); }
    private void reloadLanguage() {
        saveResource("active-language.yml", true);
        String locale = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "active-language.yml")).getString("language", "zh_TW");
        String path = getResource("lang/" + locale + ".yml") == null ? "lang/zh_TW.yml" : "lang/" + locale + ".yml";
        File file = new File(getDataFolder(), path);
        YamlConfiguration bundled = loadBundled(path);
        boolean mayUpdate = prepareLanguageFile(file, path, bundled);
        language = YamlConfiguration.loadConfiguration(file);
        language.setDefaults(bundled);
        language.options().copyDefaults(true);
        if (!mayUpdate) return;
        try {
            language.save(file);
        } catch (IOException exception) {
            getLogger().warning(console(bundled, "language-update-failed").replace("{file}", file.getName()));
        }
    }

    private YamlConfiguration loadBundled(String path) {
        try (InputStream input = getResource(path)) {
            if (input == null) return new YamlConfiguration();
            return YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            return new YamlConfiguration();
        }
    }

    private boolean prepareLanguageFile(File file, String path, YamlConfiguration bundled) {
        if (!file.isFile()) {
            return copyBundled(path, file, bundled);
        }
        int bundledVersion = bundled.getInt("language-format-version", 1);
        int localVersion = YamlConfiguration.loadConfiguration(file).getInt("language-format-version", 1);
        if (localVersion >= bundledVersion) return true;
        File backup = new File(file.getParentFile(),
                file.getName() + ".pre-v" + bundledVersion + ".bak");
        try {
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            getLogger().warning(console(bundled, "language-backup-failed")
                    .replace("{file}", file.getName()).replace("{backup}", backup.getName()));
            return false;
        }
        return copyBundled(path, file, bundled);
    }

    private boolean copyBundled(String path, File file, YamlConfiguration bundled) {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            getLogger().warning(console(bundled, "language-directory-failed").replace("{directory}", parent.getPath()));
            return false;
        }
        try (InputStream input = getResource(path)) {
            if (input == null) return false;
            Files.copy(input, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException exception) {
            getLogger().warning(console(bundled, "language-copy-failed").replace("{file}", file.getName()));
            return false;
        }
    }

    private void send(CommandSender sender, String key, Map<String, String> values) { sender.sendMessage(colour(replace(language.getString("messages." + key, key), values))); }
    private String console(String key) { return console(language, key); }
    private String console(YamlConfiguration source, String key) { return source.getString("console." + key, key); }
    private String replace(String value, Map<String, String> values) { for (Map.Entry<String, String> entry : values.entrySet()) value = value.replace("{" + entry.getKey() + "}", entry.getValue()); return value; }
    private String colour(String value) { return ChatColor.translateAlternateColorCodes('&', value); }
}
