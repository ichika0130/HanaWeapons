package xyz.hanamae.hanaWeapons;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.Collections;

public class WeaponManager {

    private final HanaWeapons plugin;
    private final Map<Integer, WeaponData> weapons = new HashMap<>();
    private final Map<org.bukkit.entity.EntityType, Double> entityApcMap = new HashMap<>();
    private final Map<org.bukkit.entity.EntityType, Boolean> entityHeavyEffectMap = new HashMap<>();
    private int postureBarLength = 20;
    private static final java.util.Set<org.bukkit.entity.EntityType> GROUP_UNDEAD = java.util.EnumSet.of(
            org.bukkit.entity.EntityType.SKELETON,
            org.bukkit.entity.EntityType.STRAY,
            org.bukkit.entity.EntityType.WITHER_SKELETON,
            org.bukkit.entity.EntityType.ZOMBIE,
            org.bukkit.entity.EntityType.HUSK,
            org.bukkit.entity.EntityType.DROWNED,
            org.bukkit.entity.EntityType.ZOMBIE_VILLAGER,
            org.bukkit.entity.EntityType.ZOMBIFIED_PIGLIN,
            org.bukkit.entity.EntityType.ZOGLIN,
            org.bukkit.entity.EntityType.SKELETON_HORSE,
            org.bukkit.entity.EntityType.ZOMBIE_HORSE,
            org.bukkit.entity.EntityType.PHANTOM,
            org.bukkit.entity.EntityType.WITHER
    );

    public WeaponManager(HanaWeapons plugin) {
        this.plugin = plugin;
    }

    private void loadPostureConfig() {
        File file = new File(plugin.getDataFolder(), "posture.yml");
        if (!file.exists()) {
            plugin.saveResource("posture.yml", false);
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        
        postureBarLength = config.getInt("settings.bar_length", 20);
        
        entityApcMap.clear();
        ConfigurationSection section = config.getConfigurationSection("entities");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    org.bukkit.entity.EntityType type = org.bukkit.entity.EntityType.valueOf(key.toUpperCase());
                    entityApcMap.put(type, section.getDouble(key));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid entity type in posture.yml (entities): " + key);
                }
            }
        }

        entityHeavyEffectMap.clear();
        ConfigurationSection effectSection = config.getConfigurationSection("entity_impact_effects");
        if (effectSection != null) {
            for (String key : effectSection.getKeys(false)) {
                try {
                    org.bukkit.entity.EntityType type = org.bukkit.entity.EntityType.valueOf(key.toUpperCase());
                    if (effectSection.isConfigurationSection(key)) {
                         entityHeavyEffectMap.put(type, effectSection.getBoolean(key + ".heavy_particle", false));
                    } else {
                         // Fallback for simple boolean
                         entityHeavyEffectMap.put(type, effectSection.getBoolean(key));
                    }
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid entity type in posture.yml (effects): " + key);
                }
            }
        }
        
        plugin.getLogger().info("Loaded posture config: " + entityApcMap.size() + " APC entities, " + entityHeavyEffectMap.size() + " effect entities.");
    }
    
    public int getPostureBarLength() {
        return postureBarLength;
    }
    
    public boolean hasHeavyEffect(org.bukkit.entity.EntityType type) {
        return entityHeavyEffectMap.getOrDefault(type, true); // Default true
    }

    /**
     * 自动加载配置文件中的所有武器数据
     * 包括架势条配置、武器属性、连招步骤、亡灵特攻等
     */
    public void loadWeapons() {
        loadPostureConfig(); // 加载架势配置
        
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
            String materialName = weaponSection.getString("material", "NETHERITE_SWORD");
            org.bukkit.Material material = org.bukkit.Material.matchMaterial(materialName);
            if (material == null) material = org.bukkit.Material.NETHERITE_SWORD;

            // ================== 单手/双手基础属性 ==================
            double oneHandSpeed = weaponSection.getDouble("one_handed.attack_speed");
            double oneHandDamage = weaponSection.getDouble("one_handed.damage");
            double oneHandMoveSpeed = weaponSection.getDouble("one_handed.movement_speed", 0.0); // 默认不改变

            double twoHandSpeed = weaponSection.getDouble("two_handed.attack_speed");
            double twoHandDamage = weaponSection.getDouble("two_handed.damage");
            double twoHandMoveSpeed = weaponSection.getDouble("two_handed.movement_speed", 0.0); // 默认不改变

            // ================== 格挡与架势属性 ==================
            double reduction = weaponSection.getDouble("two_handed.block_reduction", 0.5);
            
            double armorPenetration = weaponSection.getDouble("armor_penetration", 1.0);
            double defenseCoefficient = weaponSection.getDouble("defense_coefficient", 0.0);
            double postureRegenRate = weaponSection.getDouble("posture_regen_rate", 2.5);
            int postureBarLength = weaponSection.getInt("posture_bar_length", -1); // -1 表示使用全局配置
            double attackRange = weaponSection.getDouble("attack_range", 3.0); // 默认 3.0 (原版距离)

            // 默认名字使用 key，实际应该从 yml 读取 name 字段
            String name = weaponSection.getString("display_name", key);
            List<String> lore = weaponSection.getStringList("lore");
            if (lore == null) lore = new java.util.ArrayList<>();
            
            // 自动追加系数显示
            if (armorPenetration > 0) {
                lore.add("§f穿甲系数: §c" + armorPenetration);
            }
            if (defenseCoefficient > 0) {
                lore.add("§f防御系数: §a" + defenseCoefficient);
            }

            // ================== 机制开关 (Mechanics) ==================
            boolean mechanicTwoHanded = weaponSection.getBoolean("mechanics.two_handed", false);
            // 兼容性自动推断
            if (!weaponSection.contains("mechanics.two_handed") && weaponSection.contains("two_handed")) {
                mechanicTwoHanded = true;
            }

            boolean mechanicBlocking = weaponSection.getBoolean("mechanics.blocking", false);
            if (!weaponSection.contains("mechanics.blocking") && defenseCoefficient > 0) {
                mechanicBlocking = true;
            }

            boolean mechanicParry = weaponSection.getBoolean("mechanics.parry", false);
            int parryWindow = weaponSection.getInt("parry_window", 200); // ms
            int parryCooldown = weaponSection.getInt("parry_cooldown", 1000); // ms

            // ================== 特效与音效 (Effects) ==================
            List<String> blockSounds = weaponSection.getStringList("effects.block_sounds");
            if (blockSounds == null || blockSounds.isEmpty()) {
                String singleSound = weaponSection.getString("effects.block_sound");
                if (singleSound != null) blockSounds = Collections.singletonList(singleSound);
                else blockSounds = Collections.singletonList("ITEM_SHIELD_BLOCK");
            }
            List<org.bukkit.Sound> parsedBlockSounds = new java.util.ArrayList<>();
            for (String s : blockSounds) parsedBlockSounds.add(parseSound(s, org.bukkit.Sound.ITEM_SHIELD_BLOCK));

            double blockPitchMin = weaponSection.getDouble("effects.block_pitch_min", 0.8);
            double blockPitchMax = weaponSection.getDouble("effects.block_pitch_max", 1.2);
            
            List<String> parrySounds = weaponSection.getStringList("effects.parry_sounds");
            if (parrySounds == null || parrySounds.isEmpty()) {
                String singleSound = weaponSection.getString("effects.parry_sound");
                if (singleSound != null) parrySounds = Collections.singletonList(singleSound);
                else parrySounds = Collections.singletonList("BLOCK_ANVIL_LAND");
            }
            List<org.bukkit.Sound> parsedParrySounds = new java.util.ArrayList<>();
            for (String s : parrySounds) parsedParrySounds.add(parseSound(s, org.bukkit.Sound.BLOCK_ANVIL_LAND));

            double parryPitchMin = weaponSection.getDouble("effects.parry_pitch_min", 1.5);
            double parryPitchMax = weaponSection.getDouble("effects.parry_pitch_max", 2.0);
            
            String blockParticleStr = weaponSection.getString("effects.block_particle", "LAVA");
            org.bukkit.Particle blockParticle = parseParticle(blockParticleStr, null); // Allow null for no particle
            int blockParticleCount = weaponSection.getInt("effects.block_particle_count", 10);
            double blockParticleSpread = weaponSection.getDouble("effects.block_particle_spread", 0.5);
            
            String parryParticleStr = weaponSection.getString("effects.parry_particle", "LAVA");
            org.bukkit.Particle parryParticle = parseParticle(parryParticleStr, org.bukkit.Particle.LAVA);
            int parryParticleCount = weaponSection.getInt("effects.parry_particle_count", 10);
            double parryParticleSpread = weaponSection.getDouble("effects.parry_particle_spread", 0.5);

            // ================== 弹反负面效果 (Debuffs) ==================
            List<ConfiguredPotionEffect> parryDebuffs = new java.util.ArrayList<>();
            if (weaponSection.contains("parry_debuffs")) {
                ConfigurationSection debuffSection = weaponSection.getConfigurationSection("parry_debuffs");
                if (debuffSection != null) {
                    // Map format: WEAKNESS: {duration: 60, amplifier: 0}
                    for (String debuffKey : debuffSection.getKeys(false)) {
                        org.bukkit.potion.PotionEffectType type = parseEffectType(debuffKey);
                        if (type != null) {
                            int duration = debuffSection.getInt(debuffKey + ".duration", 60);
                            int amplifier = debuffSection.getInt(debuffKey + ".amplifier", 0);
                            parryDebuffs.add(new ConfiguredPotionEffect(type, duration, amplifier));
                        }
                    }
                } else {
                    // List format (List<Map<String, Object>>)
                    List<Map<?, ?>> debuffList = weaponSection.getMapList("parry_debuffs");
                    for (Map<?, ?> map : debuffList) {
                         Object typeNameObj = map.get("type");
                         if (!(typeNameObj instanceof String)) continue;
                         
                         String typeName = (String) typeNameObj;
                         org.bukkit.potion.PotionEffectType type = parseEffectType(typeName);
                         if (type != null) {
                             int duration = (map.get("duration") instanceof Number) ? ((Number) map.get("duration")).intValue() : 60;
                             int amplifier = (map.get("amplifier") instanceof Number) ? ((Number) map.get("amplifier")).intValue() : 0;
                             parryDebuffs.add(new ConfiguredPotionEffect(type, duration, amplifier));
                         }
                    }
                }
            }
            
            // ================== 连招系统 (Combo) ==================
            boolean comboEnabled = weaponSection.getBoolean("combo.enabled", false);
            int comboResetTime = weaponSection.getInt("combo.reset_time", 1500);
            List<ComboStep> comboSteps = new java.util.ArrayList<>();
            
            if (comboEnabled) {
                 List<Map<?, ?>> stepsList = weaponSection.getMapList("combo.steps");
                 plugin.getLogger().info("Loading combo steps for weapon " + name + ": found " + stepsList.size() + " steps.");
                 for (Map<?, ?> map : stepsList) {
                     double dmgMult = (map.get("damage_mult") instanceof Number) ? ((Number) map.get("damage_mult")).doubleValue() : 1.0;
                     int poseAdd = (map.get("pose_model_add") instanceof Number) ? ((Number) map.get("pose_model_add")).intValue() : 0;
                     String actionBar = (map.get("action_bar") instanceof String) ? (String) map.get("action_bar") : null;
                     int nextStep = (map.get("next_step") instanceof Number) ? ((Number) map.get("next_step")).intValue() : -1;
                     comboSteps.add(new ComboStep(dmgMult, poseAdd, actionBar, nextStep));
                     plugin.getLogger().info(" - Step loaded: poseAdd=" + poseAdd + ", nextStep=" + nextStep);
                 }
            }

            // ================== 亡灵/特攻系统 (Slayer) ==================
            double slayerMultiplier = 1.0;
            java.util.Set<org.bukkit.entity.EntityType> slayerTypes = new java.util.HashSet<>();
            java.util.Set<String> slayerGroups = new java.util.HashSet<>();
            if (weaponSection.isConfigurationSection("slayer")) {
                ConfigurationSection slayerSection = weaponSection.getConfigurationSection("slayer");
                slayerMultiplier = slayerSection.getDouble("multiplier", 1.0);
                java.util.List<String> typeList = slayerSection.getStringList("types");
                for (String t : typeList) {
                    if (t == null) continue;
                    try {
                        org.bukkit.entity.EntityType et = org.bukkit.entity.EntityType.valueOf(t.toUpperCase());
                        slayerTypes.add(et);
                    } catch (IllegalArgumentException ignored) {}
                }
                java.util.List<String> groupList = slayerSection.getStringList("groups");
                for (String g : groupList) {
                    if (g != null) slayerGroups.add(g.toUpperCase());
                }
            }
            
            String skill = weaponSection.getString("skill");
            if (skill == null) skill = weaponSection.getString("mechanics.skill");
            
            int skillPoseModel = 0;
            if (weaponSection.contains("skill_pose_model")) {
                if (weaponSection.isInt("skill_pose_model")) {
                    skillPoseModel = weaponSection.getInt("skill_pose_model");
                } else {
                    plugin.getLogger().warning("Weapon " + customModelData + " has an invalid 'skill_pose_model' value. It must be an integer (CustomModelData ID), but found: " + weaponSection.get("skill_pose_model"));
                }
            }

            WeaponData data = new WeaponData(customModelData, name, material, lore, oneHandSpeed, oneHandDamage, oneHandMoveSpeed, twoHandSpeed, twoHandDamage, twoHandMoveSpeed, reduction, armorPenetration, defenseCoefficient, postureRegenRate, postureBarLength, attackRange, mechanicTwoHanded, mechanicBlocking, mechanicParry, parryWindow, parryCooldown, parsedBlockSounds, blockPitchMin, blockPitchMax, parsedParrySounds, parryPitchMin, parryPitchMax, blockParticle, blockParticleCount, blockParticleSpread, parryParticle, parryParticleCount, parryParticleSpread, parryDebuffs, comboEnabled, comboResetTime, comboSteps, slayerMultiplier, slayerTypes, slayerGroups, skill, skillPoseModel);
            weapons.put(customModelData, data);
            
            // 自动注册变体 ID (例如 1145701, 1145702, 1145703, 1145704)
            // 假设变体 ID = baseID * 100 + modelAdd
            // 我们遍历 steps 自动注册
            if (comboEnabled && !comboSteps.isEmpty()) {
                for (ComboStep step : comboSteps) {
                    if (step.poseModelAdd > 0) {
                        int variantId = customModelData * 100 + step.poseModelAdd;
                        // 将变体 ID 也指向同一个 data 对象
                        weapons.put(variantId, data);
                        plugin.getLogger().info("Registered variant ID: " + variantId + " for weapon " + name);
                    }
                }
            }

            // Register skill pose model as a variant
            if (skillPoseModel > 0) {
                weapons.put(skillPoseModel, data);
                plugin.getLogger().info("Registered skill pose ID: " + skillPoseModel + " for weapon " + name);
            }
        }
        plugin.getLogger().info("Loaded " + weapons.size() + " weapons (including variants).");
    }

    private org.bukkit.Sound parseSound(String name, org.bukkit.Sound def) {
        if (name == null) return def;
        try { return org.bukkit.Sound.valueOf(name.toUpperCase()); } catch (Exception e) { return def; }
    }

    private org.bukkit.Particle parseParticle(String name, org.bukkit.Particle def) {
        if (name == null) return def;
        try { return org.bukkit.Particle.valueOf(name.toUpperCase()); } catch (Exception e) { return def; }
    }
    
    private org.bukkit.potion.PotionEffectType parseEffectType(String name) {
        if (name == null) return null;
        return org.bukkit.potion.PotionEffectType.getByName(name.toUpperCase());
    }
    
    public static class ConfiguredPotionEffect {
        public final org.bukkit.potion.PotionEffectType type;
        public final int duration;
        public final int amplifier;
        
        public ConfiguredPotionEffect(org.bukkit.potion.PotionEffectType type, int duration, int amplifier) {
            this.type = type;
            this.duration = duration;
            this.amplifier = amplifier;
        }
    }
    
    public WeaponData getWeaponData(int customModelData) {
        return weapons.get(customModelData);
    }

    public WeaponData getWeaponData(org.bukkit.inventory.ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        
        // 优先检查 PDC 中的 NBT ID
        if (meta.getPersistentDataContainer().has(new org.bukkit.NamespacedKey(plugin, "hana_weapon_id"), org.bukkit.persistence.PersistentDataType.INTEGER)) {
            int id = meta.getPersistentDataContainer().get(new org.bukkit.NamespacedKey(plugin, "hana_weapon_id"), org.bukkit.persistence.PersistentDataType.INTEGER);
            return getWeaponData(id);
        }
        
        // 回退到 CustomModelData
        if (!meta.hasCustomModelData()) return null;
        return getWeaponData(meta.getCustomModelData());
    }
    
    public double getArmorPenetration(org.bukkit.inventory.ItemStack item) {
        if (item == null) return 1.0;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasCustomModelData()) {
            WeaponData data = getWeaponData(meta.getCustomModelData());
            if (data != null) return data.armorPenetration;
        }
        // Vanilla defaults
        String name = item.getType().name();
        if (name.contains("_AXE")) return 2.5;
        if (name.contains("_SWORD")) return 1.5;
        return 1.0;
    }

    public double getDefenseCoefficient(org.bukkit.inventory.ItemStack item) {
        if (item == null) return 0.0;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasCustomModelData()) {
            WeaponData data = getWeaponData(meta.getCustomModelData());
            if (data != null) return data.defenseCoefficient;
        }
        return 0.0;
    }
    
    public double getPostureRegenRate(org.bukkit.inventory.ItemStack item) {
        if (item == null) return 2.5;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasCustomModelData()) {
            WeaponData data = getWeaponData(meta.getCustomModelData());
            if (data != null) return data.postureRegenRate;
        }
        return 2.5;
    }
    
    public int getPostureBarLength(org.bukkit.inventory.ItemStack item) {
        if (item == null) return 0;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasCustomModelData()) {
            WeaponData data = getWeaponData(meta.getCustomModelData());
            if (data != null && data.postureBarLength > 0) return data.postureBarLength;
        }
        return 0;
    }

    public double getEntityArmorPenetration(org.bukkit.entity.LivingEntity attacker) {
        if (attacker == null) return 1.0;
        
        // 1. Check Weapon
        org.bukkit.inventory.ItemStack item = null;
        if (attacker.getEquipment() != null) item = attacker.getEquipment().getItemInMainHand();
        
        if (item != null && item.getType() != org.bukkit.Material.AIR) {
             return getArmorPenetration(item); 
        }
        
        // 2. Check Entity Type (From Map)
        return entityApcMap.getOrDefault(attacker.getType(), 1.0);
    }
    
    public double getProjectileArmorPenetration(org.bukkit.entity.EntityType type) {
        return entityApcMap.getOrDefault(type, 1.0);
    }

    public static class ComboStep {
        public final double damageMult;
        public final int poseModelAdd;
        public final String actionBar;
        public final int nextStep; // -1 for default (+1)
        
        public ComboStep(double damageMult, int poseModelAdd, String actionBar, int nextStep) {
            this.damageMult = damageMult;
            this.poseModelAdd = poseModelAdd;
            this.actionBar = actionBar;
            this.nextStep = nextStep;
        }
    }

    public static class WeaponData {
        public final int id; // Added ID
        public final String name;
        public final org.bukkit.Material material;
        public final List<String> lore;
        public final double oneHandSpeed;
        public final double oneHandDamage;
        public final double oneHandMoveSpeed;
        public final double twoHandSpeed;
        public final double twoHandDamage;
        public final double twoHandMoveSpeed;
        public final double reduction;
        public final double armorPenetration;
        public final double defenseCoefficient;
        public final double postureRegenRate;
        public final int postureBarLength;
        public final double attackRange;
        
        public final boolean mechanicTwoHanded;
        public final boolean mechanicBlocking;
        public final boolean mechanicParry;
        public final int parryWindow;
        public final int parryCooldown;

        public final List<org.bukkit.Sound> blockSounds;
        public final double blockPitchMin;
        public final double blockPitchMax;
        public final List<org.bukkit.Sound> parrySounds;
        public final double parryPitchMin;
        public final double parryPitchMax;
        public final org.bukkit.Particle blockParticle;
        public final int blockParticleCount;
        public final double blockParticleSpread;
        public final org.bukkit.Particle parryParticle;
        public final int parryParticleCount;
        public final double parryParticleSpread;
        public final List<ConfiguredPotionEffect> parryDebuffs;
        
        public final boolean comboEnabled;
        public final int comboResetTime;
        public final List<ComboStep> comboSteps;
        public final double slayerMultiplier;
        public final java.util.Set<org.bukkit.entity.EntityType> slayerTypes;
        public final java.util.Set<String> slayerGroups;
        public final String skill;
        public final int skillPoseModel;

        public WeaponData(int id, String name, org.bukkit.Material material, List<String> lore, double oneHandSpeed, double oneHandDamage, double oneHandMoveSpeed, double twoHandSpeed, double twoHandDamage, double twoHandMoveSpeed, double reduction, double armorPenetration, double defenseCoefficient, double postureRegenRate, int postureBarLength, double attackRange, boolean mechanicTwoHanded, boolean mechanicBlocking, boolean mechanicParry, int parryWindow, int parryCooldown, List<org.bukkit.Sound> blockSounds, double blockPitchMin, double blockPitchMax, List<org.bukkit.Sound> parrySounds, double parryPitchMin, double parryPitchMax, org.bukkit.Particle blockParticle, int blockParticleCount, double blockParticleSpread, org.bukkit.Particle parryParticle, int parryParticleCount, double parryParticleSpread, List<ConfiguredPotionEffect> parryDebuffs, boolean comboEnabled, int comboResetTime, List<ComboStep> comboSteps, double slayerMultiplier, java.util.Set<org.bukkit.entity.EntityType> slayerTypes, java.util.Set<String> slayerGroups, String skill, int skillPoseModel) {
            this.id = id;
            this.name = name;
            this.material = material;
            this.lore = lore;
            this.oneHandSpeed = oneHandSpeed;
            this.oneHandDamage = oneHandDamage;
            this.oneHandMoveSpeed = oneHandMoveSpeed;
            this.twoHandSpeed = twoHandSpeed;
            this.twoHandDamage = twoHandDamage;
            this.twoHandMoveSpeed = twoHandMoveSpeed;
            this.reduction = reduction;
            this.armorPenetration = armorPenetration;
            this.defenseCoefficient = defenseCoefficient;
            this.postureRegenRate = postureRegenRate;
            this.postureBarLength = postureBarLength;
            this.attackRange = attackRange;
            this.mechanicTwoHanded = mechanicTwoHanded;
            this.mechanicBlocking = mechanicBlocking;
            this.mechanicParry = mechanicParry;
            this.parryWindow = parryWindow;
            this.parryCooldown = parryCooldown;
            this.blockSounds = blockSounds;
            this.blockPitchMin = blockPitchMin;
            this.blockPitchMax = blockPitchMax;
            this.parrySounds = parrySounds;
            this.parryPitchMin = parryPitchMin;
            this.parryPitchMax = parryPitchMax;
            this.blockParticle = blockParticle;
            this.blockParticleCount = blockParticleCount;
            this.blockParticleSpread = blockParticleSpread;
            this.parryParticle = parryParticle;
            this.parryParticleCount = parryParticleCount;
            this.parryParticleSpread = parryParticleSpread;
            this.parryDebuffs = parryDebuffs;
            this.comboEnabled = comboEnabled;
            this.comboResetTime = comboResetTime;
            this.comboSteps = comboSteps;
            this.slayerMultiplier = slayerMultiplier;
            this.slayerTypes = slayerTypes;
            this.slayerGroups = slayerGroups;
            this.skill = skill;
            this.skillPoseModel = skillPoseModel;
        }
    }

    /**
     * 判断目标实体是否符合“屠戮”特攻条件
     * @param data 武器数据
     * @param type 目标实体类型
     * @return 是否触发特攻
     */
    public boolean matchesSlayerTarget(WeaponData data, org.bukkit.entity.EntityType type) {
        if (data == null || type == null) return false;
        // 1. 精确匹配实体类型
        if (data.slayerTypes != null && data.slayerTypes.contains(type)) return true;
        // 2. 匹配实体分组（如 UNDEAD）
        if (data.slayerGroups != null) {
            for (String g : data.slayerGroups) {
                if ("UNDEAD".equalsIgnoreCase(g)) {
                    if (GROUP_UNDEAD.contains(type)) return true;
                }
            }
        }
        return false;
    }
}
