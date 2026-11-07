package hlvm.stats;

import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Read-only, in-memory stats of a previous world.
 *
 * The stats are read from the vanilla {@code stats/<uuid>.json} files of the old world.
 * Nothing is written to disk and the regular {@link StatsIndex} is never touched.
 *
 * Old UUIDs are translated to the UUIDs the players have now (what EasyAuth's
 * {@code forced-offline-uuid} produces: the offline UUID of the player's name):
 * <ul>
 * <li>Offline UUIDs (version 3) are kept as they are.</li>
 * <li>Floodgate (Bedrock) UUIDs and premium (random, version 4) UUIDs are translated to the offline UUID
 * of the player's name (Bedrock names carry the Floodgate prefix, e.g. ".Steve"). A premium player and the
 * offline account of the same name therefore end up as one player, and their values are added.</li>
 * <li>{@code uuid-map.json} can override any of that by hand.</li>
 * </ul>
 */
public class OldWorldStats {
    private static final String FLOODGATE_PREFIX = "00000000-0000-0000-0009-";

    // Stats that were renamed by Mojang between versions: old key -> current key
    private static final Map<String, String> STAT_ALIASES = Map.of(
            "minecraft:custom/minecraft:play_one_minute", "minecraft:custom/minecraft:play_time");

    private static final OldWorldStats EMPTY = new OldWorldStats();

    private final Map<String, Map<UUID, Integer>> byStat = new HashMap<>();
    private final Map<UUID, UUID> aliases = new HashMap<>(); // old uuid -> current uuid (only if they differ)
    private final Map<UUID, String> names = new HashMap<>(); // current uuid -> name
    private int players;

    private OldWorldStats() {
    }

    public static OldWorldStats empty() {
        return EMPTY;
    }

    /** True if at least one old player file was loaded. */
    public boolean hasData() {
        return players > 0;
    }

    public int playerCount() {
        return players;
    }

    /** Old value of a stat for a player (0 if none). Accepts current UUIDs and old Floodgate UUIDs. */
    public int get(UUID uuid, String statKey) {
        Map<UUID, Integer> byPlayer = byStat.get(statKey);
        if (byPlayer == null) {
            return 0;
        }
        UUID target = aliases.getOrDefault(uuid, uuid);
        return byPlayer.getOrDefault(target, 0);
    }

    /** All non-zero old values of a stat, keyed by current UUID. */
    public Map<UUID, Integer> all(String statKey) {
        Map<UUID, Integer> byPlayer = byStat.get(statKey);
        return byPlayer == null ? Collections.emptyMap() : Collections.unmodifiableMap(byPlayer);
    }

    /** Name known for a player of the old world, or null. */
    public String nameOf(UUID uuid) {
        return names.get(aliases.getOrDefault(uuid, uuid));
    }

    /** UUID a player has in the current world: the same one for Java, the offline one for Bedrock. */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    public static boolean isFloodgateUuid(UUID uuid) {
        return uuid.toString().startsWith(FLOODGATE_PREFIX);
    }

    /**
     * The UUID a player has in the current world. Floodgate and premium (version 4) UUIDs become the offline
     * UUID of {@code name}; offline UUIDs, and anything whose name is unknown, are returned unchanged.
     */
    public static UUID canonicalFor(UUID uuid, String name) {
        if (name == null || name.isEmpty()) {
            return uuid;
        }
        if (isFloodgateUuid(uuid) || uuid.version() == 4) {
            return offlineUuid(name);
        }
        return uuid;
    }

    /**
     * Loads the old world stats.
     *
     * @param oldWorldDir   folder holding the old {@code stats/*.json} (directly or inside a {@code stats}
     *                      subfolder), plus the optional {@code usercache.json} and {@code uuid-map.json}
     * @param usercaches    extra usercache.json files used to find the names of Bedrock players
     *                      (normally the current server's one)
     * @param fallbackNames last resort name lookup (UUID -> name), may return null
     * @param log           receives human readable messages (warnings, summary)
     */
    public static OldWorldStats load(Path oldWorldDir, List<Path> usercaches,
            Function<UUID, String> fallbackNames, Consumer<String> log) {
        OldWorldStats result = new OldWorldStats();

        Path statsDir = Files.isDirectory(oldWorldDir.resolve("stats")) ? oldWorldDir.resolve("stats") : oldWorldDir;
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(statsDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(files::add);
        } catch (IOException e) {
            log.accept("Could not read old world stats folder " + statsDir + ": " + e.getMessage());
            return result;
        }
        Collections.sort(files);

        // Names known for UUIDs: server usercache first, the old world's usercache overrides it
        Map<UUID, String> known = new HashMap<>();
        for (Path usercache : usercaches) {
            readUsercache(usercache, known, log);
        }
        readUsercache(oldWorldDir.resolve("usercache.json"), known, log);

        Map<String, String> overrides = readUuidMap(oldWorldDir.resolve("uuid-map.json"), log);

        int skipped = 0;
        List<String> unresolved = new ArrayList<>();
        // Offline-named files are merged first. Files from Floodgate UUIDs come last and only raise values
        // (never add up): the migration script leaves a copy under the offline UUID, and the original and the
        // copy hold the same data.
        List<Object[]> pending = new ArrayList<>(); // {Path file, UUID target, Boolean fromFloodgate}
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            UUID oldUuid;
            try {
                oldUuid = UUID.fromString(fileName.substring(0, fileName.length() - ".json".length()));
            } catch (IllegalArgumentException e) {
                continue; // not a player file (usercache.json, uuid-map.json, ...)
            }

            UUID target = oldUuid;
            String name = known.get(oldUuid);
            String override = overrides.get(oldUuid.toString());
            if (override != null) {
                try {
                    target = UUID.fromString(override);
                } catch (IllegalArgumentException notAUuid) {
                    name = override;
                    target = offlineUuid(override);
                }
            } else {
                if (name == null && fallbackNames != null) {
                    name = fallbackNames.apply(oldUuid);
                }
                if (isFloodgateUuid(oldUuid) && (name == null || name.isEmpty())) {
                    unresolved.add(oldUuid.toString());
                    skipped++;
                    continue;
                }
                target = canonicalFor(oldUuid, name);
            }

            if (!target.equals(oldUuid)) {
                result.aliases.put(oldUuid, target);
            }
            if (name != null && !name.isEmpty()) {
                result.names.putIfAbsent(target, name);
            }
            pending.add(new Object[] { file, target, isFloodgateUuid(oldUuid) });
        }
        pending.sort(Comparator.comparing(entry -> (Boolean) entry[2])); // stable: false before true

        for (Object[] entry : pending) {
            Path file = (Path) entry[0];
            UUID target = (UUID) entry[1];
            boolean fromFloodgate = (Boolean) entry[2];
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
                    JsonReader json = new JsonReader(reader)) {
                result.parse(json, target, fromFloodgate);
                result.players++;
            } catch (Exception e) {
                log.accept("Skipping old world stats file " + file.getFileName() + ": " + e.getMessage());
                skipped++;
            }
        }

        if (!unresolved.isEmpty()) {
            log.accept("Could not find a name for these Bedrock (Floodgate) UUIDs, their old stats are ignored. "
                    + "Add them to config/stats-command/old-world/uuid-map.json as \"uuid\": \".Name\": " + unresolved);
        }
        log.accept("Old world stats loaded: " + result.players + " players, " + skipped + " skipped.");
        return result;
    }

    private void parse(JsonReader reader, UUID target, boolean raiseOnly) throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (!name.equals("stats")) {
                reader.skipValue();
                continue;
            }
            reader.beginObject();
            while (reader.hasNext()) {
                String statType = reader.nextName();
                reader.beginObject();
                while (reader.hasNext()) {
                    String statKey = reader.nextName();
                    long value = reader.nextLong();
                    if (value <= 0) {
                        continue;
                    }
                    String fullKey = statType + "/" + statKey;
                    fullKey = STAT_ALIASES.getOrDefault(fullKey, fullKey);
                    byStat.computeIfAbsent(fullKey, k -> new HashMap<>())
                            .merge(target, (int) Math.min(value, Integer.MAX_VALUE),
                                    raiseOnly ? Math::max : OldWorldStats::addClamped);
                }
                reader.endObject();
            }
            reader.endObject();
        }
        reader.endObject();
    }

    static int addClamped(int a, int b) {
        return (int) Math.min((long) a + (long) b, Integer.MAX_VALUE);
    }

    /** Reads a vanilla usercache.json: [{"name": "...", "uuid": "...", "expiresOn": "..."}, ...] */
    private static void readUsercache(Path file, Map<UUID, String> into, Consumer<String> log) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
                JsonReader json = new JsonReader(reader)) {
            json.beginArray();
            while (json.hasNext()) {
                String name = null;
                String uuid = null;
                json.beginObject();
                while (json.hasNext()) {
                    String key = json.nextName();
                    if (key.equals("name")) {
                        name = json.nextString();
                    } else if (key.equals("uuid")) {
                        uuid = json.nextString();
                    } else {
                        json.skipValue();
                    }
                }
                json.endObject();
                if (name != null && !name.isEmpty() && uuid != null) {
                    try {
                        into.put(UUID.fromString(uuid), name);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            json.endArray();
        } catch (Exception e) {
            log.accept("Could not read " + file + ": " + e.getMessage());
        }
    }

    /** Reads uuid-map.json: {"old uuid": "new uuid or player name"} (keys are normalized UUID strings). */
    private static Map<String, String> readUuidMap(Path file, Consumer<String> log) {
        Map<String, String> map = new HashMap<>();
        if (!Files.isRegularFile(file)) {
            return map;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
                JsonReader json = new JsonReader(reader)) {
            json.beginObject();
            while (json.hasNext()) {
                String key = json.nextName();
                String value = json.nextString();
                try {
                    map.put(UUID.fromString(key).toString(), value);
                } catch (IllegalArgumentException e) {
                    log.accept("uuid-map.json: ignoring invalid UUID key " + key);
                }
            }
            json.endObject();
        } catch (Exception e) {
            log.accept("Could not read " + file + ": " + e.getMessage());
        }
        return map;
    }
}
