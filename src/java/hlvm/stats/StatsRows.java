package hlvm.stats;

import java.util.*;
import java.util.function.UnaryOperator;

/**
 * Builds the rows of a leaderboard for a {@link StatsMode}. Pure logic (no Minecraft classes) so it can be
 * tested on its own.
 *
 * The same person can show up under several UUIDs (an old Floodgate UUID and the offline UUID it became, a
 * premium UUID and the offline UUID of the same name). Every UUID is first reduced to the one the player has
 * now, so one person is always one row.
 */
public final class StatsRows {
    private StatsRows() {
    }

    /** One line of a leaderboard: the player's value in the current world and in the old world. */
    public static final class Row {
        public final UUID uuid;
        public final int current;
        public final int old;

        public Row(UUID uuid, int current, int old) {
            this.uuid = uuid;
            this.current = current;
            this.old = old;
        }

        public long sortValue(StatsMode mode) {
            switch (mode) {
                case OLD:
                    return old;
                case TOTAL:
                    return (long) current + (long) old;
                default:
                    return current;
            }
        }
    }

    /**
     * @param currentRaw current world values by the UUID they are stored under (may include several UUIDs
     *                   of the same person, e.g. a Floodgate UUID and its migrated copy)
     * @param canonical  maps any UUID to the UUID the player has now
     * @param old        old world stats (already keyed by current UUIDs)
     * @param statKey    e.g. "minecraft:custom/minecraft:play_time"
     */
    public static List<Row> build(Map<UUID, Integer> currentRaw, UnaryOperator<UUID> canonical,
            OldWorldStats old, String statKey, StatsMode mode) {
        // Several UUIDs of one person in the current world are copies of the same data (the migration copies
        // the Floodgate stats to the offline UUID), so keep the highest value instead of adding them up.
        Map<UUID, Integer> currentValues = new HashMap<>();
        if (mode != StatsMode.OLD) {
            for (Map.Entry<UUID, Integer> entry : currentRaw.entrySet()) {
                currentValues.merge(canonical.apply(entry.getKey()), entry.getValue(), Math::max);
            }
        }

        Set<UUID> players = new HashSet<>();
        if (mode == StatsMode.BOTH || mode == StatsMode.CURRENT) {
            players.addAll(currentValues.keySet());
        } else if (mode == StatsMode.OLD) {
            players.addAll(old.all(statKey).keySet());
        } else {
            players.addAll(currentValues.keySet());
            players.addAll(old.all(statKey).keySet());
        }

        List<Row> rows = new ArrayList<>();
        for (UUID uuid : players) {
            Row row = new Row(uuid, currentValues.getOrDefault(uuid, 0),
                    mode == StatsMode.CURRENT ? 0 : old.get(uuid, statKey));
            if (row.sortValue(mode) > 0) {
                rows.add(row);
            }
        }

        // Highest first; UUID as tie-breaker so pages stay stable between commands
        rows.sort((a, b) -> {
            int cmp = Long.compare(b.sortValue(mode), a.sortValue(mode));
            return cmp != 0 ? cmp : a.uuid.compareTo(b.uuid);
        });
        return rows;
    }
}
