package xyz.hanamae.hanaWeapons;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import xyz.hanamae.hanaWeapons.WeaponManager.WeaponData;

import java.util.Map;
import java.util.UUID;

import org.bukkit.event.block.Action;
import org.bukkit.util.RayTraceResult;

public class WeaponListener implements Listener {

    private final HanaWeapons plugin;
    private final WeaponManager weaponManager;

    public WeaponListener(HanaWeapons plugin, WeaponManager weaponManager) {
        this.plugin = plugin;
        this.weaponManager = weaponManager;
    }

    /**
     * 处理 F 键副手切换事件
     * 用于在单手/双手模式之间切换
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        
        // Prevent swapping if offhand is placeholder, UNLESS mainhand is the 2H weapon (Toggle logic)
        WeaponData data = weaponManager.getWeaponData(mainHand);
        
        // 如果主手没有识别出数据，可能是因为连招改变了 ModelData
        // 尝试从 NBT 强制读取（虽然 getWeaponData 已经做了，但为了保险起见，我们信任 getWeaponData 的 NBT 逻辑）
        // 如果 data 还是 null，那说明这把剑彻底坏了或者不是我们的剑
        
        if (isPlaceholder(offHand)) {
            // Check if mainhand is valid 2H weapon
            // 如果主手是空的或者是其他物品，说明出现了“孤儿占位符”的情况
            if (data != null && data.mechanicTwoHanded) {
                // Let the logic below handle it (it will cancel the event and toggle)
            } else {
                // Orphaned placeholder or invalid state -> remove it
                event.setCancelled(true);
                player.getInventory().setItemInOffHand(null);
                player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 1.0f);
                return;
            }
        }

        if (data != null && data.mechanicTwoHanded) {
            event.setCancelled(true);
            
            boolean isPlaceholder = isPlaceholder(offHand);
            
            if (!isPlaceholder && offHand != null && offHand.getType() != Material.AIR) {
                sendActionBar(player, "§c副手被占用，无法双手握持");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 1.0f);
                return;
            }

            boolean toTwoHanded = !isPlaceholder;
            
            // 如果要切换回单手，且当前处于连招姿态（ModelData 不等于原始 ID），是否要重置模型？
            // 最好重置一下，避免带着举剑姿态回到单手模式
            if (!toTwoHanded) {
                ItemMeta meta = mainHand.getItemMeta();
                if (meta.hasCustomModelData() && meta.getCustomModelData() != data.id) {
                    meta.setCustomModelData(data.id);
                    mainHand.setItemMeta(meta);
                }
            }

            if (toTwoHanded) {
                // Switch to 2H
                ItemStack placeholder = createPlaceholderItem(mainHand);
                player.getInventory().setItemInOffHand(placeholder);
                sendActionBar(player, "§b§l[ 双手握持 ]");
                player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_IRON, 1f, 0.8f);
                updateItemState(player, mainHand, data, true);
            } else {
                // Switch to 1H
                player.getInventory().setItemInOffHand(null);
                sendActionBar(player, "§7[ 单手握持 ]");
                player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 1.0f);
                updateItemState(player, mainHand, data, false);
            }
            scheduleUpdate(player);
        }
    }

    /**
     * 处理玩家交互事件 (右键防御/弹反, 左键连招)
     */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        
        // Check Main Hand
        WeaponData data = weaponManager.getWeaponData(mainHand);
        
        // Check Off Hand if Main Hand is not valid
        if (data == null) {
             data = weaponManager.getWeaponData(offHand);
        }

        // ================== 左键连招逻辑 (空挥/点击方块) ==================
        if (event.getAction().name().contains("LEFT_CLICK")) {
            // [New] Extended Reach Logic
            if (event.getAction() == Action.LEFT_CLICK_AIR) {
                WeaponData mainHandData = weaponManager.getWeaponData(player.getInventory().getItemInMainHand());
                if (mainHandData != null && mainHandData.attackRange > 3.0) {
                     RayTraceResult result = player.getWorld().rayTraceEntities(
                        player.getEyeLocation(), 
                        player.getEyeLocation().getDirection(), 
                        mainHandData.attackRange, 
                        0.5, 
                        entity -> entity != player && entity instanceof org.bukkit.entity.LivingEntity
                    );
                    
                    if (result != null && result.getHitEntity() != null) {
                        player.attack(result.getHitEntity());
                    }
                }
            }

            // Only if we have a valid weapon with combo enabled
            if (data != null && data.comboEnabled) {
                // 如果是双手武器，必须处于双手模式才能连招
                if (data.mechanicTwoHanded) {
                    boolean isTwoHandedMode = isPlaceholder(player.getInventory().getItemInOffHand());
                    if (!isTwoHandedMode) {
                        return; // Ignore
                    }
                }
                
                // 触发连招逻辑 (推进 Stage, 切换模型)
                // 具体的伤害倍率在 EntityDamageByEntityEvent 中应用
                // 防抖逻辑 (Debounce) 在 ComboManager 中处理，避免与 EntityDamageEvent 重复触发
                plugin.getComboManager().handleAttack(player, mainHand, data);
            }
            return; // Don't process Right Click logic below
        }

        // ================== 右键防御/弹反逻辑 ==================
        if (event.getAction().name().contains("RIGHT")) {
             
             // 如果是双手武器，且处于单手模式（副手不是占位符），则直接跳过后续逻辑
             // 这样可以避免在单手挥舞大剑时触发弹反冷却或架势条 UI
             if (data != null && data.mechanicTwoHanded) {
                 // Check if we are in Two-Handed mode
                 boolean isTwoHandedMode = false;
                 // 检查主手是否是该武器，并且副手是占位符
                 if (weaponManager.getWeaponData(mainHand) == data) {
                     if (isPlaceholder(offHand)) isTwoHandedMode = true;
                 }
                 // 检查副手是否是该武器 (通常双手武器只能放主手生效，但为了严谨)
                 // 双手武器放在副手通常无法进入双持模式（逻辑上只有主手触发F键切换）
                 
                 if (!isTwoHandedMode) {
                     // 单手模式：不做任何处理，直接返回
                     return;
                 }
             }
             
             // 检查是否处于连招硬直/窗口期 (禁止防御)
             if (data != null && data.comboEnabled) {
                 if (mainHand.hasItemMeta() && mainHand.getItemMeta().hasCustomModelData()) {
                     int currentModel = mainHand.getItemMeta().getCustomModelData();
                     // 如果当前模型 ID 不等于基础 ID，说明正处于连招姿态中
                     if (currentModel != data.id) {
                         sendActionBar(player, "§c[ 动作未完成 ]");
                         event.setCancelled(true);
                         return;
                     }
                 }
             }
             
             if (data != null && data.mechanicParry) {
                 if (plugin.isOnParryCooldown(player)) {
                     // 冷却中：强制取消交互
                     event.setCancelled(true);
                     return;
                 }
                 // 触发弹反：设置冷却
                 plugin.setParryCooldown(player, data.parryCooldown);
                 
                 // 添加原版物品冷却效果
                 // Determine which hand holds the weapon to apply cooldown correctly
                 Material cooldownMat = Material.AIR;
                 if (weaponManager.getWeaponData(mainHand) == data) cooldownMat = mainHand.getType();
                 else if (weaponManager.getWeaponData(offHand) == data) cooldownMat = offHand.getType();
                 
                 if (cooldownMat != Material.AIR) {
                     player.setCooldown(cooldownMat, data.parryCooldown / 50);
                 }
                 
                 // 播放技能释放音效 (横扫)
                 player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.2f);
                 
                 // 播放横扫特效
                 player.getWorld().spawnParticle(org.bukkit.Particle.SWEEP_ATTACK, player.getEyeLocation().add(player.getLocation().getDirection().multiply(0.5)), 1);
             }
             
             plugin.updateBlockingStatus(player);
             
             if (!plugin.isOnParryCooldown(player)) {
                 plugin.updateBlockStartTime(player);
             }
        }
    }

    @EventHandler
    public void onHeldItemChange(PlayerItemHeldEvent event) { scheduleUpdate(event.getPlayer()); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) { 
        if (isPlaceholder(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            event.getItemDrop().remove();
        }
        scheduleUpdate(event.getPlayer()); 
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (isPlaceholder(event.getItem().getItemStack())) {
                event.setCancelled(true);
                event.getItem().remove();
            } else {
                scheduleUpdate(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        
        // Check current item (if moving placeholder)
        if (isPlaceholder(event.getCurrentItem())) {
            event.setCancelled(true);
            event.setCurrentItem(null); // Force remove
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.5f, 2.0f);
            return;
        }
        
        // Check cursor item (if holding placeholder)
        if (isPlaceholder(event.getCursor())) {
            event.setCancelled(true);
            event.setCursor(null); // Force remove
            return;
        }
        
        // Check offhand slot (40 in inventory, 45 in open container?)
        // Slot 40 is usually offhand in PlayerInventory
        if (event.getSlot() == 40 && isPlaceholder(event.getCurrentItem())) {
             event.setCancelled(true);
             event.setCurrentItem(null);
             return;
        }

        scheduleUpdate(player);
    }
    
    /**
     * 处理攻击伤害事件
     * 应用连招伤害加成、亡灵特攻等，以及限制短武器的攻击距离
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAttack(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            ItemStack mainHand = player.getInventory().getItemInMainHand();
            
            // Debug Log
            // plugin.getLogger().info("Player " + player.getName() + " attacked with " + mainHand.getType());
            
            WeaponData data = weaponManager.getWeaponData(mainHand);
            if (data == null) {
                // plugin.getLogger().info("No WeaponData found for this item.");
                return;
            }
            
            // 检查攻击距离限制 (针对短武器)
            // 如果配置距离小于 3.0，则进行距离判定
            if (data.attackRange < 3.0 && data.attackRange > 0) {
                double distance = player.getEyeLocation().distance(event.getEntity().getLocation());
                // 这里加 1.5 是为了宽容度 (因为 distance 是到实体脚底，且考虑到延迟)
                // 也可以用更精确的 RayTrace 反向验证，但简单距离判定通常足够且性能好
                if (distance > data.attackRange + 1.5) {
                    event.setCancelled(true);
                    return;
                }
            }
            
            // plugin.getLogger().info("Weapon: " + data.name + " (ID: " + data.id + "), ComboEnabled: " + data.comboEnabled);
            
            if (data.comboEnabled) {
                // IMPORTANT FIX: Only allow combo if the weapon is in Two-Handed Mode
                // If it's a 2H weapon but currently in 1H mode, do NOT trigger combo
                if (data.mechanicTwoHanded) {
                    boolean isTwoHandedMode = isPlaceholder(player.getInventory().getItemInOffHand());
                    if (!isTwoHandedMode) {
                        plugin.getLogger().info("Combo ignored: Weapon is 2H but player is in 1H mode.");
                        
                        // Safety: If model is stuck in pose (ID != data.id), reset it immediately
                        if (mainHand.hasItemMeta() && mainHand.getItemMeta().hasCustomModelData()) {
                            if (mainHand.getItemMeta().getCustomModelData() != data.id) {
                                ItemMeta meta = mainHand.getItemMeta();
                                meta.setCustomModelData(data.id);
                                mainHand.setItemMeta(meta);
                                plugin.getLogger().info("Reset stuck model to default.");
                            }
                        }
                        return;
                    }
                }
                
                // Apply Damage Multiplier
                double mult = plugin.getComboManager().getDamageMultiplier(player, data);
                plugin.getLogger().info("Applying Combo Mult: " + mult);
                
                if (mult != 1.0) {
                    event.setDamage(event.getDamage() * mult);
                }

                // 亡灵/特攻加成逻辑
                if (event.getEntity() instanceof org.bukkit.entity.LivingEntity) {
                    org.bukkit.entity.EntityType targetType = event.getEntity().getType();
                    if (data.slayerMultiplier > 1.0 && weaponManager.matchesSlayerTarget(data, targetType)) {
                        event.setDamage(event.getDamage() * data.slayerMultiplier);
                    }
                }
                
                // Trigger Combo Logic (Advance Stage, Change Model)
                // 注意：这里的 handleAttack 和 onInteract 中的 handleAttack 会被防抖逻辑(debounce)过滤
                // 确保一次挥剑只触发一次连招推进
                plugin.getComboManager().handleAttack(player, mainHand, data);
            }
        }
    }

    // ================== Helpers ==================

    private void scheduleUpdate(Player player) {
        new BukkitRunnable() {
            @Override
            public void run() {
                ItemStack mainHand = player.getInventory().getItemInMainHand();
                WeaponData data = weaponManager.getWeaponData(mainHand);
                
                if (data != null) {
                    boolean isTwoHandedState = false;
                    if (data.mechanicTwoHanded) {
                        isTwoHandedState = isPlaceholder(player.getInventory().getItemInOffHand());
                    }
                    updateItemState(player, mainHand, data, isTwoHandedState);
                } else {
                    ItemStack offHand = player.getInventory().getItemInOffHand();
                    if (isPlaceholder(offHand)) {
                        player.getInventory().setItemInOffHand(null);
                        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 1.0f);
                    }
                }
            }
        }.runTaskLater(plugin, 1L);
    }

    private void updateItemState(Player player, ItemStack item, WeaponData data, boolean isTwoHandedState) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        
        // Critical Fix: If we are in 1H mode, FORCE reset model to default ID
        // This handles the edge case where player switched items during a combo pose
        if (!isTwoHandedState && data.mechanicTwoHanded) {
             if (meta.hasCustomModelData() && meta.getCustomModelData() != data.id) {
                 meta.setCustomModelData(data.id);
                 // We don't need to save back to item yet, setWeaponAttributes will use this meta object
                 plugin.getLogger().info("Auto-corrected 1H model for " + player.getName());
             }
        }

        double speed = isTwoHandedState ? data.twoHandSpeed : data.oneHandSpeed;
        double damage = isTwoHandedState ? data.twoHandDamage : data.oneHandDamage;
        double moveSpeed = isTwoHandedState ? data.twoHandMoveSpeed : data.oneHandMoveSpeed;

        setWeaponAttributes(meta, speed, damage, moveSpeed);
        
        // Write NBT ID
        meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "hana_weapon_id"), org.bukkit.persistence.PersistentDataType.INTEGER, data.id);
        
        boolean shouldBlock = false;
        if (data.mechanicBlocking) {
            if (data.mechanicTwoHanded) {
                // 如果是双手武器，只有在双手握持状态下才能防御
                shouldBlock = isTwoHandedState;
            } else {
                // 单手武器且mechanicBlocking=true，则始终可以防御（例如盾牌）
                shouldBlock = true;
            }
        }
        
        // Safety check: if data says twoHanded=true but we are in 1H mode, FORCE mechanicBlocking off for this item state
        if (data.mechanicTwoHanded && !isTwoHandedState) {
            shouldBlock = false;
        }

        ItemStack finalItem;
        if (shouldBlock) {
            finalItem = createBlockingWeapon(item, meta, data.reduction);
        } else {
            finalItem = createStandardWeapon(item, meta);
        }

        player.getInventory().setItemInMainHand(finalItem);
    }

    private ItemStack createBlockingWeapon(ItemStack baseItem, ItemMeta baseMeta, double reduction) {
        try {
            String materialName = baseItem.getType().getKey().toString();
            String componentsNbt = String.format(
                "%s[consumable={consume_seconds:72000,animation:'block',has_consume_particles:false,can_always_use:true},blocks_attacks={damage_reductions:[{base:0.0f,factor:%.2ff}]}]",
                materialName, reduction
            );
            ItemStack tempItem = Bukkit.getUnsafe().modifyItemStack(new ItemStack(baseItem.getType()), componentsNbt);
            ItemMeta tempMeta = tempItem.getItemMeta();
            copyItemMeta(baseMeta, tempMeta);
            tempMeta.setAttributeModifiers(baseMeta.getAttributeModifiers());
            tempItem.setItemMeta(tempMeta);
            return tempItem;
        } catch (Exception e) {
            return createStandardWeapon(baseItem, baseMeta);
        }
    }

    private ItemStack createStandardWeapon(ItemStack baseItem, ItemMeta baseMeta) {
        ItemStack cleanItem = new ItemStack(baseItem.getType());
        ItemMeta cleanMeta = cleanItem.getItemMeta();
        copyItemMeta(baseMeta, cleanMeta);
        cleanMeta.setAttributeModifiers(baseMeta.getAttributeModifiers());
        cleanItem.setItemMeta(cleanMeta);
        return cleanItem;
    }

    private void setWeaponAttributes(ItemMeta meta, double speed, double damage, double moveSpeed) {
        UUID speedUuid = UUID.fromString("FA233E1C-4180-4865-B01B-BCCE9785ACA3");
        UUID damageUuid = UUID.fromString("CB3F55D3-645C-4F38-A497-9C13A33DB5CF");
        UUID moveSpeedUuid = UUID.fromString("662A6B8D-DA3E-4C1C-8813-96EA6097278D");

        meta.removeAttributeModifier(Attribute.ATTACK_SPEED);
        meta.removeAttributeModifier(Attribute.ATTACK_DAMAGE);
        meta.removeAttributeModifier(Attribute.MOVEMENT_SPEED);

        meta.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(speedUuid, "generic.attack_speed", speed,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(damageUuid, "generic.attack_damage", damage - 1.0,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        
        if (moveSpeed != 0.0) {
            meta.addAttributeModifier(Attribute.MOVEMENT_SPEED, new AttributeModifier(moveSpeedUuid, "generic.movement_speed", moveSpeed,
                    AttributeModifier.Operation.ADD_SCALAR, EquipmentSlotGroup.MAINHAND));
        }
    }

    private void copyItemMeta(ItemMeta source, ItemMeta target) {
        if (source.hasDisplayName()) target.setDisplayName(source.getDisplayName());
        if (source.hasLore()) target.setLore(source.getLore());
        if (source.hasCustomModelData()) target.setCustomModelData(source.getCustomModelData());
        if (source instanceof org.bukkit.inventory.meta.Damageable s && target instanceof org.bukkit.inventory.meta.Damageable t) {
            if (s.hasDamage()) t.setDamage(s.getDamage());
        }
        if (source.hasEnchants()) {
            for (Map.Entry<org.bukkit.enchantments.Enchantment, Integer> entry : source.getEnchants().entrySet()) {
                target.addEnchant(entry.getKey(), entry.getValue(), true);
            }
        }
    }

    private ItemStack createPlaceholderItem(ItemStack mainHand) {
        ItemStack placeholder = new ItemStack(mainHand.getType());
        ItemMeta meta = placeholder.getItemMeta();
        if (meta != null) {
            if (mainHand.hasItemMeta()) copyItemMeta(mainHand.getItemMeta(), meta);
            meta.setDisplayName("§7[ 双手握持 ]");
            
            // 尝试恢复原始模型 ID (从 NBT 读取)
            // 这样即使主手处于连招姿态 (如 1145701)，副手依然显示为原始模型 (11457)
            // 保持视觉上的稳定性
            ItemMeta mainMeta = mainHand.getItemMeta();
            if (mainMeta.getPersistentDataContainer().has(new org.bukkit.NamespacedKey(plugin, "hana_weapon_id"), org.bukkit.persistence.PersistentDataType.INTEGER)) {
                int baseId = mainMeta.getPersistentDataContainer().get(new org.bukkit.NamespacedKey(plugin, "hana_weapon_id"), org.bukkit.persistence.PersistentDataType.INTEGER);
                meta.setCustomModelData(baseId);
            }
            
            meta.removeAttributeModifier(Attribute.ATTACK_SPEED);
            meta.removeAttributeModifier(Attribute.ATTACK_DAMAGE);
            meta.removeAttributeModifier(Attribute.MOVEMENT_SPEED);
            placeholder.setItemMeta(meta);
        }
        return placeholder;
    }

    private boolean isPlaceholder(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getDisplayName() != null && item.getItemMeta().getDisplayName().contains("双手握持");
    }

    private void sendActionBar(Player player, String message) {
        player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, new net.md_5.bungee.api.chat.TextComponent(message));
    }
}
