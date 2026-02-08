package gg.auroramc.potionaddon.gui;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class QuestGuiCommand implements CommandExecutor {
    private final QuestGuiManager manager;

    public QuestGuiCommand(QuestGuiManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used in-game.");
            return true;
        }
        if (!player.hasPermission("auroraquests.gui")) {
            player.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }
        player.openInventory(manager.createMainMenu(player.getUniqueId(), 0));
        return true;
    }
}
