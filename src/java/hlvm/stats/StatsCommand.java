package hlvm.stats;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.Block;
import net.minecraft.command.argument.GameProfileArgumentType;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.command.argument.RegistryEntryReferenceArgumentType;
import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.Stat;
import net.minecraft.stat.StatHandler;
import net.minecraft.stat.StatType;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Style;
import net.minecraft.util.Formatting;

import java.util.*;
import java.util.Set;

import static net.minecraft.server.command.CommandManager.*;

/**
 * Stats Command Mod - Provides player statistics lookup and leaderboards.
 * 
 * Commands:
 * /stats <player> <category> <item> - Look up individual player stats
 * /stats top <category> <item> [page] - View leaderboards
 * 
 * Categories: mined, used, crafted, broken, picked_up, dropped, killed,
 * killed_by, custom
 */
public class StatsCommand implements ModInitializer {

	private static final int PAGE_SIZE = 10;
	private static StatsIndex statsIndex;

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
		});

		// Clear index on server stop
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			if (statsIndex != null) {
				statsIndex.close();
				statsIndex = null;
			}
		});

		// Capture stats when player disconnects
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (statsIndex == null)
				return;

			ServerPlayerEntity player = handler.getPlayer();
			statsIndex.cachePlayerName(player.getUuid(), player.getName().getString());

			int playerId = statsIndex.getOrCreatePlayerId(player.getUuid());
			StatHandler statHandler = player.getStatHandler();

			for (StatType<?> statType : Registries.STAT_TYPE) {
				persistStats(statType, statHandler, playerId);
			}
		});
	}

	private void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> {
			LiteralArgumentBuilder<ServerCommandSource> statsCmd = literal("stats")
					.executes(ctx -> showHelp(ctx))
					.then(literal("help").executes(ctx -> showHelp(ctx)));

			// Player stats lookup
			var playerArg = argument("target", GameProfileArgumentType.gameProfile());

			// Block stats
			playerArg.then(literal("mined")
					.then(argument("block",
							RegistryEntryReferenceArgumentType.registryEntry(registryAccess, RegistryKeys.BLOCK))
							.executes(ctx -> executeStat(ctx, Stats.MINED,
									RegistryEntryReferenceArgumentType
											.getRegistryEntry(ctx, "block", RegistryKeys.BLOCK).value(),
									"mined", false))
							.then(argument("share", BoolArgumentType.bool())
									.suggests((ctx, builder) -> builder.buildFuture())
									.executes(ctx -> executeStat(ctx,
											Stats.MINED,
											RegistryEntryReferenceArgumentType
													.getRegistryEntry(ctx, "block", RegistryKeys.BLOCK).value(),
											"mined", BoolArgumentType.getBool(ctx, "share"))))));

			// Item stats
			for (var entry : List.of(
					Map.entry("used", Stats.USED),
					Map.entry("crafted", Stats.CRAFTED),
					Map.entry("broken", Stats.BROKEN),
					Map.entry("picked_up", Stats.PICKED_UP),
					Map.entry("dropped", Stats.DROPPED))) {
				playerArg.then(literal(entry.getKey())
						.then(argument("item",
								RegistryEntryReferenceArgumentType.registryEntry(registryAccess, RegistryKeys.ITEM))
								.executes(ctx -> executeStat(ctx, entry.getValue(),
										RegistryEntryReferenceArgumentType
												.getRegistryEntry(ctx, "item", RegistryKeys.ITEM).value(),
										entry.getKey().replace("_", " "), false))
								.then(argument("share", BoolArgumentType.bool())
										.suggests((ctx, builder) -> builder.buildFuture())
										.executes(ctx -> executeStat(ctx,
												entry.getValue(),
												RegistryEntryReferenceArgumentType
														.getRegistryEntry(ctx, "item", RegistryKeys.ITEM).value(),
												entry.getKey().replace("_", " "),
												BoolArgumentType.getBool(ctx, "share"))))));
			}

			// Entity stats
			playerArg.then(literal("killed")
					.then(argument("entity",
							RegistryEntryReferenceArgumentType.registryEntry(registryAccess, RegistryKeys.ENTITY_TYPE))
							.executes(ctx -> executeStat(ctx, Stats.KILLED,
									RegistryEntryReferenceArgumentType
											.getRegistryEntry(ctx, "entity", RegistryKeys.ENTITY_TYPE).value(),
									"killed", false))
							.then(argument("share", BoolArgumentType.bool())
									.suggests((ctx, builder) -> builder.buildFuture())
									.executes(ctx -> executeStat(ctx,
											Stats.KILLED,
											RegistryEntryReferenceArgumentType
													.getRegistryEntry(ctx, "entity", RegistryKeys.ENTITY_TYPE).value(),
											"killed", BoolArgumentType.getBool(ctx, "share"))))));

			playerArg.then(literal("killed_by")
					.then(argument("entity",
							RegistryEntryReferenceArgumentType.registryEntry(registryAccess, RegistryKeys.ENTITY_TYPE))
							.executes(ctx -> executeStat(ctx, Stats.KILLED_BY,
									RegistryEntryReferenceArgumentType
											.getRegistryEntry(ctx, "entity", RegistryKeys.ENTITY_TYPE).value(),
									"killed by", false))
							.then(argument("share", BoolArgumentType.bool())
									.suggests((ctx, builder) -> builder.buildFuture())
									.executes(ctx -> executeStat(ctx,
											Stats.KILLED_BY,
											RegistryEntryReferenceArgumentType
													.getRegistryEntry(ctx, "entity", RegistryKeys.ENTITY_TYPE).value(),
											"killed by", BoolArgumentType.getBool(ctx, "share"))))));

			// Custom stats
			SuggestionProvider<ServerCommandSource> customStatSuggestions = (ctx, builder) -> {
				for (Identifier id : Stats.CUSTOM.getRegistry()) {
					builder.suggest(id.toString());
				}
				return builder.buildFuture();
			};

			playerArg.then(literal("custom")
					.then(argument("stat", IdentifierArgumentType.identifier())
							.suggests(customStatSuggestions)
							.suggests(customStatSuggestions)
							.executes(ctx -> executeCustomStat(ctx, IdentifierArgumentType.getIdentifier(ctx, "stat"),
									false))
							.then(argument("share", BoolArgumentType.bool())
									.suggests((ctx, builder) -> builder.buildFuture())
									.executes(ctx -> executeCustomStat(ctx,
											IdentifierArgumentType.getIdentifier(ctx, "stat"),
											BoolArgumentType.getBool(ctx, "share"))))));

			statsCmd.then(playerArg);

			// Leaderboards
			var topCmd = literal("top");

			// Top mined
			topCmd.then(literal("mined")
					.then(argument("block",
							RegistryEntryReferenceArgumentType.registryEntry(registryAccess, RegistryKeys.BLOCK))
							.executes(ctx -> executeTop(ctx, Stats.MINED,
									RegistryEntryReferenceArgumentType
											.getRegistryEntry(ctx, "block", RegistryKeys.BLOCK).value(),
									"mined", 1, false))
							.then(argument("share", BoolArgumentType.bool())
									.suggests((ctx, builder) -> builder.buildFuture())
									.executes(ctx -> executeTop(ctx, Stats.MINED,
											RegistryEntryReferenceArgumentType
													.getRegistryEntry(ctx, "block", RegistryKeys.BLOCK).value(),
											"mined", 1, BoolArgumentType.getBool(ctx, "share"))))
							.then(argument("page", IntegerArgumentType.integer(1))
									.executes(ctx -> executeTop(ctx, Stats.MINED,
											RegistryEntryReferenceArgumentType
													.getRegistryEntry(ctx, "block", RegistryKeys.BLOCK).value(),
											"mined", IntegerArgumentType.getInteger(ctx, "page"), false))
									.then(argument("share", BoolArgumentType.bool())
											.suggests((ctx, builder) -> builder.buildFuture())
											.executes(ctx -> executeTop(ctx, Stats.MINED,
													RegistryEntryReferenceArgumentType
															.getRegistryEntry(ctx, "block", RegistryKeys.BLOCK).value(),
													"mined", IntegerArgumentType.getInteger(ctx, "page"),
													BoolArgumentType.getBool(ctx, "share")))))));

			// Top item stats
			for (var entry : List.of(
					Map.entry("used", Stats.USED),
					Map.entry("crafted", Stats.CRAFTED),
					Map.entry("broken", Stats.BROKEN),
					Map.entry("picked_up", Stats.PICKED_UP),
					Map.entry("dropped", Stats.DROPPED))) {
				topCmd.then(literal(entry.getKey())
						.then(argument("item",
								RegistryEntryReferenceArgumentType.registryEntry(registryAccess, RegistryKeys.ITEM))
								.executes(ctx -> executeTop(ctx, entry.getValue(),
										RegistryEntryReferenceArgumentType
												.getRegistryEntry(ctx, "item", RegistryKeys.ITEM).value(),
										entry.getKey().replace("_", " "), 1, false))
								.then(argument("share", BoolArgumentType.bool())
										.suggests((ctx, builder) -> builder.buildFuture())
										.executes(ctx -> executeTop(ctx, entry.getValue(),
												RegistryEntryReferenceArgumentType
														.getRegistryEntry(ctx, "item", RegistryKeys.ITEM).value(),
												entry.getKey().replace("_", " "), 1,
												BoolArgumentType.getBool(ctx, "share"))))
								.then(argument("page", IntegerArgumentType.integer(1))
										.executes(ctx -> executeTop(ctx, entry.getValue(),
												RegistryEntryReferenceArgumentType
														.getRegistryEntry(ctx, "item", RegistryKeys.ITEM).value(),
												entry.getKey().replace("_", " "),
												IntegerArgumentType.getInteger(ctx, "page"), false))
										.then(argument("share", BoolArgumentType.bool())
												.suggests((ctx, builder) -> builder.buildFuture())
												.executes(ctx -> executeTop(ctx, entry.getValue(),
														RegistryEntryReferenceArgumentType
																.getRegistryEntry(ctx, "item", RegistryKeys.ITEM)
																.value(),
														entry.getKey().replace("_", " "),
														IntegerArgumentType.getInteger(ctx, "page"),
														BoolArgumentType.getBool(ctx, "share")))))));
			}

			// Top entity stats
			topCmd.then(literal("killed")
					.then(argument("entity",
							RegistryEntryReferenceArgumentType.registryEntry(registryAccess, RegistryKeys.ENTITY_TYPE))
							.executes(ctx -> executeTop(ctx, Stats.KILLED,
									RegistryEntryReferenceArgumentType
											.getRegistryEntry(ctx, "entity", RegistryKeys.ENTITY_TYPE).value(),
									"killed", 1, false))
							.then(argument("share", BoolArgumentType.bool())
									.suggests((ctx, builder) -> builder.buildFuture())
									.executes(ctx -> executeTop(ctx, Stats.KILLED,
											RegistryEntryReferenceArgumentType
													.getRegistryEntry(ctx, "entity", RegistryKeys.ENTITY_TYPE).value(),
											"killed", 1, BoolArgumentType.getBool(ctx, "share"))))
							.then(argument("page", IntegerArgumentType.integer(1))
									.executes(ctx -> executeTop(ctx, Stats.KILLED,
											RegistryEntryReferenceArgumentType
													.getRegistryEntry(ctx, "entity", RegistryKeys.ENTITY_TYPE).value(),
											"killed", IntegerArgumentType.getInteger(ctx, "page"), false))
									.then(argument("share", BoolArgumentType.bool())
											.suggests((ctx, builder) -> builder.buildFuture())
											.executes(ctx -> executeTop(ctx, Stats.KILLED,
													RegistryEntryReferenceArgumentType
															.getRegistryEntry(ctx, "entity", RegistryKeys.ENTITY_TYPE)
															.value(),
													"killed", IntegerArgumentType.getInteger(ctx, "page"),
													BoolArgumentType.getBool(ctx, "share")))))));

			topCmd.then(literal("killed_by")
					.then(argument("entity",
							RegistryEntryReferenceArgumentType.registryEntry(registryAccess, RegistryKeys.ENTITY_TYPE))
							.executes(ctx -> executeTop(ctx, Stats.KILLED_BY,
									RegistryEntryReferenceArgumentType
											.getRegistryEntry(ctx, "entity", RegistryKeys.ENTITY_TYPE).value(),
									"killed by", 1, false))
							.then(argument("share", BoolArgumentType.bool())
									.suggests((ctx, builder) -> builder.buildFuture())
									.executes(ctx -> executeTop(ctx, Stats.KILLED_BY,
											RegistryEntryReferenceArgumentType
													.getRegistryEntry(ctx, "entity", RegistryKeys.ENTITY_TYPE).value(),
											"killed by", 1, BoolArgumentType.getBool(ctx, "share"))))
							.then(argument("page", IntegerArgumentType.integer(1))
									.executes(ctx -> executeTop(ctx, Stats.KILLED_BY,
											RegistryEntryReferenceArgumentType
													.getRegistryEntry(ctx, "entity", RegistryKeys.ENTITY_TYPE).value(),
											"killed by", IntegerArgumentType.getInteger(ctx, "page"), false))
									.then(argument("share", BoolArgumentType.bool())
											.suggests((ctx, builder) -> builder.buildFuture())
											.executes(ctx -> executeTop(ctx, Stats.KILLED_BY,
													RegistryEntryReferenceArgumentType
															.getRegistryEntry(ctx, "entity", RegistryKeys.ENTITY_TYPE)
															.value(),
													"killed by", IntegerArgumentType.getInteger(ctx, "page"),
													BoolArgumentType.getBool(ctx, "share")))))));

			// Top custom stats
			topCmd.then(literal("custom")
					.then(argument("stat", IdentifierArgumentType.identifier())
							.suggests(customStatSuggestions)
							.executes(
									ctx -> executeCustomTop(ctx, IdentifierArgumentType.getIdentifier(ctx, "stat"), 1,
											false))
							.then(argument("share", BoolArgumentType.bool())
									.suggests((ctx, builder) -> builder.buildFuture())
									.executes(ctx -> executeCustomTop(ctx,
											IdentifierArgumentType.getIdentifier(ctx, "stat"), 1,
											BoolArgumentType.getBool(ctx, "share"))))
							.then(argument("page", IntegerArgumentType.integer(1))
									.executes(ctx -> executeCustomTop(ctx,
											IdentifierArgumentType.getIdentifier(ctx, "stat"),
											IntegerArgumentType.getInteger(ctx, "page"), false))
									.then(argument("share", BoolArgumentType.bool())
											.suggests((ctx, builder) -> builder.buildFuture())
											.executes(ctx -> executeCustomTop(ctx,
													IdentifierArgumentType.getIdentifier(ctx, "stat"),
													IntegerArgumentType.getInteger(ctx, "page"),
													BoolArgumentType.getBool(ctx, "share")))))));

			statsCmd.then(topCmd);
			dispatcher.register(statsCmd);
		});
	}

	// ==================== COMMAND HANDLERS ====================

	/**
	 * Displays a concise help message for the /stats command.
	 */
	private int showHelp(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal("§6--- Stats Command ---"), false);
		ctx.getSource().sendFeedback(
				() -> Text.literal("§e/stats <player> <category> <item>§r - Look up a player's stat"), false);
		ctx.getSource().sendFeedback(() -> Text.literal("§e/stats top <category> <item> [page]§r - View leaderboard"),
				false);
		ctx.getSource()
				.sendFeedback(() -> Text.literal(
						"§7Categories:§r mined, used, crafted, broken, picked_up, dropped, killed, killed_by, custom"),
						false);
		ctx.getSource().sendFeedback(() -> Text.literal("§7Examples:§r"), false);
		ctx.getSource().sendFeedback(() -> Text.literal("  /stats Steve mined minecraft:diamond_ore"), false);
		ctx.getSource().sendFeedback(() -> Text.literal("  /stats top custom minecraft:play_time"), false);
		return 1;
	}

	/**
	 * Executes a stat lookup for standard stat types (blocks, items, entities).
	 */
	private <T> int executeStat(CommandContext<ServerCommandSource> ctx, StatType<T> type, T key, String verb,
			boolean share) {
		try {
			var profiles = GameProfileArgumentType.getProfileArgument(ctx, "target");
			if (profiles.isEmpty()) {
				sendError(ctx, "Player not found.");
				return 0;
			}
			var profile = profiles.iterator().next();

			MinecraftServer server = ctx.getSource().getServer();
			ServerPlayerEntity onlinePlayer = server.getPlayerManager().getPlayer(profile.id());

			int value;
			if (onlinePlayer != null) {
				value = onlinePlayer.getStatHandler().getStat(type.getOrCreateStat(key));
			} else {
				if (statsIndex == null) {
					sendError(ctx, "Index not loaded yet.");
					return 0;
				}
				String statKey = getStatTypeId(type) + "/" + getStatKeyString(key);
				value = statsIndex.getPlayerStat(profile.id(), statKey);
			}

			String message = formatStatMessage(profile.name(), verb, value, getReadableName(key), type);

			if (share) {
				broadcastAsPlayer(ctx, Text.literal(message));
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
	private int executeCustomStat(CommandContext<ServerCommandSource> ctx, Identifier statId, boolean share) {
		try {
			Identifier registeredId = findRegisteredCustomStat(statId);
			if (registeredId == null) {
				sendError(ctx, "Unknown custom stat: " + statId);
				return 0;
			}

			var profiles = GameProfileArgumentType.getProfileArgument(ctx, "target");
			if (profiles.isEmpty()) {
				sendError(ctx, "Player not found.");
				return 0;
			}
			var profile = profiles.iterator().next();

			MinecraftServer server = ctx.getSource().getServer();
			ServerPlayerEntity onlinePlayer = server.getPlayerManager().getPlayer(profile.id());

			int value;
			if (onlinePlayer != null) {
				value = onlinePlayer.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(registeredId));
			} else {
				if (statsIndex == null) {
					sendError(ctx, "Index not loaded yet.");
					return 0;
				}
				value = statsIndex.getPlayerStat(profile.id(), "minecraft:custom/" + statId);
			}

			String readableName = getCustomStatReadableName(statId);
			String formattedValue = formatCustomStatValue(statId.getPath(), value);
			String message = String.format("%s: §a%s§r %s", profile.name(), formattedValue, readableName);
			if (share) {
				broadcastAsPlayer(ctx, Text.literal(message));
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
	private <T> int executeTop(CommandContext<ServerCommandSource> ctx, StatType<T> type, T key, String verb,
			int page, boolean share) {
		if (statsIndex == null) {
			sendError(ctx, "Index not loaded yet.");
			return 0;
		}

		MinecraftServer server = ctx.getSource().getServer();
		String statKey = getStatTypeId(type) + "/" + getStatKeyString(key);
		Stat<T> stat = type.getOrCreateStat(key);

		List<StatsIndex.Entry> results = buildLeaderboard(server, statKey, stat);
		return displayLeaderboard(ctx, results, getReadableName(key), verb, type, page, share);
	}

	/**
	 * Executes a leaderboard lookup for custom stats.
	 */
	private int executeCustomTop(CommandContext<ServerCommandSource> ctx, Identifier statId, int page, boolean share) {
		if (statsIndex == null) {
			sendError(ctx, "Index not loaded yet.");
			return 0;
		}

		Identifier registeredId = findRegisteredCustomStat(statId);
		if (registeredId == null) {
			sendError(ctx, "Unknown custom stat: " + statId);
			return 0;
		}

		MinecraftServer server = ctx.getSource().getServer();
		String statKey = "minecraft:custom/" + statId;
		Stat<Identifier> stat = Stats.CUSTOM.getOrCreateStat(registeredId);

		List<StatsIndex.Entry> results = buildLeaderboard(server, statKey, stat);
		String readableName = getCustomStatReadableName(statId);

		return displayCustomLeaderboard(ctx, results, readableName, statId.getPath(), page, share);
	}

	// ==================== HELPER METHODS ====================

	/**
	 * Finds the exact Identifier object from the custom stat registry.
	 * This is required because StatType.getOrCreateStat() uses object identity for
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
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			UUID uuid = player.getUuid();
			int liveValue = player.getStatHandler().getStat(stat);

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
	 * Displays a paginated leaderboard.
	 */
	private <T> int displayLeaderboard(CommandContext<ServerCommandSource> ctx, List<StatsIndex.Entry> results,
			String itemName, String verb, StatType<T> type, int page, boolean share) {
		int start = (page - 1) * PAGE_SIZE;
		if (start >= results.size() || results.isEmpty()) {
			sendError(ctx, "No stats found (or page empty).");
			return 0;
		}

		int end = Math.min(start + PAGE_SIZE, results.size());
		List<StatsIndex.Entry> pageResults = results.subList(start, end);

		// Send header
		String header = formatTopHeader(verb, itemName, type, page);
		if (share) {
			broadcastAsPlayer(ctx, Text.literal(header));
		} else {
			sendWithShareButton(ctx, header);
		}

		// Send entries
		MinecraftServer server = ctx.getSource().getServer();
		for (int i = 0; i < pageResults.size(); i++) {
			StatsIndex.Entry entry = pageResults.get(i);
			int rank = start + i + 1;
			int value = entry.value;
			String name = getPlayerName(server, entry.uuid);

			Text line = Text.literal(String.format("#%d %s: §a%,d", rank, name, value));
			if (share) {
				ctx.getSource().getServer().getPlayerManager().broadcast(line, false);
			} else {
				ctx.getSource().sendFeedback(() -> line, false);
			}
		}

		return 1;
	}

	/**
	 * Gets a player's display name (online name, cached name, or truncated UUID).
	 */
	private String getPlayerName(MinecraftServer server, UUID uuid) {
		ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
		if (player != null) {
			return player.getName().getString();
		}
		String cached = statsIndex.getCachedName(uuid);
		return cached != null ? cached : uuid.toString().substring(0, 8) + "...";
	}

	/**
	 * Persists all stats for a player when they disconnect.
	 */
	private <T> void persistStats(StatType<T> statType, StatHandler statHandler, int playerId) {
		for (T key : statType.getRegistry()) {
			Stat<T> stat = statType.getOrCreateStat(key);
			int value = statHandler.getStat(stat);
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
	private int displayCustomLeaderboard(CommandContext<ServerCommandSource> ctx, List<StatsIndex.Entry> results,
			String itemName, String statPath, int page, boolean share) {
		int start = (page - 1) * PAGE_SIZE;
		if (start >= results.size() || results.isEmpty()) {
			sendError(ctx, "No stats found (or page empty).");
			return 0;
		}

		int end = Math.min(start + PAGE_SIZE, results.size());
		List<StatsIndex.Entry> pageResults = results.subList(start, end);

		// Send header
		String header = String.format("--- Top %s (Page %d) ---", itemName, page);

		if (share) {
			broadcastAsPlayer(ctx, Text.literal(header));
		} else {
			sendWithShareButton(ctx, header);
		}

		// Send entries with formatted values
		MinecraftServer server = ctx.getSource().getServer();
		for (int i = 0; i < pageResults.size(); i++) {
			StatsIndex.Entry entry = pageResults.get(i);
			int rank = start + i + 1;
			String formattedValue = formatCustomStatValue(statPath, entry.value);
			String name = getPlayerName(server, entry.uuid);

			Text line = Text.literal(String.format("#%d %s: §a%s", rank, name, formattedValue));
			if (share) {
				ctx.getSource().getServer().getPlayerManager().broadcast(line, false);
			} else {
				ctx.getSource().sendFeedback(() -> line, false);
			}
		}

		return 1;
	}

	private void sendWithShareButton(CommandContext<ServerCommandSource> ctx, String message) {

		String cmd = ctx.getInput();
		if (!cmd.startsWith("/")) {
			cmd = "/" + cmd;
		}
		String shareCommand = cmd + " true";

		Text shareButton = Text.literal(" [Share]")
				.styled(style -> style
						.withColor(Formatting.GREEN)
						.withClickEvent(new ClickEvent.RunCommand(shareCommand))
						.withHoverEvent(
								new HoverEvent.ShowText(Text.literal("Click to share in chat"))));

		ctx.getSource().sendFeedback(() -> Text.literal(message).append(shareButton), false);
	}

	private void broadcastAsPlayer(CommandContext<ServerCommandSource> ctx, Text message) {
		String playerName = ctx.getSource().getName();
		Text prefix = Text.literal("<" + playerName + "> ");
		Text fullMessage = prefix.copy().append(message);
		ctx.getSource().getServer().getPlayerManager().broadcast(fullMessage, false);
	}

	private void sendError(CommandContext<ServerCommandSource> ctx, String message) {
		ctx.getSource().sendFeedback(() -> Text.literal("§c" + message), false);
	}

	private <T> String formatStatMessage(String playerName, String verb, int value, String itemName, StatType<T> type) {
		if (type == Stats.CUSTOM) {
			return String.format("%s: §a%,d§r %s", playerName, value, itemName);
		} else if (type == Stats.KILLED_BY) {
			return String.format("%s was killed by §a%,d§r %s", playerName, value, itemName);
		} else if (type == Stats.KILLED) {
			return String.format("%s has killed §a%,d§r %s", playerName, value, itemName);
		} else {
			return String.format("%s has %s §a%,d§r %s", playerName, verb, value, itemName);
		}
	}

	private <T> String formatTopHeader(String verb, String itemName, StatType<T> type, int page) {
		if (type == Stats.CUSTOM) {
			return String.format("--- Top %s (Page %d) ---", itemName, page);
		} else if (type == Stats.KILLED_BY) {
			return String.format("--- Most Killed By %s (Page %d) ---", itemName, page);
		} else if (type == Stats.KILLED) {
			return String.format("--- Most %s Killed (Page %d) ---", itemName, page);
		} else {
			String capitalizedVerb = verb.substring(0, 1).toUpperCase() + verb.substring(1);
			return String.format("--- Most %s %s (Page %d) ---", itemName, capitalizedVerb, page);
		}
	}

	// ==================== STAT KEY UTILITIES ====================

	private <T> String getStatTypeId(StatType<T> type) {
		Identifier id = Registries.STAT_TYPE.getId(type);
		return id != null ? id.toString() : "minecraft:custom";
	}

	private <T> String getStatKey(Stat<T> stat) {
		return getStatTypeId(stat.getType()) + "/" + getStatKeyString(stat.getValue());
	}

	private <T> String getStatKeyString(T key) {
		if (key instanceof Block block) {
			return Registries.BLOCK.getId(block).toString();
		} else if (key instanceof Item item) {
			return Registries.ITEM.getId(item).toString();
		} else if (key instanceof EntityType<?> entityType) {
			return Registries.ENTITY_TYPE.getId(entityType).toString();
		} else if (key instanceof Identifier id) {
			return id.toString();
		}
		return key.toString();
	}

	private <T> String getReadableName(T key) {
		if (key instanceof Block block) {
			return block.getName().getString();
		} else if (key instanceof Item item) {
			return item.getName().getString();
		} else if (key instanceof EntityType<?> entityType) {
			return entityType.getName().getString();
		} else if (key instanceof Identifier id) {
			return id.getPath().replace("_", " ");
		}
		return key.toString();
	}
}