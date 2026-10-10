package dev.wardenloot;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.*;

public class WardenLoot extends JavaPlugin implements Listener {

    private static final double HELMET_DROP_CHANCE = 1.0;
    private static final double HAMMER_DROP_CHANCE = 1.0;
    private static final long SHOCKWAVE_COOLDOWN_MS = 5000;
    private static final double SHOCKWAVE_DAMAGE = 6.0;
    private static final double SHOCKWAVE_RADIUS = 4.0;

    private NamespacedKey itemKey;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

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
                Component.text("Strike to unleash a sonic shockwave.", NamedTextColor.GRAY),
                Component.text("+Damage, Sonic Shockwave (5s cooldown)", NamedTextColor.AQUA)
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

    @EventHandler
    public void onWardenDeath(EntityDeathEvent e) {
        if (e.getEntityType() != EntityType.WARDEN) return;
        Random r = new Random();
        if (r.nextDouble() < HELMET_DROP_CHANCE) e.getDrops().add(createHelmet());
        if (r.nextDouble() < HAMMER_DROP_CHANCE) e.getDrops().add(createHammer());
    }

    @EventHandler
    public void onHammerHit(EntityDamageByEntityEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(e.getDamager() instanceof Player player)) return;
        if (!(e.getEntity() instanceof LivingEntity victim)) return;
        if (!isItem(player.getInventory().getItemInMainHand(), "hammer")) return;

        long now = System.currentTimeMillis();
        long last = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < SHOCKWAVE_COOLDOWN_MS) return;
        cooldowns.put(player.getUniqueId(), now);

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
