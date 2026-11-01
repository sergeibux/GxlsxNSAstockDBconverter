package main.java.GxlsxNSAstockDBconverter.utils;

import org.sqlite.SQLiteConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Reads the NSA-app database of the organisation (orgs/&lt;ORG&gt;.db, read-only) and computes, for every
 * equipment, the values it has in the Drive workbooks (see the mapping table of the README).
 */
public final class StockDb {

    /** Sheet of an equipment type and the titles of its columns; null: no such column in the sheet. */
    public static final class SheetColumns {
        public final String sheet, idCol, feature, lastRevision, nextRevision, stock;

        SheetColumns(String sheet, String idCol, String feature, String lastRevision, String nextRevision,
                     String stock) {
            this.sheet = sheet;
            this.idCol = idCol;
            this.feature = feature;
            this.lastRevision = lastRevision;
            this.nextRevision = nextRevision;
            this.stock = stock;
        }
    }

    /** Equipment type name (equipmentType.name, EquipmentType.TypeName on the app side) => sheet. */
    public static final Map<String, SheetColumns> SHEETS = Map.of(
            "Bloc", new SheetColumns("BLOCS", "Identification Bloc", "Type de Blocs",
                    "Date de dernière inspection visuelle", null, null),
            "Détendeur", new SheetColumns("Détendeur NSA", "Identification", "Type",
                    "Date de dernière révision", "Date de prochaine révision", null),
            "STAB", new SheetColumns("Stab NSA", "Identification", "Type",
                    "Date de dernière révision", "Date de prochaine révision", "Affectation initiale"));

    public static final String STATE_COLUMN = "Etat et position au";
    static final String LOAN_STATE = "Prêt en cours à : ";
    /** equipment.status (EquipmentStatus on the app side) => state; any other status: the place name. */
    static final Map<String, String> STATE_OF_STATUS = Map.of(
            "à contrôler", "En révision",
            "non disponible", "Non disponible");
    private static final ZoneId CLUB_ZONE = ZoneId.of("Europe/Paris");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu");

    /** Values of one equipment in the workbooks. */
    public static final class EquipmentState {
        public final int id;
        public final String type;          // equipmentType.name
        public final String reference;
        /** null if the type has no sheet (nothing is ever written for it) */
        public final String sheet, idCol;
        /** column title => value; a column without a value is absent (never written) */
        public final LinkedHashMap<String, String> values;

        EquipmentState(int id, String type, String reference, String sheet, String idCol,
                       LinkedHashMap<String, String> values) {
            this.id = id;
            this.type = type;
            this.reference = reference;
            this.sheet = sheet;
            this.idCol = idCol;
            this.values = values;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof EquipmentState))
                return false;
            EquipmentState e = (EquipmentState) o;
            return id == e.id && Objects.equals(type, e.type) && Objects.equals(reference, e.reference)
                    && Objects.equals(sheet, e.sheet) && Objects.equals(idCol, e.idCol) && values.equals(e.values);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, type, reference, sheet, idCol, values);
        }
    }

    private StockDb() {}

    /**
     * @param dbFile orgs/&lt;ORG&gt;.db, opened read-only; all queries run in one read transaction
     *               (consistent view while the API writes)
     * @return equipment id => its values in the workbooks
     */
    public static Map<Integer, EquipmentState> read(String dbFile) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(10_000);      // the API holds its write lock only for the length of an upload
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + dbFile, config.toProperties())) {
            db.setAutoCommit(false);
            try (Statement st = db.createStatement()) {
                Map<Integer, String> borrowers = lastValueByEquipment(st,
                        "SELECT em.equipment, m.firstname, m.lastname FROM equipmentMember em"
                                + " JOIN members m ON m.id = em.member ORDER BY em.id", true);
                Map<Integer, String> places = lastValueByEquipment(st,
                        "SELECT ep.equipment, p.name FROM equipmentPlace ep"
                                + " JOIN places p ON p.id = ep.place ORDER BY ep.id", false);
                Map<Integer, EquipmentState> states = new LinkedHashMap<>();
                try (ResultSet rs = st.executeQuery(
                        "SELECT e.id, e.reference, e.status, e.lastRevisionTs, t.name AS type, t.revisionInterval,"
                                + " s.name AS stock, f.feature AS feature"
                                + " FROM equipment e"
                                + " LEFT JOIN equipmentType t ON t.id = e.type"
                                + " LEFT JOIN equipmentStock s ON s.id = e.stock"
                                + " LEFT JOIN equipmentTypeFeature f ON f.id = e.feature"
                                + " ORDER BY e.id")) {
                    while (rs.next()) {
                        int id = rs.getInt("id");
                        states.put(id, state(id, rs.getString("type"), rs.getString("reference"),
                                rs.getString("status"), rs.getLong("lastRevisionTs"), rs.getLong("revisionInterval"),
                                rs.getString("stock"), rs.getString("feature"), borrowers.get(id), places.get(id)));
                    }
                }
                return states;
            } finally {
                db.rollback();              // read only: ends the read transaction
            }
        }
    }

    /** Values written in the workbooks for one equipment (package-private for the tests). */
    static EquipmentState state(int id, String type, String reference, String status, long lastRevisionTs,
                                long revisionInterval, String stock, String feature, String borrower,
                                String place) {
        SheetColumns sheet = SHEETS.get(type);
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        if (sheet != null) {
            put(values, STATE_COLUMN, borrower != null ? LOAN_STATE + borrower
                    : STATE_OF_STATUS.getOrDefault(status, place));
            put(values, sheet.feature, feature);
            if (lastRevisionTs > 0) {
                put(values, sheet.lastRevision, date(lastRevisionTs));
                if (sheet.nextRevision != null && revisionInterval > 0)
                    put(values, sheet.nextRevision, date(lastRevisionTs + revisionInterval));
            }
            if (sheet.stock != null)
                put(values, sheet.stock, stock);
        }
        return new EquipmentState(id, type, reference, sheet == null ? null : sheet.sheet,
                sheet == null ? null : sheet.idCol, values);
    }

    private static void put(Map<String, String> values, String column, String value) {
        if (value != null && !value.isBlank())
            values.put(column, value.trim());
    }

    /** JJ/MM/AAAA in the club's time zone (timestamps in milliseconds). */
    static String date(long ms) {
        return DATE.format(Instant.ofEpochMilli(ms).atZone(CLUB_ZONE));
    }

    /** equipment => value of its last row (highest id): "First Last" for a borrower, the name for a place. */
    private static Map<Integer, String> lastValueByEquipment(Statement st, String sql, boolean person)
            throws SQLException {
        Map<Integer, String> values = new HashMap<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String value = person
                        ? (Objects.toString(rs.getString(2), "") + " " + Objects.toString(rs.getString(3), "")).trim()
                        : rs.getString(2);
                values.put(rs.getInt(1), value);
            }
        }
        return values;
    }
}
