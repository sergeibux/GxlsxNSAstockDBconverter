package main.java.GxlsxNSAstockDBconverter.utils;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class ConfigReader {
    private Properties properties;

    public ConfigReader(String configFilePath) throws IOException {
        properties = new Properties();
        properties.load(new InputStreamReader(new FileInputStream (configFilePath), StandardCharsets.UTF_8));
    }

    public String getFileName(String file) {
        return properties.getProperty("fileName." + file);
    }

    public String getApiKey() {
        return properties.getProperty("apiKey");
    }

    /**
     * Columns written only when a row is created, separated by ";" (key protected.columns).
     * @return null when the key does not exist (the default list of Spreadsheet applies); empty if the key is empty
     */
    public List<String> getProtectedColumns() {
        String value = properties.getProperty("protected.columns");
        if (value == null)
            return null;
        List<String> titles = new ArrayList<>();
        for (String title : value.split(";"))
            if (!title.isBlank())
                titles.add(title.trim());
        return titles;
    }

    /** NSA-app database of the organisation, read-only (key db.file, e.g. /var/lib/nsa/orgs/NSA.db); null if unset. */
    public String getDbFile() {
        String path = properties.getProperty("db.file", "").trim();
        return path.isEmpty() ? null : path;
    }

    /** Values of the workbooks at the last successful run (key snapshot.file, mode 600); null if unset. */
    public String getSnapshotFile() {
        String path = properties.getProperty("snapshot.file", "").trim();
        return path.isEmpty() ? null : path;
    }

    /**
     * Local tests only: file where a copy of each workbook is written before its upload (key debug.copy).
     * @return null when the key is unset or empty (the server never writes such a copy)
     */
    public String getDebugCopy() {
        String path = properties.getProperty("debug.copy", "").trim();
        return path.isEmpty() ? null : path;
    }
}
