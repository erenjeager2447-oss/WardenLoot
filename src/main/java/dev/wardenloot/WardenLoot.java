package dev.wardenloot;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.*;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.*;

public class WardenLoot extends JavaPlugin implements Listener {

    private static final long SHOCKWAVE_COOLDOWN_MS = 5000;
    private static final double SHOCKWAVE_DAMAGE = 6.0;
    private static final double SHOCKWAVE_RADIUS = 4.0;

    private static final long BEAM_COOLDOWN_MS = 6000;
    private static final double BEAM_DAMAGE = 10.0;
    private static final double BEAM_RANGE = 20.0;

    private NamespacedKey itemKey;
    private final Map<UUID, Long> shockCooldowns = new HashMap<>();
    private final Map<UUID, Long> beamCooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        itemKey = new NamespacedKey(this, "warden_item");
        getServer().getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!isItem(p.getInventory().getHelmet(), "helmet")) continue;
                p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 300, 0, true, false, false));
                p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 60, 0, true, false, false));
                p.removePotionEffect(PotionEffectType.DARKNESS);
            }
        }, 40L, 40L);
    }

    private ItemStack createHelmet() {
        ItemStack item = new ItemStack(Material.NETHERITE_HELMET);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Warden Helmet", NamedTextColor.DARK_AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Sees in the dark. Fears nothing.", NamedTextColor.GRAY),
                Component.text("+4 Hearts, Night Vision, Resistance I", NamedTextColor.AQUA)
        ));
        meta.setCustomModelData(1001);

        EquipmentSlotGroup slot = EquipmentSlotGroup.HEAD;
        meta.addAttributeModifier(Attribute.ARMOR, new AttributeModifier(
                new NamespacedKey(this, "helmet_armor"), 5.0, AttributeModifier.Operation.ADD_NUMBER, slot));
        meta.addAttributeModifier(Attribute.ARMOR_TOUGHNESS, new AttributeModifier(
                new NamespacedKey(this, "helmet_toughness"), 3.0, AttributeModifier.Operation.ADD_NUMBER, slot));
        meta.addAttributeModifier(Attribute.KNOCKBACK_RESISTANCE, new AttributeModifier(
                new NamespacedKey(this, "helmet_kb"), 0.1, AttributeModifier.Operation.ADD_NUMBER, slot));
        meta.addAttributeModifier(Attribute.MAX_HEALTH, new AttributeModifier(
                new NamespacedKey(this, "helmet_health"), 8.0, AttributeModifier.Operation.ADD_NUMBER, slot));

        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, "helmet");
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createHammer() {
        ItemStack item = new ItemStack(Material.MACE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Warden Hammer", NamedTextColor.DARK_AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Right-click: Sonic Beam (6s cooldown)", NamedTextColor.AQUA),
                Component.text("Hit: Sonic Shockwave (5s cooldown)", NamedTextColor.AQUA)
        ));
        meta.setCustomModelData(1002);

        EquipmentSlotGroup slot = EquipmentSlotGroup.MAINHAND;
        meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(
                new NamespacedKey(this, "hammer_damage"), 9.0, AttributeModifier.Operation.ADD_NUMBER, slot));
        meta.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(
                new NamespacedKey(this, "hammer_speed"), -3.0, AttributeModifier.Operation.ADD_NUMBER, slot));

        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, "hammer");
        item.setItemMeta(meta);
        return item;
    }

    private boolean isItem(ItemStack item, String type) {
        if (item == null || !item.hasItemMeta()) return false;
        String v = item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        return type.equals(v);
    }

    // ---- Warden death: ONE random item (50% helmet, 50% hammer) ----
    @EventHandler
    public void onWardenDeath(EntityDeathEvent e) {
        if (e.getEntityType() != EntityType.WARDEN) return;
        if (new Random().nextBoolean()) {
            e.getDrops().add(createHelmet());
        } else {
            e.getDrops().add(createHammer());
        }
    }

    // ---- Right-click: Sonic Beam ----
    @EventHandler
    public void onHammerUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = e.getPlayer();
        if (!isItem(player.getInventory().getItemInMainHand(), "hammer")) return;

        long now = System.currentTimeMillis();
        long last = beamCooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < BEAM_COOLDOWN_MS) {
            long left = (BEAM_COOLDOWN_MS - (now - last)) / 1000 + 1;
            player.sendActionBar(Component.text("Sonic Beam ready in " + left + "s", NamedTextColor.RED));
            return;
        }
        beamCooldowns.put(player.getUniqueId(), now);

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World w = player.getWorld();

        double maxDist = BEAM_RANGE;
        RayTraceResult blockHit = w.rayTraceBlocks(eye, dir, BEAM_RANGE, FluidCollisionMode.NEVER, true);
        if (blockHit != null) {
            maxDist = blockHit.getHitPosition().distance(eye.toVector());
        }

        w.playSound(eye, Sound.ENTITY_WARDEN_SONIC_BOOM, 3.0f, 1.0f);

        DamageSource source = DamageSource.builder(DamageType.SONIC_BOOM)
                .withDirectEntity(player)
                .withCausingEntity(player)
                .build();

        Set<UUID> alreadyHit = new HashSet<>();
        for (double d = 1.0; d < maxDist; d += 1.0) {
            Location point = eye.clone().add(dir.clone().multiply(d));
            w.spawnParticle(Particle.SONIC_BOOM, point, 1, 0, 0, 0, 0);

            for (Entity near : w.getNearbyEntities(point, 1.2, 1.2, 1.2)) {
                if (!(near instanceof LivingEntity le)) continue;
                if (near.equals(player) || !alreadyHit.add(near.getUniqueId())) continue;
                le.damage(BEAM_DAMAGE, source);
                le.setVelocity(dir.clone().multiply(1.5).setY(0.5));
            }
        }
    }

    // ---- Melee hit: Shockwave ----
    @EventHandler
    public void onHammerHit(EntityDamageByEntityEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(e.getDamager() instanceof Player player)) return;
        if (!(e.getEntity() instanceof LivingEntity victim)) return;
        if (!isItem(player.getInventory().getItemInMainHand(), "hammer")) return;

        long now = System.currentTimeMillis();
        long last = shockCooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < SHOCKWAVE_COOLDOWN_MS) return;
        shockCooldowns.put(player.getUniqueId(), now);

        Location center = victim.getLocation();
        World w = center.getWorld();
        w.playSound(center, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.0f, 1.2f);
        w.spawnParticle(Particle.SONIC_BOOM, center.clone().add(0, 1, 0), 1);

        for (Entity near : victim.getNearbyEntities(SHOCKWAVE_RADIUS, 2, SHOCKWAVE_RADIUS)) {
            if (!(near instanceof LivingEntity le) || near.equals(player)) continue;
            le.damage(SHOCKWAVE_DAMAGE, player);
            Vector push = le.getLocation().toVector().subtract(center.toVector());
            if (push.lengthSquared() > 0) {
                le.setVelocity(push.normalize().multiply(0.8).setY(0.35));
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        if (!p.hasPermission("wardenloot.give")) {
            p.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        if (args.length != 1) {
            p.sendMessage(Component.text("Usage: /wardengive <helmet|hammer>", NamedTextColor.YELLOW));
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "helmet" -> p.getInventory().addItem(createHelmet());
            case "hammer" -> p.getInventory().addItem(createHammer());
            default -> p.sendMessage(Component.text("Usage: /wardengive <helmet|hammer>", NamedTextColor.YELLOW));
        }
        return true;
    }
                               }
