package com.sovereignsmp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sovereign SMP — artifact one of seven: the Sovereign Mace of Gravity.
 * Only one unique authentic item/token is minted; only its registered owner can use powers.
 * Remaining artifact slots are reserved, not implemented in this initial release.
 */
public final class SovereignSMPPlugin extends JavaPlugin implements Listener, TabCompleter {
    private NamespacedKey artifactKey;
    private NamespacedKey royalTooltipKey;
    private final Set<BlockDisplay> spawnedGravityBlocks = new HashSet<>();
    private File stateFile;
    private YamlConfiguration state;
    private UUID maceToken;
    private UUID maceOwner;

    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final Map<UUID, Integer> hitCombos = new HashMap<>();
    private static final String HORIZON = "event-horizon";
    private static final String COLLAPSE = "collapse";
    private static final String SINGULARITY = "singularity";

    @Override
    public void onEnable() {
        artifactKey = new NamespacedKey(this, "sovereign_mace_token");
        royalTooltipKey = new NamespacedKey(this, "royal");
        saveDefaultConfig();
        loadArtifactState();
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("sovereign") != null) {
            getCommand("sovereign").setExecutor(this);
            getCommand("sovereign").setTabCompleter(this);
        }
        getLogger().info("Sovereign SMP enabled: Gravity Mace (artifact 1/7).");
    }

    @Override
    public void onDisable() {
        for (BlockDisplay entity : new ArrayList<>(spawnedGravityBlocks)) {
            if (entity.isValid()) entity.remove();
        }
        spawnedGravityBlocks.clear();
    }

    private void loadArtifactState() {
        stateFile = new File(getDataFolder(), "artifacts.yml");
        state = YamlConfiguration.loadConfiguration(stateFile);
        maceToken = readUuid(state.getString("mace.token"));
        maceOwner = readUuid(state.getString("mace.owner"));
        if (maceToken == null) maceOwner = null;
    }

    private UUID readUuid(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); }
        catch (IllegalArgumentException badState) { return null; }
    }

    private void saveArtifactState() {
        state.set("mace.token", maceToken == null ? null : maceToken.toString());
        state.set("mace.owner", maceOwner == null ? null : maceOwner.toString());
        try { state.save(stateFile); }
        catch (IOException e) { getLogger().severe("Could not save artifacts.yml: " + e.getMessage()); }
    }

    // Shared formatting utilities for all seven planned artifact types.
    // Adventure RGB component gradients work WITHOUT any resource pack.
    private Component gradient(String text, int startRgb, int endRgb, boolean bold) {
        Component result = Component.empty();
        for (int i = 0; i < text.length(); i++) {
            double t = text.length() < 2 ? 0 : (double) i / (text.length() - 1);
            int sr = startRgb >> 16 & 255, sg = startRgb >> 8 & 255, sb = startRgb & 255;
            int er = endRgb >> 16 & 255, eg = endRgb >> 8 & 255, eb = endRgb & 255;
            int r = (int) Math.round(sr + (er - sr) * t);
            int g = (int) Math.round(sg + (eg - sg) * t);
            int b = (int) Math.round(sb + (eb - sb) * t);
            result = result.append(Component.text(String.valueOf(text.charAt(i)), TextColor.color(r, g, b))
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, bold));
        }
        return result;
    }

    private Component plain(String text, int rgb) {
        return Component.text(text, TextColor.color(rgb))
                .decoration(TextDecoration.ITALIC, false);
    }

    private Component flavor(String text) {
        return plain(text, 0xA49AAE).decorate(TextDecoration.ITALIC);
    }

    private Component abilityLine(String key, String seconds) {
        return gradient(key, 0x75E1FF, 0xA899FF, true)
                .append(plain("  •  ", 0x7F7A9D))
                .append(gradient(seconds, 0xFAD76A, 0xFE9D5D, false));
    }

    // Apply this method to future artifacts as well: all will share the royal tooltip style.
    private void styleArtifact(ItemMeta meta, String artifactName, List<Component> lines) {
        meta.displayName(gradient(artifactName, 0xE5B4FF, 0xF6B34D, true));
        meta.lore(lines);
        // Custom BACKGROUND needs the OPTIONAL tooltip-only pack; ordinary items are untouched.
        meta.setTooltipStyle(getConfig().getBoolean("tooltips.custom-background-enabled", false)
                ? royalTooltipKey : null);
    }

    private void styleMaceMeta(ItemMeta meta) {
        List<Component> lore = new ArrayList<>();
        lore.add(gradient("✦  LEGENDARY ARTIFACT  I / VII  ✦", 0xF9D47E, 0xB76AFF, true));
        lore.add(flavor("Let the world bend beneath its weight."));
        lore.add(flavor("Even the stars bow to a Sovereign."));
        lore.add(Component.empty());
        lore.add(gradient("PASSIVE  |  GRAVITATIONAL PRESSURE", 0xB97AFF, 0xFFCE73, true));
        lore.add(plain("Every 3rd successful hit increases enemy gravity", 0xDCD7E9));
        lore.add(plain("and briefly slows their movement.", 0xDCD7E9));
        lore.add(Component.empty());
        lore.add(gradient("I  |  EVENT HORIZON", 0xF7C66B, 0xFF8E57, true));
        lore.add(abilityLine("RIGHT-CLICK", getConfig().getInt("mace.event-horizon.cooldown-seconds",18) + "s"));
        lore.add(plain("Aim to unleash a violent gravity field,", 0xDCD7E9));
        lore.add(plain("dragging enemies toward the Sovereign.", 0xDCD7E9));
        lore.add(Component.empty());
        lore.add(gradient("II  |  GRAVITATIONAL COLLAPSE", 0xF7C66B, 0xFF8E57, true));
        lore.add(abilityLine("SNEAK + RIGHT-CLICK", getConfig().getInt("mace.collapse.cooldown-seconds",25) + "s"));
        lore.add(plain("Send a devastating shockwave through the earth,", 0xDCD7E9));
        lore.add(plain("launching and damaging enemies around you.", 0xDCD7E9));
        lore.add(Component.empty());
        lore.add(gradient("III  |  SINGULARITY", 0xF7C66B, 0xFF8E57, true));
        lore.add(abilityLine("F  •  SWAP HANDS", getConfig().getInt("mace.singularity.cooldown-seconds",40) + "s"));
        lore.add(plain("Summon a black hole that bends nearby blocks", 0xDCD7E9));
        lore.add(plain("into orbit, then violently detonates.", 0xDCD7E9));
        lore.add(Component.empty());
        lore.add(gradient("✧ ONLY ONE SOVEREIGN MAY WIELD IT ✧", 0x8CD9FF, 0xBD7EFF, true));
        styleArtifact(meta, "SOVEREIGN MACE  ✦  GRAVITY", lore);
    }

    private ItemStack restyleMace(ItemStack item) {
        if (!authentic(item)) return item;
        ItemMeta meta = item.getItemMeta();
        styleMaceMeta(meta);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createMaceItem() {
        ItemStack item = new ItemStack(Material.MACE, 1);
        ItemMeta meta = item.getItemMeta();
        meta.setUnbreakable(true);
        meta.setEnchantmentGlintOverride(true);
        meta.addEnchant(Enchantment.DENSITY, 3, true);
        meta.addEnchant(Enchantment.WIND_BURST, 1, true);
        meta.addEnchant(Enchantment.UNBREAKING, 3, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        styleMaceMeta(meta);
        meta.getPersistentDataContainer().set(artifactKey, PersistentDataType.STRING, maceToken.toString());
        item.setItemMeta(meta);
        return item;
    }

    private int refreshInventory(Player player) {
        int changed = 0;
        ItemStack[] inventory = player.getInventory().getContents();
        for (int i=0; i<inventory.length; i++) {
            if (!authentic(inventory[i])) continue;
            player.getInventory().setItem(i, restyleMace(inventory[i].clone()));
            changed++;
        }
        return changed;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (event.getPlayer().isOnline()) refreshInventory(event.getPlayer());
        }, 10L);
    }

    private boolean authentic(ItemStack item) {
        if (maceToken == null || item == null || item.getType() != Material.MACE || !item.hasItemMeta()) return false;
        String token = item.getItemMeta().getPersistentDataContainer().get(artifactKey, PersistentDataType.STRING);
        return maceToken.toString().equals(token);
    }

    private boolean canUse(Player player, ItemStack item) {
        if (!authentic(item)) return false;
        if (maceOwner == null) {
            player.sendMessage(ChatColor.YELLOW + "[Sovereign] This artifact is unclaimed. Pick it up or use /sovereign claim mace.");
            return false;
        }
        if (!maceOwner.equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "[Sovereign] Only the current owner can use the Sovereign Mace.");
            return false;
        }
        if (player.getGameMode() == GameMode.SPECTATOR) return false;
        return true;
    }

    private boolean usableTarget(Player caster, Entity target) {
        if (!(target instanceof LivingEntity living) || living instanceof ArmorStand
                || living.isDead() || !living.isValid() || living.getUniqueId().equals(caster.getUniqueId())) return false;
        if (living instanceof Player other) {
            return other.getGameMode() != GameMode.CREATIVE && other.getGameMode() != GameMode.SPECTATOR;
        }
        return true;
    }

    private List<LivingEntity> targetsIn(Player caster, Location center, double radius, double yRange) {
        List<LivingEntity> result = new ArrayList<>();
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, yRange, radius)) {
            if (!usableTarget(caster, entity)) continue;
            LivingEntity target = (LivingEntity) entity;
            if (target.getLocation().toVector().setY(0).distanceSquared(center.toVector().setY(0)) > radius * radius) continue;
            result.add(target);
        }
        return result;
    }

    private long currentTime() { return System.currentTimeMillis(); }

    private boolean ready(Player user, String ability, String configPath) {
        long now = currentTime();
        long last = cooldowns.getOrDefault(user.getUniqueId(), Map.of()).getOrDefault(ability, 0L);
        if (last > now) {
            double seconds = (last - now) / 1000.0;
            user.sendActionBar(Component.text(String.format("§d[Sovereign] §c%s: %.1fs remaining", ability, seconds)));
            user.playSound(user.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.65f);
            return false;
        }
        long ms = Math.max(0, getConfig().getLong(configPath, 20)) * 1000L;
        cooldowns.computeIfAbsent(user.getUniqueId(), unused -> new HashMap<>()).put(ability, now + ms);
        return true;
    }

    private void gravityDust(World world, Location center, int amount, double spread) {
        world.spawnParticle(Particle.DUST, center, amount,
                spread, 0.32, spread, 0.0,
                new Particle.DustOptions(Color.fromRGB(140, 65, 225), 1.25f));
        world.spawnParticle(Particle.PORTAL, center, Math.max(5, amount / 3), spread, 0.4, spread, 0.11);
    }

    // -------------------- Right click: Event Horizon --------------------
    private void castHorizon(Player user) {
        if (!ready(user, HORIZON, "mace.event-horizon.cooldown-seconds")) return;
        double castRange = Math.max(12, getConfig().getDouble("mace.event-horizon.target-range-blocks", 28.0));
        double radius = Math.max(5, getConfig().getDouble("mace.event-horizon.radius-blocks", 13.0));
        double pullStrength = Math.max(0.5, getConfig().getDouble("mace.event-horizon.pull-velocity", 1.95));
        int durationTicks = Math.max(6, getConfig().getInt("mace.event-horizon.pull-duration-ticks", 22));
        int slowTicks = getConfig().getInt("mace.event-horizon.slowness-ticks", 45);
        World world = user.getWorld();
        Location eye = user.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        var blockHit = user.rayTraceBlocks(castRange, FluidCollisionMode.NEVER);
        final Location focus = blockHit == null
                ? eye.clone().add(dir.clone().multiply(castRange * 0.85))
                : blockHit.getHitPosition().toLocation(world).subtract(dir.clone().multiply(1.0));
        final Location casterAtCast = user.getLocation().clone();

        // Multiple layers: the opening tear, the low rumble and the gravity detonation.
        world.playSound(focus, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.8f, 0.55f);
        world.playSound(focus, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.1f, 0.65f);
        world.playSound(user.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.5f);
        world.spawnParticle(Particle.REVERSE_PORTAL, focus, 130, 2.0, 1.7, 2.0, 0.3);
        world.spawnParticle(Particle.FLASH, focus, 2, 0.1, 0.2, 0.1, 0);
        gravityDust(world, focus, 140, 2.1);
        user.sendActionBar(gradient("EVENT HORIZON  ✦  GRAVITY UNLEASHED", 0xB97AFF, 0xF5B552, true));
        Set<UUID> affected = new HashSet<>();
        new BukkitRunnable() {
            int tick = 0;
            @Override public void run() {
                if (!user.isOnline() || !user.getWorld().equals(world) || tick >= durationTicks) { cancel(); return; }
                double progress = tick / (double) Math.max(1,durationTicks);
                // Ring extends around where the wielder was aiming.
                for (int i=0; i<44; i++) {
                    double angle = i * Math.PI / 22 + tick * 0.17;
                    double r = 1.2 + (radius * 0.64) * (1.0 - progress * 0.6);
                    Location p = focus.clone().add(Math.cos(angle) * r, Math.sin(angle * 3 + tick * 0.2) * 1.8, Math.sin(angle) * r);
                    world.spawnParticle(Particle.DUST, p, 1, 0,0,0,0,
                            new Particle.DustOptions(Color.fromRGB(158, 64, 240), 1.5f));
                    if (i % 5 == 0) world.spawnParticle(Particle.PORTAL,p,2,.05,.05,.05,.07);
                }
                if (tick % 6 == 0) {
                    world.playSound(focus, Sound.BLOCK_BEACON_DEACTIVATE, 0.55f, 0.65f + (float)progress * 0.35f);
                    world.spawnParticle(Particle.CLOUD, focus, 15,.7,.5,.7,.07);
                }
                for (LivingEntity target : targetsIn(user, focus, radius, Math.max(7,radius * 0.8))) {
                    Vector toward = user.getLocation().toVector().subtract(target.getLocation().toVector());
                    double dist = toward.length();
                    if (dist < 1.5) continue;
                    Vector velocity = toward.normalize().multiply(Math.min(2.35, pullStrength + dist * 0.013));
                    velocity.setY(Math.max(0.12, Math.min(0.58, velocity.getY()+0.28)));
                    target.setVelocity(velocity);
                    if (affected.add(target.getUniqueId())) {
                        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slowTicks, 1, true, true, true));
                        gravityDust(world, target.getLocation().add(0,1,0),16,.5);
                    }
                }
                tick += 2;
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    // -------------------- Sneak + right click: Gravitational Collapse --------------------
    private void castCollapse(Player user) {
        if (!ready(user, COLLAPSE, "mace.collapse.cooldown-seconds")) return;
        Location origin = user.getLocation().clone();
        double radius = Math.max(3.0, getConfig().getDouble("mace.collapse.radius-blocks", 9.0));
        double damage = getConfig().getDouble("mace.collapse.damage-health-points", 5.0);
        double knockback = getConfig().getDouble("mace.collapse.knockback", 1.2);
        World world = origin.getWorld();
        world.playSound(origin, Sound.ITEM_MACE_SMASH_GROUND, 1.7f, 0.55f);
        world.spawnParticle(Particle.CLOUD, origin.clone().add(0, 0.3, 0), 60, 1, 0.1, 1, 0.08);
        Set<UUID> hit = new HashSet<>();
        int waves = Math.max(5, (int) Math.ceil(radius));
        for (int wave = 1; wave <= waves; wave++) {
            final int step = wave;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!world.equals(origin.getWorld())) return;
                double ring = radius * step / waves;
                for (int i = 0; i < 72; i++) {
                    double angle = 2 * Math.PI * i / 72.0;
                    Location p = origin.clone().add(Math.cos(angle) * ring, 0.2, Math.sin(angle) * ring);
                    world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0,
                            new Particle.DustOptions(Color.fromRGB(154, 83, 236), 1.8f));
                }
                for (LivingEntity target : targetsIn(user, origin, ring + 0.7, 4.0)) {
                    if (!hit.add(target.getUniqueId())) continue;
                    Location targetAt = target.getLocation();
                    double dist = Math.hypot(targetAt.getX() - origin.getX(), targetAt.getZ() - origin.getZ());
                    if (dist < Math.max(0, ring - radius / waves - 1.3) || dist > ring + 0.8) {
                        hit.remove(target.getUniqueId());
                        continue;
                    }
                    target.damage(damage, user); // Bukkit damage respects deaths and normal totems.
                    Vector out = target.getLocation().toVector().subtract(origin.toVector());
                    if (out.lengthSquared() < 0.001) out = new Vector(0.2, 0, 0.2);
                    out.setY(0).normalize().multiply(knockback).setY(0.45);
                    target.setVelocity(out);
                }
            }, (long) step * 2L);
        }
        user.sendActionBar(Component.text("§dGravitational Collapse!"));
    }

    // -------------------- F (cancel swap): Singularity --------------------
    private List<BlockDisplay> createOrbitingBlocks(Location center, int count, double scanRadius) {
        World world = center.getWorld();
        List<Block> candidates = new ArrayList<>();
        int radius = (int)Math.ceil(scanRadius);
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
        for (int x=-radius; x<=radius; x++) for (int z=-radius; z<=radius; z++) {
            if (x*x + z*z > radius*radius) continue;
            for (int y=-3; y<=2; y++) {
                Block block = world.getBlockAt(cx+x, cy+y, cz+z);
                if (!block.getType().isSolid() || !block.getType().isBlock()) continue;
                if (block.getState() instanceof org.bukkit.block.TileState) continue;
                if (block.getType() == Material.BEDROCK || block.getType() == Material.BARRIER) continue;
                candidates.add(block);
            }
        }
        Collections.shuffle(candidates);
        List<BlockDisplay> displays = new ArrayList<>();
        int n = Math.min(20, Math.max(1, count));
        for (int i=0; i<n; i++) {
            Material material = i < candidates.size() ? candidates.get(i).getType() : Material.CRYING_OBSIDIAN;
            double angle = i * Math.PI * 2.0 / n;
            Location spawn = center.clone().add(Math.cos(angle) * 2.7, 0.8 + (i%4)*0.38, Math.sin(angle) * 2.7);
            BlockDisplay block = world.spawn(spawn, BlockDisplay.class, d -> {
                d.setBlock(material.createBlockData());
                d.setPersistent(false); // never persist visual chunks after a restart
                d.setTeleportDuration(1);
                d.setViewRange(1.5f);
            });
            displays.add(block);
            spawnedGravityBlocks.add(block);
        }
        return displays;
    }

    private void clearOrbitingBlocks(List<BlockDisplay> displays) {
        for (BlockDisplay display : displays) {
            spawnedGravityBlocks.remove(display);
            if (display != null && display.isValid()) display.remove();
        }
    }

    private void castSingularity(Player user) {
        if (!ready(user, SINGULARITY, "mace.singularity.cooldown-seconds")) return;
        double range = getConfig().getDouble("mace.singularity.target-range-blocks", 20.0);
        Block hit = user.getTargetBlockExact((int)Math.ceil(range), FluidCollisionMode.NEVER);
        final Location center = hit == null
                ? user.getEyeLocation().add(user.getEyeLocation().getDirection().multiply(range * 0.75))
                : hit.getLocation().add(0.5, 1.6, 0.5);
        final World world = center.getWorld();
        final int durationTicks = Math.max(20, getConfig().getInt("mace.singularity.duration-ticks", 80));
        final double radius = Math.max(2, getConfig().getDouble("mace.singularity.radius-blocks", 6.5));
        final double damage = getConfig().getDouble("mace.singularity.burst-damage-health-points", 6.0);
        final double blastPush = getConfig().getDouble("mace.singularity.burst-push-velocity", 2.1);
        final int blockCount = getConfig().getInt("mace.singularity.orbit-block-count", 12);
        final List<BlockDisplay> blocks = createOrbitingBlocks(center, blockCount,
                getConfig().getDouble("mace.singularity.block-sample-radius", 5.0));
        world.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 2.0f, 0.40f);
        world.playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.5f, 0.5f);
        world.spawnParticle(Particle.FLASH, center, 2, 0,0,0,0);
        world.spawnParticle(Particle.REVERSE_PORTAL, center, 100, 1.2, 1.2, 1.2, 0.3);
        gravityDust(world,center,120,1.5);
        user.sendActionBar(gradient("SINGULARITY  ✦  THE WORLD BENDS", 0x985EFF, 0xF4C15A, true));

        new BukkitRunnable() {
            int ticks = 0;
            boolean bursting = false;
            int burstTicks = 0;
            final List<Vector> explosionDirections = new ArrayList<>();
            @Override public void run() {
                if (!user.isOnline() || !user.getWorld().equals(world)) {
                    clearOrbitingBlocks(blocks); cancel(); return;
                }
                if (!bursting && ticks >= durationTicks) {
                    bursting = true;
                    world.playSound(center, Sound.ENTITY_WARDEN_SONIC_BOOM, 2.0f, 0.55f);
                    world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.7f);
                    world.playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.5f, 0.8f);
                    world.spawnParticle(Particle.EXPLOSION_EMITTER, center, 1);
                    world.spawnParticle(Particle.FLASH, center, 6, .5,.5,.5,0);
                    world.spawnParticle(Particle.REVERSE_PORTAL, center, 300, 2.1, 1.7, 2.1, 0.6);
                    gravityDust(world, center, 240, 3.0);
                    for (LivingEntity target : targetsIn(user, center, radius, 6.0)) {
                        target.damage(damage, user);
                        Vector out = target.getLocation().toVector().subtract(center.toVector());
                        if (out.lengthSquared() < 0.01) out = new Vector(.2,.1,.2);
                        out.normalize().multiply(blastPush).setY(.68);
                        target.setVelocity(out);
                    }
                    for (BlockDisplay d : blocks) {
                        Vector out = d.getLocation().toVector().subtract(center.toVector());
                        if (out.lengthSquared() < 0.01) out = new Vector(
                                ThreadLocalRandom.current().nextDouble(-1,1), .2,
                                ThreadLocalRandom.current().nextDouble(-1,1));
                        explosionDirections.add(out.normalize().multiply(1.3 + ThreadLocalRandom.current().nextDouble(.55)));
                    }
                }
                if (bursting) {
                    if (burstTicks >= 14) { clearOrbitingBlocks(blocks); cancel(); return; }
                    for (int i=0;i<blocks.size();i++) {
                        BlockDisplay d=blocks.get(i);
                        if (!d.isValid()) continue;
                        Vector velocity=explosionDirections.get(i).clone();
                        velocity.setY(velocity.getY()+0.12);
                        Location next=d.getLocation().add(velocity);
                        d.teleport(next);
                        world.spawnParticle(Particle.PORTAL, next, 4,.1,.1,.1,.06);
                        world.spawnParticle(Particle.CRIT, next, 2,.1,.1,.1,.02);
                    }
                    if (burstTicks % 4 == 0) {
                        world.spawnParticle(Particle.CLOUD,center,36,3,1.5,3,.14);
                    }
                    burstTicks += 2;
                    return;
                }
                double progress = ticks/(double)durationTicks;
                for (int i=0; i<blocks.size(); i++) {
                    BlockDisplay d=blocks.get(i);
                    if (!d.isValid()) continue;
                    double angle=ticks*0.13+i*2*Math.PI/blocks.size();
                    double orbit=3.3-progress*1.6+(i%3)*0.25;
                    double height=Math.sin(angle*1.6+i*0.5)*0.7+(i%4)*0.35;
                    Location next=center.clone().add(Math.cos(angle)*orbit,height,Math.sin(angle)*orbit);
                    d.teleport(next);
                }
                for (int n=0;n<30;n++) {
                    double a=ticks*.19+n*Math.PI/15;
                    double r=.7+(n%5)*.42;
                    Location p=center.clone().add(Math.cos(a)*r,Math.sin(a*2)*1.1,Math.sin(a)*r);
                    world.spawnParticle(Particle.PORTAL,p,3,.1,.1,.1,.075);
                    if(n%3==0) world.spawnParticle(Particle.DUST,p,1,0,0,0,0,
                            new Particle.DustOptions(Color.fromRGB(104,36,202),1.8f));
                }
                world.spawnParticle(Particle.REVERSE_PORTAL,center,36,1.25,.8,1.25,.23);
                if (ticks%10==0) {
                    world.playSound(center,Sound.BLOCK_BEACON_DEACTIVATE,1.0f,.5f+(float)progress*.65f);
                    world.playSound(center,Sound.ENTITY_ENDERMAN_TELEPORT,.45f,.45f+(float)progress*.2f);
                }
                if (ticks%20==0) {
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION,center,48,1.8,1.0,1.8,0,
                            new Particle.DustTransition(Color.fromRGB(130,58,235),Color.fromRGB(35,14,65),1.8f));
                }
                for (LivingEntity target : targetsIn(user, center, radius, 5.0)) {
                    Vector toward = center.toVector().subtract(target.getLocation().toVector());
                    double distance=toward.length();
                    if (distance < .65) continue;
                    Vector vel=toward.normalize().multiply(Math.min(.82,.29+distance*.048));
                    vel.setY(Math.max(-.29,Math.min(.32,vel.getY())));
                    target.setVelocity(vel);
                }
                ticks += 2;
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onMaceClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (!authentic(item)) return;
        event.setCancelled(true);
        Player user = event.getPlayer();
        if (!canUse(user, item)) return;
        if (user.isSneaking()) castCollapse(user);
        else castHorizon(user);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player user = event.getPlayer();
        ItemStack item = user.getInventory().getItemInMainHand();
        if (!authentic(item)) return;
        event.setCancelled(true); // F casts an ability; it does not swap the item.
        if (!canUse(user, item)) return;
        castSingularity(user);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void preventUnauthorizedMaceDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!authentic(attacker.getInventory().getItemInMainHand())) return;
        if (maceOwner == null || !maceOwner.equals(attacker.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMaceHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player user) || !usableTarget(user, event.getEntity())) return;
        if (!authentic(user.getInventory().getItemInMainHand()) || !user.getUniqueId().equals(maceOwner)) return;
        if (event.getFinalDamage() <= 0) return;
        LivingEntity victim = (LivingEntity) event.getEntity();
        UUID id = user.getUniqueId();
        int hits = hitCombos.getOrDefault(id, 0) + 1;
        if (hits < 3) { hitCombos.put(id, hits); return; }
        hitCombos.put(id, 0);
        victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                getConfig().getInt("mace.passive.slowness-ticks", 45), 1, true, true, true));
        // Heavier pull toward ground for airborne targets; no extra attack damage.
        Vector old = victim.getVelocity();
        if (!victim.isOnGround()) victim.setVelocity(old.setY(Math.max(-0.75, old.getY() - 0.55)));
        gravityDust(victim.getWorld(), victim.getLocation().add(0, 0.9, 0), 24, 0.4);
        user.sendActionBar(Component.text("§5Gravitational Pressure §7• Heavy gravity applied"));
        victim.getWorld().playSound(victim.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.65f, 0.75f);
    }

    // Artifact possession/ownership is persistent across server restarts.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player picker)) return;
        if (!authentic(event.getItem().getItemStack())) return;
        event.getItem().setItemStack(restyleMace(event.getItem().getItemStack()));
        if (maceOwner != null) return;
        maceOwner = picker.getUniqueId();
        saveArtifactState();
        Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "[Sovereign] " + ChatColor.GOLD
                + picker.getName() + " has claimed the Sovereign Mace of Gravity!");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onOwnerDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        if (maceOwner == null || !maceOwner.equals(dead.getUniqueId())) return;
        maceOwner = null;
        saveArtifactState();
        hitCombos.remove(dead.getUniqueId());
        cooldowns.remove(dead.getUniqueId());
        // Vanilla drops work normally. When keepInventory is enabled, drop only the artifact,
        // so it becomes claimable instead of being locked to a defeated player.
        if (event.getKeepInventory()) {
            ItemStack lost = null;
            for (int slot = 0; slot < dead.getInventory().getSize(); slot++) {
                ItemStack stack = dead.getInventory().getItem(slot);
                if (!authentic(stack)) continue;
                if (lost == null) lost = stack.clone();
                dead.getInventory().setItem(slot, null);
            }
            event.getDrops().removeIf(this::authentic);
            if (lost != null) {
                final ItemStack itemToDrop = lost;
                final Location at = dead.getLocation().clone();
                Bukkit.getScheduler().runTask(this, () -> at.getWorld().dropItemNaturally(at, itemToDrop));
            }
        }
        String killer = dead.getKiller() == null ? "someone else" : dead.getKiller().getName();
        Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "[Sovereign] " + ChatColor.GOLD
                + dead.getName() + " was defeated! The Sovereign Mace is now claimable by " + killer + " or another player.");
    }

    private ItemStack findArtifact(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (authentic(item)) return item;
        }
        return null;
    }

    private void handItemTo(Player player, ItemStack item) {
        var leftovers = player.getInventory().addItem(item);
        if (!leftovers.isEmpty()) {
            for (ItemStack left : leftovers.values()) player.getWorld().dropItemNaturally(player.getLocation(), left);
            player.sendMessage(ChatColor.YELLOW + "[Sovereign] Your inventory was full, so the Mace dropped at your feet.");
        }
    }

    private void claim(Player player) {
        if (maceToken == null) {
            player.sendMessage(ChatColor.RED + "[Sovereign] The Sovereign Mace has not been minted yet.");
        } else if (maceOwner != null) {
            player.sendMessage(ChatColor.RED + "[Sovereign] The artifact already has an owner.");
        } else if (findArtifact(player) == null) {
            player.sendMessage(ChatColor.RED + "[Sovereign] You must physically possess the authentic mace to claim it.");
        } else {
            maceOwner = player.getUniqueId();
            saveArtifactState();
            Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "[Sovereign] " + player.getName()
                    + " claimed the Sovereign Mace of Gravity!");
        }
    }

    private String ownerName() {
        if (maceOwner == null) return "Unclaimed";
        String n = Bukkit.getOfflinePlayer(maceOwner).getName();
        return n == null ? maceOwner.toString() : n;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("sovereign")) return false;
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(ChatColor.LIGHT_PURPLE + "[Sovereign SMP] Legendary artifacts: 1/7 implemented");
            sender.sendMessage(ChatColor.GRAY + "/sovereign status — artifact ownership");
            sender.sendMessage(ChatColor.GRAY + "/sovereign claim mace — claim an unowned authentic mace in your inventory");
            sender.sendMessage(ChatColor.GRAY + "/sovereign cooldowns — your mace ability cooldowns");
            sender.sendMessage(ChatColor.GRAY + "/sovereign restyle — update an old Mace's tooltip in inventory");
            if (sender.hasPermission("sovereign.admin")) {
                sender.sendMessage(ChatColor.GOLD + "/sovereign mint mace <player> — create first artifact ONCE");
                sender.sendMessage(ChatColor.GOLD + "/sovereign recover mace <player> — invalidate old item, replace it");
                sender.sendMessage(ChatColor.GOLD + "/sovereign tooltip <on|off> — optional custom background pack");
            }
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "restyle" -> {
                if (!(sender instanceof Player player)) {sender.sendMessage("Players only."); return true;}
                int changed=refreshInventory(player);
                player.sendMessage(ChatColor.LIGHT_PURPLE + "[Sovereign] Restyled " + changed + " authentic artifacts.");
                return true;
            }
            case "tooltip" -> {
                if (!sender.hasPermission("sovereign.admin")) {sender.sendMessage(ChatColor.RED+"No permission.");return true;}
                if (args.length<2 || !List.of("on","off").contains(args[1].toLowerCase())) {
                    sender.sendMessage(ChatColor.YELLOW+"Usage: /sovereign tooltip <on|off> (pack required for 'on')");return true;
                }
                boolean enabled=args[1].equalsIgnoreCase("on");
                getConfig().set("tooltips.custom-background-enabled",enabled);
                saveConfig();
                for (Player player : Bukkit.getOnlinePlayers()) refreshInventory(player);
                sender.sendMessage(ChatColor.LIGHT_PURPLE+"[Sovereign] Royal tooltip background "
                        +(enabled?"enabled. Players must install the optional pack!":"disabled."));
                return true;
            }
            case "status", "artifacts" -> {
                sender.sendMessage(ChatColor.GOLD + "Sovereign Artifacts (1 / 7)");
                sender.sendMessage(ChatColor.LIGHT_PURPLE + "1. Sovereign Mace of Gravity: " + ownerName()
                        + (maceToken == null ? " (not minted)" : ""));
                sender.sendMessage(ChatColor.GRAY + "2–7. Future artifacts (not yet implemented)");
                return true;
            }
            case "claim" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
                if (args.length < 2 || !args[1].equalsIgnoreCase("mace")) { sender.sendMessage("Usage: /sovereign claim mace"); return true; }
                claim(player);
                return true;
            }
            case "cooldowns" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
                sender.sendMessage(ChatColor.LIGHT_PURPLE + "[Sovereign] Mace cooldowns:");
                for (String ability : List.of(HORIZON, COLLAPSE, SINGULARITY)) {
                    long until = cooldowns.getOrDefault(player.getUniqueId(), Map.of()).getOrDefault(ability, 0L);
                    String remaining = until <= currentTime() ? "READY" : String.format("%.1fs", (until - currentTime()) / 1000.0);
                    sender.sendMessage(ChatColor.GRAY + ability + ": " + ChatColor.AQUA + remaining);
                }
                return true;
            }
            case "mint", "recover" -> {
                if (!sender.hasPermission("sovereign.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                if (args.length < 3 || !args[1].equalsIgnoreCase("mace")) {
                    sender.sendMessage(ChatColor.RED + "Usage: /sovereign " + args[0] + " mace <player>");
                    return true;
                }
                Player recipient = Bukkit.getPlayerExact(args[2]);
                if (recipient == null) { sender.sendMessage(ChatColor.RED + "That player must be online."); return true; }
                boolean recovery = args[0].equalsIgnoreCase("recover");
                if (!recovery && maceToken != null) {
                    sender.sendMessage(ChatColor.RED + "The Sovereign Mace already exists! Use /sovereign recover mace <player> only if the original is lost.");
                    return true;
                }
                maceToken = UUID.randomUUID(); // Recovery invalidates ALL copies of old token.
                maceOwner = recipient.getUniqueId();
                saveArtifactState();
                handItemTo(recipient, createMaceItem());
                Bukkit.broadcastMessage(ChatColor.GOLD + "[Sovereign] " + recipient.getName()
                        + (recovery ? " now owns the recovered " : " has received the first ")
                        + "Sovereign Mace of Gravity!");
                return true;
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown subcommand. Use /sovereign help.");
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> opts = new ArrayList<>(List.of("help", "status", "artifacts", "claim", "cooldowns", "restyle"));
            if (sender.hasPermission("sovereign.admin")) { opts.add("mint"); opts.add("recover"); opts.add("tooltip"); }
            return opts.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("tooltip") && sender.hasPermission("sovereign.admin")) return List.of("on","off");
        if (args.length == 2 && List.of("mint", "recover", "claim").contains(args[0].toLowerCase())) return List.of("mace");
        if (args.length == 3 && List.of("mint", "recover").contains(args[0].toLowerCase())) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[2].toLowerCase())).toList();
        }
        return List.of();
    }
}
