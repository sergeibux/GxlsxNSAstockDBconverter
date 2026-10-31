package main.java.GxlsxNSAstockDBconverter;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import main.java.GxlsxNSAstockDBconverter.utils.ConfigReader;
import main.java.GxlsxNSAstockDBconverter.utils.GglApiLayer;
import main.java.GxlsxNSAstockDBconverter.utils.Logger;
import main.java.GxlsxNSAstockDBconverter.utils.Spreadsheet;

import java.io.*;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;

import static java.lang.System.exit;


public class Main {
    public static void main(String[] args) {

        if (args.length != 1) {
            Logger.ERROR("This application needs one parameter: filepath and name with db changes to apply");
            exit (1);
        }

        String changedRecordsFile = args[0];

        try {
            ConfigReader config = new ConfigReader("GxlsxNSAstockDB_converter.conf");
            String serviceAccountKey = config.getApiKey();
            String fileName_tanks = config.getFileName("blocs");
            String fileName_bcd_and_regs = config.getFileName("det_et_stabs");

            Map<String,Spreadsheet> spreadsheets = new HashMap<>();
            spreadsheets.put("BLOCS", prepare(fileName_tanks, serviceAccountKey));
            Logger.LOG("#" + spreadsheets.size());
            spreadsheets.put("Détendeur NSA_Stab NSA", prepare(fileName_bcd_and_regs, serviceAccountKey));
            Logger.LOG("#" + spreadsheets.size());

            process(changedRecordsFile, spreadsheets);

            validate(serviceAccountKey, spreadsheets);
        } catch (IOException e) {
            Logger.ERROR_printStacktrace_only(e.getStackTrace());
        } catch (Exception e) {
            Logger.ERROR_printStacktrace("Unexpected error.", e.getStackTrace());
            e.printStackTrace();
        }
    }

    private static void editFile(String ln, Map<String, Spreadsheet> spreadsheets) {
        Gson gson = new Gson();
        JsonObject json = gson.fromJson(ln, JsonObject.class);
        Logger.LOG("#" + spreadsheets.size());
        try {
            for (Map.Entry<String, Spreadsheet> spreadsheet : spreadsheets.entrySet())
                if (spreadsheet.getKey().toUpperCase().contains(json.get("sheetName").getAsString().toUpperCase())) {
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
                "\n{\"sheetName\":\"val\", \"idCol\":\"val\", \"idKey\":\"val\", \"editCol\":\"val\", \"newVal\":\"val\"}");
    }

    private static Spreadsheet prepare(String fileName, String serviceAccountKey) {
        GglApiLayer tcpLayer = new GglApiLayer(fileName, serviceAccountKey);
        if (!tcpLayer.checkConnection()) {
            Logger.ERROR("Unable to connect to Google Drive.");
            return null;
        }

        Spreadsheet spreadsheet = null;
        try {
            spreadsheet = tcpLayer.downloadFile();
        } catch (IOException | GeneralSecurityException e) {
            Logger.WARN("File  with containing DB changes not found. Nothing to do.");
            exit (0);
        }

        return spreadsheet;
    }

    private static void process (String changedRecordsFile, Map<String,Spreadsheet> spreadsheets) {
        String ln;
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(changedRecordsFile);
            BufferedReader br = new BufferedReader(new InputStreamReader(fis));
            while ((ln = br.readLine()) != null)
                editFile(ln, spreadsheets);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void validate(String serviceAccountKey, Map<String, Spreadsheet> spreadsheets) {
        for (Map.Entry<String, Spreadsheet> spreadsheet : spreadsheets.entrySet()) {
            GglApiLayer tcpLayer = new GglApiLayer(spreadsheet.getValue().getFileName(), serviceAccountKey);
            if (!tcpLayer.checkConnection()) {
                Logger.ERROR("Unable to connect to Google Drive.");
                return;
            }

            try (FileOutputStream fos = new FileOutputStream("debug.xlsx")) {
//               fos.write(spreadsheet.getBaos().toByteArray());
                spreadsheet.getValue().getWorkbook().write(fos);
                tcpLayer.uploadFile(spreadsheet.getValue().getBaos());
            } catch (GeneralSecurityException e) {
                Logger.ERROR_printStacktrace("Security issue.", e.getStackTrace());
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                Logger.ERROR_printStacktrace_only(e.getStackTrace());
            }
        }
    }
}
