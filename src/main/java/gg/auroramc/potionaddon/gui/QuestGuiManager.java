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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

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
    public static final String MAIN_TITLE = ChatColor.DARK_AQUA + "AuroraQuests: Guilds";
    public static final String GUILD_TITLE_PREFIX = ChatColor.DARK_AQUA + "Guild: ";
    public static final String QUEST_TITLE_PREFIX = ChatColor.DARK_GREEN + "Quest: ";
    public static final String TASKS_TITLE_PREFIX = ChatColor.DARK_PURPLE + "Tasks: ";
    public static final String DELETE_TITLE_PREFIX = ChatColor.DARK_RED + "Delete quest: ";

    private static final int QUESTS_PER_PAGE = 45;
    private static final int GUILDS_PER_PAGE = 45;
    private static final int TASKS_PER_PAGE = 45;
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault());

    private final PotionConsumeAddon plugin;
    private final Path questsDirectory;
    private final Map<UUID, Integer> currentGuildPage = new HashMap<>();
    private final Map<UUID, Integer> currentQuestPage = new HashMap<>();
    private final Map<UUID, Map<Integer, String>> guildMenuSlots = new HashMap<>();
    private final Map<UUID, Map<Integer, Path>> guildQuestMenuSlots = new HashMap<>();
    private final Map<UUID, Path> selectedQuest = new HashMap<>();
    private final Map<UUID, String> selectedGuild = new HashMap<>();
    private final Map<UUID, Map<Integer, String>> taskMenuSlots = new HashMap<>();
    private final Map<UUID, Path> editingQuest = new HashMap<>();
    private final Map<UUID, PendingChatInput> pendingChatInputs = new HashMap<>();

    public QuestGuiManager(PotionConsumeAddon plugin) {
        this.plugin = plugin;
        Plugin auroraQuests = Bukkit.getPluginManager().getPlugin("AuroraQuests");
        this.questsDirectory = auroraQuests != null
                ? auroraQuests.getDataFolder().toPath().resolve("quest_pools")
                : plugin.getDataFolder().toPath().resolve("quest_pools");
        ensureQuestsDirectory();
    }

    public Inventory createMainMenu(UUID playerId, int page) {
        Map<String, List<Path>> grouped = groupQuestsByGuild();
        List<String> guilds = new ArrayList<>(grouped.keySet());
        Collections.sort(guilds, String.CASE_INSENSITIVE_ORDER);
        int totalPages = Math.max(1, (int) Math.ceil(guilds.size() / (double) GUILDS_PER_PAGE));
        int safePage = Math.min(Math.max(page, 0), totalPages - 1);
        currentGuildPage.put(playerId, safePage);

        Inventory inventory = Bukkit.createInventory(null, 54, MAIN_TITLE);
        Map<Integer, String> slotMap = new HashMap<>();

        int startIndex = safePage * GUILDS_PER_PAGE;
        int endIndex = Math.min(startIndex + GUILDS_PER_PAGE, guilds.size());
        int slot = 0;
        for (int i = startIndex; i < endIndex; i++) {
            String guild = guilds.get(i);
            ItemStack item = new ItemStack(Material.BOOKSHELF);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.GOLD + guild);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Quests: " + grouped.getOrDefault(guild, Collections.emptyList()).size());
            lore.add(ChatColor.YELLOW + "Click to open");
            meta.setLore(lore);
            item.setItemMeta(meta);
            inventory.setItem(slot, item);
            slotMap.put(slot, guild);
            slot++;
        }
        guildMenuSlots.put(playerId, slotMap);

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

    public Inventory createGuildQuestMenu(UUID playerId, String guild, int page) {
        selectedGuild.put(playerId, guild);
        List<Path> quests = groupQuestsByGuild().getOrDefault(guild, Collections.emptyList());
        int totalPages = Math.max(1, (int) Math.ceil(quests.size() / (double) QUESTS_PER_PAGE));
        int safePage = Math.min(Math.max(page, 0), totalPages - 1);
        currentQuestPage.put(playerId, safePage);

        Inventory inventory = Bukkit.createInventory(null, 54, GUILD_TITLE_PREFIX + ChatColor.WHITE + guild);
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
        guildQuestMenuSlots.put(playerId, slotMap);

        inventory.setItem(45, createMenuItem(Material.LIME_WOOL, ChatColor.GREEN + "Create quest",
                Collections.singletonList(ChatColor.GRAY + "Create a new quest in this guild")));
        inventory.setItem(46, createMenuItem(Material.SUNFLOWER, ChatColor.YELLOW + "Reload list",
                Collections.singletonList(ChatColor.GRAY + "Refresh quest files")));
        inventory.setItem(48, createMenuItem(Material.ARROW, ChatColor.GRAY + "Back to guilds",
                Collections.singletonList(ChatColor.GRAY + "Return to guild list")));

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
        inventory.setItem(14, createMenuItem(Material.NETHER_STAR, ChatColor.LIGHT_PURPLE + "Quest tasks",
                Collections.singletonList(ChatColor.GRAY + "Manage tasks & difficulty")));
        inventory.setItem(15, createMenuItem(Material.BARRIER, ChatColor.RED + "Delete quest",
                Collections.singletonList(ChatColor.GRAY + "Remove this quest file")));
        inventory.setItem(22, createMenuItem(Material.ARROW, ChatColor.GRAY + "Back",
                Collections.singletonList(ChatColor.GRAY + "Return to list")));

        return inventory;
    }

    public Inventory createTaskMenu(UUID playerId, Path questFile) {
        selectedQuest.put(playerId, questFile);
        List<String> taskKeys = getTaskKeys(questFile);
        int totalPages = Math.max(1, (int) Math.ceil(taskKeys.size() / (double) TASKS_PER_PAGE));
        int safePage = 0;
        Inventory inventory = Bukkit.createInventory(null, 54,
                TASKS_TITLE_PREFIX + ChatColor.WHITE + questFile.getFileName().toString());

        Map<Integer, String> slotMap = new HashMap<>();
        int startIndex = safePage * TASKS_PER_PAGE;
        int endIndex = Math.min(startIndex + TASKS_PER_PAGE, taskKeys.size());
        int slot = 0;
        for (int i = startIndex; i < endIndex; i++) {
            String taskKey = taskKeys.get(i);
            int taskNumber = i + 1;
            QuestDifficulty difficulty = getTaskDifficulty(questFile, taskKey);
            ItemStack item = new ItemStack(difficulty.getMaterial(), Math.min(64, taskNumber));
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.AQUA + "Task #" + taskNumber + ChatColor.GRAY + " (" + taskKey + ")");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Difficulty: " + difficulty.getDisplayName());
            lore.add(ChatColor.GRAY + "Task number: " + taskNumber);
            lore.add(ChatColor.YELLOW + "Click to change difficulty");
            meta.setLore(lore);
            item.setItemMeta(meta);
            inventory.setItem(slot, item);
            slotMap.put(slot, taskKey);
            slot++;
        }
        taskMenuSlots.put(playerId, slotMap);

        inventory.setItem(45, createMenuItem(Material.LIME_WOOL, ChatColor.GREEN + "Add task",
                Collections.singletonList(ChatColor.GRAY + "Create a new task with easy difficulty")));
        inventory.setItem(49, createMenuItem(Material.ARROW, ChatColor.GRAY + "Back",
                Collections.singletonList(ChatColor.GRAY + "Return to quest menu")));

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
        pendingChatInputs.put(playerId, new PendingChatInput(action, questFile, selectedGuild.get(playerId)));
    }

    public PendingChatInput consumeChatInput(UUID playerId) {
        return pendingChatInputs.remove(playerId);
    }

    public Path getSelectedQuest(UUID playerId) {
        return selectedQuest.get(playerId);
    }

    public Map<Integer, String> getGuildMenuSlots(UUID playerId) {
        return guildMenuSlots.getOrDefault(playerId, Collections.emptyMap());
    }

    public Map<Integer, Path> getGuildQuestMenuSlots(UUID playerId) {
        return guildQuestMenuSlots.getOrDefault(playerId, Collections.emptyMap());
    }

    public Map<Integer, String> getTaskMenuSlots(UUID playerId) {
        return taskMenuSlots.getOrDefault(playerId, Collections.emptyMap());
    }

    public int getCurrentPage(UUID playerId) {
        return currentQuestPage.getOrDefault(playerId, 0);
    }

    public int getCurrentGuildPage(UUID playerId) {
        return currentGuildPage.getOrDefault(playerId, 0);
    }

    public String getSelectedGuild(UUID playerId) {
        return selectedGuild.get(playerId);
    }

    public Path getQuestsDirectory() {
        return questsDirectory;
    }

    public PotionConsumeAddon getPlugin() {
        return plugin;
    }

    public void clearSelections(UUID playerId) {
        selectedQuest.remove(playerId);
        selectedGuild.remove(playerId);
        taskMenuSlots.remove(playerId);
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
        try (Stream<Path> stream = Files.walk(questsDirectory, 3)) {
            return stream
                    .filter(path -> !Files.isDirectory(path))
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
        private final String guildName;

        public PendingChatInput(ChatAction action, Path questFile, String guildName) {
            this.action = action;
            this.questFile = questFile;
            this.guildName = guildName;
        }

        public ChatAction getAction() {
            return action;
        }

        public Path getQuestFile() {
            return questFile;
        }

        public String getGuildName() {
            return guildName;
        }
    }

    public enum ChatAction {
        CREATE,
        RENAME,
        DUPLICATE
    }

    public enum QuestDifficulty {
        EASY(ChatColor.GREEN + "Easy", Material.GREEN_WOOL),
        MEDIUM(ChatColor.YELLOW + "Medium", Material.YELLOW_WOOL),
        HARD(ChatColor.RED + "Hard", Material.RED_WOOL);

        private final String displayName;
        private final Material material;

        QuestDifficulty(String displayName, Material material) {
            this.displayName = displayName;
            this.material = material;
        }

        public String getDisplayName() {
            return displayName;
        }

        public Material getMaterial() {
            return material;
        }

        public QuestDifficulty next() {
            return switch (this) {
                case EASY -> MEDIUM;
                case MEDIUM -> HARD;
                case HARD -> EASY;
            };
        }
    }

    private Map<String, List<Path>> groupQuestsByGuild() {
        List<Path> quests = listQuestFiles();
        Map<String, List<Path>> grouped = new HashMap<>();
        for (Path quest : quests) {
            String guild = resolveGuildName(quest);
            grouped.computeIfAbsent(guild, key -> new ArrayList<>()).add(quest);
        }
        return grouped;
    }

    private String resolveGuildName(Path questFile) {
        Path relative = questsDirectory.relativize(questFile);
        if (relative.getNameCount() == 1) {
            return "Без гильдии";
        }
        if (relative.getName(0).toString().equalsIgnoreCase("quest")) {
            return "Без гильдии";
        }
        if (relative.getNameCount() > 2 && relative.getName(1).toString().equalsIgnoreCase("quest")) {
            return relative.getName(0).toString();
        }
        if (relative.getNameCount() > 1) {
            return relative.getName(0).toString();
        }
        return "Без гильдии";
    }

    public Path resolveQuestDirectory(String guild) {
        Path baseDir = questsDirectory;
        if (guild != null && !"Без гильдии".equals(guild)) {
            baseDir = questsDirectory.resolve(guild);
        }
        Path questDir = baseDir.resolve("quest");
        if (Files.isDirectory(questDir)) {
            return questDir;
        }
        return baseDir;
    }

    public String getGuildForQuest(Path questFile) {
        return resolveGuildName(questFile);
    }

    private List<String> getTaskKeys(Path questFile) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(questFile.toFile());
        ConfigurationSection tasks = config.getConfigurationSection("tasks");
        if (tasks == null) {
            return Collections.emptyList();
        }
        List<String> keys = new ArrayList<>(tasks.getKeys(false));
        keys.sort(String.CASE_INSENSITIVE_ORDER);
        return keys;
    }

    public QuestDifficulty getTaskDifficulty(Path questFile, String taskKey) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(questFile.toFile());
        String value = config.getString("tasks." + taskKey + ".difficulty", QuestDifficulty.EASY.name());
        try {
            return QuestDifficulty.valueOf(value.toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException e) {
            return QuestDifficulty.EASY;
        }
    }

    public void updateTaskDifficulty(Path questFile, String taskKey, QuestDifficulty difficulty) throws IOException {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(questFile.toFile());
        config.set("tasks." + taskKey + ".difficulty", difficulty.name());
        config.save(questFile.toFile());
    }

    public String addTask(Path questFile) throws IOException {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(questFile.toFile());
        ConfigurationSection tasks = config.getConfigurationSection("tasks");
        if (tasks == null) {
            tasks = config.createSection("tasks");
        }
        int index = 1;
        String key = "task_" + index;
        while (tasks.contains(key)) {
            index++;
            key = "task_" + index;
        }
        ConfigurationSection newTask = tasks.createSection(key);
        newTask.set("task", "CONSUME");
        newTask.set("args.amount", 1);
        newTask.set("args.types", Collections.singletonList("strength"));
        newTask.set("difficulty", QuestDifficulty.EASY.name());
        config.save(questFile.toFile());
        return key;
    }
}
