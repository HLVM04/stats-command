package hlvm.stats;

import com.google.gson.stream.JsonReader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class StatsIndex {
    private static final int RECORD_BYTES = Integer.BYTES * 3;
    private static final long COMPACT_AT_BYTES = 1024L * 1024L * 1024L;

    private final Path indexDir;
    private final Map<UUID, Integer> playerIds = new ConcurrentHashMap<>();
    private final List<UUID> playerIdList = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Integer> statIds = new ConcurrentHashMap<>();
    private final Map<Integer, Map<Integer, Integer>> statsByStat = new ConcurrentHashMap<>();
    private final Map<Integer, List<Entry>> topCache = new ConcurrentHashMap<>();

    // Username cache: UUID -> last known username
    private final Map<UUID, String> playerNames = new ConcurrentHashMap<>();

    // File modification times for incremental indexing: Filename -> Last Modified
    // Time
    private final Map<String, Long> lastIndexedTime = new ConcurrentHashMap<>();

    private DataOutputStream recordWriter;
    private long recordFileSize;
    private final Object ioLock = new Object();
    private final AtomicBoolean isSyncing = new AtomicBoolean(false);

    public StatsIndex(MinecraftServer server) {
        this.indexDir = server.getWorldPath(LevelResource.ROOT).resolve("stats_index");
        try {
            Files.createDirectories(indexDir);
            loadMappings();
            loadIncrementalCache();
            initStorage();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void initStorage() throws IOException {
        Path v2File = indexDir.resolve("index.v2");
        if (Files.exists(v2File)) {
            loadV2(v2File);
        } else {
            migrateV1DataBin();
            compact();
        }

        if (recordWriter != null) {
            recordWriter.close();
        }
        this.recordFileSize = Files.exists(v2File) ? Files.size(v2File) : 0;
        this.recordWriter = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(v2File.toFile(), true)));
    }

    // Used only to read old data.bin databases.
    private static final int MAX_PLAYERS_CAPACITY = 100_000;

    // --- 1. MAPPING LOADING ---
    private void loadMappings() throws IOException {
        // Load player UUIDs
        File playersFile = indexDir.resolve("players.map").toFile();
        if (playersFile.exists()) {
            try (Scanner scanner = new Scanner(playersFile)) {
                int id = 0;
                while (scanner.hasNext()) {
                    String line = scanner.next();
                    if (!line.isEmpty()) {
                        try {
                            UUID uuid = UUID.fromString(line);
                            playerIds.put(uuid, id);
                            playerIdList.add(uuid);
                            id++;
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
            }
        }

        // Load stat keys
        File statsFile = indexDir.resolve("stats.map").toFile();
        if (statsFile.exists()) {
            try (Scanner scanner = new Scanner(statsFile)) {
                int id = 0;
                while (scanner.hasNext()) {
                    String key = scanner.next();
                    statIds.put(key, id);
                    id++;
                }
            }
        }

        // Load username cache
        File namesFile = indexDir.resolve("names.map").toFile();
        if (namesFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(namesFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(":", 2);
                    if (parts.length == 2) {
                        try {
                            UUID uuid = UUID.fromString(parts[0]);
                            playerNames.put(uuid, parts[1]);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
            }
        }
    }

    private void loadIncrementalCache() {
        File cacheFile = indexDir.resolve("incremental.cache").toFile();
        if (!cacheFile.exists())
            return;

        try (Scanner scanner = new Scanner(cacheFile)) {
            while (scanner.hasNext()) {
                String line = scanner.next();
                String[] parts = line.split("=");
                if (parts.length == 2) {
                    lastIndexedTime.put(parts[0], Long.parseLong(parts[1]));
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to load incremental cache: " + e.getMessage());
        }
    }

    private void loadV2(Path v2File) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(v2File.toFile())))) {
            while (true) {
                try {
                    int statId = in.readInt();
                    int playerId = in.readInt();
                    int value = in.readInt();
                    putInMemory(playerId, statId, value);
                } catch (EOFException done) {
                    return;
                }
            }
        }
    }

    private void migrateV1DataBin() throws IOException {
        Path dataFile = indexDir.resolve("data.bin");
        if (!Files.exists(dataFile))
            return;

        long fileSize = Files.size(dataFile);
        try (RandomAccessFile raf = new RandomAccessFile(dataFile.toFile(), "r");
                FileChannel channel = raf.getChannel()) {
            ByteBuffer row = ByteBuffer.allocate(playerIdList.size() * Integer.BYTES);
            for (int statId = 0; statId < statIds.size(); statId++) {
                long offset = (long) statId * MAX_PLAYERS_CAPACITY * Integer.BYTES;
                if (offset >= fileSize)
                    break;

                row.clear();
                int bytesToRead = (int) Math.min(row.capacity(), fileSize - offset);
                row.limit(bytesToRead);
                while (row.hasRemaining()) {
                    if (channel.read(row, offset + row.position()) == -1) {
                        break;
                    }
                }
                row.flip();

                for (int playerId = 0; playerId < playerIdList.size() && row.remaining() >= Integer.BYTES; playerId++) {
                    int value = row.getInt();
                    if (value > 0) {
                        putInMemory(playerId, statId, value);
                    }
                }
            }
        }
    }

    private void putInMemory(int playerId, int statId, int value) {
        if (playerId < 0 || playerId >= playerIdList.size())
            return;

        if (value > 0) {
            statsByStat.computeIfAbsent(statId, ignored -> new ConcurrentHashMap<>()).put(playerId, value);
        } else {
            Map<Integer, Integer> byPlayer = statsByStat.get(statId);
            if (byPlayer != null) {
                byPlayer.remove(playerId);
            }
        }
        topCache.remove(statId);
    }

    private void appendRecord(int playerId, int statId, int value) throws IOException {
        if (recordFileSize > COMPACT_AT_BYTES) {
            compact();
        }
        recordWriter.writeInt(statId);
        recordWriter.writeInt(playerId);
        recordWriter.writeInt(value);
        recordFileSize += RECORD_BYTES;
    }

    private synchronized void compact() throws IOException {
        Path v2File = indexDir.resolve("index.v2");
        Path tmpFile = indexDir.resolve("index.v2.tmp");

        if (recordWriter != null) {
            recordWriter.close();
            recordWriter = null;
        }

        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(tmpFile.toFile())))) {
            for (Map.Entry<Integer, Map<Integer, Integer>> statEntry : statsByStat.entrySet()) {
                int statId = statEntry.getKey();
                for (Map.Entry<Integer, Integer> playerEntry : statEntry.getValue().entrySet()) {
                    out.writeInt(statId);
                    out.writeInt(playerEntry.getKey());
                    out.writeInt(playerEntry.getValue());
                }
            }
        }

        try {
            Files.move(tmpFile, v2File, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmpFile, v2File, StandardCopyOption.REPLACE_EXISTING);
        }
        recordFileSize = Files.size(v2File);
        recordWriter = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(v2File.toFile(), true)));
    }

    private void saveIncrementalCache() {
        try (PrintWriter out = new PrintWriter(new FileWriter(indexDir.resolve("incremental.cache").toFile()))) {
            for (Map.Entry<String, Long> entry : lastIndexedTime.entrySet()) {
                out.println(entry.getKey() + "=" + entry.getValue());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // --- 2. ASYNC SYNC ---
    public void syncAll(MinecraftServer server) {
        if (isSyncing.getAndSet(true))
            return;

        new Thread(() -> {
            try {
                System.out.println("[PlayerStats] Starting incremental stat indexing...");
                File statsDir = server.getWorldPath(LevelResource.PLAYER_STATS_DIR).toFile();
                File[] files = statsDir.listFiles((dir, name) -> name.endsWith(".json"));

                if (files == null) {
                    isSyncing.set(false);
                    return;
                }

                int count = 0;
                int skipped = 0;

                for (File file : files) {
                    try {
                        String filename = file.getName();
                        long lastMod = file.lastModified();

                        // Check if we need to update this file
                        if (lastIndexedTime.containsKey(filename) && lastIndexedTime.get(filename) >= lastMod) {
                            skipped++;
                            continue;
                        }

                        String uuidStr = filename.replace(".json", "");
                        UUID uuid = UUID.fromString(uuidStr);

                        int pid = getOrCreatePlayerId(uuid);
                        if (pid == -1) {
                            System.err.println("[PlayerStats] Max players reached! Cannot index " + uuidStr);
                            continue;
                        }

                        try (JsonReader reader = new JsonReader(new FileReader(file))) {
                            parseJsonStat(reader, pid);
                        }

                        lastIndexedTime.put(filename, lastMod);
                        count++;

                        // Periodically save cache in case of crash
                        if (count % 100 == 0) {
                            saveIncrementalCache();
                        }

                    } catch (Exception e) {
                        System.err.println("Failed to index file: " + file.getName());
                    }
                }

                // Final save
                saveIncrementalCache();
                synchronized (ioLock) {
                    compact();
                }

                System.out.println(
                        "[PlayerStats] Indexing complete! Processed " + count + ", Skipped " + skipped + " players.");
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                isSyncing.set(false);
            }
        }).start();
    }

    private void parseJsonStat(JsonReader reader, int pid) throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (name.equals("stats")) {
                reader.beginObject();
                while (reader.hasNext()) {
                    String statType = reader.nextName();
                    reader.beginObject();
                    while (reader.hasNext()) {
                        String statKey = reader.nextName();
                        int value = reader.nextInt();

                        String fullKey = statType + "/" + statKey;
                        int sid = getOrCreateStatId(fullKey);
                        updateStat(pid, sid, value);
                    }
                    reader.endObject();
                }
                reader.endObject();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
    }

    // --- 3. ID MANAGEMENT ---
    public synchronized int getOrCreatePlayerId(UUID uuid) {
        if (playerIds.containsKey(uuid))
            return playerIds.get(uuid);

        // Check hard limit with new stride
        if (playerIdList.size() >= MAX_PLAYERS_CAPACITY) {
            return -1;
        }

        int newId = playerIds.size();
        playerIds.put(uuid, newId);
        playerIdList.add(uuid);
        appendToFile("players.map", uuid.toString());
        return newId;
    }

    public synchronized int getOrCreateStatId(String statKey) {
        if (statIds.containsKey(statKey))
            return statIds.get(statKey);
        int newId = statIds.size();

        statIds.put(statKey, newId);
        appendToFile("stats.map", statKey);
        return newId;
    }

    // --- USERNAME CACHE ---
    public void cachePlayerName(UUID uuid, String name) {
        String oldName = playerNames.get(uuid);
        if (!name.equals(oldName)) {
            playerNames.put(uuid, name);
            saveNamesCache();
        }
    }

    public String getCachedName(UUID uuid) {
        return playerNames.get(uuid);
    }

    private synchronized void saveNamesCache() {
        try (PrintWriter out = new PrintWriter(new FileWriter(indexDir.resolve("names.map").toFile()))) {
            for (Map.Entry<UUID, String> entry : playerNames.entrySet()) {
                out.println(entry.getKey().toString() + ":" + entry.getValue());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Get all known player UUIDs (for live stat merging)
    public Set<UUID> getAllPlayerUUIDs() {
        return new HashSet<>(playerIds.keySet());
    }

    private void appendToFile(String filename, String content) {
        try (FileWriter fw = new FileWriter(indexDir.resolve(filename).toFile(), true);
                PrintWriter out = new PrintWriter(new BufferedWriter(fw))) {
            out.println(content);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // --- 4. BINARY WRITING --
    public void updateStat(int playerId, int statId, int value) {
        if (playerId == -1 || playerId >= playerIdList.size())
            return;

        synchronized (ioLock) {
            putInMemory(playerId, statId, value);
            try {
                appendRecord(playerId, statId, value);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void close() {
        synchronized (ioLock) {
            try {
                if (recordWriter != null) {
                    recordWriter.flush();
                }
                compact();
                if (recordWriter != null) {
                    recordWriter.close();
                    recordWriter = null;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // --- 5. READING (Leaderboard) ---
    public List<Entry> getTop(String statKey, int page, int pageSize) {
        if (!statIds.containsKey(statKey))
            return Collections.emptyList();
        int statId = statIds.get(statKey);
        List<Entry> allEntries = topCache.computeIfAbsent(statId, this::buildTop);

        int start = (page - 1) * pageSize;
        if (start >= allEntries.size())
            return Collections.emptyList();

        int end = Math.min(start + pageSize, allEntries.size());
        return new ArrayList<>(allEntries.subList(start, end));
    }

    // --- 6. SINGLE PLAYER LOOKUP ---
    public int getPlayerStat(UUID playerUuid, String statKey) {
        if (!playerIds.containsKey(playerUuid) || !statIds.containsKey(statKey)) {
            return 0;
        }
        int playerId = playerIds.get(playerUuid);
        int statId = statIds.get(statKey);

        Map<Integer, Integer> byPlayer = statsByStat.get(statId);
        return byPlayer == null ? 0 : byPlayer.getOrDefault(playerId, 0);
    }

    private List<Entry> buildTop(int statId) {
        Map<Integer, Integer> byPlayer = statsByStat.get(statId);
        if (byPlayer == null || byPlayer.isEmpty()) {
            return Collections.emptyList();
        }

        List<Entry> allEntries = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : byPlayer.entrySet()) {
            int playerId = entry.getKey();
            if (playerId >= 0 && playerId < playerIdList.size()) {
                allEntries.add(new Entry(playerIdList.get(playerId), entry.getValue()));
            }
        }
        allEntries.sort((e1, e2) -> Integer.compare(e2.value, e1.value));
        return allEntries;
    }

    public static class Entry {
        public final UUID uuid;
        public int value;

        public Entry(UUID u, int v) {
            this.uuid = u;
            this.value = v;
        }
    }
}
