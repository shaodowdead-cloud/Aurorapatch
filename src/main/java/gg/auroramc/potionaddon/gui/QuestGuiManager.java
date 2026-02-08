package gg.auroramc.potionaddon.gui;

import gg.auroramc.potionaddon.PotionConsumeAddon;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class QuestGuiManager {
    public static final String MAIN_TITLE = ChatColor.DARK_AQUA + "AuroraQuests: Quests";
    public static final String QUEST_TITLE_PREFIX = ChatColor.DARK_GREEN + "Quest: ";
    public static final String DELETE_TITLE_PREFIX = ChatColor.DARK_RED + "Delete quest: ";

    private static final int QUESTS_PER_PAGE = 45;
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault());

    private final PotionConsumeAddon plugin;
    private final Path questsDirectory;
    private final Map<UUID, Integer> currentPage = new HashMap<>();
    private final Map<UUID, Map<Integer, Path>> mainMenuSlots = new HashMap<>();
    private final Map<UUID, Path> selectedQuest = new HashMap<>();
    private final Map<UUID, Path> editingQuest = new HashMap<>();
    private final Map<UUID, PendingChatInput> pendingChatInputs = new HashMap<>();

    public QuestGuiManager(PotionConsumeAddon plugin) {
        this.plugin = plugin;
        Plugin auroraQuests = Bukkit.getPluginManager().getPlugin("AuroraQuests");
        this.questsDirectory = auroraQuests != null
                ? auroraQuests.getDataFolder().toPath().resolve("quests")
                : plugin.getDataFolder().toPath().resolve("quests");
        ensureQuestsDirectory();
    }

    public Inventory createMainMenu(UUID playerId, int page) {
        List<Path> quests = listQuestFiles();
        int totalPages = Math.max(1, (int) Math.ceil(quests.size() / (double) QUESTS_PER_PAGE));
        int safePage = Math.min(Math.max(page, 0), totalPages - 1);
        currentPage.put(playerId, safePage);

        Inventory inventory = Bukkit.createInventory(null, 54, MAIN_TITLE);
        Map<Integer, Path> slotMap = new HashMap<>();

        int startIndex = safePage * QUESTS_PER_PAGE;
        int endIndex = Math.min(startIndex + QUESTS_PER_PAGE, quests.size());
        int slot = 0;
        for (int i = startIndex; i < endIndex; i++) {
            Path questFile = quests.get(i);
            ItemStack item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.GOLD + questFile.getFileName().toString());
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Size: " + readFileSize(questFile));
            lore.add(ChatColor.GRAY + "Modified: " + readLastModified(questFile));
            lore.add(ChatColor.YELLOW + "Click to manage");
            meta.setLore(lore);
            item.setItemMeta(meta);
            inventory.setItem(slot, item);
            slotMap.put(slot, questFile);
            slot++;
        }
        mainMenuSlots.put(playerId, slotMap);

        inventory.setItem(45, createMenuItem(Material.LIME_WOOL, ChatColor.GREEN + "Create quest",
                Collections.singletonList(ChatColor.GRAY + "Create a new quest file")));
        inventory.setItem(46, createMenuItem(Material.SUNFLOWER, ChatColor.YELLOW + "Reload list",
                Collections.singletonList(ChatColor.GRAY + "Refresh quest files")));

        boolean hasPrev = safePage > 0;
        boolean hasNext = safePage < totalPages - 1;

        inventory.setItem(52, createMenuItem(hasPrev ? Material.ARROW : Material.GRAY_DYE,
                ChatColor.AQUA + "Previous page",
                Collections.singletonList(ChatColor.GRAY + "Page " + (safePage + 1) + "/" + totalPages)));
        inventory.setItem(53, createMenuItem(hasNext ? Material.ARROW : Material.GRAY_DYE,
                ChatColor.AQUA + "Next page",
                Collections.singletonList(ChatColor.GRAY + "Page " + (safePage + 1) + "/" + totalPages)));

        return inventory;
    }

    public Inventory createQuestMenu(UUID playerId, Path questFile) {
        selectedQuest.put(playerId, questFile);
        Inventory inventory = Bukkit.createInventory(null, 27,
                QUEST_TITLE_PREFIX + ChatColor.WHITE + questFile.getFileName().toString());

        inventory.setItem(10, createMenuItem(Material.PAPER, ChatColor.AQUA + "Duplicate quest",
                Collections.singletonList(ChatColor.GRAY + "Copy to a new file")));
        inventory.setItem(11, createMenuItem(Material.WRITABLE_BOOK, ChatColor.GREEN + "Edit quest",
                Collections.singletonList(ChatColor.GRAY + "Open a book editor")));
        inventory.setItem(13, createMenuItem(Material.NAME_TAG, ChatColor.YELLOW + "Rename quest",
                Collections.singletonList(ChatColor.GRAY + "Change file name")));
        inventory.setItem(15, createMenuItem(Material.BARRIER, ChatColor.RED + "Delete quest",
                Collections.singletonList(ChatColor.GRAY + "Remove this quest file")));
        inventory.setItem(22, createMenuItem(Material.ARROW, ChatColor.GRAY + "Back",
                Collections.singletonList(ChatColor.GRAY + "Return to list")));

        return inventory;
    }

    public Inventory createDeleteConfirmMenu(Path questFile) {
        Inventory inventory = Bukkit.createInventory(null, 27,
                DELETE_TITLE_PREFIX + ChatColor.WHITE + questFile.getFileName().toString());
        inventory.setItem(11, createMenuItem(Material.RED_WOOL, ChatColor.RED + "Confirm delete",
                Collections.singletonList(ChatColor.GRAY + "This cannot be undone")));
        inventory.setItem(15, createMenuItem(Material.GREEN_WOOL, ChatColor.GREEN + "Cancel",
                Collections.singletonList(ChatColor.GRAY + "Keep the quest file")));
        return inventory;
    }

    public void openEditor(org.bukkit.entity.Player player, Path questFile) {
        String content = readQuestContent(questFile);
        List<String> pages = splitIntoPages(content, 240);
        ItemStack book = new ItemStack(Material.WRITABLE_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle("Quest Editor");
        meta.setAuthor(player.getName());
        meta.setPages(pages);
        book.setItemMeta(meta);
        editingQuest.put(player.getUniqueId(), questFile);
        player.openBook(book);
    }

    public void updateQuestFromBook(UUID playerId, BookMeta newMeta) throws IOException {
        Path questFile = editingQuest.remove(playerId);
        if (questFile == null) {
            return;
        }
        List<String> pages = newMeta.getPages();
        String content = String.join("\n", pages).stripTrailing() + "\n";
        Files.writeString(questFile, content, StandardCharsets.UTF_8);
    }

    public void beginChatInput(UUID playerId, ChatAction action, Path questFile) {
        pendingChatInputs.put(playerId, new PendingChatInput(action, questFile));
    }

    public PendingChatInput consumeChatInput(UUID playerId) {
        return pendingChatInputs.remove(playerId);
    }

    public Path getSelectedQuest(UUID playerId) {
        return selectedQuest.get(playerId);
    }

    public Map<Integer, Path> getMainMenuSlots(UUID playerId) {
        return mainMenuSlots.getOrDefault(playerId, Collections.emptyMap());
    }

    public int getCurrentPage(UUID playerId) {
        return currentPage.getOrDefault(playerId, 0);
    }

    public Path getQuestsDirectory() {
        return questsDirectory;
    }

    public PotionConsumeAddon getPlugin() {
        return plugin;
    }

    public void clearSelections(UUID playerId) {
        selectedQuest.remove(playerId);
    }

    private void ensureQuestsDirectory() {
        try {
            Files.createDirectories(questsDirectory);
        } catch (IOException e) {
            plugin.getLogger().warning("Unable to create quests directory: " + e.getMessage());
        }
    }

    private List<Path> listQuestFiles() {
        if (!Files.exists(questsDirectory)) {
            return Collections.emptyList();
        }
        try (Stream<Path> stream = Files.list(questsDirectory)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".yml")
                            || path.getFileName().toString().endsWith(".yaml"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ENGLISH)))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            plugin.getLogger().warning("Unable to list quest files: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private ItemStack createMenuItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private String readQuestContent(Path questFile) {
        try {
            if (Files.exists(questFile)) {
                return Files.readString(questFile, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Unable to read quest file: " + e.getMessage());
        }
        return "";
    }

    private List<String> splitIntoPages(String content, int maxLength) {
        if (content.isEmpty()) {
            return Collections.singletonList("");
        }
        List<String> pages = new ArrayList<>();
        String remaining = content;
        while (!remaining.isEmpty()) {
            int end = Math.min(maxLength, remaining.length());
            pages.add(remaining.substring(0, end));
            remaining = remaining.substring(end);
        }
        return pages;
    }

    private String readFileSize(Path questFile) {
        try {
            return Files.size(questFile) + " bytes";
        } catch (IOException e) {
            return "Unknown";
        }
    }

    private String readLastModified(Path questFile) {
        try {
            Instant modified = Files.getLastModifiedTime(questFile).toInstant();
            return DATE_FORMATTER.format(modified);
        } catch (IOException e) {
            return "Unknown";
        }
    }

    public static class PendingChatInput {
        private final ChatAction action;
        private final Path questFile;

        public PendingChatInput(ChatAction action, Path questFile) {
            this.action = action;
            this.questFile = questFile;
        }

        public ChatAction getAction() {
            return action;
        }

        public Path getQuestFile() {
            return questFile;
        }
    }

    public enum ChatAction {
        CREATE,
        RENAME,
        DUPLICATE
    }
}
