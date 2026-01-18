package xyz.hanamae.hanaWeapons;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.Listener;

public class HanaWeapons extends JavaPlugin {
    private WeaponManager weaponManager;
    private WeaponRegistry weaponRegistry;

    // Old onCommand method removed to use HanaWeaponsCommand
    /* 
    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (label.equalsIgnoreCase("hw") && args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("hw.admin")) return true;
            weaponManager.loadWeapons(); // 重新调用加载方法
            sender.sendMessage("§a[HanaWeapons] 配置文件已重载！");
            return true;
        }
        return false;
    }
    */

    @Override
    public void onEnable() {
        this.weaponManager = new WeaponManager(this);
        this.weaponManager.loadWeapons(); // 关键修复：启动时加载配置
        
        this.weaponRegistry = new WeaponRegistry(this, weaponManager);
        
        getServer().getPluginManager().registerEvents(new WeaponListener(this, weaponRegistry), this);
        
        // Register command
        getCommand("hw").setExecutor(new HanaWeaponsCommand(this, weaponManager));
        
        getLogger().info("HanaWeapons On");
    }
    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }

}