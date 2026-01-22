package xyz.hanamae.hanaWeapons;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PostureManager {

    private final HanaWeapons plugin;
    private final WeaponManager weaponManager;
    private final Map<UUID, Double> staminaMap = new HashMap<>(); // 0.0 - 100.0
    private final Map<UUID, Long> guardBreakMap = new HashMap<>(); // End time
    private final Map<UUID, Long> stunMap = new HashMap<>(); // Stun End Time
    private final Map<UUID, Long> lastActionMap = new HashMap<>(); // Last time stamina reduced
    private final Map<UUID, Long> fullTimeMap = new HashMap<>(); // Time when stamina reached 100

    private BukkitTask regenTask;

    public PostureManager(HanaWeapons plugin, WeaponManager weaponManager) {
        this.plugin = plugin;
        this.weaponManager = weaponManager;
    }

    public void onEnable() {
        regenTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 1L);
    }

    public void onDisable() {
        if (regenTask != null) {
            regenTask.cancel();
            regenTask = null;
        }
    }
    
    // ... Existing onEnable/onDisable ...

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            // Check Stun
            if (isStunned(player)) {
                updateStunBar(player);
                continue; // Skip regen if stunned
            }
            
            if (handleGuardBreak(player, now)) continue;
            if (shouldSkipTick(player, now)) continue;
            
            // Check valid defense weapon
            double dcMain = weaponManager.getDefenseCoefficient(player.getInventory().getItemInMainHand());
            double dcOff = weaponManager.getDefenseCoefficient(player.getInventory().getItemInOffHand());
            if (Math.max(dcMain, dcOff) <= 0 && !isShowingFullAnimation(player, now)) continue;

            processStaminaRegen(player, now);
            updateActionBar(player);
        }
    }

    private boolean handleGuardBreak(Player player, long now) {
        if (!isGuardBroken(player)) return false;

        long timeLeft = guardBreakMap.get(player.getUniqueId()) - now;
        if (timeLeft > 0) {
            applyBreakCooldowns(player);
            updateActionBar(player);
            return true;
        }

        // Recovery
        guardBreakMap.remove(player.getUniqueId());
        staminaMap.put(player.getUniqueId(), 50.0);
        player.sendMessage("§a§l[ 架势恢复 ]");
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 2f);
        return true;
    }

    private void applyBreakCooldowns(Player player) {
        if (player.getInventory().getItemInMainHand().getType() != Material.AIR) {
            player.setCooldown(player.getInventory().getItemInMainHand().getType(), 10);
        }
        if (player.getInventory().getItemInOffHand().getType() != Material.AIR) {
            player.setCooldown(player.getInventory().getItemInOffHand().getType(), 10);
        }
    }

    private boolean shouldSkipTick(Player player, long now) {
        if (!staminaMap.containsKey(player.getUniqueId())) {
            staminaMap.put(player.getUniqueId(), 100.0);
        }
        
        // 只要架势条没满，就始终 Tick (不跳过)
        // 这样即使切了物品或者没在格挡，也能持续恢复并显示 UI
        if (staminaMap.get(player.getUniqueId()) < 100.0) {
            return false;
        }

        // 如果满了，则根据是否格挡/动画来决定是否跳过 (隐藏 UI)
        boolean shouldShow = isShowingFullAnimation(player, now);
        if (!shouldShow && !plugin.isBlockingOrRecentlyBlocking(player)) {
            return true;
        }
        return false;
    }

    private boolean isShowingFullAnimation(Player player, long now) {
        Long fullTime = fullTimeMap.get(player.getUniqueId());
        return fullTime != null && now - fullTime < 1000;
    }

    private void processStaminaRegen(Player player, long now) {
        // Use plugin.isBlockingOrRecentlyBlocking to handle cooldown interruptions
        if (plugin.isBlockingOrRecentlyBlocking(player)) {
            lastActionMap.put(player.getUniqueId(), now);
            return;
        }

        long lastAction = lastActionMap.getOrDefault(player.getUniqueId(), 0L);
        if (now - lastAction > 1500) {
            double current = staminaMap.getOrDefault(player.getUniqueId(), 100.0);
            if (current < 100.0) {
                // 修改逻辑：恢复速度取 Min 而不是 Max
                // 防止切到快武器回血，保持“最慢”的那个恢复速度作为惩罚
                // 但如果有一手是空手/非武器，则忽略那一手（避免空手拖慢）
                
                double rateMain = weaponManager.getPostureRegenRate(player.getInventory().getItemInMainHand());
                double rateOff = weaponManager.getPostureRegenRate(player.getInventory().getItemInOffHand());
                
                // 逻辑：
                // 1. 只有当 rate > 0 时才视为有效武器速度
                // 2. 如果两手都有武器，取较小值 (Min)，防止利用小刀快速回盾
                // 3. 如果只有一手有武器，取该武器的值
                // 4. 如果两手都没武器，取默认值 (2.0)
                
                double rate = 2.0; // 默认值
                
                if (rateMain > 0 && rateOff > 0) {
                    rate = Math.min(rateMain, rateOff);
                } else if (rateMain > 0) {
                    rate = rateMain;
                } else if (rateOff > 0) {
                    rate = rateOff;
                } else {
                    rate = 2.0;
                }

                current = Math.min(100.0, current + rate);
                staminaMap.put(player.getUniqueId(), current);

                if (current >= 100.0) {
                    fullTimeMap.put(player.getUniqueId(), now);
                }
            }
        }
    }

    public void damageStamina(Player player, double amount) {
        UUID uuid = player.getUniqueId();
        double current = staminaMap.getOrDefault(uuid, 100.0);
        current -= amount;
        
        lastActionMap.put(uuid, System.currentTimeMillis());

        if (current <= 0) {
            current = 0;
            triggerGuardBreak(player);
        }
        
        staminaMap.put(uuid, current);
        updateActionBar(player);
    }

    public void triggerParryStun(org.bukkit.entity.LivingEntity target, int durationTicks) {
        if (target == null) return;
        
        long durationMillis = durationTicks * 50L;
        
        // Apply Potions (Strong CC)
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, durationTicks, 255));
        target.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, durationTicks, 128)); // Prevent Jumping
        target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, durationTicks, 255)); // Prevent Damage
        target.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, durationTicks, 255)); // Prevent Swinging/Digging
        
        // Record Stun (For event cancellation)
        if (target instanceof Player player) {
            stunMap.put(player.getUniqueId(), System.currentTimeMillis() + durationMillis);
            updateStunBar(player);
        } else {
            // For mobs, we can maybe add AI removal later, but potions are usually enough
            // Or store in a temporary map if we want to cancel their attacks via events too
            // Since mobs don't have UUIDs persisted across restarts usually, simple map is fine
            // But for now, let's just stick to players for the map, mobs get potions.
            // Actually, Listener needs to know if mob is stunned to cancel damage event
            // So we should map non-player entities too?
            // Let's use metadata for mobs, it's easier.
            target.setMetadata("hana_stun", new org.bukkit.metadata.FixedMetadataValue(plugin, System.currentTimeMillis() + durationMillis));
        }
    }
    
    public boolean isStunned(org.bukkit.entity.LivingEntity entity) {
        if (entity == null) return false;
        if (entity instanceof Player player) {
            Long end = stunMap.get(player.getUniqueId());
            if (end == null) return false;
            if (System.currentTimeMillis() > end) {
                stunMap.remove(player.getUniqueId());
                return false;
            }
            return true;
        } else {
             if (entity.hasMetadata("hana_stun")) {
                 long end = entity.getMetadata("hana_stun").get(0).asLong();
                 if (System.currentTimeMillis() > end) {
                     entity.removeMetadata("hana_stun", plugin);
                     return false;
                 }
                 return true;
             }
        }
        return false;
    }
    
    private void updateStunBar(Player player) {
        Long end = stunMap.get(player.getUniqueId());
        if (end != null) {
            long left = end - System.currentTimeMillis();
            sendActionBar(player, "§e§l[ ! 僵直 ! ] §7" + String.format("%.1f", Math.max(0, left) / 1000.0) + "s");
        }
    }

    public void triggerGuardBreak(Player player) {
        triggerGuardBreak(player, true);
    }

    public void triggerGuardBreak(Player player, boolean applyDebuffs) {
        if (isGuardBroken(player)) return;
        
        guardBreakMap.put(player.getUniqueId(), System.currentTimeMillis() + 3000);
        player.playSound(player.getLocation(), Sound.ITEM_SHIELD_BREAK, 1f, 0.5f);
        player.clearActiveItem();
        
        if (applyDebuffs) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 80, 0));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 0));
        }
    }

    public boolean isGuardBroken(Player player) {
        return guardBreakMap.containsKey(player.getUniqueId());
    }

    private void updateActionBar(Player player) {
        if (plugin.getSkillManager() != null && plugin.getSkillManager().isDrawingUI(player)) {
            return;
        }

        if (isGuardBroken(player)) {
            long timeLeft = guardBreakMap.get(player.getUniqueId()) - System.currentTimeMillis();
            sendActionBar(player, "§c§l[ 破防状态 ] §7" + String.format("%.1f", timeLeft / 1000.0) + "s");
            return;
        }
        
        double current = staminaMap.getOrDefault(player.getUniqueId(), 100.0);
        
        // 只有当架势条已满，且没有在格挡，且没有播放满条动画时，才不显示
        if (current >= 100.0 && !plugin.isBlockingOrRecentlyBlocking(player) && !isShowingFullAnimation(player, System.currentTimeMillis())) return;
        
        int lenMain = 0;
        WeaponManager.WeaponData dataMain = weaponManager.getWeaponData(player.getInventory().getItemInMainHand());
        if (dataMain != null && dataMain.postureBarLength > 0) {
            boolean isTwoHandedMech = dataMain.mechanicTwoHanded;
            boolean isInTwoHandedMode = isPlaceholder(player.getInventory().getItemInOffHand());
            
            // 如果不是双手武器，或者处于双手模式，则计算长度
            // (即：单手持有的双手武器不贡献长度)
            if (!isTwoHandedMech || isInTwoHandedMode) {
                lenMain = dataMain.postureBarLength;
            }
        }

        int lenOff = weaponManager.getPostureBarLength(player.getInventory().getItemInOffHand());
        
        int bars = Math.max(lenMain, lenOff);
        if (bars <= 0) bars = weaponManager.getPostureBarLength();
        
        int fill = (int) ((current / 100.0) * bars);
        StringBuilder sb = new StringBuilder("§e架势: §8[");
        for (int i = 0; i < bars; i++) {
            if (i < fill) sb.append("§6|");
            else sb.append("§7.");
        }
        sb.append("§8]");
        sendActionBar(player, sb.toString());
    }

    private void sendActionBar(Player player, String message) {
        player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, new net.md_5.bungee.api.chat.TextComponent(message));
    }

    private boolean isPlaceholder(org.bukkit.inventory.ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getDisplayName() != null && item.getItemMeta().getDisplayName().contains("双手握持");
    }
}