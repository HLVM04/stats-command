package hlvm.stats;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.world.level.block.Block;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatsCounter;
import net.minecraft.stats.StatType;
import net.minecraft.stats.Stats;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.ChatFormatting;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.Set;
import java.util.function.IntFunction;

import hlvm.stats.StatsRows.Row;

import static net.minecraft.commands.Commands.*;

/**
 * Stats Command Mod - Provides player statistics lookup and leaderboards.
 * 
 * Commands:
 * /stats <player> <category> <item> [share] [mode] - Look up individual player stats
 * /stats top <category> <item> [page] [share] [mode] - View leaderboards
 *
 * mode: both (default, current world with the old world in parentheses),
 * current, old, total. The old world's stats are read from
 * config/stats-command/old-world/ (see README).
 * 
 * Categories: mined, used, crafted, broken, picked_up, dropped, killed,
 * killed_by, custom
 */
public class StatsCommand implements ModInitializer {

	private static final int PAGE_SIZE = 10;
	private static StatsIndex statsIndex;

	// Stats of the previous world (read-only, loaded in the background at start)
	private static volatile OldWorldStats oldStats = OldWorldStats.empty();
	private static volatile boolean oldStatsReady = false;

	// Stats that are measured in ticks (20 ticks = 1 second)
	private static final Set<String> TIME_STATS = Set.of(
			"play_time", "time_since_death", "time_since_rest", "sneak_time",
			"total_world_time");

	// Stats that are measured in centimeters
	private static final Set<String> DISTANCE_STATS = Set.of(
			"walk_one_cm", "sprint_one_cm", "swim_one_cm", "fall_one_cm",
			"climb_one_cm", "fly_one_cm", "walk_under_water_one_cm",
			"minecart_one_cm", "boat_one_cm", "pig_one_cm", "horse_one_cm",
			"aviate_one_cm", "walk_on_water_one_cm", "strider_one_cm",
			"crouch_one_cm");

	// ==================== INITIALIZATION ====================

	@Override
	public void onInitialize() {
		registerLifecycleEvents();
		registerCommands();
	}

	private void registerLifecycleEvents() {
		// Initialize index on server start
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			statsIndex = new StatsIndex(server);
			statsIndex.syncAll(server);
			loadOldWorldStats(statsIndex);
		});

		// Clear index on server stop
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			if (statsIndex != null) {
				statsIndex.close();
				statsIndex = null;
			}
			oldStats = OldWorldStats.empty();
			oldStatsReady = false;
		});

		// Capture stats when player disconnects
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (statsIndex == null)
				return;

			ServerPlayer player = handler.getPlayer();
			statsIndex.cachePlayerName(player.getUUID(), player.getName().getString());

			int playerId = statsIndex.getOrCreatePlayerId(player.getUUID());
			StatsCounter statHandler = player.getStats();

			for (StatType<?> statType : BuiltInRegistries.STAT_TYPE) {
				persistStats(statType, statHandler, playerId);
			}
		});
	}

	/**
	 * Runs a command with its optional trailing arguments already parsed.
	 */
	@FunctionalInterface
	private interface TrailingRunner {
		int run(CommandContext<CommandSourceStack> ctx, boolean share, StatsMode mode) throws CommandSyntaxException;
	}

	/**
	 * Makes a command node executable and attaches the optional trailing arguments:
	 * [share] and [mode] (both|current|old|total), in any order. Examples of what
	 * is accepted after the node: nothing, "true", "old", "true old", "old true".
	 */
	private static void addTrailingOptions(ArgumentBuilder<CommandSourceStack, ?> node, TrailingRunner runner) {
		node.executes(ctx -> runner.run(ctx, false, StatsMode.BOTH));

		// [share] [mode]
		var shareNode = argument("share", BoolArgumentType.bool())
				.executes(ctx -> runner.run(ctx, BoolArgumentType.getBool(ctx, "share"), StatsMode.BOTH));
		for (StatsMode mode : StatsMode.values()) {
			shareNode.then(literal(mode.id)
					.executes(ctx -> runner.run(ctx, BoolArgumentType.getBool(ctx, "share"), mode)));
		}
		node.then(shareNode);

		// [mode] [share]
		for (StatsMode mode : StatsMode.values()) {
			node.then(literal(mode.id)
					.executes(ctx -> runner.run(ctx, false, mode))
					.then(argument("share", BoolArgumentType.bool())
							.executes(ctx -> runner.run(ctx, BoolArgumentType.getBool(ctx, "share"), mode))));
		}
	}

	/**
	 * Loads the old world's stats from config/stats-command/old-world/ in the background.
	 */
	private void loadOldWorldStats(StatsIndex index) {
		Thread thread = new Thread(() -> {
			try {
				Path oldWorldDir = FabricLoader.getInstance().getConfigDir()
						.resolve("stats-command").resolve("old-world");
				Files.createDirectories(oldWorldDir.resolve("stats"));
				Path serverUsercache = FabricLoader.getInstance().getGameDir().resolve("usercache.json");
				oldStats = OldWorldStats.load(oldWorldDir, List.of(serverUsercache),
						index::getCachedName, msg -> System.out.println("[StatsCommand] " + msg));
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				oldStatsReady = true;
			}
		}, "StatsCommand-OldWorld");
		thread.setDaemon(true);
		thread.start();
	}

	private void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> {
			LiteralArgumentBuilder<CommandSourceStack> statsCmd = literal("stats")
					.executes(ctx -> showHelp(ctx))
					.then(literal("help").executes(ctx -> showHelp(ctx)));

			// Player stats lookup: /stats <player> <category> <stat> [share] [mode]
			var playerArg = argument("target", GameProfileArgument.gameProfile());

			// Block stats
			var minedBlock = argument("block", ResourceArgument.resource(registryAccess, Registries.BLOCK));
			addTrailingOptions(minedBlock, (ctx, share, mode) -> executeStat(ctx, Stats.BLOCK_MINED,
					ResourceArgument.getResource(ctx, "block", Registries.BLOCK).value(),
					"mined", share, mode));
			playerArg.then(literal("mined").then(minedBlock));

			// Item stats
			for (var entry : List.of(
					Map.entry("used", Stats.ITEM_USED),
					Map.entry("crafted", Stats.ITEM_CRAFTED),
					Map.entry("broken", Stats.ITEM_BROKEN),
					Map.entry("picked_up", Stats.ITEM_PICKED_UP),
					Map.entry("dropped", Stats.ITEM_DROPPED))) {
				var itemNode = argument("item", ResourceArgument.resource(registryAccess, Registries.ITEM));
				addTrailingOptions(itemNode, (ctx, share, mode) -> executeStat(ctx, entry.getValue(),
						ResourceArgument.getResource(ctx, "item", Registries.ITEM).value(),
						entry.getKey().replace("_", " "), share, mode));
				playerArg.then(literal(entry.getKey()).then(itemNode));
			}

			// Entity stats
			var killedEntity = argument("entity", ResourceArgument.resource(registryAccess, Registries.ENTITY_TYPE));
			addTrailingOptions(killedEntity, (ctx, share, mode) -> executeStat(ctx, Stats.ENTITY_KILLED,
					ResourceArgument.getResource(ctx, "entity", Registries.ENTITY_TYPE).value(),
					"killed", share, mode));
			playerArg.then(literal("killed").then(killedEntity));

			var killedByEntity = argument("entity", ResourceArgument.resource(registryAccess, Registries.ENTITY_TYPE));
			addTrailingOptions(killedByEntity, (ctx, share, mode) -> executeStat(ctx, Stats.ENTITY_KILLED_BY,
					ResourceArgument.getResource(ctx, "entity", Registries.ENTITY_TYPE).value(),
					"killed by", share, mode));
			playerArg.then(literal("killed_by").then(killedByEntity));

			// Custom stats
			SuggestionProvider<CommandSourceStack> customStatSuggestions = (ctx, builder) -> {
				for (Identifier id : Stats.CUSTOM.getRegistry()) {
					builder.suggest(id.toString());
				}
				return builder.buildFuture();
			};

			var customStat = argument("stat", IdentifierArgument.id()).suggests(customStatSuggestions);
			addTrailingOptions(customStat, (ctx, share, mode) -> executeCustomStat(ctx,
					IdentifierArgument.getId(ctx, "stat"), share, mode));
			playerArg.then(literal("custom").then(customStat));

			statsCmd.then(playerArg);

			// Leaderboards: /stats top <category> <stat> [page] [share] [mode]
			var topCmd = literal("top");

			// Top mined
			var topBlock = argument("block", ResourceArgument.resource(registryAccess, Registries.BLOCK));
			addTrailingOptions(topBlock, (ctx, share, mode) -> executeTop(ctx, Stats.BLOCK_MINED,
					ResourceArgument.getResource(ctx, "block", Registries.BLOCK).value(),
					"mined", 1, share, mode));
			var topBlockPage = argument("page", IntegerArgumentType.integer(1));
			addTrailingOptions(topBlockPage, (ctx, share, mode) -> executeTop(ctx, Stats.BLOCK_MINED,
					ResourceArgument.getResource(ctx, "block", Registries.BLOCK).value(),
					"mined", IntegerArgumentType.getInteger(ctx, "page"), share, mode));
			topBlock.then(topBlockPage);
			topCmd.then(literal("mined").then(topBlock));

			// Top item stats
			for (var entry : List.of(
					Map.entry("used", Stats.ITEM_USED),
					Map.entry("crafted", Stats.ITEM_CRAFTED),
					Map.entry("broken", Stats.ITEM_BROKEN),
					Map.entry("picked_up", Stats.ITEM_PICKED_UP),
					Map.entry("dropped", Stats.ITEM_DROPPED))) {
				var topItem = argument("item", ResourceArgument.resource(registryAccess, Registries.ITEM));
				addTrailingOptions(topItem, (ctx, share, mode) -> executeTop(ctx, entry.getValue(),
						ResourceArgument.getResource(ctx, "item", Registries.ITEM).value(),
						entry.getKey().replace("_", " "), 1, share, mode));
				var topItemPage = argument("page", IntegerArgumentType.integer(1));
				addTrailingOptions(topItemPage, (ctx, share, mode) -> executeTop(ctx, entry.getValue(),
						ResourceArgument.getResource(ctx, "item", Registries.ITEM).value(),
						entry.getKey().replace("_", " "),
						IntegerArgumentType.getInteger(ctx, "page"), share, mode));
				topItem.then(topItemPage);
				topCmd.then(literal(entry.getKey()).then(topItem));
			}

			// Top entity stats
			var topKilled = argument("entity", ResourceArgument.resource(registryAccess, Registries.ENTITY_TYPE));
			addTrailingOptions(topKilled, (ctx, share, mode) -> executeTop(ctx, Stats.ENTITY_KILLED,
					ResourceArgument.getResource(ctx, "entity", Registries.ENTITY_TYPE).value(),
					"killed", 1, share, mode));
			var topKilledPage = argument("page", IntegerArgumentType.integer(1));
			addTrailingOptions(topKilledPage, (ctx, share, mode) -> executeTop(ctx, Stats.ENTITY_KILLED,
					ResourceArgument.getResource(ctx, "entity", Registries.ENTITY_TYPE).value(),
					"killed", IntegerArgumentType.getInteger(ctx, "page"), share, mode));
			topKilled.then(topKilledPage);
			topCmd.then(literal("killed").then(topKilled));

			var topKilledBy = argument("entity", ResourceArgument.resource(registryAccess, Registries.ENTITY_TYPE));
			addTrailingOptions(topKilledBy, (ctx, share, mode) -> executeTop(ctx, Stats.ENTITY_KILLED_BY,
					ResourceArgument.getResource(ctx, "entity", Registries.ENTITY_TYPE).value(),
					"killed by", 1, share, mode));
			var topKilledByPage = argument("page", IntegerArgumentType.integer(1));
			addTrailingOptions(topKilledByPage, (ctx, share, mode) -> executeTop(ctx, Stats.ENTITY_KILLED_BY,
					ResourceArgument.getResource(ctx, "entity", Registries.ENTITY_TYPE).value(),
					"killed by", IntegerArgumentType.getInteger(ctx, "page"), share, mode));
			topKilledBy.then(topKilledByPage);
			topCmd.then(literal("killed_by").then(topKilledBy));

			// Top custom stats
			var topCustom = argument("stat", IdentifierArgument.id()).suggests(customStatSuggestions);
			addTrailingOptions(topCustom, (ctx, share, mode) -> executeCustomTop(ctx,
					IdentifierArgument.getId(ctx, "stat"), 1, share, mode));
			var topCustomPage = argument("page", IntegerArgumentType.integer(1));
			addTrailingOptions(topCustomPage, (ctx, share, mode) -> executeCustomTop(ctx,
					IdentifierArgument.getId(ctx, "stat"),
					IntegerArgumentType.getInteger(ctx, "page"), share, mode));
			topCustom.then(topCustomPage);
			topCmd.then(literal("custom").then(topCustom));

			statsCmd.then(topCmd);
			dispatcher.register(statsCmd);
		});
	}

	// ==================== COMMAND HANDLERS ====================

	/**
	 * Displays a concise help message for the /stats command.
	 */
	private int showHelp(CommandContext<CommandSourceStack> ctx) {
		ctx.getSource().sendSuccess(() -> Component.literal("§6--- Stats Command ---"), false);
		ctx.getSource().sendSuccess(
				() -> Component.literal("§e/stats <player> <category> <item> [mode]§r - Look up a player's stat"), false);
		ctx.getSource().sendSuccess(
				() -> Component.literal("§e/stats top <category> <item> [page] [mode]§r - View leaderboard"),
				false);
		ctx.getSource()
				.sendSuccess(() -> Component.literal(
						"§7Categories:§r mined, used, crafted, broken, picked_up, dropped, killed, killed_by, custom"),
						false);
		ctx.getSource().sendSuccess(() -> Component.literal(
				"§7Modes:§r both (default, old world in parentheses), current, old, total"), false);
		ctx.getSource().sendSuccess(() -> Component.literal("§7Examples:§r"), false);
		ctx.getSource().sendSuccess(() -> Component.literal("  /stats Steve mined minecraft:diamond_ore"), false);
		ctx.getSource().sendSuccess(() -> Component.literal("  /stats top custom minecraft:play_time total"), false);
		return 1;
	}

	/**
	 * Highest indexed current-world value of a stat over every UUID the player's
	 * stats may be stored under (see equivalentUuids). 0 if the index is not ready.
	 */
	private int indexedValue(UUID id, String name, String statKey) {
		if (statsIndex == null) {
			return 0;
		}
		int best = 0;
		for (UUID candidate : equivalentUuids(id, name)) {
			best = Math.max(best, statsIndex.getPlayerStat(candidate, statKey));
		}
		return best;
	}

	/**
	 * Checks that the requested mode can be served. Returns an error message, or
	 * null if everything is fine.
	 */
	private String checkMode(StatsMode mode) {
		if (mode == StatsMode.CURRENT || mode == StatsMode.BOTH) {
			return null; // BOTH falls back to the current world when there is no old data
		}
		if (!oldStatsReady) {
			return "Old world stats are still loading, try again in a moment.";
		}
		if (!oldStats.hasData()) {
			return "No old world stats found. Put the old world's stats/*.json files in "
					+ "config/stats-command/old-world/stats/ and restart the server.";
		}
		return null;
	}

	/**
	 * BOTH only makes sense when old world data exists; without it the output is
	 * exactly the one of the original mod.
	 */
	private StatsMode effectiveMode(StatsMode mode) {
		if (mode == StatsMode.BOTH && !oldStats.hasData()) {
			return StatsMode.CURRENT;
		}
		return mode;
	}

	/**
	 * Formats the value(s) of a stat for the given mode: "§a1,824 §7(3,243)" for
	 * BOTH, a single green value for the others.
	 */
	private String formatValue(StatsMode mode, int current, int old, IntFunction<String> formatter) {
		switch (mode) {
			case BOTH:
				return "§a" + formatter.apply(current) + " §7(" + formatter.apply(old) + ")";
			case OLD:
				return "§a" + formatter.apply(old);
			case TOTAL:
				return "§a" + formatter.apply(OldWorldStats.addClamped(current, old));
			default:
				return "§a" + formatter.apply(current);
		}
	}

	/**
	 * Executes a stat lookup for standard stat types (blocks, items, entities).
	 */
	private <T> int executeStat(CommandContext<CommandSourceStack> ctx, StatType<T> type, T key, String verb,
			boolean share, StatsMode requestedMode) {
		try {
			String modeError = checkMode(requestedMode);
			if (modeError != null) {
				sendError(ctx, modeError);
				return 0;
			}
			StatsMode mode = effectiveMode(requestedMode);

			var profiles = GameProfileArgument.getGameProfiles(ctx, "target");
			if (profiles.isEmpty()) {
				sendError(ctx, "Player not found.");
				return 0;
			}
			var profile = profiles.iterator().next();

			MinecraftServer server = ctx.getSource().getServer();
			ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(profile.id());
			String statKey = getStatTypeId(type) + "/" + getStatKeyString(key);

			if (onlinePlayer == null && statsIndex == null) {
				sendError(ctx, "Index not loaded yet.");
				return 0;
			}
			int value = onlinePlayer != null ? onlinePlayer.getStats().getValue(type.get(key)) : 0;
			value = Math.max(value, indexedValue(profile.id(), profile.name(), statKey));
			int oldValue = mode == StatsMode.CURRENT ? 0
					: oldStats.get(OldWorldStats.canonicalFor(profile.id(), profile.name()), statKey);

			String valueText = formatValue(mode, value, oldValue, v -> String.format("%,d", v));
			String message = formatStatMessage(profile.name(), verb, valueText, getReadableName(key), type, mode);
			if (share) {
				broadcastAsPlayer(ctx, Component.literal(message));
			} else {
				sendWithShareButton(ctx, message);
			}
			return 1;
		} catch (Exception e) {
			sendError(ctx, e.getMessage());
			return 0;
		}
	}

	/**
	 * Executes a stat lookup for custom stats.
	 * Custom stats require special handling to get the exact registered Identifier.
	 */
	private int executeCustomStat(CommandContext<CommandSourceStack> ctx, Identifier statId, boolean share,
			StatsMode requestedMode) {
		try {
			String modeError = checkMode(requestedMode);
			if (modeError != null) {
				sendError(ctx, modeError);
				return 0;
			}
			StatsMode mode = effectiveMode(requestedMode);

			Identifier registeredId = findRegisteredCustomStat(statId);
			if (registeredId == null) {
				sendError(ctx, "Unknown custom stat: " + statId);
				return 0;
			}

			var profiles = GameProfileArgument.getGameProfiles(ctx, "target");
			if (profiles.isEmpty()) {
				sendError(ctx, "Player not found.");
				return 0;
			}
			var profile = profiles.iterator().next();

			MinecraftServer server = ctx.getSource().getServer();
			ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(profile.id());
			String statKey = "minecraft:custom/" + registeredId;

			if (onlinePlayer == null && statsIndex == null) {
				sendError(ctx, "Index not loaded yet.");
				return 0;
			}
			int value = onlinePlayer != null ? onlinePlayer.getStats().getValue(Stats.CUSTOM.get(registeredId)) : 0;
			value = Math.max(value, indexedValue(profile.id(), profile.name(), statKey));
			int oldValue = mode == StatsMode.CURRENT ? 0
					: oldStats.get(OldWorldStats.canonicalFor(profile.id(), profile.name()), statKey);

			String readableName = getCustomStatReadableName(statId);
			String valueText = formatValue(mode, value, oldValue, v -> formatCustomStatValue(statId.getPath(), v));
			String message = String.format("%s: %s§r %s%s", profile.name(), valueText, readableName,
					modeSuffix(mode));
			if (share) {
				broadcastAsPlayer(ctx, Component.literal(message));
			} else {
				sendWithShareButton(ctx, message);
			}
			return 1;
		} catch (Exception e) {
			sendError(ctx, e.getMessage());
			return 0;
		}
	}

	/**
	 * Executes a leaderboard lookup for standard stat types.
	 */
	private <T> int executeTop(CommandContext<CommandSourceStack> ctx, StatType<T> type, T key, String verb,
			int page, boolean share, StatsMode requestedMode) {
		if (statsIndex == null) {
			sendError(ctx, "Index not loaded yet.");
			return 0;
		}
		String modeError = checkMode(requestedMode);
		if (modeError != null) {
			sendError(ctx, modeError);
			return 0;
		}
		StatsMode mode = effectiveMode(requestedMode);

		MinecraftServer server = ctx.getSource().getServer();
		String statKey = getStatTypeId(type) + "/" + getStatKeyString(key);
		Stat<T> stat = type.get(key);

		List<Row> results = buildRows(server, statKey, stat, mode);
		return displayLeaderboard(ctx, results, getReadableName(key), verb, type, page, share, mode);
	}

	/**
	 * Executes a leaderboard lookup for custom stats.
	 */
	private int executeCustomTop(CommandContext<CommandSourceStack> ctx, Identifier statId, int page, boolean share,
			StatsMode requestedMode) {
		if (statsIndex == null) {
			sendError(ctx, "Index not loaded yet.");
			return 0;
		}
		String modeError = checkMode(requestedMode);
		if (modeError != null) {
			sendError(ctx, modeError);
			return 0;
		}
		StatsMode mode = effectiveMode(requestedMode);

		Identifier registeredId = findRegisteredCustomStat(statId);
		if (registeredId == null) {
			sendError(ctx, "Unknown custom stat: " + statId);
			return 0;
		}

		MinecraftServer server = ctx.getSource().getServer();
		String statKey = "minecraft:custom/" + registeredId;
		Stat<Identifier> stat = Stats.CUSTOM.get(registeredId);

		List<Row> results = buildRows(server, statKey, stat, mode);
		String readableName = getCustomStatReadableName(statId);

		return displayCustomLeaderboard(ctx, results, readableName, statId.getPath(), page, share, mode);
	}

	// ==================== HELPER METHODS ====================

	/**
	 * Finds the exact Identifier object from the custom stat registry.
	 * This is required because StatType.get() uses object identity for
	 * registry lookups.
	 */
	private Identifier findRegisteredCustomStat(Identifier statId) {
		for (Identifier id : Stats.CUSTOM.getRegistry()) {
			if (id.equals(statId)) {
				return id;
			}
		}
		return null;
	}

	/**
	 * Builds a merged leaderboard from indexed stats and live online player stats.
	 */
	private <T> List<StatsIndex.Entry> buildLeaderboard(MinecraftServer server, String statKey, Stat<T> stat) {
		Map<UUID, StatsIndex.Entry> entryMap = new HashMap<>();

		// Load indexed results
		for (StatsIndex.Entry entry : statsIndex.getTop(statKey, 1, 10000)) {
			entryMap.put(entry.uuid, entry);
		}

		// Merge with live stats from online players
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			UUID uuid = player.getUUID();
			int liveValue = player.getStats().getValue(stat);

			if (entryMap.containsKey(uuid)) {
				entryMap.get(uuid).value = liveValue;
			} else if (liveValue > 0) {
				entryMap.put(uuid, new StatsIndex.Entry(uuid, liveValue));
			}

			statsIndex.cachePlayerName(uuid, player.getName().getString());
		}

		// Sort by value descending
		List<StatsIndex.Entry> sorted = new ArrayList<>(entryMap.values());
		sorted.sort((a, b) -> Integer.compare(b.value, a.value));
		return sorted;
	}

	/**
	 * Name known for a UUID: online name, cached name, or the old world's name.
	 */
	private String knownName(UUID uuid) {
		String name = statsIndex != null ? statsIndex.getCachedName(uuid) : null;
		return name != null ? name : oldStats.nameOf(uuid);
	}

	/**
	 * The UUID a player has now. Floodgate UUIDs (and premium ones) are turned into
	 * the offline UUID of their name, which is what EasyAuth's forced-offline-uuid
	 * gives them. This way one person is one row, even while their stats still
	 * exist under several UUIDs.
	 */
	private UUID canonicalUuid(UUID uuid) {
		return OldWorldStats.canonicalFor(uuid, knownName(uuid));
	}

	/**
	 * All the UUIDs a player's current-world stats can be stored under: the one
	 * given, its canonical one, and any Floodgate UUID that has the same name.
	 */
	private Set<UUID> equivalentUuids(UUID id, String name) {
		Set<UUID> ids = new LinkedHashSet<>();
		ids.add(id);
		ids.add(OldWorldStats.canonicalFor(id, name));
		if (statsIndex != null && name != null) {
			for (UUID other : statsIndex.getAllPlayerUUIDs()) {
				if (OldWorldStats.isFloodgateUuid(other) && name.equals(statsIndex.getCachedName(other))) {
					ids.add(other);
				}
			}
		}
		return ids;
	}

	/**
	 * Builds the leaderboard rows for a mode, sorted by the value that mode ranks
	 * by (current world for BOTH/CURRENT, old world for OLD, the sum for TOTAL).
	 * Players that only exist in the old world appear in OLD and TOTAL only.
	 */
	private <T> List<Row> buildRows(MinecraftServer server, String statKey, Stat<T> stat, StatsMode mode) {
		Map<UUID, Integer> currentRaw = new HashMap<>();
		if (mode != StatsMode.OLD) {
			for (StatsIndex.Entry entry : buildLeaderboard(server, statKey, stat)) {
				currentRaw.put(entry.uuid, entry.value);
			}
		}
		return StatsRows.build(currentRaw, this::canonicalUuid, oldStats, statKey, mode);
	}

	/**
	 * Displays a paginated leaderboard.
	 */
	private <T> int displayLeaderboard(CommandContext<CommandSourceStack> ctx, List<Row> results,
			String itemName, String verb, StatType<T> type, int page, boolean share, StatsMode mode) {
		int start = (page - 1) * PAGE_SIZE;
		if (start >= results.size() || results.isEmpty()) {
			sendError(ctx, "No stats found (or page empty).");
			return 0;
		}

		int end = Math.min(start + PAGE_SIZE, results.size());
		List<Row> pageResults = results.subList(start, end);

		// Send header
		String header = formatTopHeader(verb, itemName, type, page, mode);
		if (share) {
			broadcastAsPlayer(ctx, Component.literal(header));
		} else {
			sendWithShareButton(ctx, header);
		}

		// Send entries
		MinecraftServer server = ctx.getSource().getServer();
		for (int i = 0; i < pageResults.size(); i++) {
			Row entry = pageResults.get(i);
			int rank = start + i + 1;
			String valueText = formatValue(mode, entry.current, entry.old, v -> String.format("%,d", v));
			String name = getPlayerName(server, entry.uuid);

			Component line = Component.literal(String.format("#%d %s: %s", rank, name, valueText));
			if (share) {
				ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(line, false);
			} else {
				ctx.getSource().sendSuccess(() -> line, false);
			}
		}

		return 1;
	}

	/**
	 * Gets a player's display name (online name, cached name, or truncated UUID).
	 */
	private String getPlayerName(MinecraftServer server, UUID uuid) {
		ServerPlayer player = server.getPlayerList().getPlayer(uuid);
		if (player != null) {
			return player.getName().getString();
		}
		String cached = statsIndex.getCachedName(uuid);
		if (cached == null) {
			cached = oldStats.nameOf(uuid);
		}
		return cached != null ? cached : uuid.toString().substring(0, 8) + "...";
	}

	/**
	 * Persists all stats for a player when they disconnect.
	 */
	private <T> void persistStats(StatType<T> statType, StatsCounter statHandler, int playerId) {
		for (T key : statType.getRegistry()) {
			Stat<T> stat = statType.get(key);
			int value = statHandler.getValue(stat);
			if (value > 0) {
				String fullKey = getStatKey(stat);
				int statId = statsIndex.getOrCreateStatId(fullKey);
				statsIndex.updateStat(playerId, statId, value);
			}
		}
	}

	// ==================== FORMATTING ====================

	/**
	 * Formats a custom stat value based on its type (time, distance, or plain
	 * number).
	 */
	private String formatCustomStatValue(String statPath, int value) {
		if (TIME_STATS.contains(statPath)) {
			return formatTime(value);
		} else if (DISTANCE_STATS.contains(statPath)) {
			return formatDistance(value);
		}
		return String.format("%,d", value);
	}

	/**
	 * Formats ticks as human-readable time (e.g., "2h 30m" or "45 minutes").
	 */
	private String formatTime(int ticks) {
		int totalSeconds = ticks / 20;
		int hours = totalSeconds / 3600;
		int minutes = (totalSeconds % 3600) / 60;

		if (hours > 0) {
			return String.format("%dh %dm", hours, minutes);
		} else if (minutes > 0) {
			return String.format("%d minutes", minutes);
		} else {
			return String.format("%d seconds", totalSeconds);
		}
	}

	/**
	 * Formats centimeters as human-readable distance (e.g., "1.5 km" or "200 m").
	 */
	private String formatDistance(int cm) {
		double meters = cm / 100.0;
		if (meters >= 1000) {
			return String.format("%.1f km", meters / 1000.0);
		} else {
			return String.format("%.0f m", meters);
		}
	}

	/**
	 * Gets a readable name for a custom stat, removing the "_one_cm" suffix for
	 * distance stats.
	 */
	private String getCustomStatReadableName(Identifier statId) {
		String path = statId.getPath();
		// Clean up distance stat names (e.g., "walk_one_cm" -> "walked")
		if (path.endsWith("_one_cm")) {
			String base = path.substring(0, path.length() - 7);
			return base.replace("_", " ");
		}
		return path.replace("_", " ");
	}

	/**
	 * Displays a paginated leaderboard for custom stats with formatted values.
	 */
	private int displayCustomLeaderboard(CommandContext<CommandSourceStack> ctx, List<Row> results,
			String itemName, String statPath, int page, boolean share, StatsMode mode) {
		int start = (page - 1) * PAGE_SIZE;
		if (start >= results.size() || results.isEmpty()) {
			sendError(ctx, "No stats found (or page empty).");
			return 0;
		}

		int end = Math.min(start + PAGE_SIZE, results.size());
		List<Row> pageResults = results.subList(start, end);

		// Send header
		String header = String.format("--- Top %s%s (Page %d) ---", itemName, mode.headerLabel(), page);
		if (share) {
			broadcastAsPlayer(ctx, Component.literal(header));
		} else {
			sendWithShareButton(ctx, header);
		}

		// Send entries with formatted values
		MinecraftServer server = ctx.getSource().getServer();
		for (int i = 0; i < pageResults.size(); i++) {
			Row entry = pageResults.get(i);
			int rank = start + i + 1;
			String valueText = formatValue(mode, entry.current, entry.old, v -> formatCustomStatValue(statPath, v));
			String name = getPlayerName(server, entry.uuid);

			Component line = Component.literal(String.format("#%d %s: %s", rank, name, valueText));
			if (share) {
				ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(line, false);
			} else {
				ctx.getSource().sendSuccess(() -> line, false);
			}
		}

		return 1;
	}

	private void sendWithShareButton(CommandContext<CommandSourceStack> ctx, String message) {
		String shareCommand = StatsMode.shareCommand(ctx.getInput());
		Component shareButton = Component.literal(" [Share]")
				.withStyle(style -> style
						.withColor(ChatFormatting.GREEN)
						.withClickEvent(new ClickEvent.RunCommand(shareCommand))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to share in chat"))));

		ctx.getSource().sendSuccess(() -> Component.literal(message).append(shareButton), false);
	}

	private void broadcastAsPlayer(CommandContext<CommandSourceStack> ctx, Component message) {
		String playerName = ctx.getSource().getTextName();
		Component fullMessage = Component.literal("<" + playerName + "> ").append(message);
		ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(fullMessage, false);
	}

	private void sendError(CommandContext<CommandSourceStack> ctx, String message) {
		ctx.getSource().sendFailure(Component.literal("§c" + message));
	}

	/**
	 * Text appended to single-player lookups so a shared message says which world
	 * it is about. BOTH needs none: the value in parentheses is the old world.
	 */
	private String modeSuffix(StatsMode mode) {
		switch (mode) {
			case OLD:
				return " §7(Old world)";
			case TOTAL:
				return " §7(Total)";
			default:
				return "";
		}
	}

	private <T> String formatStatMessage(String playerName, String verb, String valueText, String itemName,
			StatType<T> type, StatsMode mode) {
		String suffix = modeSuffix(mode);
		if (type == Stats.CUSTOM) {
			return String.format("%s: %s§r %s%s", playerName, valueText, itemName, suffix);
		} else if (type == Stats.ENTITY_KILLED_BY) {
			return String.format("%s was killed by %s§r %s%s", playerName, valueText, itemName, suffix);
		} else if (type == Stats.ENTITY_KILLED) {
			return String.format("%s has killed %s§r %s%s", playerName, valueText, itemName, suffix);
		} else {
			return String.format("%s has %s %s§r %s%s", playerName, verb, valueText, itemName, suffix);
		}
	}

	private <T> String formatTopHeader(String verb, String itemName, StatType<T> type, int page, StatsMode mode) {
		String label = mode.headerLabel();
		if (type == Stats.CUSTOM) {
			return String.format("--- Top %s%s (Page %d) ---", itemName, label, page);
		} else if (type == Stats.ENTITY_KILLED_BY) {
			return String.format("--- Most Killed By %s%s (Page %d) ---", itemName, label, page);
		} else if (type == Stats.ENTITY_KILLED) {
			return String.format("--- Most %s Killed%s (Page %d) ---", itemName, label, page);
		} else {
			String capitalizedVerb = verb.substring(0, 1).toUpperCase() + verb.substring(1);
			return String.format("--- Most %s %s%s (Page %d) ---", itemName, capitalizedVerb, label, page);
		}
	}

	// ==================== STAT KEY UTILITIES ====================

	private <T> String getStatTypeId(StatType<T> type) {
		Identifier id = BuiltInRegistries.STAT_TYPE.getKey(type);
		return id != null ? id.toString() : "minecraft:custom";
	}

	private <T> String getStatKey(Stat<T> stat) {
		return getStatTypeId(stat.getType()) + "/" + getStatKeyString(stat.getValue());
	}

	private <T> String getStatKeyString(T key) {
		if (key instanceof Block block) {
			return BuiltInRegistries.BLOCK.getKey(block).toString();
		} else if (key instanceof Item item) {
			return BuiltInRegistries.ITEM.getKey(item).toString();
		} else if (key instanceof EntityType<?> entityType) {
			return BuiltInRegistries.ENTITY_TYPE.getKey(entityType).toString();
		} else if (key instanceof Identifier id) {
			return id.toString();
		}
		return key.toString();
	}

	private <T> String getReadableName(T key) {
		if (key instanceof Block block) {
			return block.getName().getString();
		} else if (key instanceof Item item) {
			return item.getName(item.getDefaultInstance()).getString();
		} else if (key instanceof EntityType<?> entityType) {
			return entityType.getDescription().getString();
		} else if (key instanceof Identifier id) {
			return id.getPath().replace("_", " ");
		}
		return key.toString();
	}
}