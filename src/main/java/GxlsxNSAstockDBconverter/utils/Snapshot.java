package main.java.GxlsxNSAstockDBconverter.utils;

import smv_logger.src.Logger.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import main.java.GxlsxNSAstockDBconverter.utils.StockDb.EquipmentState;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Values of every equipment in the workbooks at the last successful run (the "snapshot"), and the changes
 * between it and the database: only what changed in the app since then is written to the workbooks.
 * The snapshot of an equipment advances only once its changes are uploaded, so a failed run is retried.
 */
public final class Snapshot {
    private static final Logger LOGGER = Logger.getInstance();

    /** One change for the workbooks: a line of the changes format (see the README) and the equipment it comes from. */
    public static final class Change {
        public final int equipmentId;
        public final String sheet;
        public final String line;

        Change(int equipmentId, String sheet, JsonObject line) {
            this.equipmentId = equipmentId;
            this.sheet = sheet;
            this.line = line.toString();
        }
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int FORMAT = 1;

    /** File content: {"format":1, "equipment":[…]} */
    private static final class Content {
        int format;
        List<EquipmentState> equipment;
    }

    private Snapshot() {}

    /**
     * @return equipment id => values at the last successful run; null if the file does not exist (first run)
     * @throws IOException if the file exists but cannot be read: the caller must stop, recreating it would
     *                     lose the changes not uploaded yet
     */
    public static Map<Integer, EquipmentState> load(Path file) throws IOException {
        if (!Files.exists(file))
            return null;
        Content content;
        try {
            content = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<Content>() {}.getType());
        } catch (JsonParseException e) {
            throw new IOException("snapshot " + file + " unreadable: " + e.getMessage(), e);
        }
        if (content == null || content.format != FORMAT || content.equipment == null)
            throw new IOException("snapshot " + file + " unreadable: unknown format");
        Map<Integer, EquipmentState> states = new LinkedHashMap<>();
        for (EquipmentState state : content.equipment)
            states.put(state.id, state);
        return states;
    }

    /** Written to a temporary file (mode 600) in the same directory, synced, then renamed over the old one. */
    public static void save(Path file, Map<Integer, EquipmentState> states) throws IOException {
        Content content = new Content();
        content.format = FORMAT;
        content.equipment = new ArrayList<>(states.values());
        writeAtomically(file, GSON.toJson(content).getBytes(StandardCharsets.UTF_8));
    }

    /** Written to a temporary file (mode 600) in the same directory, synced, then renamed over the old one. */
    static void writeAtomically(Path file, byte[] data) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(dir, file.getFileName() + ".", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining())
                    channel.write(buffer);
                channel.force(true);
            }
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Changes between the snapshot and the database, deletions first:
     * - new equipment: every known column (the converter adds the row);
     * - equipment gone: its row is marked deleted;
     * - reference changed (case and spaces ignored): old row marked deleted, new row with every known column;
     * - type changed: nothing written, logged;
     * - otherwise the columns whose value changed; a value that disappeared leaves its cell untouched,
         */
    public static List<Change> diff(Map<Integer, EquipmentState> snapshot, Map<Integer, EquipmentState> current) {
        List<Change> deletions = new ArrayList<>();
        List<Change> edits = new ArrayList<>();
        Set<Integer> ids = new LinkedHashSet<>(snapshot.keySet());
        ids.addAll(current.keySet());
        for (int id : ids) {
            EquipmentState before = snapshot.get(id);
            EquipmentState now = current.get(id);
            if (before == null) {
                if (now.sheet == null)
                    LOGGER.LOG("New equipment \"" + now.reference + "\": type \"" + now.type + "\" has no sheet, not written", Logger.LEVEL.WARN);
                else
                    addAll(edits, now);
            } else if (now == null) {
                if (before.sheet != null)
                    deletions.add(deletion(before));
            } else if (!Objects.equals(before.type, now.type)) {
                LOGGER.LOG("Equipment \"" + now.reference + "\": type changed from \"" + before.type + "\" to \""
                        + now.type + "\", workbooks not updated", Logger.LEVEL.WARN);
            } else if (now.sheet == null) {
                // type without a sheet: never written
            } else if (!Spreadsheet.normalize(before.reference).equals(Spreadsheet.normalize(now.reference))) {
                deletions.add(deletion(before));
                addAll(edits, now);
            } else {
                for (Map.Entry<String, String> value : now.values.entrySet()) {
                    String old = before.values.get(value.getKey());
                    if (value.getValue().equals(old))
                        continue;
                    // snapshot of a version that wrote the status: the cell holds that status, keep it
                    if (value.getKey().equals(StockDb.STATE_COLUMN) && StockDb.LEGACY_STATUS_STATES.contains(old))
                        continue;
                    edits.add(edit(now, value.getKey(), value.getValue()));
                }
            }
        }
        deletions.addAll(edits);
        return deletions;
    }

    /**
     * Snapshot after a run: an equipment takes its database values if all its changes were uploaded
     * (or if it had none), it keeps its snapshot values otherwise, so that they are written at the next run.
     * @param uploadedSheets sheets of the workbooks uploaded during the run
     */
    public static Map<Integer, EquipmentState> advance(Map<Integer, EquipmentState> snapshot,
                                                       Map<Integer, EquipmentState> current,
                                                       Collection<Change> changes, Set<String> uploadedSheets) {
        Set<Integer> pending = new HashSet<>();
        for (Change change : changes)
            if (!uploadedSheets.contains(change.sheet))
                pending.add(change.equipmentId);
        Map<Integer, EquipmentState> next = new LinkedHashMap<>();
        Set<Integer> ids = new LinkedHashSet<>(snapshot.keySet());
        ids.addAll(current.keySet());
        for (int id : ids) {
            EquipmentState kept = pending.contains(id) ? snapshot.get(id) : current.get(id);
            if (kept != null)
                next.put(id, kept);
        }
        return next;
    }

    private static void addAll(List<Change> changes, EquipmentState state) {
        for (Map.Entry<String, String> value : state.values.entrySet())
            changes.add(edit(state, value.getKey(), value.getValue()));
    }

    private static Change edit(EquipmentState state, String column, String value) {
        JsonObject line = new JsonObject();
        line.addProperty("sheetName", state.sheet);
        line.addProperty("idCol", state.idCol);
        line.addProperty("idKey", state.reference);
        line.addProperty("editCol", column);
        line.addProperty("newVal", value);
        return new Change(state.id, state.sheet, line);
    }

    private static Change deletion(EquipmentState state) {
        JsonObject line = new JsonObject();
        line.addProperty("op", "delete");
        line.addProperty("sheetName", state.sheet);
        line.addProperty("idCol", state.idCol);
        line.addProperty("idKey", state.reference);
        return new Change(state.id, state.sheet, line);
    }
}
