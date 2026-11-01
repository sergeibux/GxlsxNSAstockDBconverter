package main.java.GxlsxNSAstockDBconverter.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import main.java.GxlsxNSAstockDBconverter.utils.StockDb.SheetColumns;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Import from the workbooks into the database, run right after the workbooks were updated from the database:
 * they should then hold the same values as the database, so any difference left is a change made by hand in
 * the workbook. It is written to the database through the API:
 * - equipment unknown to the database (reference compared case and spaces ignored): created;
 * - known equipment: the columns whose meaning differs from the database are updated;
 * - rows marked deleted in the app, the free slot row ("Identification Libre"), rows without reference: ignored;
 * - nothing is ever deleted in the database; loans are never created nor ended.
 * The app keeps priority: the rows are sent with the start of the previous run as lastUpdate, the server keeps
 * any row changed in the app since then ("skipped", imported at the next run if still different).
 */
public final class WorkbookImport {

    static final String OWNER_COLUMN = "Propriétaire";
    static final String CLUB_OWNER = "Club NSA";
    /** Owner text of the free slot rows of the BLOCS sheet: not an equipment. */
    static final String FREE_SLOT_OWNER = "Identification Libre";
    /** Place of the equipment owned by members (created if missing); such equipment is not lendable. */
    static final String HOME_PLACE = "Maison";
    /** Characteristic of an equipment created without one (the database requires one). */
    static final String NO_FEATURE = "Non renseigné";
    /** Stock of an equipment created without a known one (equipment.stock is required). */
    static final String DEFAULT_STOCK = "Milieu naturel";
    static final String AVAILABLE = "disponible";
    static final String TO_CHECK = "à contrôler";
    static final String UNAVAILABLE = "non disponible";
    /** State texts (case and spaces ignored) => status; any other text is a place name (status available). */
    static final Map<String, String> STATUS_OF_STATE = new HashMap<>();
    static {
        for (String state : List.of("En révision", "En réparation"))
            STATUS_OF_STATE.put(Spreadsheet.normalize(state), TO_CHECK);
        for (String state : List.of("Non disponible", "Non conforme", "Villa - Non coforme", "Retiré sur demande"))
            STATUS_OF_STATE.put(Spreadsheet.normalize(state), UNAVAILABLE);
    }
    /** At most this many changes per upload (the server accepts 500). */
    private static final int MAX_CHANGES_PER_UPLOAD = 400;
    private static final ZoneId CLUB_ZONE = ZoneId.of("Europe/Paris");
    private static final DataFormatter FORMATTER = new DataFormatter();
    private static final DateTimeFormatter TEXT_DATE =
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT);

    private WorkbookImport() {}

    /* ---------- Database ---------- */

    /** Rows of the database the import compares with. */
    public static final class Db {
        final Map<String, Integer> typeIds = new HashMap<>();             // equipmentType.name => id
        final Map<Integer, String> stocks = new LinkedHashMap<>();        // id => name
        final Map<Integer, Feature> features = new LinkedHashMap<>();
        final Map<Integer, String> places = new LinkedHashMap<>();        // id => name
        final Map<Integer, Equipment> equipment = new LinkedHashMap<>();

        /** @return lastUpdate of the equipment, null if it does not exist */
        public Long lastUpdate(int equipmentId) {
            Equipment e = equipment.get(equipmentId);
            return e == null ? null : e.lastUpdate;
        }
    }

    static final class Feature {
        final int id, type;
        final String text;

        Feature(int id, int type, String text) {
            this.id = id;
            this.type = type;
            this.text = text;
        }
    }

    static final class Equipment {
        int id, type, stock, feature;
        String reference, status;
        long lastRevisionTs, lastUpdate;
        Integer place;              // last equipmentPlace row, null if none
        long placeLastUpdate;
        boolean loaned;
    }

    /** Reads the database read-only, in one read transaction. */
    public static Db readDb(String dbFile) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(10_000);
        Db db = new Db();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile, config.toProperties())) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                try (ResultSet rs = st.executeQuery("SELECT id, name FROM equipmentType")) {
                    while (rs.next())
                        db.typeIds.put(rs.getString(2), rs.getInt(1));
                }
                try (ResultSet rs = st.executeQuery("SELECT id, name FROM equipmentStock ORDER BY id")) {
                    while (rs.next())
                        db.stocks.put(rs.getInt(1), rs.getString(2));
                }
                try (ResultSet rs = st.executeQuery("SELECT id, equipmentType, feature FROM equipmentTypeFeature ORDER BY id")) {
                    while (rs.next())
                        db.features.put(rs.getInt(1), new Feature(rs.getInt(1), rs.getInt(2), rs.getString(3)));
                }
                try (ResultSet rs = st.executeQuery("SELECT id, name FROM places ORDER BY id")) {
                    while (rs.next())
                        db.places.put(rs.getInt(1), rs.getString(2));
                }
                try (ResultSet rs = st.executeQuery("SELECT id, reference, type, status, stock, feature, lastRevisionTs,"
                        + " lastUpdate FROM equipment ORDER BY id")) {
                    while (rs.next()) {
                        Equipment e = new Equipment();
                        e.id = rs.getInt(1);
                        e.reference = rs.getString(2);
                        e.type = rs.getInt(3);
                        e.status = rs.getString(4);
                        e.stock = rs.getInt(5);          // TEXT column holding the id
                        e.feature = rs.getInt(6);
                        e.lastRevisionTs = rs.getLong(7);
                        e.lastUpdate = rs.getLong(8);
                        db.equipment.put(e.id, e);
                    }
                }
                try (ResultSet rs = st.executeQuery("SELECT equipment, place, lastUpdate FROM equipmentPlace ORDER BY id")) {
                    while (rs.next()) {
                        Equipment e = db.equipment.get(rs.getInt(1));
                        if (e != null) {
                            e.place = rs.getInt(2);
                            e.placeLastUpdate = rs.getLong(3);
                        }
                    }
                }
                try (ResultSet rs = st.executeQuery("SELECT DISTINCT equipment FROM equipmentMember")) {
                    while (rs.next()) {
                        Equipment e = db.equipment.get(rs.getInt(1));
                        if (e != null)
                            e.loaned = true;
                    }
                }
            } finally {
                c.rollback();
            }
        }
        return db;
    }

    /* ---------- Workbooks ---------- */

    /** One equipment row of a sheet; null fields: empty or unreadable cell. */
    public static final class SheetRow {
        String sheet, type, reference, owner, state, feature, stock;
        LocalDate lastRevision;
        String lastRevisionText;    // text of the cell when it is not a date (logged)
        int rowNum;                 // 1-based, for the logs
    }

    /**
     * Equipment rows of the sheets of a workbook that have an equipment type (StockDb.SHEETS).
     * A reference found on several rows of a sheet is ignored (logged): its row cannot be told.
     */
    public static List<SheetRow> readRows(Workbook workbook) {
        List<SheetRow> rows = new ArrayList<>();
        for (Map.Entry<String, SheetColumns> type : StockDb.SHEETS.entrySet()) {
            Sheet sheet = workbook.getSheet(type.getValue().sheet);
            if (sheet != null)
                rows.addAll(readSheet(sheet, type.getKey(), type.getValue()));
        }
        return rows;
    }

    private static List<SheetRow> readSheet(Sheet sheet, String type, SheetColumns columns) {
        List<SheetRow> rows = new ArrayList<>();
        Row header = null;
        int idCol = -1;
        for (Row row : sheet) {
            idCol = column(row, columns.idCol);
            if (idCol >= 0) {
                header = row;
                break;
            }
        }
        if (header == null) {
            Logger.ERROR("Column \"" + columns.idCol + "\" not found in sheet " + sheet.getSheetName() + ": not imported");
            return rows;
        }
        int stateCol = column(header, StockDb.STATE_COLUMN);
        int featureCol = column(header, columns.feature);
        int revisionCol = columns.lastRevision == null ? -1 : column(header, columns.lastRevision);
        int stockCol = columns.stock == null ? -1 : column(header, columns.stock);
        int ownerCol = column(header, OWNER_COLUMN);
        String deleted = Spreadsheet.normalize(Spreadsheet.DELETED_MARK);
        String freeSlot = Spreadsheet.normalize(FREE_SLOT_OWNER);

        Map<String, Integer> seen = new HashMap<>();
        for (int r = header.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            String reference = text(row, idCol);
            if (reference == null || Spreadsheet.normalize(reference).startsWith(deleted))
                continue;
            String owner = text(row, ownerCol);
            if (owner != null && Spreadsheet.normalize(owner).equals(freeSlot))
                continue;
            SheetRow line = new SheetRow();
            line.sheet = sheet.getSheetName();
            line.type = type;
            line.rowNum = r + 1;
            line.reference = reference;
            line.owner = owner;
            line.state = text(row, stateCol);
            line.feature = text(row, featureCol);
            line.stock = text(row, stockCol);
            Cell revision = (row == null || revisionCol < 0) ? null : row.getCell(revisionCol);
            line.lastRevision = date(revision);
            if (line.lastRevision == null)
                line.lastRevisionText = text(row, revisionCol);
            seen.merge(Spreadsheet.normalize(reference), 1, Integer::sum);
            rows.add(line);
        }
        rows.removeIf(line -> {
            int count = seen.get(Spreadsheet.normalize(line.reference));
            if (count > 1)
                Logger.WARN("\"" + line.reference + "\" found on " + count + " rows of sheet " + line.sheet
                        + " (row " + line.rowNum + "): not imported");
            return count > 1;
        });
        return rows;
    }

    /** Cell text as displayed, trimmed (line breaks inside kept); null if empty. */
    private static String text(Row row, int col) {
        if (row == null || col < 0)
            return null;
        String value = FORMATTER.formatCellValue(row.getCell(col)).trim();
        return value.isEmpty() ? null : value;
    }

    /** A real date, or a JJ/MM/AAAA text; null otherwise ("?", empty…). */
    private static LocalDate date(Cell cell) {
        if (cell == null)
            return null;
        if (cell.getCellType() == CellType.NUMERIC)
            return DateUtil.isCellDateFormatted(cell) ? cell.getLocalDateTimeCellValue().toLocalDate() : null;
        if (cell.getCellType() == CellType.STRING)
            try {
                return LocalDate.parse(cell.getStringCellValue().trim(), TEXT_DATE);
            } catch (DateTimeParseException e) {
                return null;
            }
        return null;
    }

    private static int column(Row row, String title) {
        if (row == null)
            return -1;
        String wanted = Spreadsheet.normalize(title);
        for (Cell cell : row)
            if (Spreadsheet.normalize(FORMATTER.formatCellValue(cell)).equals(wanted))
                return cell.getColumnIndex();
        return -1;
    }

    /* ---------- Changes for the API ---------- */

    /**
     * Changes of one equipment, or the creation of a place or a characteristic: refused by the server, it is
     * dropped with every group that needs what it creates, and the others are sent again.
     */
    static final class Group {
        final String label;
        final List<JsonObject> changes = new ArrayList<>();
        final Set<Integer> creates = new HashSet<>();      // temporary ids created
        final Set<Integer> needs = new HashSet<>();        // temporary ids of other groups used
        /** Existing equipment updated (its id), or null */
        Integer equipmentId;
        /** Index in changes of the equipment insert, -1 if none */
        int equipmentInsert = -1;

        Group(String label) {
            this.label = label;
        }
    }

    /** Groups to send, in order (places and characteristics before the equipment that use them). */
    public static final class Plan {
        final List<Group> groups = new ArrayList<>();
        int created, updated;
        /** Some differences were not sent (app more recent): the workbooks must be compared again next run. */
        boolean deferred;

        public boolean isEmpty() {
            return groups.isEmpty();
        }

        public boolean deferred() {
            return deferred;
        }
    }

    /** Builds the changes to send; temporary ids are taken from the state, which must be saved before sending. */
    public static Plan plan(Db db, List<SheetRow> rows, long lastUpdate, long now, ImportState state) {
        return new Planner(db, lastUpdate, now, state).plan(rows);
    }

    private static final class Planner {
        final Db db;
        final long lastUpdate, now;
        final ImportState state;
        final Plan plan = new Plan();
        final Map<String, List<Equipment>> byReference = new HashMap<>();
        final Map<String, Integer> placeIds = new HashMap<>();      // normalized name => id or temporary id
        final Map<String, Integer> featureIds = new HashMap<>();    // type + normalized text => id or temporary id
        final Set<Integer> pendingIds = new HashSet<>();            // temporary ids of places/characteristics

        Planner(Db db, long lastUpdate, long now, ImportState state) {
            this.db = db;
            this.lastUpdate = lastUpdate;
            this.now = now;
            this.state = state;
            for (Equipment e : db.equipment.values())
                byReference.computeIfAbsent(Spreadsheet.normalize(e.reference), k -> new ArrayList<>()).add(e);
            for (Map.Entry<Integer, String> place : db.places.entrySet())
                placeIds.putIfAbsent(Spreadsheet.normalize(place.getValue()), place.getKey());
            for (Feature f : db.features.values())
                featureIds.putIfAbsent(f.type + "\u0000" + Spreadsheet.normalize(f.text), f.id);
        }

        Plan plan(List<SheetRow> rows) {
            List<Group> equipment = new ArrayList<>();
            for (SheetRow row : rows) {
                Integer type = db.typeIds.get(row.type);
                if (type == null) {
                    Logger.WARN("Type \"" + row.type + "\" not in the database: \"" + row.reference + "\" not imported");
                    continue;
                }
                List<Equipment> known = byReference.get(Spreadsheet.normalize(row.reference));
                Group group;
                if (known == null)
                    group = create(row, type);
                else if (known.size() > 1) {
                    Logger.WARN("\"" + row.reference + "\" is " + known.size() + " equipment in the database: not imported");
                    continue;
                } else if (known.get(0).type != type) {
                    Logger.WARN("\"" + row.reference + "\" (sheet " + row.sheet + ") is another type of equipment in"
                            + " the database: not imported");
                    continue;
                } else
                    group = update(row, known.get(0));
                if (group != null)
                    equipment.add(group);
            }
            plan.groups.addAll(equipment);      // after the places and characteristics added meanwhile
            return plan;
        }

        /** Owner other than the club (BLOCS sheet): not lendable, at home. */
        boolean memberOwned(SheetRow row) {
            return row.owner != null && !Spreadsheet.normalize(row.owner).equals(Spreadsheet.normalize(CLUB_OWNER));
        }

        Group create(SheetRow row, int type) {
            String status;
            Integer place = null;
            Group group = new Group("new \"" + row.reference + "\" (sheet " + row.sheet + ", row " + row.rowNum + ")");
            if (memberOwned(row)) {
                status = UNAVAILABLE;
                place = placeId(HOME_PLACE, group);
            } else {
                State s = state(row.state);
                status = s == null || s.status == null ? AVAILABLE : s.status;
                if (s != null && s.place != null)
                    place = placeId(s.place, group);
            }
            Integer stock = row.stock == null ? null : stockId(row.stock);
            if (row.stock != null && stock == null)
                Logger.WARN("\"" + row.reference + "\": unknown stock \"" + row.stock + "\", " + DEFAULT_STOCK + " used");
            if (stock == null)
                stock = stockId(DEFAULT_STOCK);
            if (stock == null) {
                Logger.ERROR("Stock \"" + DEFAULT_STOCK + "\" not in the database: \"" + row.reference + "\" not imported");
                return null;
            }
            long revision = 0;
            if (row.lastRevision != null)
                revision = midnight(row.lastRevision);
            else
                Logger.WARN("\"" + row.reference + "\": no revision date"
                        + (row.lastRevisionText == null ? "" : " (\"" + row.lastRevisionText + "\")")
                        + ", created with revision due (not lendable until a date is entered)");

            JsonObject values = new JsonObject();
            values.addProperty("reference", row.reference);
            values.addProperty("type", type);
            values.addProperty("status", status);
            values.addProperty("stock", stock);
            values.addProperty("feature", featureId(type, row.feature == null ? NO_FEATURE : row.feature, group));
            values.addProperty("creationTs", now);
            values.addProperty("lastRevisionTs", revision);
            int equipmentId = state.newTempId();
            group.equipmentInsert = group.changes.size();
            group.changes.add(change("equipment", "insert", equipmentId, values));
            group.creates.add(equipmentId);
            if (place != null)
                group.changes.add(placeLink(equipmentId, place));
            plan.created++;
            return group;
        }

        Group update(SheetRow row, Equipment e) {
            Group group = new Group("\"" + e.reference + "\" (sheet " + row.sheet + ", row " + row.rowNum + ")");
            JsonObject values = new JsonObject();
            if (row.feature != null) {
                Feature current = db.features.get(e.feature);
                if (current == null || !Spreadsheet.normalize(current.text).equals(Spreadsheet.normalize(row.feature)))
                    values.addProperty("feature", featureId(e.type, row.feature, group));
            }
            if (row.lastRevision != null
                    && (e.lastRevisionTs <= 0 || !localDate(e.lastRevisionTs).equals(row.lastRevision)))
                values.addProperty("lastRevisionTs", midnight(row.lastRevision));
            if (row.stock != null) {
                Integer stock = stockId(row.stock);
                if (stock == null)
                    Logger.WARN("\"" + e.reference + "\": unknown stock \"" + row.stock + "\", not imported");
                else if (stock != e.stock)
                    values.addProperty("stock", stock);
            }
            Integer newPlace = null;
            State s = memberOwned(row) || e.loaned ? null : state(row.state);   // loans: app only
            if (s != null) {
                if (s.status != null && !s.status.equals(e.status))
                    values.addProperty("status", s.status);
                if (s.place != null) {
                    String currentPlace = e.place == null ? null : db.places.get(e.place);
                    if (currentPlace == null || !Spreadsheet.normalize(currentPlace).equals(Spreadsheet.normalize(s.place))) {
                        // equipmentPlace is replaced (delete + insert), which the server does not check against
                        // lastUpdate: not done if the app changed the equipment or its place since the last run
                        if (e.lastUpdate >= lastUpdate || e.placeLastUpdate >= lastUpdate) {
                            Logger.LOG("\"" + e.reference + "\": place \"" + s.place + "\" not imported, the"
                                    + " equipment was changed in the app since the last run (compared again next run)");
                            plan.deferred = true;
                        } else {
                            newPlace = placeId(s.place, group);
                            values.addProperty("status", s.status);     // moves lastUpdate like the app does
                        }
                    }
                }
            }
            if (values.size() == 0)
                return null;            // characteristic and place are only looked up when they changed
            group.equipmentId = e.id;
            group.changes.add(0, change("equipment", "update", e.id, values));
            if (newPlace != null) {
                JsonObject delete = new JsonObject();
                delete.addProperty("table", "equipmentPlace");
                delete.addProperty("op", "delete");
                JsonObject match = new JsonObject();
                match.addProperty("equipment", e.id);
                delete.add("match", match);
                group.changes.add(delete);
                group.changes.add(placeLink(e.id, newPlace));
            }
            Logger.LOG(group.label + ": changed in the workbook: " + values.keySet() + (newPlace == null ? "" : " + place"));
            plan.updated++;
            return group;
        }

        /** Id of the place, created (own group) if no place has this name. */
        int placeId(String name, Group user) {
            String key = Spreadsheet.normalize(name);
            Integer id = placeIds.get(key);
            if (id == null) {
                id = state.newTempId();
                JsonObject values = new JsonObject();
                values.addProperty("name", name);
                values.addProperty("address", "");
                values.addProperty("coordinates", "");
                Group group = new Group("new place \"" + name + "\"");
                group.changes.add(change("places", "insert", id, values));
                group.creates.add(id);
                plan.groups.add(group);
                placeIds.put(key, id);
                pendingIds.add(id);
            }
            if (pendingIds.contains(id))
                user.needs.add(id);
            return id;
        }

        /** Id of the characteristic of this type, created (own group) if none has this text. */
        int featureId(int type, String text, Group user) {
            String key = type + "\u0000" + Spreadsheet.normalize(text);
            Integer id = featureIds.get(key);
            if (id == null) {
                id = state.newTempId();
                JsonObject values = new JsonObject();
                values.addProperty("feature", text);
                values.addProperty("equipmentType", type);
                Group group = new Group("new characteristic \"" + text.replace('\n', ' ') + "\"");
                group.changes.add(change("equipmentTypeFeature", "insert", id, values));
                group.creates.add(id);
                plan.groups.add(group);
                featureIds.put(key, id);
                pendingIds.add(id);
            }
            if (pendingIds.contains(id))
                user.needs.add(id);
            return id;
        }

        Integer stockId(String name) {
            for (Map.Entry<Integer, String> stock : db.stocks.entrySet())
                if (Spreadsheet.normalize(stock.getValue()).equals(Spreadsheet.normalize(name)))
                    return stock.getKey();
            return null;
        }

        JsonObject placeLink(int equipment, int place) {
            JsonObject values = new JsonObject();
            values.addProperty("equipment", equipment);
            values.addProperty("place", place);
            return change("equipmentPlace", "insert", state.newTempId(), values);
        }

        JsonObject change(String table, String op, int id, JsonObject values) {
            JsonObject change = new JsonObject();
            change.addProperty("table", table);
            change.addProperty("op", op);
            change.addProperty("id", id);
            change.addProperty("lastUpdate", lastUpdate);
            change.add("values", values);
            return change;
        }
    }

    /** Meaning of a state cell: a status, and/or a place (status available). */
    private static final class State {
        final String status, place;

        State(String status, String place) {
            this.status = status;
            this.place = place;
        }
    }

    /** @return null if nothing can be imported from the cell (empty, loan) */
    private static State state(String text) {
        if (text == null)
            return null;
        String key = Spreadsheet.normalize(text);
        if (key.startsWith(Spreadsheet.normalize(StockDb.LOAN_STATE)))
            return null;
        String status = STATUS_OF_STATE.get(key);
        return status != null ? new State(status, null) : new State(AVAILABLE, text);
    }

    static long midnight(LocalDate date) {
        return date.atStartOfDay(CLUB_ZONE).toInstant().toEpochMilli();
    }

    private static LocalDate localDate(long ms) {
        return Instant.ofEpochMilli(ms).atZone(CLUB_ZONE).toLocalDate();
    }

    /* ---------- Upload ---------- */

    /** Outcome of the upload. */
    public static final class Result {
        /** Network or server error: the import stopped (compared again next run) */
        public boolean failed;
        /** Everything was applied (nothing kept by the server, refused or deferred) */
        public boolean complete = true;
        /** Equipment created or updated => lastUpdate sent */
        public final Map<Integer, Long> touched = new LinkedHashMap<>();
        int created, updated, kept, refused;

        /** One line for the log, null if nothing was sent. */
        public String summary() {
            if (created + updated + kept + refused == 0)
                return null;
            return "Import from the workbooks: " + created + " equipment created, " + updated + " updated, "
                    + kept + " kept (changed in the app since the last run), " + refused + " refused by the server";
        }
    }

    /**
     * Sends the groups, at most MAX_CHANGES_PER_UPLOAD changes per upload. A change refused by the server
     * (400, 404, 409 with its index): its group and those depending on it are dropped, the upload is sent again.
     */
    public static Result apply(Plan plan, NsaApi api, long lastUpdate) {
        Result result = new Result();
        result.complete = !plan.deferred;
        Set<Integer> dropped = new HashSet<>();
        List<Group> remaining = new ArrayList<>(plan.groups);
        while (!remaining.isEmpty()) {
            List<Group> batch = new ArrayList<>();
            int size = 0;
            while (!remaining.isEmpty()
                    && (batch.isEmpty() || size + remaining.get(0).changes.size() <= MAX_CHANGES_PER_UPLOAD)) {
                Group g = remaining.remove(0);
                if (!dropped(g, dropped)) {
                    batch.add(g);
                    size += g.changes.size();
                }
            }
            while (!batch.isEmpty()) {
                List<JsonObject> changes = new ArrayList<>();
                List<Group> owners = new ArrayList<>();
                for (Group g : batch)
                    for (JsonObject change : g.changes) {
                        changes.add(change);
                        owners.add(g);
                    }
                NsaApi.Response response;
                try {
                    response = api.sendChanges(changes);
                } catch (IOException e) {
                    Logger.ERROR_printMsg("Import: upload to the NSA-app API failed (compared again next run)",
                            e.getMessage());
                    result.failed = true;
                    return result;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    result.failed = true;
                    return result;
                }
                if (response.code == 200) {
                    record(response.body, owners, result, lastUpdate);
                    break;
                }
                int index = response.refusedIndex();
                boolean dataError = response.code == 400 || response.code == 404 || response.code == 409;
                if (!dataError || index < 0 || index >= owners.size()) {
                    Logger.ERROR("Import: the NSA-app API refused the upload (" + response.error()
                            + "), compared again next run");
                    result.failed = true;
                    return result;
                }
                Group refused = owners.get(index);
                Logger.WARN("Import: " + refused.label + " refused by the server: " + response.error());
                result.refused++;
                result.complete = false;
                dropped.addAll(refused.creates);
                batch.remove(refused);
                batch.removeIf(g -> {
                    boolean d = dropped(g, dropped);
                    if (d)
                        Logger.WARN("Import: " + g.label + " not sent (it needs what was refused)");
                    return d;
                });
            }
        }
        return result;
    }

    /** True if the group needs something refused (its own creations are then refused too). */
    private static boolean dropped(Group g, Set<Integer> dropped) {
        for (int id : g.needs)
            if (dropped.contains(id)) {
                dropped.addAll(g.creates);
                return true;
            }
        return false;
    }

    private static void record(JsonObject body, List<Group> owners, Result result, long lastUpdate) {
        JsonArray results = body.has("results") ? body.getAsJsonArray("results") : new JsonArray();
        Set<Group> counted = new HashSet<>();
        int[] offsets = new int[owners.size()];
        for (int i = 1; i < owners.size(); i++)
            offsets[i] = owners.get(i) == owners.get(i - 1) ? offsets[i - 1] + 1 : 0;
        for (JsonElement element : results) {
            JsonObject r = element.getAsJsonObject();
            int i = r.get("i").getAsInt();
            if (i < 0 || i >= owners.size())
                continue;
            Group g = owners.get(i);
            String status = r.get("status").getAsString();
            if (g.equipmentId != null && offsets[i] == 0) {          // the equipment update is the group's first change
                if ("skipped".equals(status)) {
                    Logger.LOG("Import: " + g.label + " kept, changed in the app since the last run (compared again next run)");
                    result.kept++;
                    result.complete = false;
                } else {
                    result.updated++;
                    result.touched.put(g.equipmentId, lastUpdate);
                }
            } else if (g.equipmentInsert == offsets[i] && counted.add(g)) {
                result.created++;
                result.touched.put(r.get("id").getAsInt(), lastUpdate);
                Logger.LOG("Import: " + g.label + " created (#" + r.get("id").getAsInt() + ")");
            }
        }
    }
}
