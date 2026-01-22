package xyz.hanamae.hanaWeapons;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 技能管理器 (SkillManager)
 * 负责处理武器的特殊技能逻辑，例如大剑的"准备架势" (Square Off)。
 * 主要功能：
 * 1. 监听玩家潜行 (Shift) 事件来切换架势。
 * 2. 切换武器模型 (CustomModelData) 以展示特殊动作。
 * 3. 处理架势下的攻击逻辑 (冲刺、特效、伤害)。
 */
public class SkillManager implements Listener {

    private final HanaWeapons plugin;
    private final WeaponManager weaponManager;
    
    // 记录玩家开始潜行的时间，用于计算蓄力进度
    private final Map<UUID, Long> sneakStartTimes = new HashMap<>();
    
    // 记录玩家是否已经蓄力完成 (进入就绪状态)
    private final Map<UUID, Boolean> readyState = new HashMap<>();
    
    // 记录技能冷却时间
    private final Map<UUID, Long> skillCooldowns = new HashMap<>();
    
    // 记录当前正在释放技能的玩家及其额外的护甲穿透属性
    private final Map<UUID, Double> activeSkillBonusAP = new HashMap<>();

    // 技能类型常量
    private static final String SKILL_SQUARE_OFF = "SQUARE_OFF";

    // ==========================================
    // "准备架势" (Square Off) 的配置缓存
    // ==========================================
    private long squareOffChargeTime = 1000;      // 蓄力所需时间 (毫秒)
    private double squareOffDamageMult = 1.5;     // 伤害倍率
    private double squareOffArmorPen = 1.5;       // 技能期间的额外穿甲系数
    private long squareOffCooldown = 2000;        // 技能冷却时间 (毫秒)
    private double squareOffDashVelocity = 1.5;   // 冲刺速度

    // Display Entity 配置 (目前已弃用，改用 CustomModelData 切换)
    private boolean useDisplayEntity = false;
    private int invisibleModelData = 0;
    private Vector3f poseTranslation = new Vector3f(0.0f, 0.0f, 0.0f); 
    private Vector3f poseScale = new Vector3f(1.0f, 1.0f, 1.0f);
    private org.joml.Quaternionf poseRotation = new org.joml.Quaternionf(); 

    public SkillManager(HanaWeapons plugin, WeaponManager weaponManager) {
        this.plugin = plugin;
        this.weaponManager = weaponManager;
        loadConfig();      // 加载配置文件
        startTickTask();   // 启动每 tick 运行的任务 (用于更新 UI)
    }

    /**
     * 加载 skills.yml 配置文件
     */
    public void loadConfig() {
        File file = new File(plugin.getDataFolder(), "skills.yml");
        if (!file.exists()) {
            plugin.saveResource("skills.yml", false);
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        
        // 尝试读取 Square Off 技能的配置段落
        ConfigurationSection section = config.getConfigurationSection("skills." + SKILL_SQUARE_OFF);
        if (section == null) {
            // 兼容旧配置结构
            section = config.getConfigurationSection(SKILL_SQUARE_OFF);
        }
        
        if (section != null) {
            squareOffChargeTime = section.getLong("charge_time", 1000);
            squareOffDamageMult = section.getDouble("damage_multiplier", 1.5);
            squareOffArmorPen = section.getDouble("armor_penetration", 1.5);
            squareOffCooldown = section.getLong("cooldown", 2000);
            squareOffDashVelocity = section.getDouble("dash_velocity", 1.5);
            
            useDisplayEntity = section.getBoolean("use_display_entity", false);
            invisibleModelData = section.getInt("invisible_model_data", 0);
            
            // 加载 Display Entity 的变换参数 (如果启用)
            if (section.contains("pose_translation")) {
                java.util.List<Double> list = section.getDoubleList("pose_translation");
                if (list.size() >= 3) {
                    poseTranslation = new Vector3f(list.get(0).floatValue(), list.get(1).floatValue(), list.get(2).floatValue());
                }
            }
            if (section.contains("pose_rotation_degrees")) {
                java.util.List<Double> list = section.getDoubleList("pose_rotation_degrees");
                if (list.size() >= 3) {
                     float radX = (float) Math.toRadians(list.get(0));
                     float radY = (float) Math.toRadians(list.get(1));
                     float radZ = (float) Math.toRadians(list.get(2));
                     poseRotation = new org.joml.Quaternionf().rotationXYZ(radX, radY, radZ);
                 }
             }
        }
    }

    /**
     * 启动一个每 tick (0.05秒) 运行的任务
     * 用于检查玩家的蓄力进度并显示 Action Bar 提示
     */
    private void startTickTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    checkStanceProgress(player, now);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * 检查并显示玩家的架势蓄力进度条
     */
    private void checkStanceProgress(Player player, long now) {
        if (!player.isSneaking()) return; // 只有潜行时才检查
        
        // 获取手持武器数据
        WeaponManager.WeaponData data = weaponManager.getWeaponData(player.getInventory().getItemInMainHand());
        // 只有配置了 SQUARE_OFF 技能的武器才生效
        if (data == null || data.skill == null || !data.skill.equalsIgnoreCase(SKILL_SQUARE_OFF)) return;
        
        // 检查冷却
        if (skillCooldowns.containsKey(player.getUniqueId())) {
            long cdEnd = skillCooldowns.get(player.getUniqueId());
            if (now < cdEnd) {
                return; // 冷却中不显示进度条
            }
        }

        Long start = sneakStartTimes.get(player.getUniqueId());
        if (start == null) return;

        long elapsed = now - start;
        
        // 如果蓄力时间已到
        if (elapsed >= squareOffChargeTime) {
            if (!readyState.getOrDefault(player.getUniqueId(), false)) {
                readyState.put(player.getUniqueId(), true); // 标记为就绪
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.5f); // 提示音
            }
            sendActionBar(player, "§b§l[ ! 准备就绪 ! ]");
        } else {
            // 显示蓄力进度条
            int totalBars = 20;
            int filledBars = (int) ((elapsed / (double) squareOffChargeTime) * totalBars);
            StringBuilder sb = new StringBuilder("§e准备架势: §8[");
            for (int i = 0; i < totalBars; i++) {
                if (i < filledBars) sb.append("§a|");
                else sb.append("§7.");
            }
            sb.append("§8]");
            sendActionBar(player, sb.toString());
        }
    }

    /**
     * 监听玩家潜行 (Shift) 事件
     * 负责进入和退出架势
     */
    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        
        // 当玩家按下 Shift (开始潜行)
        if (event.isSneaking()) {
            // 如果玩家正在格挡 (按住右键)，则不允许进入架势 (防止冲突)
            if (player.isHandRaised() || player.isBlocking()) {
                return;
            }
        
            WeaponManager.WeaponData data = weaponManager.getWeaponData(player.getInventory().getItemInMainHand());
            if (data != null && data.skill != null && data.skill.equalsIgnoreCase(SKILL_SQUARE_OFF)) {
                // 检查冷却
                if (skillCooldowns.containsKey(player.getUniqueId())) {
                    if (System.currentTimeMillis() < skillCooldowns.get(player.getUniqueId())) {
                         player.sendMessage("§c技能冷却中...");
                         return;
                    }
                }
                
                // 记录开始时间，标记未就绪
                sneakStartTimes.put(player.getUniqueId(), System.currentTimeMillis());
                readyState.put(player.getUniqueId(), false);
                
                // 切换武器模型到 "姿态模式"
                if (data.skillPoseModel > 0) {
                    updateWeaponModel(player, player.getInventory().getItemInMainHand(), data.skillPoseModel);
                } else {
                    // 如果没配置姿态ID，尝试自动推算 (BaseID * 100 + 1)
                    int poseId = data.id * 100 + 1; 
                    updateWeaponModel(player, player.getInventory().getItemInMainHand(), poseId);
                }

                // 临时隐藏副手物品 (视觉隐藏)
                hideOffhandVisual(player);
            }
        } else {
            // 当玩家松开 Shift (停止潜行)
            if (sneakStartTimes.containsKey(player.getUniqueId())) {
                exitStance(player);
            }
        }
    }

    /**
     * 辅助方法：发送数据包隐藏副手物品
     */
    private void hideOffhandVisual(Player player) {
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null && offhand.getType() != Material.AIR) {
            ItemStack air = new ItemStack(Material.AIR);
            org.bukkit.inventory.EquipmentSlot slot = org.bukkit.inventory.EquipmentSlot.OFF_HAND;
            
            // 隐藏给自己 (虽然会导致UI图标消失，但这是为了隐藏第一人称模型的必要妥协)
            player.sendEquipmentChange(player, slot, air);
            
            // 隐藏给他人
            for (Player p : player.getWorld().getPlayers()) {
                if (p != player && p.canSee(player)) {
                    p.sendEquipmentChange(player, slot, air);
                }
            }
        }
    }

    /**
     * 辅助方法：发送数据包恢复副手物品
     */
    private void restoreOffhandVisual(Player player) {
        ItemStack currentOffhand = player.getInventory().getItemInOffHand();
        ItemStack itemToShow = (currentOffhand != null) ? currentOffhand : new ItemStack(Material.AIR);
        org.bukkit.inventory.EquipmentSlot slot = org.bukkit.inventory.EquipmentSlot.OFF_HAND;

        // 恢复给自己
        player.sendEquipmentChange(player, slot, itemToShow);
        
        // 恢复给他人
        for (Player p : player.getWorld().getPlayers()) {
            if (p != player && p.canSee(player)) {
                p.sendEquipmentChange(player, slot, itemToShow);
            }
        }
    }

    /**
     * 退出架势，恢复状态和物品
     */
    private void exitStance(Player player) {
        sneakStartTimes.remove(player.getUniqueId());
        readyState.remove(player.getUniqueId());
        
        // 恢复武器模型到正常状态
        WeaponManager.WeaponData data = weaponManager.getWeaponData(player.getInventory().getItemInMainHand());
        if (data != null) {
            revertWeaponModel(player, player.getInventory().getItemInMainHand());
        }

        // 恢复副手物品 (视觉)
        restoreOffhandVisual(player);
    }

    /**
     * 监听玩家交互 (点击) 事件
     * 负责在架势下触发攻击
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        
        // 如果正在蓄力中，禁止右键格挡 (防止卡住)
        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (isDrawingUI(player)) { 
                event.setCancelled(true);
                return;
            }
        }
        
        // 如果玩家左键点击 (攻击) 且已经就绪
        if (event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK) {
            if (readyState.getOrDefault(player.getUniqueId(), false)) {
                executeSquareOff(player); // 执行技能
                event.setCancelled(true); // 取消原版挥动，使用我们的自定义逻辑
            }
        }
    }
    
    /**
     * 监听实体伤害事件
     * 确保技能也能在打到怪物时触发
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        Player player = (Player) event.getDamager();
        
        if (readyState.getOrDefault(player.getUniqueId(), false)) {
            executeSquareOff(player);
            event.setCancelled(true); // 取消这次普通攻击，转而执行技能的冲刺伤害逻辑
        }
    }

    /**
     * 防止玩家在架势期间因为点击看似空的副手槽位而导致物品显示异常
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        // 只有处于架势状态的玩家需要处理
        if (sneakStartTimes.containsKey(player.getUniqueId())) {
            // 如果点击的是副手槽位 (45 是副手槽位的通常索引，但在不同容器中可能不同，所以用 SlotType 判断更稳)
            if (event.getSlotType() == InventoryType.SlotType.QUICKBAR && event.getSlot() == 40) { // 40 is offhand in player inventory
                 // 阻止操作并强制刷新背包，确保护甲/物品显示正确
                 event.setCancelled(true);
                 player.updateInventory();
            } else if (event.getSlotType() == InventoryType.SlotType.CONTAINER && event.getSlot() == 40) {
                 // 某些界面下副手可能是 Container slot 40
                 event.setCancelled(true);
                 player.updateInventory();
            } else if (event.getClick().isKeyboardClick() && event.getHotbarButton() == 40) {
                 // 防止用键盘交换到副手
                 event.setCancelled(true);
                 player.updateInventory();
            }
        }
    }
    
    /**
     * 防止玩家在架势期间使用 F 键交换副手物品
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        if (sneakStartTimes.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * 执行 "准备架势" 的攻击逻辑
     */
    private void executeSquareOff(Player player) {
        // 清除状态
        readyState.remove(player.getUniqueId());
        sneakStartTimes.remove(player.getUniqueId());
        
        // 设置冷却
        skillCooldowns.put(player.getUniqueId(), System.currentTimeMillis() + squareOffCooldown);
        if (player.getInventory().getItemInMainHand().getType() != Material.AIR) {
             player.setCooldown(player.getInventory().getItemInMainHand().getType(), 20); // 给原版物品也加个短冷却
        }

        // 恢复武器模型
        revertWeaponModel(player, player.getInventory().getItemInMainHand());
        
        // 恢复副手物品 (视觉)
        restoreOffhandVisual(player);
        
        // 执行冲刺和特效
        performDashAttack(player);
    }

    /**
     * 处理冲刺、特效和伤害判定
     */
    private void performDashAttack(Player player) {
        Vector dir = player.getLocation().getDirection().normalize();
        
        // 1. 冲刺位移 (Y轴微调防止卡地)
        player.setVelocity(dir.multiply(squareOffDashVelocity).setY(0.2));
        
        // 2. 播放音效
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.5f);
        
        // 获取武器的攻击范围配置
        WeaponManager.WeaponData data = weaponManager.getWeaponData(player.getInventory().getItemInMainHand());
        double range = (data != null) ? data.attackRange : 1.5;
        
        // 计算技能基础伤害 (基于武器双手伤害)
        double baseDamage = (data != null) ? data.twoHandDamage : 10.0;

        // 3. 播放粒子特效 (优化位置和形态)
        // 计算玩家前方 range 格的位置
        org.bukkit.Location origin = player.getEyeLocation().add(dir.clone().multiply(range));
        
        // A. 生成一个横扫粒子 (增加水平打击感)
        player.getWorld().spawnParticle(org.bukkit.Particle.SWEEP_ATTACK, origin, 1);
        
        // 记录技能期间的额外穿甲
        activeSkillBonusAP.put(player.getUniqueId(), squareOffArmorPen);
        
        // 4. 延迟伤害判定 (模拟冲刺过程中的碰撞)
        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (ticks++ > 5) { // 持续 5 ticks (0.25秒)
                    this.cancel(); 
                    activeSkillBonusAP.remove(player.getUniqueId());
                    return; 
                }
                // 对周围实体造成伤害，范围使用配置的 range
                for (org.bukkit.entity.Entity e : player.getNearbyEntities(range, range, range)) {
                    if (e instanceof org.bukkit.entity.LivingEntity && e != player) {
                        // 造成伤害：武器基础伤害 * 技能倍率
                        ((org.bukkit.entity.LivingEntity) e).damage(baseDamage * squareOffDamageMult, player);
                        e.getWorld().playSound(e.getLocation(), Sound.ENTITY_IRON_GOLEM_DAMAGE, 1.0f, 1.0f);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }
    
    // 辅助方法：判断玩家是否正在蓄力 (绘制UI中)
    public boolean isDrawingUI(Player player) {
        return sneakStartTimes.containsKey(player.getUniqueId());
    }
    
    // 获取当前技能提供的额外穿甲
    public double getBonusArmorPenetration(Player player) {
        return activeSkillBonusAP.getOrDefault(player.getUniqueId(), 0.0);
    }

    private void sendActionBar(Player player, String message) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message));
    }
    
    /**
     * 核心方法：更新武器模型 (设置 CustomModelData)
     * 包含 NBT 备份逻辑，确保能还原
     */
    private void updateWeaponModel(Player player, ItemStack item, int targetModelId) {
        if (item == null || !item.hasItemMeta()) return;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        
        // 1. 尝试读取已保存的 Base ID (原始ID)
        int baseId = -1;
        if (meta.getPersistentDataContainer().has(new org.bukkit.NamespacedKey(plugin, "hana_weapon_id"), org.bukkit.persistence.PersistentDataType.INTEGER)) {
            baseId = meta.getPersistentDataContainer().get(new org.bukkit.NamespacedKey(plugin, "hana_weapon_id"), org.bukkit.persistence.PersistentDataType.INTEGER);
        }
        
        // 2. 如果没读到，尝试从当前的 ModelData 反推
        if (baseId == -1 || baseId > 1000000) {
             if (meta.hasCustomModelData()) {
                 int currentModelId = meta.getCustomModelData();
                 if (currentModelId > 1000000) {
                     baseId = currentModelId / 100; // 假设变体ID是 BaseID * 100 + X
                 } else {
                     baseId = currentModelId;
                 }
             }
        }
        
        // 3. 将 Base ID 写入 NBT (持久化保存，防止丢失)
        if (baseId > 0) {
            meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "hana_weapon_id"), org.bukkit.persistence.PersistentDataType.INTEGER, baseId);
        }

        // 4. 应用新的 CustomModelData
        if (meta.hasCustomModelData() && meta.getCustomModelData() != targetModelId) {
            meta.setCustomModelData(targetModelId);
            item.setItemMeta(meta);
            player.getInventory().setItemInMainHand(item); // 关键：强制刷新玩家手持物品，解决客户端不同步问题
        }
    }

    /**
     * 核心方法：恢复武器模型
     */
    private void revertWeaponModel(Player player, ItemStack item) {
        if (item == null || !item.hasItemMeta()) return;
        
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        int baseId = -1;
        
        // 1. 优先从 NBT 读取 Base ID
        if (meta.getPersistentDataContainer().has(new org.bukkit.NamespacedKey(plugin, "hana_weapon_id"), org.bukkit.persistence.PersistentDataType.INTEGER)) {
            baseId = meta.getPersistentDataContainer().get(new org.bukkit.NamespacedKey(plugin, "hana_weapon_id"), org.bukkit.persistence.PersistentDataType.INTEGER);
        }
        
        // 2. 如果没有 NBT，尝试反推
        if (baseId == -1 && meta.hasCustomModelData()) {
            int currentId = meta.getCustomModelData();
            if (currentId > 1000000) {
                baseId = currentId / 100;
            } else {
                baseId = currentId;
            }
        }
        
        // 3. 恢复模型
        if (baseId > 0 && meta.hasCustomModelData() && meta.getCustomModelData() != baseId) {
            meta.setCustomModelData(baseId);
            item.setItemMeta(meta);
            player.getInventory().setItemInMainHand(item); // 强制刷新
        }
    }
}
