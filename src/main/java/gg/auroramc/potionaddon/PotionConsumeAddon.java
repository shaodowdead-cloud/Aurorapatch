package gg.auroramc.potionaddon;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionData;
import org.bukkit.potion.PotionType;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Add-on for AuroraQuests that differentiates potion consumption by base type and level.
 *
 * <p>This plugin listens for {@link PlayerItemConsumeEvent}. When a player drinks a potion, it
 * determines the base potion type (e.g. STRENGTH, INVISIBILITY) and whether the potion is
 * upgraded (level II) or extended (long duration). It then uses reflection to interact with
 * AuroraQuests' API at runtime. The add‑on locates the player's active quests and any
 * {@code ConsumeObjective}s, constructs an appropriate {@code TypeId} meta object (with a key
 * matching the potion variant such as {@code strength}, {@code strong_strength} or
 * {@code long_strength}) and progresses the objective by one. This allows quests that specify
 * potion types in their {@code types} argument to be progressed correctly when those specific
 * potions are consumed.</p>
 *
 * <p>For example, if a quest has a task like:
 * <pre>
 * tasks:
 *   drink_strength:
 *     task: CONSUME
 *     args:
 *       amount: 1
 *       types:
 *         - "strength"
 *         - "strong_strength"
 *         - "long_strength"
 * </pre>
 * then consuming a regular strength potion will progress the first type, a level II potion
 * progresses the {@code strong_strength} entry, and an extended potion progresses the
 * {@code long_strength} entry. Without this add‑on, AuroraQuests treats all potions the same and
 * cannot differentiate between strength and invisibility or their levels.</p>
 *
 * <p>Note: This plugin uses reflection to access AuroraQuests classes. It will silently
 * do nothing if AuroraQuests is not present or if the internal API changes in future versions.</p>
 */
public final class PotionConsumeAddon extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        // Only register our listener if AuroraQuests is present.
        if (Bukkit.getPluginManager().getPlugin("AuroraQuests") == null) {
            getLogger().warning("AuroraQuests not found; PotionConsumeAddon will remain inactive.");
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("PotionConsumeAddon enabled and listening for potion consumption events.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        // Only handle drinking potions; ignore splash and lingering potions here.
        if (item == null || item.getType() != Material.POTION) {
            return;
        }
        if (!(item.getItemMeta() instanceof PotionMeta potionMeta)) {
            return;
        }

        PotionData data = potionMeta.getBasePotionData();
        PotionType type = data.getType();
        // Some potion types such as WATER or MUNDANE are not considered quest potions.
        if (type == PotionType.UNCRAFTABLE || type == PotionType.WATER || type == PotionType.MUNDANE || type == PotionType.THICK || type == PotionType.AWKWARD) {
            return;
        }

        // Determine the variant key similar to AuroraQuests' convention:
        // base: "strength", long: "long_strength", strong: "strong_strength".
        String baseKey = type.name().toLowerCase(Locale.ROOT);
        String variantKey;
        if (data.isUpgraded()) {
            variantKey = "strong_" + baseKey;
        } else if (data.isExtended()) {
            variantKey = "long_" + baseKey;
        } else {
            variantKey = baseKey;
        }

        progressConsumeObjectives(event.getPlayer(), variantKey);
    }

    /**
     * Use reflection to progress all active Consume objectives for the given player using the
     * specified potion key. If AuroraQuests' API is not available or changes, this method will
     * silently fail without throwing exceptions.
     *
     * @param player     the player who consumed the potion
     * @param potionKey  the key representing the potion variant (e.g. "strength", "long_strength")
     */
    @SuppressWarnings("unchecked")
    private void progressConsumeObjectives(Player player, String potionKey) {
        try {
            // Get AuroraQuestsPlugin instance: AuroraQuestsPlugin.inst()
            Class<?> questsClass = Class.forName("gg.auroramc.quests.api.AuroraQuestsPlugin");
            Method instMethod = questsClass.getMethod("inst");
            Object questsInstance = instMethod.invoke(null);

            // Retrieve the ProfileManager and player's profile
            Method getProfileManager = questsClass.getMethod("getProfileManager");
            Object profileManager = getProfileManager.invoke(questsInstance);
            Class<?> profileManagerClass = profileManager.getClass();
            Method getProfile = profileManagerClass.getMethod("getProfile", Player.class);
            Object profile = getProfile.invoke(profileManager, player);
            if (profile == null) {
                return;
            }
            // Get quest pools
            Method getQuestPools = profile.getClass().getMethod("getQuestPools");
            Collection<?> pools = (Collection<?>) getQuestPools.invoke(profile);
            if (pools == null) {
                return;
            }
            // Preload classes we need via reflection
            Class<?> typeIdClass = Class.forName("gg.auroramc.quests.api.objective.meta.TypeId");
            Class<?> objectiveMetaClass = Class.forName("gg.auroramc.quests.api.objective.ObjectiveMeta");
            Class<?> consumeObjectiveClass = Class.forName("gg.auroramc.quests.api.objective.types.ConsumeObjective");

            // Prepare TypeId constructor: new TypeId(namespace, key)
            Constructor<?> typeIdConstructor = typeIdClass.getConstructor(String.class, String.class);

            for (Object pool : pools) {
                // For each pool, get active quests
                Method getActiveQuests = pool.getClass().getMethod("getActiveQuests");
                Collection<?> quests = (Collection<?>) getActiveQuests.invoke(pool);
                if (quests == null) continue;
                for (Object quest : quests) {
                    Method getObjectives = quest.getClass().getMethod("getObjectives");
                    List<?> objectives = (List<?>) getObjectives.invoke(quest);
                    if (objectives == null) continue;
                    for (Object objective : objectives) {
                        // Check if this is a ConsumeObjective by class
                        if (!consumeObjectiveClass.isAssignableFrom(objective.getClass())) {
                            continue;
                        }
                        // Create a TypeId for the potion variant
                        Object typeId = typeIdConstructor.newInstance("minecraft", potionKey);
                        // Build the objective meta
                        Method metaMethod = objective.getClass().getMethod("meta", typeIdClass);
                        Object objectiveMeta = metaMethod.invoke(objective, typeId);
                        // Progress the objective by 1
                        Method progressMethod = objective.getClass().getMethod("progress", int.class, objectiveMetaClass);
                        progressMethod.invoke(objective, 1, objectiveMeta);
                    }
                }
            }
        } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException |
                 IllegalAccessException | InvocationTargetException e) {
            // Fail silently to avoid spamming console; optionally print stack trace in debug mode
            // e.printStackTrace();
        }
    }
}