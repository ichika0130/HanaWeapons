package xyz.hanamae.hanaWeapons;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class HanaWeaponsCommand implements CommandExecutor, TabCompleter {

    private final HanaWeapons plugin;
    private final WeaponManager manager;

    public HanaWeaponsCommand(HanaWeapons plugin, WeaponManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // /hw give <player> <model_data>
        if (args.length >= 3 && args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("hanaweapons.admin")) {
                sender.sendMessage("§cYou do not have permission.");
                return true;
            }

            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage("§cPlayer not found.");
                return true;
            }

            int modelData;
            try {
                modelData = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid model data ID.");
                return true;
            }

            WeaponManager.WeaponData data = manager.getWeaponData(modelData);
            if (data == null) {
                sender.sendMessage("§cWeapon not found with ID: " + modelData);
                return true;
            }

            // Create weapon
            ItemStack item = new ItemStack(Material.NETHERITE_SWORD); // 默认材质
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(data.name != null ? data.name : "Hana Greatsword");
                meta.setCustomModelData(modelData);
                // Lore 可以在这里添加，如果 data 里有的话
                // meta.setLore(data.lore);
                item.setItemMeta(meta);
            }

            target.getInventory().addItem(item);
            sender.sendMessage("§aGave " + data.name + " to " + target.getName());
            return true;
        }
        
        // Reload command
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("hanaweapons.admin")) {
                sender.sendMessage("§cNo permission.");
                return true;
            }
            manager.loadWeapons();
            sender.sendMessage("§aConfiguration reloaded.");
            return true;
        }

        sender.sendMessage("§eUsage: /hw give <player> <model_data> | /hw reload");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("give", "reload");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return null; // Player list
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            // Suggest known model data IDs
            // 这里可以改进为从 manager 获取所有 ID
            return List.of("11451"); 
        }
        return new ArrayList<>();
    }
}
