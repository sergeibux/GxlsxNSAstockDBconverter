package main.java.GxlsxNSAstockDBconverter;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import main.java.GxlsxNSAstockDBconverter.utils.ConfigReader;
import main.java.GxlsxNSAstockDBconverter.utils.GglApiLayer;
import main.java.GxlsxNSAstockDBconverter.utils.Logger;
import main.java.GxlsxNSAstockDBconverter.utils.Snapshot;
import main.java.GxlsxNSAstockDBconverter.utils.Spreadsheet;
import main.java.GxlsxNSAstockDBconverter.utils.StockDb;
import main.java.GxlsxNSAstockDBconverter.utils.StockDb.EquipmentState;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static java.lang.System.exit;


public class Main {
    /** A workbook changed on Drive during a run is downloaded and processed again, at most this many times. */
    private static final int MAX_ATTEMPTS = 10;
    /** Exit code when changes could not be uploaded: run.sh keeps the changes file for the next run. */
    private static final int EXIT_RETRY_LATER = 3;
    /** Drive access for a file name and a service account key; replaced by a fake in tests. */
    static BiFunction<String, String, GglApiLayer> driveLayers = GglApiLayer::new;
    /** Local tests only (key debug.copy): copy of each workbook written before its upload; null on the server. */
    static String debugCopy;
    /** The club's time zone: the server clock is UTC, a run just after midnight must write the club's date. */
    private static final ZoneId CLUB_ZONE = ZoneId.of("Europe/Paris");
    /** Columns written only when a row is created (key protected.columns); null: default list of Spreadsheet. */
    static List<String> protectedColumns;

    /** Exit code: wrong arguments, unreadable configuration, database or snapshot. */
    private static final int EXIT_ERROR = 1;
    /** Workbook key (its sheet names, see concerns) => key of its file name in the configuration (fileName.*). */
    private static final Map<String, String> WORKBOOKS = new LinkedHashMap<>();
    static {
        WORKBOOKS.put("BLOCS", "blocs");
        WORKBOOKS.put("Détendeur NSA_Stab NSA", "det_et_stabs");
    }

    /**
     * Database mode: --conf &lt;file&gt;; test mode (changes file, no database nor snapshot):
     * --changes &lt;file.jsonl&gt; --conf &lt;file&gt;, in any order.
     */
    public static void main(String[] args) {
        String confFile = null;
        String changesFile = null;
        boolean valid = args.length % 2 == 0;           // options always followed by their value
        for (int i = 0; valid && i < args.length; i += 2) {
            if ("--conf".equals(args[i]))
                confFile = args[i + 1];
            else if ("--changes".equals(args[i]))
                changesFile = args[i + 1];
            else
                valid = false;
        }
        if (!valid || confFile == null) {
            Logger.ERROR("Usage:\n"
                    + "\t--conf <config file>                              changes read from the database (db.file, snapshot.file)\n"
                    + "\t--changes <file.jsonl> --conf <config file>      tests: changes read from the file");
            exit(EXIT_ERROR);
        }
        int code;
        try {
            ConfigReader config = new ConfigReader(confFile);
            debugCopy = config.getDebugCopy();
            protectedColumns = config.getProtectedColumns();
            code = changesFile != null ? runChangesFile(config, changesFile) : runDatabase(config);
        } catch (IOException e) {
            Logger.ERROR_printMsg("Cannot read the configuration " + confFile, e.getMessage());
            code = EXIT_ERROR;
        } catch (Exception e) {
            Logger.ERROR_printStacktrace("Unexpected error.", e.getStackTrace());
            e.printStackTrace();
            code = EXIT_RETRY_LATER;
        }
        if (code != 0)
            exit(code);
    }

    /** Test mode: the lines of the file, applied in their order. */
    private static int runChangesFile(ConfigReader config, String changesFile) throws IOException {
        List<String> changes = readChanges(changesFile);
        boolean allUploaded = true;
        for (Map.Entry<String, String> workbook : WORKBOOKS.entrySet())
            allUploaded &= syncWorkbook(workbook.getKey(), config.getFileName(workbook.getValue()), changes,
                    config.getApiKey());
        return allUploaded ? 0 : EXIT_RETRY_LATER;
    }

    /**
     * Database mode: what changed in the database since the snapshot is written to the workbooks; the snapshot
     * of an equipment advances once its changes are uploaded. First run: the snapshot is only created.
     */
    private static int runDatabase(ConfigReader config) {
        String dbFile = config.getDbFile();
        String snapshotFile = config.getSnapshotFile();
        if (dbFile == null || snapshotFile == null) {
            Logger.ERROR("db.file and snapshot.file are required in the configuration (or use --changes)");
            return EXIT_ERROR;
        }
        Path snapshotPath = Paths.get(snapshotFile);
        Map<Integer, EquipmentState> current;
        Map<Integer, EquipmentState> snapshot;
        try {
            current = StockDb.read(dbFile);
            snapshot = Snapshot.load(snapshotPath);
        } catch (SQLException e) {
            Logger.ERROR_printMsg("Cannot read the database " + dbFile, e.getMessage());
            return EXIT_ERROR;
        } catch (IOException e) {
            Logger.ERROR_printMsg("Cannot read the snapshot (not recreated: the changes not uploaded yet would be lost)",
                    e.getMessage());
            return EXIT_ERROR;
        }
        if (snapshot == null)
            return saveSnapshot(snapshotPath, current, current.size() + " equipment: snapshot created, nothing written");

        List<Snapshot.Change> changes = Snapshot.diff(snapshot, current);
        if (changes.isEmpty())                          // nothing to write: silent run
            return current.equals(snapshot) ? 0 : saveSnapshot(snapshotPath, current, null);

        List<String> lines = changes.stream().map(c -> c.line).collect(Collectors.toList());
        Set<String> uploadedSheets = new HashSet<>();
        List<String> uploaded = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (Map.Entry<String, String> workbook : WORKBOOKS.entrySet()) {
            String fileName = config.getFileName(workbook.getValue());
            boolean concerned = lines.stream().anyMatch(ln -> concerns(ln, workbook.getKey()));
            if (syncWorkbook(workbook.getKey(), fileName, lines, config.getApiKey())) {
                for (Snapshot.Change change : changes)
                    if (workbook.getKey().toUpperCase().contains(change.sheet.toUpperCase()))
                        uploadedSheets.add(change.sheet);
                if (concerned)
                    uploaded.add(fileName);
            } else
                failed.add(fileName);
        }
        String summary = changes.stream().map(c -> c.equipmentId).distinct().count() + " equipment changed, "
                + changes.size() + " cell(s); uploaded: " + (uploaded.isEmpty() ? "none" : String.join(", ", uploaded))
                + (failed.isEmpty() ? "" : "; not uploaded, retried at the next run: " + String.join(", ", failed));
        int code = saveSnapshot(snapshotPath, Snapshot.advance(snapshot, current, changes, uploadedSheets), summary);
        return code != 0 ? code : failed.isEmpty() ? 0 : EXIT_RETRY_LATER;
    }

    /** @param summary logged once saved (null: nothing logged) */
    private static int saveSnapshot(Path file, Map<Integer, EquipmentState> states, String summary) {
        try {
            Snapshot.save(file, states);
        } catch (IOException e) {
            Logger.ERROR_printMsg("Cannot write the snapshot " + file
                    + ": the changes uploaded will be applied again at the next run", e.getMessage());
            return EXIT_ERROR;
        }
        if (summary != null)
            Logger.LOG(summary);
        return 0;
    }

    /**
     * Downloads the workbook, applies the changes that concern it and uploads it, unless someone changed it on
     * Drive meanwhile (its manual changes would be overwritten): then everything is done again from the download,
     * at most MAX_ATTEMPTS times.
     * @param key sheet names of the workbook ("BLOCS", "Détendeur NSA_Stab NSA")
     * @return true if uploaded, or if no change concerns this workbook
     */
    static boolean syncWorkbook(String key, String fileName, List<String> changes, String serviceAccountKey) {
        List<String> mine = new ArrayList<>();
        for (String ln : changes)
            if (concerns(ln, key))
                mine.add(ln);
        if (mine.isEmpty())
            return true;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            GglApiLayer tcpLayer = driveLayers.apply(fileName, serviceAccountKey);
            if (!tcpLayer.checkConnection()) {
                Logger.ERROR("Unable to connect to Google Drive.");
                return false;
            }
            try {
                Spreadsheet spreadsheet = tcpLayer.downloadFile();
                if (protectedColumns != null)
                    spreadsheet.setProtectedColumns(protectedColumns);
                Map<String, Spreadsheet> workbook = Map.of(key, spreadsheet);
                for (String ln : mine)
                    editFile(ln, workbook);
                spreadsheet.stampStateDate(LocalDate.now(CLUB_ZONE));
                if (!tcpLayer.isUnchangedSinceDownload()) {
                    Logger.WARN(spreadsheet.getFileName() + " changed on Drive during the run: attempt "
                            + attempt + "/" + MAX_ATTEMPTS + ", processed again");
                    continue;
                }
                if (debugCopy != null)
                    try (FileOutputStream fos = new FileOutputStream(debugCopy)) {
                        spreadsheet.getWorkbook().write(fos);
                    }
                tcpLayer.uploadFile(spreadsheet.getBaos());
                return true;
            } catch (IOException | GeneralSecurityException e) {
                Logger.ERROR_printMsg("Cannot update " + fileName + ": changes kept for the next run.", e.getMessage());
                return false;
            }
        }
        Logger.WARN(fileName + " still changed on Drive after " + MAX_ATTEMPTS + " attempts: changes kept for the next run");
        return false;
    }

    /** True if the change line targets a sheet of this workbook (same rule as editFile). */
    private static boolean concerns(String ln, String key) {
        try {
            JsonObject json = new Gson().fromJson(ln, JsonObject.class);
            return json != null && json.has("sheetName")
                    && key.toUpperCase().contains(json.get("sheetName").getAsString().toUpperCase());
        } catch (JsonParseException e) {
            promptHelp(ln);
            return false;
        }
    }

    private static List<String> readChanges(String changedRecordsFile) throws IOException {
        List<String> changes = new ArrayList<>();
        for (String ln : Files.readAllLines(Paths.get(changedRecordsFile)))
            if (!ln.isBlank())
                changes.add(ln);
        return changes;
    }

    private static void editFile(String ln, Map<String, Spreadsheet> spreadsheets) {
        Gson gson = new Gson();
        JsonObject json = gson.fromJson(ln, JsonObject.class);
        Logger.LOG("#" + spreadsheets.size());
        try {
            // "op": "delete" for an equipment deleted in the app (row marked, never removed); a cell edit otherwise
            boolean delete = json.has("op") && "delete".equalsIgnoreCase(json.get("op").getAsString());
            for (Map.Entry<String, Spreadsheet> spreadsheet : spreadsheets.entrySet())
                if (spreadsheet.getKey().toUpperCase().contains(json.get("sheetName").getAsString().toUpperCase())) {
                    if (delete)
                        spreadsheet.getValue().markDeleted(
                                json.get("sheetName").getAsString(),
                                json.get("idCol").getAsString(),
                                json.get("idKey").getAsString());
                    else
                        spreadsheet.getValue().editSheet(
                                json.get("sheetName").getAsString(),
                                json.get("idCol").getAsString(),
                                json.get("idKey").getAsString(),
                                json.get("editCol").getAsString(),
                                json.get("newVal").getAsString());
                    break;
                }
        } catch (JsonParseException | NullPointerException e){
            promptHelp(ln);
        }
    }

    private static void promptHelp(String params) {
        Logger.WARN("Params not valid. You entered:" +
                "\n" + params +
                "\n Expected params :" +
                "\n{\"sheetName\":\"val\", \"idCol\":\"val\", \"idKey\":\"val\", \"editCol\":\"val\", \"newVal\":\"val\"}" +
                "\nor, for an equipment deleted in the app (its identification cell is marked):" +
                "\n{\"op\":\"delete\", \"sheetName\":\"val\", \"idCol\":\"val\", \"idKey\":\"val\"}");
    }

}
