package xyz.hanamae.hanaWeapons;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.entity.Player;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

/**
 * HanaWeapons 主类 - 负责插件初始化、管理器注册及核心状态追踪
 */
public class HanaWeapons extends JavaPlugin implements Listener {
    private WeaponManager weaponManager;
    private PostureManager postureManager;
    private ComboManager comboManager;

    private SkillManager skillManager;
    // Configuration
    private int blockingToleranceMs = 600;

    // 追踪玩家最后一次格挡的时间戳（用于判定格挡容错）
    private final Map<UUID, Long> lastBlockingTime = new HashMap<>();
    // 追踪玩家开始格挡的瞬间时间点（用于计算招架 Parry 的时间窗）
    private final Map<UUID, Long> blockStartTime = new HashMap<>();
    // 追踪玩家招架的冷却时间戳
    private final Map<UUID, Long> parryCooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        // 0. 加载配置
        saveDefaultConfig();
        blockingToleranceMs = getConfig().getInt("blocking-tolerance-ms", 600);

        // 1. 初始化管理器
        this.weaponManager = new WeaponManager(this);
        this.weaponManager.loadWeapons(); // 关键：启动时从配置文件加载武器数据
        
        this.postureManager = new PostureManager(this, weaponManager);
        this.postureManager.onEnable(); // 启动姿态管理器的逻辑（如任务调度）
        
        this.comboManager = new ComboManager(this, weaponManager);
        this.skillManager = new SkillManager(this, weaponManager);
        // Explicitly load skill config (though constructor does it too)
        this.skillManager.loadConfig();
        
        getServer().getPluginManager().registerEvents(skillManager, this);
        getServer().getPluginManager().registerEvents(new WeaponListener(this, weaponManager), this);
        getServer().getPluginManager().registerEvents(new PostureListener(this, postureManager, weaponManager), this);
        getServer().getPluginManager().registerEvents(new StunListener(postureManager), this);
        
        // 3. 注册指令
        getCommand("hw").setExecutor(new HanaWeaponsCommand(this, weaponManager));
        
        // 4. 启动格挡追踪任务 (每 1 tick 运行一次)
        // 目的：实时更新玩家是否处于格挡或举盾状态，因为原版 API 的 getBlockStartTime 并不完全好用
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
                    // 检查玩家是否正在格挡、举手或正在使用物品
                    // 优化：仅当玩家手持有效武器时才更新格挡状态，避免吃东西误判
                    if (isHoldingValidWeapon(player) && (player.isBlocking() || player.isHandRaised())) {
                        lastBlockingTime.put(player.getUniqueId(), now);
                    }
                }
            }
        }.runTaskTimer(this, 1L, 1L);
        
        getLogger().info("HanaWeapons 已启动");
    }

    @org.bukkit.event.EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastBlockingTime.remove(uuid);
        blockStartTime.remove(uuid);
        parryCooldowns.remove(uuid);
    }
    
    /**
     * 判断玩家是否持有有效武器（主手或副手）
     */
    /**
     * 检查玩家是否持有有效的“可格挡”武器
     * 防止普通物品或未启用格挡机制的武器触发格挡逻辑
     */
    private boolean isHoldingValidWeapon(Player player) {
        WeaponManager.WeaponData mainData = weaponManager.getWeaponData(player.getInventory().getItemInMainHand());
        if (mainData != null && mainData.mechanicBlocking) return true;

        WeaponManager.WeaponData offData = weaponManager.getWeaponData(player.getInventory().getItemInOffHand());
        if (offData != null && offData.mechanicBlocking) return true;
        
        return false;
    }
    
    /**
     * 判断玩家是否正在格挡，或在极短的时间前（配置容错时间内）刚刚停止格挡
     * 用于补偿网络延迟或动画切换瞬间的检测失效
     */
    public boolean isBlockingOrRecentlyBlocking(Player player) {
        // 核心修复：必须持有有效且启用了 blocking 机制的武器，否则一律视为未格挡
        // 这解决了“长剑(blocking:false)右键也会触发架势条 Action Bar”的问题
        if (!isHoldingValidWeapon(player)) return false;

        // 1. 正在格挡或举手（且持有有效武器）
        if (player.isBlocking() || player.isHandRaised()) return true;
        
        // 2. 检查近期格挡记录（容错）
        Long lastTime = lastBlockingTime.get(player.getUniqueId());
        return lastTime != null && (System.currentTimeMillis() - lastTime) <= blockingToleranceMs;
    }

    /**
     * 更新玩家最后格挡的时间戳
     */
    public void updateBlockingStatus(Player player) {
        lastBlockingTime.put(player.getUniqueId(), System.currentTimeMillis());
    }
    
    /**
     * 记录玩家开始格挡的具体时刻
     */
    public void updateBlockStartTime(Player player) {
        blockStartTime.put(player.getUniqueId(), System.currentTimeMillis());
    }

    /**
     * 获取玩家开始格挡的时间戳，用于计算招架判定
     */
    public long getBlockStartTime(Player player) {
        return blockStartTime.getOrDefault(player.getUniqueId(), 0L);
    }
    
    /**
     * 检查玩家招架技能是否在冷却中
     */
    public boolean isOnParryCooldown(Player player) {
        Long cooldownEnd = parryCooldowns.get(player.getUniqueId());
        return cooldownEnd != null && System.currentTimeMillis() < cooldownEnd;
    }

    /**
     * 设置玩家的招架冷却时间
     */
    public void setParryCooldown(Player player, int cooldownMs) {
        parryCooldowns.put(player.getUniqueId(), System.currentTimeMillis() + cooldownMs);
    }

    /**
     * 获取连招管理器
     */
    public ComboManager getComboManager() {
        return comboManager;
    }

    public SkillManager getSkillManager() {
        return skillManager;
    }

    @Override
    public void onDisable() {
        // 插件关闭时清理姿态管理器的资源（如 BossBar）
        if (postureManager != null) postureManager.onDisable();
    }
}