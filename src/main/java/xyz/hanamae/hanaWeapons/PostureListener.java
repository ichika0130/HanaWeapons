package xyz.hanamae.hanaWeapons;

import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.Vector;

public class PostureListener implements Listener {

    private final HanaWeapons plugin;
    private final PostureManager postureManager;
    private final WeaponManager weaponManager;

    public PostureListener(HanaWeapons plugin, PostureManager postureManager, WeaponManager weaponManager) {
        this.plugin = plugin;
        this.postureManager = postureManager;
        this.weaponManager = weaponManager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        // 如果受害者不是玩家，则跳过
        if (!(event.getEntity() instanceof Player victim)) return;

        // 计算攻击者的护甲穿透系数 (APC)
        double apc = calculateAttackerAPC(event);
        
        // 尝试触发招架 (即便玩家因为冷却或延迟在技术上未处于格挡状态)
        if (tryParry(event, victim)) {
            return; // 招架成功，直接返回
        }

        // 增强版格挡检查：检查是否正在格挡、是否举手，或是否存在近期交互记录以涵盖冷却间隙和视觉延迟
        boolean isBlocking = isValidBlock(victim, event.getDamager());

        if (isBlocking) {
            handleBlocking(event, victim, apc);
        } else {
            handleArmorPenetration(event, victim, apc);
        }
    }
    
    /**
     * 尝试判定招架逻辑
     */
    private boolean tryParry(EntityDamageByEntityEvent event, Player victim) {
        WeaponManager.WeaponData dataMain = weaponManager.getWeaponData(victim.getInventory().getItemInMainHand());
        WeaponManager.WeaponData dataOff = weaponManager.getWeaponData(victim.getInventory().getItemInOffHand());
        
        // 确定当前哪只手持有的武器具备招架机制
        WeaponManager.WeaponData activeParryWeapon = null;
        if (dataMain != null && dataMain.mechanicParry) activeParryWeapon = dataMain;
        else if (dataOff != null && dataOff.mechanicParry) activeParryWeapon = dataOff;
        
        if (activeParryWeapon != null) {
            // 朝向检查：确保玩家正对着攻击者
            if (event.getDamager() != null) {
                Vector victimLook = victim.getEyeLocation().getDirection().setY(0).normalize();
                Vector toAttacker = event.getDamager().getLocation().toVector().subtract(victim.getLocation().toVector()).setY(0).normalize();
                // 使用点积判断，如果角度大于 90 度（点积 <= 0），判定为未面对攻击者
                if (victimLook.dot(toAttacker) <= 0.0) return false;
            }

            // 计算从开始格挡到受到伤害的时间差
            long start = plugin.getBlockStartTime(victim);
            long diff = System.currentTimeMillis() - start;
            
            // 如果时间差在武器配置的招架判定帧内 (parryWindow)
            if (diff <= activeParryWeapon.parryWindow && diff >= 0) { 
                // 招架成功！
                event.setDamage(0); // 免疫本次伤害
                
                // 消耗对应武器的耐久度
                if (activeParryWeapon == dataMain) damageWeapon(victim, victim.getInventory().getItemInMainHand(), 1);
                else damageWeapon(victim, victim.getInventory().getItemInOffHand(), 1);
                
                // 招架音效处理
                Sound pSound = Sound.BLOCK_ANVIL_LAND;
                if (activeParryWeapon.parrySounds != null && !activeParryWeapon.parrySounds.isEmpty()) {
                    pSound = activeParryWeapon.parrySounds.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(activeParryWeapon.parrySounds.size()));
                }
                
                // 计算随机音调
                float pPitch = (float) (activeParryWeapon.parryPitchMin + (activeParryWeapon.parryPitchMax - activeParryWeapon.parryPitchMin) * java.util.concurrent.ThreadLocalRandom.current().nextDouble());
                
                // 粒子效果处理
                org.bukkit.Particle pParticle = activeParryWeapon.parryParticle;
                int pCount = activeParryWeapon.parryParticleCount;
                double pSpread = activeParryWeapon.parryParticleSpread;
                
                victim.getWorld().playSound(victim.getLocation(), pSound, 1.0f, pPitch);
                
                if (pParticle != null) {
                    victim.getWorld().spawnParticle(pParticle, victim.getEyeLocation().add(victim.getLocation().getDirection()), pCount, pSpread, pSpread, pSpread, 0.0);
                }
                
                // 给攻击者施加减益效果 (招架僵直/眩晕)
                if (event.getDamager() instanceof LivingEntity attacker) {
                    // 触发僵直 (3秒 = 60 ticks)
                    postureManager.triggerParryStun(attacker, 60);
                    attacker.getWorld().playSound(attacker.getLocation(), Sound.ITEM_SHIELD_BREAK, 1.0f, 0.5f);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * 计算攻击者的护甲穿透系数
     */
    private double calculateAttackerAPC(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof LivingEntity attacker) {
            double apc = weaponManager.getEntityArmorPenetration(attacker);
            
            // Add Skill Bonus if applicable
            if (attacker instanceof Player player && plugin.getSkillManager() != null) {
                apc += plugin.getSkillManager().getBonusArmorPenetration(player);
            }
            return apc;
        } else if (event.getDamager() instanceof Projectile proj) {
            return weaponManager.getProjectileArmorPenetration(proj.getType());
        }
        return 1.0;
    }

    /**
     * 验证是否为有效格挡
     */
    private boolean isValidBlock(Player victim, org.bukkit.entity.Entity attacker) {
        // 如果玩家正处于破防状态，无法格挡
        if (postureManager.isGuardBroken(victim)) return false;
        
        // 宽松的格挡判定：原版格挡状态 OR 举手状态 OR 插件记录的近期格挡动作
        boolean isBlockingState = victim.isBlocking() || victim.isHandRaised() || plugin.isBlockingOrRecentlyBlocking(victim);
        if (!isBlockingState) return false;
        
        if (attacker == null) return false;

        // 视距朝向检查
        Vector victimLook = victim.getEyeLocation().getDirection().setY(0).normalize();
        Vector toAttacker = attacker.getLocation().toVector().subtract(victim.getLocation().toVector()).setY(0).normalize();
        return victimLook.dot(toAttacker) > 0.0;
    }

    /**
     * 处理格挡逻辑（体力扣除、武器耐久、穿盾伤害）
     */
    private void handleBlocking(EntityDamageByEntityEvent event, Player victim, double apc) {
        WeaponManager.WeaponData dataMain = weaponManager.getWeaponData(victim.getInventory().getItemInMainHand());
        WeaponManager.WeaponData dataOff = weaponManager.getWeaponData(victim.getInventory().getItemInOffHand());
        
        // 获取主副手中防御系数的最大值
        double dcMain = (dataMain != null) ? dataMain.defenseCoefficient : 0.0;
        double dcOff = (dataOff != null) ? dataOff.defenseCoefficient : 0.0;
        double dc = Math.max(dcMain, dcOff);

        // 如果防御系数为0，视为未格挡，走护甲穿透逻辑
        if (dc <= 0) {
            handleArmorPenetration(event, victim, apc);
            return;
        }

        // 扣除玩家体力（当前伤害的2倍）
        postureManager.damageStamina(victim, event.getDamage() * 2.0);
        
        // 确定执行格挡的物品（优先选择防御系数高或主手的武器）
        org.bukkit.inventory.ItemStack blockingItem;
        WeaponManager.WeaponData activeWeapon;
        
        if (dcMain >= dcOff && dcMain > 0) {
            blockingItem = victim.getInventory().getItemInMainHand();
            activeWeapon = dataMain;
        } else {
            blockingItem = victim.getInventory().getItemInOffHand();
            activeWeapon = dataOff;
        }
        damageWeapon(victim, blockingItem, 1);
        
        // 初始化默认音效和粒子
        Sound blockSound = Sound.ITEM_SHIELD_BLOCK;
        org.bukkit.Particle blockParticle = org.bukkit.Particle.LAVA;
        int blockParticleCount = 10;
        double blockParticleSpread = 0.5;
        float pitchMin = 0.8f;
        float pitchMax = 1.2f;
        
        if (activeWeapon != null) {
            if (activeWeapon.blockSounds != null && !activeWeapon.blockSounds.isEmpty()) {
                blockSound = activeWeapon.blockSounds.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(activeWeapon.blockSounds.size()));
            }
            pitchMin = (float) activeWeapon.blockPitchMin;
            pitchMax = (float) activeWeapon.blockPitchMax;
            blockParticle = activeWeapon.blockParticle;
            blockParticleCount = activeWeapon.blockParticleCount;
            blockParticleSpread = activeWeapon.blockParticleSpread;
        }
        
        float finalPitch = pitchMin + (pitchMax - pitchMin) * (float) java.util.concurrent.ThreadLocalRandom.current().nextDouble();

        // 判定穿刺：如果攻击者的穿透系数 (APC) 大于武器的防御系数 (DC)
        if (apc > dc) {
            // 计算透盾后的真实伤害
            double trueDamage = event.getDamage() * (apc - dc + 1) * 0.1;
            applyTrueDamage(victim, trueDamage);
            event.setDamage(0); // 抵消原版伤害
            victim.getWorld().playSound(victim.getLocation(), blockSound, 0.5f, finalPitch * 1.5f); // 刺穿时音调更高
        } else {
            // 完美格挡：完全免伤
            event.setDamage(0);
            victim.getWorld().playSound(victim.getLocation(), blockSound, 1.0f, finalPitch);
        }
        
        // 粒子逻辑：仅当攻击者是“重型”生物时触发
        if (event.getDamager() instanceof LivingEntity attacker) {
            boolean hasHeavy = weaponManager.hasHeavyEffect(attacker.getType());
            if (hasHeavy && blockParticle != null) {
                victim.getWorld().spawnParticle(blockParticle, victim.getEyeLocation().add(victim.getLocation().getDirection()), blockParticleCount, blockParticleSpread, blockParticleSpread, blockParticleSpread, 0.0);
            }
        }
    }

    /**
     * 处理未被格挡时的护甲穿透逻辑
     */
    private void handleArmorPenetration(EntityDamageByEntityEvent event, Player victim, double apc) {
        if (apc <= 1.0) return;

        org.bukkit.attribute.AttributeInstance armorAttr = victim.getAttribute(org.bukkit.attribute.Attribute.ARMOR);
        double armor = (armorAttr != null) ? armorAttr.getValue() : 0.0;
        
        if (armor <= 0) return;

        // 根据 APC 和受害者当前的护甲值计算额外的真实伤害
        double extraDamage = event.getDamage() * (apc - 1.0) * (armor / 20.0) * 0.2;
        if (extraDamage > 0) {
            applyTrueDamage(victim, extraDamage);
        }
    }

    /**
     * 施加绕过护甲的真实伤害
     */
    private void applyTrueDamage(Player victim, double damage) {
        double newHealth = victim.getHealth() - damage;
        victim.setHealth(Math.max(0, newHealth));
        victim.playEffect(org.bukkit.EntityEffect.HURT); // 触发受伤红光效果
    }

    /**
     * 扣除武器耐久度，包含对耐久附魔的模拟处理
     */
    private void damageWeapon(Player player, org.bukkit.inventory.ItemStack item, int amount) {
        if (item == null || item.getType().getMaxDurability() <= 0) return;
        if (!(item.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damageable)) return;
        
        // 模拟耐久附魔 (Unbreaking)
        int level = item.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.UNBREAKING);
        if (level > 0 && java.util.concurrent.ThreadLocalRandom.current().nextInt(level + 1) > 0) {
            return;
        }

        int newDamage = damageable.getDamage() + amount;
        if (newDamage >= item.getType().getMaxDurability()) {
            item.setAmount(0); // 武器损毁
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
        } else {
            damageable.setDamage(newDamage);
            item.setItemMeta(damageable);
        }
    }
}