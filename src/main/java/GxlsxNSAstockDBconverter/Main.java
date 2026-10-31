package main.java.GxlsxNSAstockDBconverter;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import main.java.GxlsxNSAstockDBconverter.utils.ConfigReader;
import main.java.GxlsxNSAstockDBconverter.utils.GglApiLayer;
import main.java.GxlsxNSAstockDBconverter.utils.Logger;
import main.java.GxlsxNSAstockDBconverter.utils.Spreadsheet;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static java.lang.System.exit;


public class Main {
    /** A workbook changed on Drive during a run is downloaded and processed again, at most this many times. */
    private static final int MAX_ATTEMPTS = 10;
    /** Exit code when changes could not be uploaded: run.sh keeps the changes file for the next run. */
    private static final int EXIT_RETRY_LATER = 3;
    /** Drive access for a file name and a service account key; replaced by a fake in tests. */
    static BiFunction<String, String, GglApiLayer> driveLayers = GglApiLayer::new;

    public static void main(String[] args) {

        if (args.length != 2) {
            Logger.ERROR("This application needs 2 parameters: \n" +
                    "\t- 1:filepath and name with db changes to apply" +
                    "\t- 2:config filepath");
            exit (1);
        }

        String changedRecordsFile = args[0];
        String confFile = args[1];
        boolean allUploaded = true;
        try {
            ConfigReader config = new ConfigReader(confFile);
            String serviceAccountKey = config.getApiKey();
            List<String> changes = readChanges(changedRecordsFile);
            allUploaded &= syncWorkbook("BLOCS", config.getFileName("blocs"), changes, serviceAccountKey);
            allUploaded &= syncWorkbook("Détendeur NSA_Stab NSA", config.getFileName("det_et_stabs"), changes, serviceAccountKey);
        } catch (IOException e) {
            Logger.ERROR_printStacktrace_only(e.getStackTrace());
            allUploaded = false;
        } catch (Exception e) {
            Logger.ERROR_printStacktrace("Unexpected error.", e.getStackTrace());
            e.printStackTrace();
            allUploaded = false;
        }
        if (!allUploaded)
            exit(EXIT_RETRY_LATER);
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
                Map<String, Spreadsheet> workbook = Map.of(key, spreadsheet);
                for (String ln : mine)
                    editFile(ln, workbook);
                if (!tcpLayer.isUnchangedSinceDownload()) {
                    Logger.WARN(spreadsheet.getFileName() + " changed on Drive during the run: attempt "
                            + attempt + "/" + MAX_ATTEMPTS + ", processed again");
                    continue;
                }
                try (FileOutputStream fos = new FileOutputStream("debug.xlsx")) {
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
                                json.get("idKey").getAsString(),
                                json.get("editCol").getAsString());
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
                "\nor, for an equipment deleted in the app (row marked in the state column):" +
                "\n{\"op\":\"delete\", \"sheetName\":\"val\", \"idCol\":\"val\", \"idKey\":\"val\", \"editCol\":\"<state column>\"}");
    }

}
