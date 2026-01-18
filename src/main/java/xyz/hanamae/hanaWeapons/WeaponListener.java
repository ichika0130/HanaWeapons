package xyz.hanamae.hanaWeapons;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import xyz.hanamae.hanaWeapons.api.AbstractWeapon;

import java.util.Optional;

public class WeaponListener implements Listener {

    private final HanaWeapons plugin;
    private final WeaponRegistry registry;

    public WeaponListener(HanaWeapons plugin, WeaponRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    // ==========================================
    // Core Logic: Delegate to Weapon Implementation
    // ==========================================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        
        Optional<AbstractWeapon> weaponOpt = registry.findWeapon(mainHand);
        if (weaponOpt.isPresent()) {
            event.setCancelled(true); // 只要是自定义武器，就接管F键
            weaponOpt.get().onSwapHand(player, mainHand, player.getInventory().getItemInOffHand());
            
            // 延迟更新状态以确保同步
            scheduleUpdate(player);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        registry.findWeapon(item).ifPresent(weapon -> weapon.onInteract(event));
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player player) {
            ItemStack item = player.getInventory().getItemInMainHand();
            registry.findWeapon(item).ifPresent(weapon -> weapon.onDamage(event));
        }
    }

    // ==========================================
    // State Maintenance (Update Attributes, etc.)
    // ==========================================

    @EventHandler
    public void onHeldItemChange(PlayerItemHeldEvent event) {
        scheduleUpdate(event.getPlayer());
    }

    @EventHandler
    public void onDropItem(PlayerDropItemEvent event) {
        // 只有丢弃武器时才更新 (或者手里本来有武器丢了)
        // 简单起见，直接更新
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

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // 防止移动占位符
        if (event.getRawSlot() == 45 || event.getSlot() == 40) {
            if (isPlaceholder(event.getCurrentItem())) {
                event.setCancelled(true);
                return;
            }
        }

        scheduleUpdate(player);
    }

    // ==========================================
    // Helpers
    // ==========================================

    private void scheduleUpdate(Player player) {
        new BukkitRunnable() {
            @Override
            public void run() {
                ItemStack mainHand = player.getInventory().getItemInMainHand();
                registry.findWeapon(mainHand).ifPresent(weapon -> weapon.updateState(player, mainHand));
                
                // 如果手里拿的不是自定义武器，但副手有占位符，需要清除
                if (registry.findWeapon(mainHand).isEmpty()) {
                    ItemStack offHand = player.getInventory().getItemInOffHand();
                    if (isPlaceholder(offHand)) {
                        player.getInventory().setItemInOffHand(null);
                        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 1.0f);
                    }
                }
            }
        }.runTaskLater(plugin, 1L);
    }

    private boolean isPlaceholder(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getDisplayName() != null && item.getItemMeta().getDisplayName().contains("双手握持");
    }
}
