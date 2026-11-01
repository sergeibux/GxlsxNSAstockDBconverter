package main.java.GxlsxNSAstockDBconverter.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * State of the import from the workbooks (key import.state.file, mode 600: it holds the API session token):
 * - device: random id of the converter for the API (its temporary ids are remembered per account + device);
 * - nextTempId: next negative temporary id, never reused (saved before each upload);
 * - token, tokenExpiresAt: API session, renewed when refused or expired;
 * - lastPass: start of the last run (ms), sent as lastUpdate of the imported rows: a change made in the app
 *   after it keeps priority;
 * - versions: workbook => Drive version entirely imported; an unchanged workbook is not downloaded again.
 */
public final class ImportState {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final int FORMAT = 1;
    /** The token is renewed a bit before its expiry, a run never fails half way because of it. */
    private static final long TOKEN_MARGIN_S = 3600;

    private static final class Content {
        int format = FORMAT;
        String device;
        long nextTempId = -1;
        String token;
        long tokenExpiresAt;
        long lastPass;
        Map<String, Long> versions = new LinkedHashMap<>();
    }

    private final Path file;
    private final Content content;

    private ImportState(Path file, Content content) {
        this.file = file;
        this.content = content;
    }

    /**
     * @return the saved state, or a new one (new device id) if the file does not exist
     * @throws IOException if the file exists but cannot be read (not recreated: the temporary ids of the device
     *                     would be reused)
     */
    public static ImportState load(Path file) throws IOException {
        if (!Files.exists(file)) {
            Content content = new Content();
            byte[] id = new byte[16];
            new SecureRandom().nextBytes(id);
            StringBuilder hex = new StringBuilder();
            for (byte b : id)
                hex.append(String.format("%02x", b));
            content.device = hex.toString();
            return new ImportState(file, content);
        }
        Content content;
        try {
            content = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Content.class);
        } catch (JsonParseException e) {
            throw new IOException("import state " + file + " unreadable: " + e.getMessage(), e);
        }
        if (content == null || content.format != FORMAT || content.device == null
                || !content.device.matches("[0-9a-f]{32}") || content.nextTempId >= 0)
            throw new IOException("import state " + file + " unreadable: unknown format");
        if (content.versions == null)
            content.versions = new LinkedHashMap<>();
        return new ImportState(file, content);
    }

    public void save() throws IOException {
        Snapshot.writeAtomically(file, GSON.toJson(content).getBytes(StandardCharsets.UTF_8));
    }

    String device() {
        return content.device;
    }

    /** New temporary id (negative, never given twice: the state must be saved before the upload). */
    int newTempId() {
        long id = content.nextTempId--;
        if (id < Integer.MIN_VALUE)
            throw new IllegalStateException("temporary ids exhausted");
        return (int) id;
    }

    boolean hasValidToken() {
        return content.token != null && content.tokenExpiresAt - TOKEN_MARGIN_S > System.currentTimeMillis() / 1000;
    }

    String token() {
        return content.token;
    }

    /** Saved at once: a token not kept would leave an unused session on the server. */
    void setToken(String token, long expiresAt) throws IOException {
        content.token = token;
        content.tokenExpiresAt = expiresAt;
        save();
    }

    /** @return start of the previous run (ms), 0 if none */
    public long lastPass() {
        return content.lastPass;
    }

    public void setLastPass(long ms) {
        content.lastPass = ms;
    }

    /** @return Drive version of the workbook last imported entirely, null if none */
    public Long importedVersion(String workbook) {
        return content.versions.get(workbook);
    }

    /** @param version null: the workbook must be compared again at the next run */
    public void setImportedVersion(String workbook, Long version) {
        if (version == null)
            content.versions.remove(workbook);
        else
            content.versions.put(workbook, version);
    }
}
