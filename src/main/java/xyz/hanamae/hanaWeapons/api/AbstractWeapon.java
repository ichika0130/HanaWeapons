package xyz.hanamae.hanaWeapons.api;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import xyz.hanamae.hanaWeapons.HanaWeapons;
import xyz.hanamae.hanaWeapons.WeaponManager;

import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.Map;
import java.util.UUID;

public abstract class AbstractWeapon {

    protected final HanaWeapons plugin;
    protected final WeaponManager manager;

    public AbstractWeapon(HanaWeapons plugin, WeaponManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    /**
     * 判断物品是否属于该武器类型
     */
    public abstract boolean matches(ItemStack item);

    /**
     * 处理左右手切换事件 (F键)
     */
    public abstract void onSwapHand(Player player, ItemStack mainHand, ItemStack offHand);
    
    /**
     * 状态更新 (用于捡起物品、切换栏位等被动触发的情况)
     */
    public abstract void updateState(Player player, ItemStack item);

    /**
     * 处理交互事件 (右键)
     */
    public void onInteract(PlayerInteractEvent event) {}

    /**
     * 处理受伤/格挡事件
     */
    public void onDamage(EntityDamageByEntityEvent event) {}

    // ==========================================
    // Shared Helper Methods (Copied from WeaponListener)
    // ==========================================

    protected void setWeaponAttributes(ItemMeta meta, double speed, double damage) {
        UUID speedUuid = UUID.fromString("FA233E1C-4180-4865-B01B-BCCE9785ACA3");
        UUID damageUuid = UUID.fromString("CB3F55D3-645C-4F38-A497-9C13A33DB5CF");

        meta.removeAttributeModifier(Attribute.ATTACK_SPEED);
        meta.removeAttributeModifier(Attribute.ATTACK_DAMAGE);

        meta.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(speedUuid, "generic.attack_speed", speed,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(damageUuid, "generic.attack_damage", damage - 1.0,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
    }

    protected void copyItemMeta(ItemMeta source, ItemMeta target) {
        if (source.hasDisplayName()) target.setDisplayName(source.getDisplayName());
        if (source.hasLore()) target.setLore(source.getLore());
        if (source.hasCustomModelData()) target.setCustomModelData(source.getCustomModelData());
        if (source.hasEnchants()) {
            for (Map.Entry<org.bukkit.enchantments.Enchantment, Integer> entry : source.getEnchants().entrySet()) {
                target.addEnchant(entry.getKey(), entry.getValue(), true);
            }
        }
    }

    protected ItemStack createPlaceholderItem(ItemStack mainHand) {
        ItemStack placeholder = mainHand.clone();
        ItemMeta meta = placeholder.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§7[ 双手握持 ]");
            meta.removeAttributeModifier(Attribute.ATTACK_SPEED);
            meta.removeAttributeModifier(Attribute.ATTACK_DAMAGE);
            placeholder.setItemMeta(meta);

            // Clean NBT
            forceRemoveBlockingComponents(placeholder);
        }
        return placeholder;
    }

    protected ItemStack forceRemoveBlockingComponents(ItemStack item) {
        try {
            String removeNbt = "{components:{\"!minecraft:consumable\":{},\"!minecraft:blocks_attacks\":{}}}";
            return Bukkit.getUnsafe().modifyItemStack(item, removeNbt);
        } catch (Exception ignored) {
            return item;
        }
    }
}
