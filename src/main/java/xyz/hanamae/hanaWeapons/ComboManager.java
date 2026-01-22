package xyz.hanamae.hanaWeapons;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ComboManager {

    private final HanaWeapons plugin;
    private final WeaponManager weaponManager;
    private final Map<UUID, Integer> comboStage = new HashMap<>(); // Current stage (0-based index)
    private final Map<UUID, Long> lastAttackTime = new HashMap<>();
    private final Map<UUID, BukkitTask> resetTasks = new HashMap<>();

    private final Map<UUID, Long> lastComboUpdate = new HashMap<>(); // Last time the combo STAGE was updated

    public ComboManager(HanaWeapons plugin, WeaponManager weaponManager) {
        this.plugin = plugin;
        this.weaponManager = weaponManager;
    }

    /**
     * 处理玩家攻击时的连招逻辑
     * 1. 检查连招状态
     * 2. 计算下一次攻击的阶段
     * 3. 切换武器模型 (Pose)
     * 4. 设置连招重置任务
     */
    public void handleAttack(Player player, ItemStack weapon, WeaponManager.WeaponData data) {
        if (!data.comboEnabled || data.comboSteps.isEmpty()) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        
        // Debounce: 如果距离上次连招更新太近 (< 50ms)，则忽略此次更新
        // 防止横扫攻击 (Sweep Attack) 一次挥剑触发多次连招推进
        if (lastComboUpdate.containsKey(uuid)) {
            long timeDiff = now - lastComboUpdate.get(uuid);
            if (timeDiff < 50) { // 50ms threshold
                // plugin.getLogger().info("DEBUG: Ignoring sweep attack for combo (diff: " + timeDiff + "ms)");
                return;
            }
        }
        
        int currentStage = comboStage.getOrDefault(uuid, 0);
        
        // Check if previous combo timed out (though task should handle it, double check)
        // Update time
        lastAttackTime.put(uuid, now);
        lastComboUpdate.put(uuid, now); // 标记此次为有效的连招更新

        // Determine next stage
        int nextStage = 0;
        
        // Get current step config to see if it dictates the next step
        WeaponManager.ComboStep currentStepConfig = data.comboSteps.get(currentStage);
        
        // Fix: Use the current step's nextStep config
        if (currentStepConfig.nextStep >= 0 && currentStepConfig.nextStep < data.comboSteps.size()) {
            nextStage = currentStepConfig.nextStep;
        } else {
            // Default: +1, loop
            nextStage = (currentStage + 1) % data.comboSteps.size();
        }
        
        // Cancel old reset task
        if (resetTasks.containsKey(uuid)) {
            resetTasks.get(uuid).cancel();
        }
        
        // Schedule new reset task
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            resetCombo(player, data);
        }, (long) (data.comboResetTime / 50.0));
        resetTasks.put(uuid, task);
        
        // Update Combo Stage for NEXT attack
        comboStage.put(uuid, nextStage);
        
        // Apply "Pose" based on the CURRENT attack's requirement for AFTER-ATTACK pose
        updateWeaponModel(player, weapon, data.name, currentStepConfig.poseModelAdd, currentStepConfig.actionBar);
    }
    
    /**
     * 获取当前连招阶段的伤害倍率
     */
    public double getDamageMultiplier(Player player, WeaponManager.WeaponData data) {
        if (!data.comboEnabled || data.comboSteps.isEmpty()) return 1.0;
        
        int stage = comboStage.getOrDefault(player.getUniqueId(), 0);
        if (stage >= data.comboSteps.size()) stage = 0; // Safety
        
        return data.comboSteps.get(stage).damageMult;
    }

    /**
     * 重置连招状态
     * 将连招阶段归零，并恢复武器模型为初始状态
     */
    private void resetCombo(Player player, WeaponManager.WeaponData data) {
        UUID uuid = player.getUniqueId();
        comboStage.remove(uuid);
        lastAttackTime.remove(uuid);
        lastComboUpdate.remove(uuid);
        resetTasks.remove(uuid);
        
        // Reset Model to base
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        // Verify it's still the same weapon
        WeaponManager.WeaponData currentData = weaponManager.getWeaponData(mainHand);
        if (currentData == data) { // Reference check or ID check
             // Reset to 0 add (Base model)
             updateWeaponModel(player, mainHand, data.name, 0, null);
        }
    }
    
    /**
     * 更新武器模型 (CustomModelData)
     * 核心逻辑：基于 Base ID (存储在 NBT 中) + modelAdd 计算新的 ModelData
     */
    private void updateWeaponModel(Player player, ItemStack item, String weaponName, int modelAdd, String actionBar) {
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        
        if (actionBar != null) {
            player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, new net.md_5.bungee.api.chat.TextComponent(actionBar));
        }
        
        // 尝试读取 Base ID
        int baseId = -1;
        
        // 优先从 PDC 读取
        if (meta.getPersistentDataContainer().has(new org.bukkit.NamespacedKey(plugin, "hana_weapon_id"), org.bukkit.persistence.PersistentDataType.INTEGER)) {
            baseId = meta.getPersistentDataContainer().get(new org.bukkit.NamespacedKey(plugin, "hana_weapon_id"), org.bukkit.persistence.PersistentDataType.INTEGER);
        }
        
        // 如果 PDC 没读到，或者读到的 ID 看起来像是一个变体 ID (大于 1000000)，尝试修正
        if (baseId == -1 || baseId > 1000000) {
             if (meta.hasCustomModelData()) {
                 int currentModelId = meta.getCustomModelData();
                 if (currentModelId > 1000000) {
                     baseId = currentModelId / 100;
                 } else {
                     baseId = currentModelId;
                 }
             }
        }
        
        // 确保写入正确的 Base ID 到 NBT，防止下次读错
        if (baseId > 0) {
            meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "hana_weapon_id"), org.bukkit.persistence.PersistentDataType.INTEGER, baseId);
        }

        if (baseId > 0) {
            // 计算新的 ID
            int newId = baseId;
            if (modelAdd > 0) {
                newId = baseId * 100 + modelAdd;
            }
            
            if (meta.hasCustomModelData() && meta.getCustomModelData() != newId) {
                meta.setCustomModelData(newId);
                item.setItemMeta(meta);
                
                // CRITICAL FIX: Explicitly update the player's inventory
                // Sometimes modifying the item stack directly isn't enough if it's a clone
                player.getInventory().setItemInMainHand(item);
                
                // DEBUG
                plugin.getLogger().info("DEBUG: Updated weapon model to " + newId + " for " + player.getName());
            } else {
                plugin.getLogger().info("DEBUG: Model already " + newId + " or no change needed.");
            }
        }
    }
}
