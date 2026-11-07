package hlvm.stats;

import java.util.Locale;

/**
 * Which world(s) a command should show. Written as an optional last word of the command:
 * {@code /stats top crafted minecraft:chest old}
 */
public enum StatsMode {
    /** Current world, with the old world's value in parentheses (default). */
    BOTH("both"),
    /** Current world only (the behaviour of the original mod). */
    CURRENT("current"),
    /** Old world only. */
    OLD("old"),
    /** Current world + old world added together. */
    TOTAL("total");

    public final String id;

    StatsMode(String id) {
        this.id = id;
    }

    /** Text placed between the leaderboard title and "(Page n)". */
    public String headerLabel() {
        switch (this) {
            case BOTH:
                return " (Old world)";
            case OLD:
                return " (Old world only)";
            case TOTAL:
                return " (Total)";
            default:
                return "";
        }
    }

    public static StatsMode fromId(String id) {
        for (StatsMode mode : values()) {
            if (mode.id.equals(id.toLowerCase(Locale.ROOT))) {
                return mode;
            }
        }
        return BOTH;
    }

    /**
     * Turns the command a player typed into the command that shares the result in chat:
     * appends " true", or switches an explicit "false" to "true". Works with the mode word before or after
     * the share flag (".. 2 old" -> ".. 2 old true", ".. false old" -> ".. true old").
     */
    public static String shareCommand(String input) {
        String command = input.trim();
        if (!command.startsWith("/")) {
            command = "/" + command;
        }
        String[] tokens = command.split("\\s+");
        for (int i = tokens.length - 1; i >= Math.max(0, tokens.length - 2); i--) {
            if (tokens[i].equals("true")) {
                return String.join(" ", tokens);
            }
            if (tokens[i].equals("false")) {
                tokens[i] = "true";
                return String.join(" ", tokens);
            }
        }
        return command + " true";
    }
}
