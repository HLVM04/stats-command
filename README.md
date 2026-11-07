# 📊 Stats Command

[![Modrinth Downloads](https://img.shields.io/modrinth/dt/stats-command?logo=modrinth&label=Modrinth%20downloads)](https://modrinth.com/mod/stats-command)

A simple Fabric mod that provides player statistics and leaderboards in one command.

## Overview

This mod allows players to view statistics and leaderboards for any tracked metric in the game. It uses the built-in vanilla stat system and can show stats for any player that has, at any time, played on the server. The index is stored in a sparse file-backed cache, so stat lookups should be super fast, with no noticable performance impact on the server.

## Features

- **Instant Lookups**: specific stats for any player (mined, crafted, broken, killed, dropped, etc.).
- **Leaderboards**: View top rankings for any category with `/stats top`.
- **Custom Stats**: Support for custom statistics registered by datapacks.
- **High Performance**:
  - Keeps indexed stats in memory while the server is running.
  - Stores only non-zero stat values on disk.
  - Non-blocking, incremental indexing system.
  - Suitable for large production servers.

## Usage

**Lookup a player:**
```
/stats <player> <category> <stat>
/stats Steve mined minecraft:diamond_ore
```

**View leaderboard:**
```
/stats top <category> <stat> [page]
/stats top killed minecraft:creeper
```

## Examples

Item and block stats:
```
/stats @s mined minecraft:stone
/stats @s mined minecraft:diamond_ore
/stats @s used minecraft:oak_planks
/stats @s crafted minecraft:crafting_table
/stats @s broken minecraft:diamond_pickaxe
/stats @s picked_up minecraft:iron_ingot
/stats @s dropped minecraft:cobblestone
```

Entity stats:
```
/stats @s killed minecraft:zombie
/stats @s killed minecraft:creeper
/stats @s killed_by minecraft:skeleton
/stats top killed minecraft:ender_dragon
```

Useful custom stats:
```
/stats @s custom minecraft:play_time
/stats @s custom minecraft:time_since_death
/stats @s custom minecraft:deaths
/stats @s custom minecraft:mob_kills
/stats @s custom minecraft:player_kills
/stats @s custom minecraft:damage_dealt
/stats @s custom minecraft:damage_taken
/stats @s custom minecraft:jump
/stats @s custom minecraft:drop
/stats @s custom minecraft:sneak_time
/stats @s custom minecraft:animals_bred
/stats @s custom minecraft:fish_caught
/stats @s custom minecraft:traded_with_villager
```

Total distance stats:
```
/stats @s custom minecraft:walk_one_cm
/stats @s custom minecraft:sprint_one_cm
/stats @s custom minecraft:swim_one_cm
/stats @s custom minecraft:fall_one_cm
/stats @s custom minecraft:climb_one_cm
/stats @s custom minecraft:fly_one_cm
/stats @s custom minecraft:boat_one_cm
/stats @s custom minecraft:minecart_one_cm
/stats @s custom minecraft:horse_one_cm
/stats @s custom minecraft:aviate_one_cm
```

Item and block stats are tracked per item or block. Use the same category with any valid item or block ID:
```
/stats top mined minecraft:stone
/stats top used minecraft:oak_planks
/stats top broken minecraft:diamond_pickaxe
/stats top picked_up minecraft:diamond
/stats top dropped minecraft:dirt
```

Minecraft's built-in stat keys are documented on the [Minecraft Wiki statistics page](https://minecraft.wiki/w/Statistics).

## Old world stats

The mod can show the stats of a previous world next to the current ones.

**Setup**

1. Copy the old world's `players/stats/*.json` files into `config/stats-command/old-world/stats/` on the server (the folder is created on first start).
2. Restart the server. The log prints `Old world stats loaded: N players`.

Nothing is written to the old world data and the regular index is not touched; old stats are read into memory at start.

**Players**

- Java players keep the same UUID in both worlds (offline mode), so nothing else is needed.
- Bedrock (Floodgate) players are matched by name: their old Floodgate UUID is translated to the offline UUID of their name (for example `.Steve`), which is what EasyAuth's `forced-offline-uuid` produces. Names are read from the server's `usercache.json`; you can also drop the old world's `usercache.json` next to the stats folder (`config/stats-command/old-world/usercache.json`).
- Anything that cannot be matched can be set by hand in `config/stats-command/old-world/uuid-map.json`, mapping an old UUID to a new UUID or to a player name:
  ```json
  {
    "00000000-0000-0000-0009-0123456789ab": ".SomeBedrockPlayer"
  }
  ```
  Players whose name cannot be found are listed in the server log and ignored.

**Modes**

Add one of these words at the end of any `/stats` command (after the page and the optional `true`, or in place of `true`):

| Mode | Shows |
|------|-------|
| `both` (default) | the current world, with the old world's value in parentheses |
| `current` | the current world only |
| `old` | the old world only |
| `total` | both worlds added together |

```
/stats top crafted minecraft:chest
--- Most Chest Crafted (Old world) (Page 1) ---
#1 marcel_mrm: 1,824 (3,243)
#2 Eddd64: 631 (0)

/stats top crafted minecraft:chest 2 total
/stats top custom minecraft:play_time old
/stats Steve mined minecraft:stone total
```

`both` ranks by the current world. `old` and `total` also include players that only played in the old world. Without old world data, `both` behaves exactly like `current`.

## Installation

Requires Fabric Loader and Fabric API. Place the mod `.jar` file in your server's `mods` folder.
