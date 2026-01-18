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

public class HanaWeaponsCommand implements CommandExecutor, TabCompleter {

    private final HanaWeapons plugin;
    private final WeaponManager manager;

    public HanaWeaponsCommand(HanaWeapons plugin, WeaponManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "give":
                return handleGive(sender, args);
            case "reload":
                return handleReload(sender);
            default:
                sendUsage(sender);
                return true;
        }
    }

    private boolean handleGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hanaweapons.admin")) {
            sender.sendMessage("§cYou do not have permission.");
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage("§cUsage: /hw give <player> <model_data>");
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

        giveWeapon(target, data, modelData);
        sender.sendMessage("§aGave " + data.name + " to " + target.getName());
        return true;
    }

    private void giveWeapon(Player target, WeaponManager.WeaponData data, int modelData) {
        ItemStack item = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(data.name != null ? data.name : "Hana Greatsword");
            meta.setCustomModelData(modelData);
            item.setItemMeta(meta);
        }
        target.getInventory().addItem(item);
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("hanaweapons.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        manager.loadWeapons();
        sender.sendMessage("§aConfiguration reloaded.");
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("§eUsage: /hw give <player> <model_data> | /hw reload");
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
            return List.of("11451");
        }
        return new ArrayList<>();
    }
}
