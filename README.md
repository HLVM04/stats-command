# Stats Command

A simple Fabric mod that provides player statistics and leaderboards in one command.

## Overview

This mod allows players to view statistics view leaderboards for any tracked metric in the game. It is engineered for scalability, utilizing memory-mapped files and incremental indexing to ensure zero impact on server performance, even with potentially tens of thousands of unique player files. It uses the built-in vanilla stat system and can show stats for any player that have, at any time, been playing on the server.

## Features

- **Instant Lookups**: specific stats for any player (mined, crafted, broken, killed, dropped, etc.).
- **Leaderboards**: View top rankings for any category with `/stats top`.
- **Custom Stats**: Support for custom statistics registered by datapacks.
- **High Performance**:
  - Uses memory-mapped I/O for nanosecond-level access times.
  - Non-blocking, incremental indexing system.
  - Zero main-thread lag, suitable for large production servers.

## Usage

**Lookup a player:**
```
/stats <player> <category> <stat>
/stats Steve mined diamond_ore
```

**View leaderboard:**
```
/stats top <category> <stat> [page]
/stats top killed creeper
```

## Installation

Requires Fabric Loader and Fabric API. Place the mod .jar file in your server's `mods` folder.
Have been tested on 1.21.11
