package xyz.hanamae.hanaWeapons;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class WeaponManager {

    private final HanaWeapons plugin;
    private final Map<Integer, WeaponData> weapons = new HashMap<>();

    public WeaponManager(HanaWeapons plugin) {
        this.plugin = plugin;
    }

    public void loadWeapons() {
        weapons.clear();
        File file = new File(plugin.getDataFolder(), "weapons.yml");
        if (!file.exists()) {
            plugin.saveResource("weapons.yml", false);
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        
        // 自动迁移旧配置 (10003 -> 11451)
        if (config.contains("weapons.10003") && !config.contains("weapons.11451")) {
            plugin.getLogger().info("Migrating legacy weapon ID 10003 to 11451...");
            ConfigurationSection oldSection = config.getConfigurationSection("weapons.10003");
            config.set("weapons.11451", oldSection);
            config.set("weapons.11451.custom_model_data", 11451); // 确保 ModelData 也更新
            config.set("weapons.10003", null); // 删除旧的
            try {
                config.save(file);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to save migrated config: " + e.getMessage());
            }
        }

        ConfigurationSection section = config.getConfigurationSection("weapons");

        if (section == null) {
            plugin.getLogger().warning("No weapons section found in weapons.yml");
            return;
        }

        for (String key : section.getKeys(false)) {
            ConfigurationSection weaponSection = section.getConfigurationSection(key);
            if (weaponSection == null) continue;

            int customModelData = weaponSection.getInt("custom_model_data");
            double oneHandSpeed = weaponSection.getDouble("one_handed.attack_speed");
            double oneHandDamage = weaponSection.getDouble("one_handed.damage");
            double twoHandSpeed = weaponSection.getDouble("two_handed.attack_speed");
            double twoHandDamage = weaponSection.getDouble("two_handed.damage");
            double reduction = weaponSection.getDouble("two_handed.block_reduction", 0.5);
            // 默认名字使用 key，实际应该从 yml 读取 name 字段
            String name = weaponSection.getString("name", key);

            WeaponData data = new WeaponData(name, oneHandSpeed, oneHandDamage, twoHandSpeed, twoHandDamage, reduction);
            weapons.put(customModelData, data);
            
            plugin.getLogger().info("Loaded weapon: " + key + " (CMD: " + customModelData + ")");
        }
    }

    public WeaponData getWeaponData(int customModelData) {
        return weapons.get(customModelData);
    }

    public static class WeaponData {
        public final String name;
        public final double oneHandSpeed;
        public final double oneHandDamage;
        public final double twoHandSpeed;
        public final double twoHandDamage;
        public final double reduction;

        public WeaponData(String name, double oneHandSpeed, double oneHandDamage, double twoHandSpeed, double twoHandDamage, double reduction) {
            this.name = name;
            this.oneHandSpeed = oneHandSpeed;
            this.oneHandDamage = oneHandDamage;
            this.twoHandSpeed = twoHandSpeed;
            this.twoHandDamage = twoHandDamage;
            this.reduction = reduction;
        }
    }
}
