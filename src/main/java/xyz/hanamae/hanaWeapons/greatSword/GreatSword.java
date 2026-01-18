package xyz.hanamae.hanaWeapons.greatSword;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;
import xyz.hanamae.hanaWeapons.HanaWeapons;
import xyz.hanamae.hanaWeapons.WeaponManager;
import xyz.hanamae.hanaWeapons.api.AbstractWeapon;

import java.util.Map;

public class GreatSword extends AbstractWeapon {

    private static final double FACING_THRESHOLD = 0.5;

    public GreatSword(HanaWeapons plugin, WeaponManager manager) {
        super(plugin, manager);
    }

    @Override
    public boolean matches(ItemStack item) {
        // 使用配置里的 11451 作为判断依据
        // 实际上这里应该更灵活，比如从 manager 获取所有大剑的 ID
        return item != null && item.hasItemMeta() && item.getItemMeta().hasCustomModelData() && item.getItemMeta().getCustomModelData() == 11451;
    }

    @Override
    public void onSwapHand(Player player, ItemStack mainHand, ItemStack offHand) {
        boolean isPlaceholder = isPlaceholder(offHand);

        // 如果副手被其他物品占用
        if (!isPlaceholder && offHand != null && offHand.getType() != Material.AIR) {
            sendActionBar(player, "§c副手被占用，无法双手握持");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 1.0f);
            return;
        }

        boolean toTwoHanded = !isPlaceholder;

        if (toTwoHanded) {
            // 切换到双手：生成占位符
            ItemStack placeholder = createPlaceholderItem(mainHand);
            player.getInventory().setItemInOffHand(placeholder);

            sendActionBar(player, "§b§l[ 双手握持 ]");
            player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_IRON, 1f, 0.8f);
            
            // 应用双手属性
            applyAttributes(player, mainHand, true);
        } else {
            // 切换回单手：移除占位符
            player.getInventory().setItemInOffHand(null);

            sendActionBar(player, "§7[ 单手握持 ]");
            player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 1.0f);
            
            // 应用单手属性
            applyAttributes(player, mainHand, false);
        }
    }

    @Override
    public void updateState(Player player, ItemStack item) {
        ItemStack offHand = player.getInventory().getItemInOffHand();
        boolean isPlaceholder = isPlaceholder(offHand);

        if (isPlaceholder) {
            // 双手模式：确保属性正确
            applyAttributes(player, item, true);
        } else {
            // 单手模式：确保属性正确
            applyAttributes(player, item, false);
        }
    }

    @Override
    public void onInteract(PlayerInteractEvent event) {
        // 大剑逻辑：只有双手握持时才能格挡
        // 这里主要用于未来的扩展，目前格挡由 vanilla 机制接管
    }

    @Override
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        // 必须双手持剑且正在格挡
        if (!matches(item) || !isPlaceholder(player.getInventory().getItemInOffHand())) return;
        if (!player.isHandRaised()) return;

        if (!(event.getDamager() instanceof LivingEntity attacker)) return;

        // 1. 方向检测
        Vector playerLook = player.getEyeLocation().getDirection().normalize();
        Vector toAttacker = attacker.getEyeLocation().toVector().subtract(player.getEyeLocation().toVector()).normalize();
        double dot = playerLook.dot(toAttacker);

        if (dot < FACING_THRESHOLD) return;

        // 2. 播放格挡音效和特效
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.8f, 1.5f);
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.0f);

        // 岩浆喷溅特效 (少量小颗粒)
        player.getWorld().spawnParticle(org.bukkit.Particle.LAVA, player.getEyeLocation().add(playerLook.multiply(0.5)), 3, 0.1, 0.1, 0.1, 0.05);
    }
    
    // ==========================================
    // Internal Logic
    // ==========================================

    private void applyAttributes(Player player, ItemStack item, boolean twoHanded) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        WeaponManager.WeaponData data = manager.getWeaponData(meta.getCustomModelData());
        if (data == null) return;

        double targetSpeed = twoHanded ? data.twoHandSpeed : data.oneHandSpeed;
        double targetDamage = twoHanded ? data.twoHandDamage : data.oneHandDamage;

        setWeaponAttributes(meta, targetSpeed, targetDamage);

        ItemStack finalItem;
        if (twoHanded) {
            finalItem = createTwoHandedWeapon(item, meta, data.reduction);
        } else {
            finalItem = createOneHandedWeapon(item, meta);
        }

        int slot = player.getInventory().getHeldItemSlot();
        player.getInventory().setItem(slot, finalItem);
        player.updateInventory();
    }
    
    private ItemStack createTwoHandedWeapon(ItemStack baseItem, ItemMeta baseMeta, double reduction) {
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
            baseItem.setItemMeta(baseMeta);
            return baseItem;
        }
    }

    private ItemStack createOneHandedWeapon(ItemStack baseItem, ItemMeta baseMeta) {
        ItemStack cleanItem = new ItemStack(baseItem.getType());
        ItemMeta cleanMeta = cleanItem.getItemMeta();

        copyItemMeta(baseMeta, cleanMeta);
        cleanMeta.setAttributeModifiers(baseMeta.getAttributeModifiers());

        // Force remove NBT
        ItemStack stripped = forceRemoveBlockingComponents(cleanItem);
        // Re-get meta in case modifyItemStack changed it
        ItemMeta strippedMeta = stripped.getItemMeta();
        copyItemMeta(baseMeta, strippedMeta); // copy again just in case
        strippedMeta.setAttributeModifiers(baseMeta.getAttributeModifiers());
        
        stripped.setItemMeta(strippedMeta);
        return stripped;
    }

    private boolean isPlaceholder(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getDisplayName() != null && item.getItemMeta().getDisplayName().contains("双手握持");
    }

    private void sendActionBar(Player player, String message) {
        player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, new net.md_5.bungee.api.chat.TextComponent(message));
    }
}
