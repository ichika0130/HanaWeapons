package xyz.hanamae.hanaWeapons;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
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
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class WeaponListener implements Listener {

    private final HanaWeapons plugin;
    private final WeaponManager manager;
    private final NamespacedKey speedKey;
    private final NamespacedKey damageKey;
    
    // Parry System
    private final Map<UUID, Long> blockStartTimes = new HashMap<>();
    private static final long PARRY_WINDOW_MS = 500;
    private static final double FACING_THRESHOLD = 0.5;

    public WeaponListener(HanaWeapons plugin, WeaponManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.speedKey = new NamespacedKey(plugin, "attack_speed");
        this.damageKey = new NamespacedKey(plugin, "attack_damage");
    }

    // ==========================================
    // Core Logic: Update Player Weapon State
    // ==========================================
    
    private void updateWeaponState(Player player) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();

        boolean isWeapon = isGreatSword(mainHand);
        boolean isPlaceholder = isPlaceholder(offHand);

        if (isWeapon) {
            if (isPlaceholder) {
                // 双手模式：启用格挡
                applyWeaponAttributes(player, mainHand, true);
            } else if (offHand != null && offHand.getType() != Material.AIR) {
                applyWeaponAttributes(player, mainHand, false);
            } else {
                applyWeaponAttributes(player, mainHand, false);
            }
        } else {
            if (isPlaceholder) {
                player.getInventory().setItemInOffHand(null);
                sendActionBar(player, "§7[ 姿态解除 ]");
                player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 1.0f);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void applyWeaponAttributes(Player player, ItemStack item, boolean twoHanded) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        WeaponManager.WeaponData data = manager.getWeaponData(meta.getCustomModelData());
        if (data == null) return;

        // Debug info
        // plugin.getLogger().info("Applying attributes: " + (twoHanded ? "Two-Handed" : "One-Handed") + " Damage: " + (twoHanded ? data.twoHandDamage : data.oneHandDamage));

        // 回归基础：仅使用 Bukkit API 设置属性，暂时不注入 NBT 组件
        // 这样可以确保 Attack Speed 和 Damage 绝对生效
        
        double targetSpeed = twoHanded ? data.twoHandSpeed : data.oneHandSpeed;
        double targetDamage = twoHanded ? data.twoHandDamage : data.oneHandDamage;
        
        // 1. 设置属性 AttributeModifiers
        // 使用固定 UUID 以避免堆叠问题，并确保正确覆盖
        UUID speedUuid = UUID.fromString("FA233E1C-4180-4865-B01B-BCCE9785ACA3");
        UUID damageUuid = UUID.fromString("CB3F55D3-645C-4F38-A497-9C13A33DB5CF");

        meta.removeAttributeModifier(Attribute.ATTACK_SPEED);
        meta.removeAttributeModifier(Attribute.ATTACK_DAMAGE);

        meta.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(speedUuid, "generic.attack_speed", targetSpeed,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(damageUuid, "generic.attack_damage", targetDamage - 1.0,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));

        // 2. 应用 Meta
        // 关键修复：采用反向覆盖法
        // 如果是双手模式，我们需要注入格挡组件。但 setItemMeta 会清洗组件。
        // 所以我们先生成一把带组件的白板剑，然后把上面的属性复制过去。
        
        if (twoHanded) {
            try {
                // A. 构造只包含格挡组件的 NBT
                String materialName = item.getType().getKey().toString();
                String componentsNbt = String.format(
                    "%s[consumable={consume_seconds:72000,animation:'block',has_consume_particles:false,can_always_use:true},blocks_attacks={damage_reductions:[{base:0.0f,factor:%.2ff}]}]",
                    materialName, data.reduction
                );
                
                // B. 生成带组件的临时物品
                ItemStack tempItem = Bukkit.getUnsafe().modifyItemStack(new ItemStack(item.getType()), componentsNbt);
                ItemMeta tempMeta = tempItem.getItemMeta(); // 这个 Meta 内部持有了 Hidden Components
                
                // C. 搬运属性到 Temp Meta
                // Display Name & Lore
                if (meta.hasDisplayName()) tempMeta.setDisplayName(meta.getDisplayName());
                if (meta.hasLore()) tempMeta.setLore(meta.getLore());
                
                // Custom Model Data
                if (meta.hasCustomModelData()) tempMeta.setCustomModelData(meta.getCustomModelData());
                
                // Attributes (直接复制我们刚才设置好的)
                tempMeta.setAttributeModifiers(meta.getAttributeModifiers());
                
                // Enchants
                if (meta.hasEnchants()) {
                    for (java.util.Map.Entry<org.bukkit.enchantments.Enchantment, Integer> entry : meta.getEnchants().entrySet()) {
                        tempMeta.addEnchant(entry.getKey(), entry.getValue(), true);
                    }
                }
                
                // D. 应用并替换
                tempItem.setItemMeta(tempMeta);
                
                // 替换背包里的物品
                int slot = player.getInventory().getHeldItemSlot();
                player.getInventory().setItem(slot, tempItem);
                
                // 既然替换了物品，就不需要最后那个 item.setItemMeta(meta) 了，也不需要 updateInventory 了（因为 setItem 会触发）
                // 但为了保险，还是 update 一下
                player.updateInventory();
                return;
                
            } catch (Exception e) {
                // 如果失败，回退到普通应用
                plugin.getLogger().warning("Failed to inject blocking components: " + e.getMessage());
            }
        }
        
        // 单手模式或注入失败，正常应用
        // 关键修复：采用“物品重建策略”，彻底清除可能残留的 Hidden Components
        if (!twoHanded) {
            // 1. 创建全新的物品，绝对不含任何隐藏组件
            ItemStack cleanItem = new ItemStack(item.getType());
            ItemMeta cleanMeta = cleanItem.getItemMeta();
            
            // 2. 搬运关键数据
            if (meta.hasDisplayName()) cleanMeta.setDisplayName(meta.getDisplayName());
            if (meta.hasLore()) cleanMeta.setLore(meta.getLore());
            if (meta.hasCustomModelData()) cleanMeta.setCustomModelData(meta.getCustomModelData());
            if (meta.hasEnchants()) {
                for (Map.Entry<org.bukkit.enchantments.Enchantment, Integer> entry : meta.getEnchants().entrySet()) {
                    cleanMeta.addEnchant(entry.getKey(), entry.getValue(), true);
                }
            }
            
            // 3. 搬运属性 (Attributes)
            // meta 里的属性已经是我们计算好并添加进去的，直接复制即可
            cleanMeta.setAttributeModifiers(meta.getAttributeModifiers());
            
            // 4. 应用 Meta 到新物品 (必须在 setItem 之前！)
            cleanItem.setItemMeta(cleanMeta);
            
            // 5. 替换背包里的物品
            int slot = player.getInventory().getHeldItemSlot();
            player.getInventory().setItem(slot, cleanItem);
            player.updateInventory();
            return;
        }
        
        // 理论上不会走到这里，因为上面涵盖了 true 和 false
        item.setItemMeta(meta);
        player.updateInventory();
        
        // 3. 暂时移除 modifyItemStack 注入逻辑
        // 等待属性功能稳定后，再通过更安全的方式（如 NMS 或 Paper API）加回格挡组件
        
        player.updateInventory();
    }

    // ==========================================
    // Guard Logic (Visuals & Audio)
    // ==========================================

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        // 只有双手握持大剑时才能格挡 (副手是占位符)
        if ((event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            if (isGreatSword(item) && isPlaceholder(player.getInventory().getItemInOffHand())) {
            }
        }
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        // 必须双手持剑且正在格挡 (isHandRaised)
        if (!isGreatSword(item) || !isPlaceholder(player.getInventory().getItemInOffHand())) return;
        if (!player.isHandRaised()) return;

        if (!(event.getDamager() instanceof LivingEntity attacker)) return;
        
        // 1. 方向检测 (必须面向攻击者)
        Vector playerLook = player.getEyeLocation().getDirection().normalize();
        Vector toAttacker = attacker.getEyeLocation().toVector().subtract(player.getEyeLocation().toVector()).normalize();
        double dot = playerLook.dot(toAttacker);

        if (dot < FACING_THRESHOLD) return;

        // 2. 播放格挡音效和特效 (无论何时被击中，只要在格挡)
        // 打铁音效
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.8f, 1.5f);
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.0f);
        
        // 岩浆喷溅特效 (少量小颗粒)
        // 在玩家面前生成一圈岩浆粒子
        player.getWorld().spawnParticle(org.bukkit.Particle.LAVA, player.getEyeLocation().add(playerLook.multiply(0.5)), 3, 0.1, 0.1, 0.1, 0.05);

        // 原生 blocks_attacks 组件会自动处理减伤 (根据 NBT 中的 factor)
        // 如果需要手动调整，可以在这里 event.setDamage(event.getDamage() * factor);
    }

    // ==========================================
    // Standard Event Handlers
    // ==========================================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        ItemStack mainHand = player.getInventory().getItemInMainHand();

        if (!isGreatSword(mainHand)) return;

        ItemStack offHand = player.getInventory().getItemInOffHand();
        boolean isPlaceholder = isPlaceholder(offHand);

        if (!isPlaceholder && offHand != null && offHand.getType() != Material.AIR) {
            event.setCancelled(true);
            sendActionBar(player, "§c副手被占用，无法双手握持");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 1.0f);
            return;
        }

        event.setCancelled(true);

        boolean toTwoHanded = !isPlaceholder;
        
        if (toTwoHanded) {
            ItemStack placeholder = mainHand.clone();
            ItemMeta meta = placeholder.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§7[ 双手握持 ]");
                // 移除所有属性，避免干扰
                meta.removeAttributeModifier(Attribute.ATTACK_SPEED);
                meta.removeAttributeModifier(Attribute.ATTACK_DAMAGE);
                placeholder.setItemMeta(meta);
                
                // 关键：移除副手的格挡组件 (如果主手有，克隆过来也会有，必须清除)
                // 确保副手只是个装饰，不会触发格挡动作
                try {
                    String removeNbt = "{components:{\"!minecraft:consumable\":{},\"!minecraft:blocks_attacks\":{}}}";
                    placeholder = Bukkit.getUnsafe().modifyItemStack(placeholder, removeNbt);
                } catch (Exception ignored) {}
            }
            player.getInventory().setItemInOffHand(placeholder);
            
            sendActionBar(player, "§b§l[ 双手握持 ]");
            player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_IRON, 1f, 0.8f);
        } else {
            player.getInventory().setItemInOffHand(null);
            
            sendActionBar(player, "§7[ 单手握持 ]");
            player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 1.0f);
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                updateWeaponState(player);
            }
        }.runTaskLater(plugin, 1L);
    }

    @EventHandler
    public void onHeldItemChange(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        new BukkitRunnable() {
            @Override
            public void run() {
                updateWeaponState(player);
            }
        }.runTaskLater(plugin, 1L);
    }

    @EventHandler
    public void onDropItem(PlayerDropItemEvent event) {
        if (isGreatSword(event.getItemDrop().getItemStack())) {
            Player player = event.getPlayer();
            new BukkitRunnable() {
                @Override
                public void run() {
                    updateWeaponState(player);
                }
            }.runTaskLater(plugin, 1L);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        if (event.getRawSlot() == 45 || event.getSlot() == 40) {
            if (isPlaceholder(event.getCurrentItem())) {
                event.setCancelled(true);
                return;
            }
        }

        int slot = event.getSlot();
        int heldSlot = player.getInventory().getHeldItemSlot();
        boolean relevant = (slot == heldSlot) || (slot == 40) || isGreatSword(event.getCurrentItem()) || isGreatSword(event.getCursor());

        if (relevant) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    updateWeaponState(player);
                }
            }.runTaskLater(plugin, 1L);
        }
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (isPlaceholder(event.getItem().getItemStack())) {
                event.setCancelled(true);
                event.getItem().remove();
            } else if (isGreatSword(event.getItem().getItemStack())) {
                 new BukkitRunnable() {
                    @Override
                    public void run() {
                        updateWeaponState(player);
                    }
                }.runTaskLater(plugin, 1L);
            }
        }
    }

    // ==========================================
    // Helpers
    // ==========================================

    @SuppressWarnings("deprecation")
    private boolean isGreatSword(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().hasCustomModelData() && item.getItemMeta().getCustomModelData() == 11451;
    }

    private boolean isPlaceholder(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getDisplayName() != null && item.getItemMeta().getDisplayName().contains("双手握持");
    }

    private void sendActionBar(Player player, String message) {
        player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, new net.md_5.bungee.api.chat.TextComponent(message));
    }
}
