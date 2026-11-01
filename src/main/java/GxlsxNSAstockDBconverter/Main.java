package main.java.GxlsxNSAstockDBconverter;

import smv_logger.src.Logger.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import main.java.GxlsxNSAstockDBconverter.utils.ConfigReader;
import main.java.GxlsxNSAstockDBconverter.utils.GglApiLayer;
import main.java.GxlsxNSAstockDBconverter.utils.ImportState;
import main.java.GxlsxNSAstockDBconverter.utils.NsaApi;
import main.java.GxlsxNSAstockDBconverter.utils.Snapshot;
import main.java.GxlsxNSAstockDBconverter.utils.Spreadsheet;
import main.java.GxlsxNSAstockDBconverter.utils.StockDb;
import main.java.GxlsxNSAstockDBconverter.utils.StockDb.EquipmentState;
import main.java.GxlsxNSAstockDBconverter.utils.WorkbookImport;

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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static java.lang.System.exit;


public class Main {
    private static final Logger LOGGER = Logger.getInstance();
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
            LOGGER.LOG("Usage:\n"
                    + "\t--conf <config file>                              changes read from the database (db.file, snapshot.file)\n"
                    + "\t--changes <file.jsonl> --conf <config file>      tests: changes read from the file", Logger.LEVEL.ERROR);
            exit(EXIT_ERROR);
        }
        int code;
        try {
            ConfigReader config = new ConfigReader(confFile);
            setLogLevel(config.getLogLevel());
            debugCopy = config.getDebugCopy();
            protectedColumns = config.getProtectedColumns();
            code = changesFile != null ? runChangesFile(config, changesFile) : runDatabase(config);
        } catch (IOException e) {
            LOGGER.LOG("Cannot read the configuration " + confFile, e, Logger.LEVEL.ERROR);
            code = EXIT_ERROR;
        } catch (Exception e) {
            LOGGER.LOG("Unexpected error.", e, Logger.LEVEL.ERROR);
            code = EXIT_RETRY_LATER;
        }
        if (code != 0)
            exit(code);
    }

    /** Key log.level: FATAL, ERROR, WARN, INFO (default), DEBUG, TRACE or ALL; until it is read, ERROR. */
    private static void setLogLevel(String name) {
        Logger.LEVEL level = Logger.LEVEL.INFO;
        boolean unknown = false;
        if (name != null)
            try {
                level = Logger.LEVEL.valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                unknown = true;
            }
        LOGGER.setMax_logLevel(level);
        if (unknown)
            LOGGER.LOG("Unknown log.level \"" + name + "\" (FATAL, ERROR, WARN, INFO, DEBUG, TRACE, ALL): INFO used",
                    Logger.LEVEL.WARN);
    }

    /** Test mode: the lines of the file, applied in their order. */
    private static int runChangesFile(ConfigReader config, String changesFile) throws IOException {
        List<String> changes = readChanges(changesFile);
        boolean allUploaded = true;
        for (Map.Entry<String, String> workbook : WORKBOOKS.entrySet())
            allUploaded &= syncWorkbook(workbook.getKey(), config.getFileName(workbook.getValue()), changes,
                    config.getApiKey(), false, null).ok;
        return allUploaded ? 0 : EXIT_RETRY_LATER;
    }

    /**
     * Database mode:
     * 1. what changed in the database since the snapshot is written to the workbooks; the snapshot of an
     *    equipment advances once its changes are uploaded. First run: the snapshot is only created (with the
     *    import: every equipment is written, the workbooks must hold the database values before the import);
     * 2. with the import (api.url): the workbooks, which should now hold the database values, are compared with
     *    the database and their differences (changes made by hand) are written to the database through the API.
     */
    private static int runDatabase(ConfigReader config) {
        long runStart = System.currentTimeMillis();
        String dbFile = config.getDbFile();
        String snapshotFile = config.getSnapshotFile();
        if (dbFile == null || snapshotFile == null) {
            LOGGER.LOG("db.file and snapshot.file are required in the configuration (or use --changes)", Logger.LEVEL.ERROR);
            return EXIT_ERROR;
        }
        ImportState importState = null;
        if (config.getApiUrl() != null) {
            if (config.getApiEmail() == null || config.getApiPassword() == null || config.getImportStateFile() == null) {
                LOGGER.LOG("api.url is set: api.email, api.password and import.state.file are required", Logger.LEVEL.ERROR);
                return EXIT_ERROR;
            }
            String protectedValue = protectedImportedColumn();
            if (protectedValue != null) {
                LOGGER.LOG("protected.columns holds \"" + protectedValue + "\", compared by the import: an app change"
                        + " of this column would never reach the workbooks and the import would undo it."
                        + " Remove it from protected.columns (or the key itself)", Logger.LEVEL.ERROR);
                return EXIT_ERROR;
            }
            try {
                importState = ImportState.load(Paths.get(config.getImportStateFile()));
            } catch (IOException e) {
                LOGGER.LOG("Cannot read the import state (not recreated: its temporary ids would be reused)", e, Logger.LEVEL.ERROR);
                return EXIT_ERROR;
            }
        }
        Path snapshotPath = Paths.get(snapshotFile);
        Map<Integer, EquipmentState> current;
        Map<Integer, EquipmentState> snapshot;
        try {
            current = StockDb.read(dbFile);
            snapshot = Snapshot.load(snapshotPath);
        } catch (SQLException e) {
            LOGGER.LOG("Cannot read the database " + dbFile, e, Logger.LEVEL.ERROR);
            return EXIT_ERROR;
        } catch (IOException e) {
            LOGGER.LOG("Cannot read the snapshot (not recreated: the changes not uploaded yet would be lost)", e, Logger.LEVEL.ERROR);
            return EXIT_ERROR;
        }
        Map<Integer, EquipmentState> saved = snapshot;
        if (snapshot == null) {
            if (importState == null)
                return saveSnapshot(snapshotPath, current, current.size() + " equipment: snapshot created, nothing written");
            LOGGER.LOG(current.size() + " equipment: first run, every equipment is written to the workbooks before the import", Logger.LEVEL.INFO);
            snapshot = new LinkedHashMap<>();
        }

        List<Snapshot.Change> changes = Snapshot.diff(snapshot, current);
        if (changes.isEmpty() && importState == null)   // nothing to write: silent run
            return current.equals(snapshot) ? 0 : saveSnapshot(snapshotPath, current, null);

        List<String> lines = changes.stream().map(c -> c.line).collect(Collectors.toList());
        Set<String> uploadedSheets = new HashSet<>();
        List<String> uploaded = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Map<String, WorkbookSync> fetched = new LinkedHashMap<>();
        for (Map.Entry<String, String> workbook : WORKBOOKS.entrySet()) {
            String fileName = config.getFileName(workbook.getValue());
            boolean concerned = lines.stream().anyMatch(ln -> concerns(ln, workbook.getKey()));
            WorkbookSync sync = syncWorkbook(workbook.getKey(), fileName, lines, config.getApiKey(),
                    importState != null, importState == null ? null : importState.importedVersion(workbook.getKey()));
            if (sync.ok) {
                for (Snapshot.Change change : changes)
                    if (workbook.getKey().toUpperCase().contains(change.sheet.toUpperCase()))
                        uploadedSheets.add(change.sheet);
                if (concerned)
                    uploaded.add(fileName);
                if (sync.content != null)
                    fetched.put(workbook.getKey(), sync);
            } else
                failed.add(fileName);
        }
        String summary = changes.isEmpty() ? null
                : changes.stream().map(c -> c.equipmentId).distinct().count() + " equipment changed, "
                + changes.size() + " cell(s); uploaded: " + (uploaded.isEmpty() ? "none" : String.join(", ", uploaded))
                + (failed.isEmpty() ? "" : "; not uploaded, retried at the next run: " + String.join(", ", failed));
        if (summary != null)
            LOGGER.LOG(summary, Logger.LEVEL.INFO);
        Map<Integer, EquipmentState> next = Snapshot.advance(snapshot, current, changes, uploadedSheets);
        int code = failed.isEmpty() ? 0 : EXIT_RETRY_LATER;
        if (importState != null) {
            int importCode = runImport(config, importState, fetched, next, runStart);
            if (importCode == EXIT_ERROR || code == 0)
                code = importCode;
        }
        if (!next.equals(saved)) {
            int saveCode = saveSnapshot(snapshotPath, next, null);
            if (saveCode != 0)
                return saveCode;
        }
        return code;
    }

    /** @return a column compared by the import that protected.columns keeps from being written, null if none */
    private static String protectedImportedColumn() {
        if (protectedColumns == null)
            return null;
        for (String title : protectedColumns)
            for (StockDb.SheetColumns columns : StockDb.SHEETS.values())
                for (String imported : new String[]{StockDb.STATE_COLUMN, columns.feature, columns.lastRevision, columns.stock})
                    if (imported != null && imported.replaceAll("\\s+", "").equalsIgnoreCase(title.replaceAll("\\s+", "")))
                        return title;
        return null;
    }

    /**
     * Step 2: differences between the workbooks downloaded (or just uploaded) and the database, written to the
     * database through the API. The equipment written enter the snapshot with their new database values: they
     * are not written back to the workbooks. A workbook entirely imported is not downloaded again until its Drive
     * version changes.
     * @param next snapshot to save, updated here
     */
    private static int runImport(ConfigReader config, ImportState state, Map<String, WorkbookSync> fetched,
                                 Map<Integer, EquipmentState> next, long runStart) {
        // lastUpdate of the rows written: a change made in the app since the previous run keeps priority
        long lastUpdate = state.lastPass() > 0 ? state.lastPass() : runStart;
        int code = 0;
        if (!fetched.isEmpty()) {
            List<WorkbookImport.SheetRow> rows = new ArrayList<>();
            for (WorkbookSync sync : fetched.values())
                rows.addAll(WorkbookImport.readRows(sync.content.getWorkbook()));
            boolean complete = true;
            try {
                WorkbookImport.Plan plan = WorkbookImport.plan(WorkbookImport.readDb(config.getDbFile()), rows,
                        lastUpdate, runStart, state);
                if (!plan.isEmpty()) {
                    state.save();               // the temporary ids of the plan are never given again
                    NsaApi api = new NsaApi(config.getApiUrl(), config.getApiOrg(), config.getApiEmail(),
                            config.getApiPassword(), state);
                    WorkbookImport.Result result = WorkbookImport.apply(plan, api, lastUpdate);
                    if (result.summary() != null)
                        LOGGER.LOG(result.summary(), Logger.LEVEL.INFO);
                    if (result.failed)
                        code = EXIT_RETRY_LATER;
                    complete = result.complete && !result.failed;
                    if (!result.touched.isEmpty()) {
                        Map<Integer, EquipmentState> fresh = StockDb.read(config.getDbFile());
                        WorkbookImport.Db freshDb = WorkbookImport.readDb(config.getDbFile());
                        for (Map.Entry<Integer, Long> touched : result.touched.entrySet())
                            // not if the app changed it meanwhile: that change must still be written to the workbooks
                            if (touched.getValue().equals(freshDb.lastUpdate(touched.getKey()))
                                    && fresh.containsKey(touched.getKey()))
                                next.put(touched.getKey(), fresh.get(touched.getKey()));
                    }
                } else
                    complete = !plan.deferred();
            } catch (SQLException e) {
                LOGGER.LOG("Import: cannot read the database " + config.getDbFile(), e, Logger.LEVEL.ERROR);
                code = EXIT_ERROR;
                complete = false;
            } catch (IOException e) {
                LOGGER.LOG("Import: cannot write the import state " + config.getImportStateFile(), e, Logger.LEVEL.ERROR);
                return EXIT_ERROR;
            }
            for (Map.Entry<String, WorkbookSync> sync : fetched.entrySet())
                state.setImportedVersion(sync.getKey(), complete ? sync.getValue().version : null);
        }
        state.setLastPass(runStart);
        try {
            state.save();
        } catch (IOException e) {
            LOGGER.LOG("Import: cannot write the import state " + config.getImportStateFile(), e, Logger.LEVEL.ERROR);
            return EXIT_ERROR;
        }
        return code;
    }

    /** @param summary logged once saved (null: nothing logged) */
    private static int saveSnapshot(Path file, Map<Integer, EquipmentState> states, String summary) {
        try {
            Snapshot.save(file, states);
        } catch (IOException e) {
            LOGGER.LOG("Cannot write the snapshot " + file
                    + ": the changes uploaded will be applied again at the next run", e, Logger.LEVEL.ERROR);
            return EXIT_ERROR;
        }
        if (summary != null)
            LOGGER.LOG(summary, Logger.LEVEL.INFO);
        return 0;
    }

    /** Outcome of syncWorkbook. */
    static final class WorkbookSync {
        /** Uploaded, or nothing to upload */
        final boolean ok;
        /** Workbook content after the changes (as uploaded), null if not downloaded */
        final Spreadsheet content;
        /** Drive version of content when this run did not change it, null otherwise */
        final Long version;

        WorkbookSync(boolean ok, Spreadsheet content, Long version) {
            this.ok = ok;
            this.content = content;
            this.version = version;
        }
    }

    /**
     * Downloads the workbook, applies the changes that concern it and uploads it, unless someone changed it on
     * Drive meanwhile (its manual changes would be overwritten): then everything is done again from the download,
     * at most MAX_ATTEMPTS times.
     * Without change for it: nothing is done, unless the content is wanted (import), then it is downloaded,
     * if its Drive version differs from importedVersion.
     * @param key sheet names of the workbook ("BLOCS", "Détendeur NSA_Stab NSA")
     */
    static WorkbookSync syncWorkbook(String key, String fileName, List<String> changes, String serviceAccountKey,
                                     boolean wantContent, Long importedVersion) {
        List<String> mine = new ArrayList<>();
        for (String ln : changes)
            if (concerns(ln, key))
                mine.add(ln);
        if (mine.isEmpty() && !wantContent)
            return new WorkbookSync(true, null, null);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            GglApiLayer tcpLayer = driveLayers.apply(fileName, serviceAccountKey);
            if (!tcpLayer.checkConnection()) {
                LOGGER.LOG("Unable to connect to Google Drive.", Logger.LEVEL.ERROR);
                return new WorkbookSync(false, null, null);
            }
            try {
                if (mine.isEmpty()) {
                    Long version = tcpLayer.version();
                    if (version != null && version.equals(importedVersion))
                        return new WorkbookSync(true, null, null);     // unchanged since it was imported
                    return new WorkbookSync(true, tcpLayer.downloadFile(), tcpLayer.getDownloadedVersion());
                }
                Spreadsheet spreadsheet = tcpLayer.downloadFile();
                if (protectedColumns != null)
                    spreadsheet.setProtectedColumns(protectedColumns);
                Map<String, Spreadsheet> workbook = Map.of(key, spreadsheet);
                for (String ln : mine)
                    editFile(ln, workbook);
                spreadsheet.stampStateDate(LocalDate.now(CLUB_ZONE));
                if (!tcpLayer.isUnchangedSinceDownload()) {
                    LOGGER.LOG(spreadsheet.getFileName() + " changed on Drive during the run: attempt "
                            + attempt + "/" + MAX_ATTEMPTS + ", processed again", Logger.LEVEL.WARN);
                    continue;
                }
                if (debugCopy != null)
                    try (FileOutputStream fos = new FileOutputStream(debugCopy)) {
                        spreadsheet.getWorkbook().write(fos);
                    }
                tcpLayer.uploadFile(spreadsheet.getBaos());
                return new WorkbookSync(true, spreadsheet, null);
            } catch (IOException | GeneralSecurityException e) {
                LOGGER.LOG("Cannot update " + fileName + ": changes kept for the next run.", e, Logger.LEVEL.ERROR);
                return new WorkbookSync(false, null, null);
            }
        }
        LOGGER.LOG(fileName + " still changed on Drive after " + MAX_ATTEMPTS + " attempts: changes kept for the next run", Logger.LEVEL.WARN);
        return new WorkbookSync(false, null, null);
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
        LOGGER.LOG("#" + spreadsheets.size(), Logger.LEVEL.DEBUG);
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
        LOGGER.LOG("Params not valid. You entered:" +
                "\n" + params +
                "\n Expected params :" +
                "\n{\"sheetName\":\"val\", \"idCol\":\"val\", \"idKey\":\"val\", \"editCol\":\"val\", \"newVal\":\"val\"}" +
                "\nor, for an equipment deleted in the app (its identification cell is marked):" +
                "\n{\"op\":\"delete\", \"sheetName\":\"val\", \"idCol\":\"val\", \"idKey\":\"val\"}", Logger.LEVEL.WARN);
    }

}
