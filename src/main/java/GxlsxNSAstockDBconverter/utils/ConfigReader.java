package main.java.GxlsxNSAstockDBconverter.utils;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
}
