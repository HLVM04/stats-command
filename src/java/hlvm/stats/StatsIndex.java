package hlvm.stats;

import com.google.gson.stream.JsonReader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class StatsIndex {
    // Initial size for the mapped file (128 MB)
    // 30k players * 1000 stats * 4 bytes = ~120MB, so 128MB is a good start.
    private static final int INITIAL_FILE_SIZE = 128 * 1024 * 1024;
    private static final int GROW_SIZE = 64 * 1024 * 1024; // Grow by 64MB chunks
    private static final int BYTES_PER_INT = 4;

    private final Path indexDir;
    private final Map<UUID, Integer> playerIds = new ConcurrentHashMap<>();
    private final List<UUID> playerIdList = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Integer> statIds = new ConcurrentHashMap<>();

    // Username cache: UUID -> last known username
    private final Map<UUID, String> playerNames = new ConcurrentHashMap<>();

    // File modification times for incremental indexing: Filename -> Last Modified
    // Time
    private final Map<String, Long> lastIndexedTime = new ConcurrentHashMap<>();

    private MappedByteBuffer mappedBuffer;
    private FileChannel fileChannel;
    private long currentFileSize;

    private final Object resizeLock = new Object();
    private final AtomicBoolean isSyncing = new AtomicBoolean(false);

    public StatsIndex(MinecraftServer server) {
        this.indexDir = server.getSavePath(WorldSavePath.ROOT).resolve("stats_index");
        try {
            Files.createDirectories(indexDir);
            loadMappings();
            loadIncrementalCache();
            initMappedFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void initMappedFile() throws IOException {
        File dataFile = indexDir.resolve("data.bin").toFile();
        this.currentFileSize = dataFile.exists() ? dataFile.length() : INITIAL_FILE_SIZE;
        if (this.currentFileSize < INITIAL_FILE_SIZE) {
            this.currentFileSize = INITIAL_FILE_SIZE;
        }

        // We keep the RandomAccessFile open implicitly via the FileChannel.
        @SuppressWarnings("resource")
        RandomAccessFile raf = new RandomAccessFile(dataFile, "rw");
        raf.setLength(currentFileSize);
        this.fileChannel = raf.getChannel();
        this.mappedBuffer = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, currentFileSize);
    }

    // We will stick to a fixed large stride for row alignment to avoid complex file
    // rewriting.
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
                File statsDir = server.getSavePath(WorldSavePath.STATS).toFile();
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
        if (playerId == -1 || playerId >= MAX_PLAYERS_CAPACITY)
            return;

        // Ensure file is large enough
        ensureFileSize(statId, playerId);

        long offset = ((long) statId * MAX_PLAYERS_CAPACITY + playerId) * BYTES_PER_INT;

        try {
            // MappedByteBuffer is thread-safe for individual putInt at different storage
            // but we should be careful if we are resizing.
            // Since we are just putting an int, it's atomic enough for stats.
            mappedBuffer.putInt((int) offset, value);
        } catch (IndexOutOfBoundsException e) {
            // Should be caught by ensureFileSize, but just in case
            synchronized (resizeLock) {
                ensureFileSize(statId, playerId);
                mappedBuffer.putInt((int) offset, value);
            }
        }
    }

    // Check if the file needs to grow to accommodate this statId
    private void ensureFileSize(int statId, int playerId) {
        long requiredPos = ((long) statId * MAX_PLAYERS_CAPACITY + playerId + 1) * BYTES_PER_INT;

        if (requiredPos > currentFileSize) {
            synchronized (resizeLock) {
                if (requiredPos > currentFileSize) {
                    try {
                        long newSize = Math.max(requiredPos + GROW_SIZE, currentFileSize + GROW_SIZE);

                        mappedBuffer.force(); // Flush old

                        // Close old channel to release FD
                        if (this.fileChannel != null) {
                            try {
                                this.fileChannel.close();
                            } catch (IOException ignored) {
                            }
                        }

                        @SuppressWarnings("resource")
                        RandomAccessFile raf = new RandomAccessFile(indexDir.resolve("data.bin").toFile(), "rw");
                        raf.setLength(newSize);
                        this.fileChannel = raf.getChannel();

                        // Create NEW map
                        this.mappedBuffer = this.fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, newSize);
                        this.currentFileSize = newSize;

                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    public void close() {
        try {
            if (mappedBuffer != null) {
                mappedBuffer.force();
            }
            if (fileChannel != null) {
                fileChannel.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // --- 5. READING (Leaderboard) ---
    public List<Entry> getTop(String statKey, int page, int pageSize) {
        if (!statIds.containsKey(statKey))
            return Collections.emptyList();
        int statId = statIds.get(statKey);

        long startOffset = (long) statId * MAX_PLAYERS_CAPACITY * BYTES_PER_INT;
        List<Entry> allEntries = new ArrayList<>();

        // Ensure we don't read past file even if valid statId (rare)
        if (startOffset >= currentFileSize) {
            return Collections.emptyList();
        }

        // We can just iterate the buffer
        // For 30k (or 100k) players, checking them all is fast in memory (sub 1ms).
        for (int pId = 0; pId < playerIdList.size(); pId++) {
            long offset = startOffset + ((long) pId * BYTES_PER_INT);
            int val = mappedBuffer.getInt((int) offset);
            if (val > 0) {
                allEntries.add(new Entry(playerIdList.get(pId), val));
            }
        }

        allEntries.sort((e1, e2) -> Integer.compare(e2.value, e1.value));

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

        long offset = ((long) statId * MAX_PLAYERS_CAPACITY + playerId) * BYTES_PER_INT;

        if (offset + BYTES_PER_INT > currentFileSize) {
            return 0;
        }

        return mappedBuffer.getInt((int) offset);
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