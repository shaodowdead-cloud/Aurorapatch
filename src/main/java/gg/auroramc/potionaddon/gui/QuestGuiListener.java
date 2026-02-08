package gg.auroramc.potionaddon.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class QuestGuiListener implements Listener {
    private final QuestGuiManager manager;

    public QuestGuiListener(QuestGuiManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        String title = event.getView().getTitle();
        if (!title.startsWith(QuestGuiManager.MAIN_TITLE)
                && !title.startsWith(QuestGuiManager.GUILD_TITLE_PREFIX)
                && !title.startsWith(QuestGuiManager.QUEST_TITLE_PREFIX)
                && !title.startsWith(QuestGuiManager.TASKS_TITLE_PREFIX)
                && !title.startsWith(QuestGuiManager.DELETE_TITLE_PREFIX)) {
            return;
        }

        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) {
            return;
        }

        UUID playerId = player.getUniqueId();
        if (title.startsWith(QuestGuiManager.MAIN_TITLE)) {
            handleGuildMenuClick(player, event.getSlot(), clicked);
            return;
        }
        if (title.startsWith(QuestGuiManager.GUILD_TITLE_PREFIX)) {
            handleGuildQuestMenuClick(player, event.getSlot(), clicked);
            return;
        }
        if (title.startsWith(QuestGuiManager.DELETE_TITLE_PREFIX)) {
            handleDeleteMenuClick(player, clicked);
            return;
        }
        if (title.startsWith(QuestGuiManager.TASKS_TITLE_PREFIX)) {
            handleTaskMenuClick(player, event.getSlot(), clicked);
            return;
        }
        handleQuestMenuClick(player, clicked);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        String title = event.getView().getTitle();
        if (title.startsWith(QuestGuiManager.DELETE_TITLE_PREFIX)) {
            manager.clearSelections(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerEditBook(PlayerEditBookEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        BookMeta newMeta = event.getNewBookMeta();
        try {
            manager.updateQuestFromBook(playerId, newMeta);
        } catch (IOException e) {
            event.getPlayer().sendMessage(ChatColor.RED + "Failed to save quest file: " + e.getMessage());
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        QuestGuiManager.PendingChatInput pending = manager.consumeChatInput(playerId);
        if (pending == null) {
            return;
        }
        event.setCancelled(true);

        String message = event.getMessage().trim();
        if (message.equalsIgnoreCase("cancel")) {
            Bukkit.getScheduler().runTask(manager.getPlugin(),
                    () -> event.getPlayer().sendMessage(ChatColor.YELLOW + "Action cancelled."));
            return;
        }

        Bukkit.getScheduler().runTask(manager.getPlugin(), () -> {
            try {
                switch (pending.getAction()) {
                    case CREATE -> handleCreate(event.getPlayer(), message);
                    case RENAME -> handleRename(event.getPlayer(), pending.getQuestFile(), message);
                    case DUPLICATE -> handleDuplicate(event.getPlayer(), pending.getQuestFile(), message);
                    default -> {
                    }
                }
            } catch (IOException e) {
                event.getPlayer().sendMessage(ChatColor.RED + "Failed to update quest file: " + e.getMessage());
            }
        });
    }

    private void handleGuildMenuClick(Player player, int slot, ItemStack clicked) {
        UUID playerId = player.getUniqueId();
        Map<Integer, String> slots = manager.getGuildMenuSlots(playerId);
        if (slots.containsKey(slot)) {
            String guild = slots.get(slot);
            player.openInventory(manager.createGuildQuestMenu(playerId, guild, 0));
            return;
        }

        if (slot == 46) {
            player.openInventory(manager.createMainMenu(playerId, manager.getCurrentGuildPage(playerId)));
            return;
        }
        if (slot == 52) {
            int page = manager.getCurrentGuildPage(playerId) - 1;
            player.openInventory(manager.createMainMenu(playerId, page));
            return;
        }
        if (slot == 53) {
            int page = manager.getCurrentGuildPage(playerId) + 1;
            player.openInventory(manager.createMainMenu(playerId, page));
        }
    }

    private void handleGuildQuestMenuClick(Player player, int slot, ItemStack clicked) {
        UUID playerId = player.getUniqueId();
        Map<Integer, Path> slots = manager.getGuildQuestMenuSlots(playerId);
        if (slots.containsKey(slot)) {
            Path questFile = slots.get(slot);
            Inventory questMenu = manager.createQuestMenu(playerId, questFile);
            player.openInventory(questMenu);
            return;
        }

        if (slot == 45) {
            player.closeInventory();
            manager.beginChatInput(playerId, QuestGuiManager.ChatAction.CREATE, null);
            player.sendMessage(ChatColor.GREEN + "Enter a file name for the new quest (or type 'cancel').");
            return;
        }
        if (slot == 46) {
            String guild = manager.getSelectedGuild(playerId);
            player.openInventory(manager.createGuildQuestMenu(playerId, guild, manager.getCurrentPage(playerId)));
            return;
        }
        if (slot == 48) {
            player.openInventory(manager.createMainMenu(playerId, manager.getCurrentGuildPage(playerId)));
            return;
        }
        if (slot == 52) {
            int page = manager.getCurrentPage(playerId) - 1;
            String guild = manager.getSelectedGuild(playerId);
            player.openInventory(manager.createGuildQuestMenu(playerId, guild, page));
            return;
        }
        if (slot == 53) {
            int page = manager.getCurrentPage(playerId) + 1;
            String guild = manager.getSelectedGuild(playerId);
            player.openInventory(manager.createGuildQuestMenu(playerId, guild, page));
        }
    }

    private void handleQuestMenuClick(Player player, ItemStack clicked) {
        String name = clicked.getItemMeta() != null ? clicked.getItemMeta().getDisplayName() : "";
        UUID playerId = player.getUniqueId();
        Path questFile = manager.getSelectedQuest(playerId);
        if (questFile == null) {
            player.closeInventory();
            return;
        }

        if (clicked.getType() == Material.WRITABLE_BOOK) {
            player.closeInventory();
            manager.openEditor(player, questFile);
            player.sendMessage(ChatColor.GREEN + "Editing quest: " + questFile.getFileName());
            return;
        }
        if (clicked.getType() == Material.NETHER_STAR) {
            player.openInventory(manager.createTaskMenu(playerId, questFile));
            return;
        }
        if (clicked.getType() == Material.NAME_TAG) {
            player.closeInventory();
            manager.beginChatInput(playerId, QuestGuiManager.ChatAction.RENAME, questFile);
            player.sendMessage(ChatColor.YELLOW + "Enter a new file name (or type 'cancel').");
            return;
        }
        if (clicked.getType() == Material.PAPER && ChatColor.stripColor(name).toLowerCase(Locale.ENGLISH).contains("duplicate")) {
            player.closeInventory();
            manager.beginChatInput(playerId, QuestGuiManager.ChatAction.DUPLICATE, questFile);
            player.sendMessage(ChatColor.YELLOW + "Enter a new file name for the copy (or type 'cancel').");
            return;
        }
        if (clicked.getType() == Material.BARRIER) {
            player.openInventory(manager.createDeleteConfirmMenu(questFile));
            return;
        }
        if (clicked.getType() == Material.ARROW) {
            String guild = manager.getGuildForQuest(questFile);
            player.openInventory(manager.createGuildQuestMenu(playerId, guild, manager.getCurrentPage(playerId)));
        }
    }

    private void handleDeleteMenuClick(Player player, ItemStack clicked) {
        Path questFile = manager.getSelectedQuest(player.getUniqueId());
        if (questFile == null) {
            player.closeInventory();
            return;
        }

        if (clicked.getType() == Material.RED_WOOL) {
            try {
                Files.deleteIfExists(questFile);
                player.sendMessage(ChatColor.RED + "Quest deleted: " + questFile.getFileName());
            } catch (IOException e) {
                player.sendMessage(ChatColor.RED + "Failed to delete quest: " + e.getMessage());
            }
            String guild = manager.getGuildForQuest(questFile);
            player.openInventory(manager.createGuildQuestMenu(player.getUniqueId(), guild, manager.getCurrentPage(player.getUniqueId())));
            return;
        }
        if (clicked.getType() == Material.GREEN_WOOL) {
            player.openInventory(manager.createQuestMenu(player.getUniqueId(), questFile));
        }
    }

    private void handleTaskMenuClick(Player player, int slot, ItemStack clicked) {
        UUID playerId = player.getUniqueId();
        Path questFile = manager.getSelectedQuest(playerId);
        if (questFile == null) {
            player.closeInventory();
            return;
        }

        if (slot == 45) {
            try {
                manager.addTask(questFile);
                player.openInventory(manager.createTaskMenu(playerId, questFile));
                player.sendMessage(ChatColor.GREEN + "Added a new task.");
            } catch (IOException e) {
                player.sendMessage(ChatColor.RED + "Failed to add task: " + e.getMessage());
            }
            return;
        }
        if (slot == 49) {
            player.openInventory(manager.createQuestMenu(playerId, questFile));
            return;
        }

        Map<Integer, String> slots = manager.getTaskMenuSlots(playerId);
        if (!slots.containsKey(slot)) {
            return;
        }
        String taskKey = slots.get(slot);
        QuestGuiManager.QuestDifficulty difficulty = manager.getTaskDifficulty(questFile, taskKey);
        QuestGuiManager.QuestDifficulty next = difficulty.next();
        try {
            manager.updateTaskDifficulty(questFile, taskKey, next);
            player.openInventory(manager.createTaskMenu(playerId, questFile));
            player.sendMessage(ChatColor.YELLOW + "Task " + taskKey + " difficulty set to " + next.getDisplayName() + ChatColor.YELLOW + ".");
        } catch (IOException e) {
            player.sendMessage(ChatColor.RED + "Failed to update task difficulty: " + e.getMessage());
        }
    }

    private void handleCreate(Player player, String fileName) throws IOException {
        Path questsDir = manager.getQuestsDirectory();
        String guild = manager.getSelectedGuild(player.getUniqueId());
        Path targetDir = guild == null || guild.equals("Без гильдии")
                ? questsDir
                : questsDir.resolve(guild);
        Path newFile = resolveQuestFile(targetDir, fileName);
        if (Files.exists(newFile)) {
            player.sendMessage(ChatColor.RED + "That quest file already exists.");
            manager.beginChatInput(player.getUniqueId(), QuestGuiManager.ChatAction.CREATE, null);
            player.sendMessage(ChatColor.YELLOW + "Enter a different file name (or type 'cancel').");
            return;
        }
        Files.createDirectories(targetDir);
        Files.writeString(newFile, defaultTemplate(), StandardCharsets.UTF_8);
        player.sendMessage(ChatColor.GREEN + "Quest created: " + newFile.getFileName());
        manager.openEditor(player, newFile);
    }

    private void handleRename(Player player, Path questFile, String newName) throws IOException {
        Path questsDir = manager.getQuestsDirectory();
        Path newFile = resolveQuestFile(questsDir, newName);
        if (Files.exists(newFile)) {
            player.sendMessage(ChatColor.RED + "That quest file already exists.");
            manager.beginChatInput(player.getUniqueId(), QuestGuiManager.ChatAction.RENAME, questFile);
            player.sendMessage(ChatColor.YELLOW + "Enter a different file name (or type 'cancel').");
            return;
        }
        Files.move(questFile, newFile);
        player.sendMessage(ChatColor.GREEN + "Quest renamed to: " + newFile.getFileName());
        player.openInventory(manager.createQuestMenu(player.getUniqueId(), newFile));
    }

    private void handleDuplicate(Player player, Path questFile, String newName) throws IOException {
        Path questsDir = manager.getQuestsDirectory();
        Path newFile = resolveQuestFile(questsDir, newName);
        if (Files.exists(newFile)) {
            player.sendMessage(ChatColor.RED + "That quest file already exists.");
            manager.beginChatInput(player.getUniqueId(), QuestGuiManager.ChatAction.DUPLICATE, questFile);
            player.sendMessage(ChatColor.YELLOW + "Enter a different file name (or type 'cancel').");
            return;
        }
        Files.copy(questFile, newFile);
        player.sendMessage(ChatColor.GREEN + "Quest duplicated: " + newFile.getFileName());
        player.openInventory(manager.createQuestMenu(player.getUniqueId(), newFile));
    }

    private Path resolveQuestFile(Path questsDir, String fileName) {
        String sanitized = fileName.trim();
        if (sanitized.isEmpty()) {
            sanitized = "new-quest.yml";
        }
        if (!sanitized.endsWith(".yml") && !sanitized.endsWith(".yaml")) {
            sanitized = sanitized + ".yml";
        }
        sanitized = sanitized.replaceAll("[^a-zA-Z0-9._-]", "_");
        return questsDir.resolve(sanitized);
    }

    private String defaultTemplate() {
        return "tasks:\n"
                + "  example_task:\n"
                + "    task: CONSUME\n"
                + "    args:\n"
                + "      amount: 1\n"
                + "      types:\n"
                + "        - \"strength\"\n";
    }
}
