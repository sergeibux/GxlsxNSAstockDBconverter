package main.java.GxlsxNSAstockDBconverter.utils;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Client of the NSA-app API (api/login.php, api/changes.php), with the account of the configuration
 * (api.email, api.password): the import from the workbooks writes the database like a phone does, so that
 * the server applies its checks (allowed columns, foreign keys, unique references) and its conflict rule
 * (a row more recent in the database than the change is kept: "skipped").
 */
public final class NsaApi {

    /** Answer of changes.php: HTTP code and JSON body (results, or error and index of the change refused). */
    public static final class Response {
        public final int code;
        public final JsonObject body;

        Response(int code, JsonObject body) {
            this.code = code;
            this.body = body;
        }

        /** Index of the change refused by the server (4xx), -1 if none. */
        public int refusedIndex() {
            return body != null && body.has("index") ? body.get("index").getAsInt() : -1;
        }

        public String error() {
            return body != null && body.has("error") ? body.get("error").getAsString() : "HTTP " + code;
        }
    }

    private static final Gson GSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final String baseUrl;
    private final String org;
    private final String email;
    private final String password;
    private final ImportState state;       // keeps the session token between runs (no new token at each run)

    public NsaApi(String baseUrl, String org, String email, String password, ImportState state) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.org = org;
        this.email = email;
        this.password = password;
        this.state = state;
    }

    /**
     * Sends one batch of changes (applied all or nothing by the server). A token refused (401) is renewed once.
     * @throws IOException network error, or an answer that is not JSON
     */
    public Response sendChanges(List<JsonObject> changes) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("o", org);
        body.addProperty("device", state.device());
        JsonArray list = new JsonArray();
        for (JsonObject change : changes)
            list.add(change);
        body.add("changes", list);
        if (!state.hasValidToken())
            login();
        Response response = post("changes.php", body, state.token());
        if (response.code == 401) {
            login();
            response = post("changes.php", body, state.token());
        }
        return response;
    }

    private void login() throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("o", org);
        body.addProperty("email", email);
        body.addProperty("password", password);
        Response response = post("login.php", body, null);
        if (response.code != 200 || response.body == null || !response.body.has("token"))
            throw new IOException("login to the NSA-app API as " + email + " refused: " + response.error());
        state.setToken(response.body.get("token").getAsString(), response.body.get("expires_at").getAsLong());
    }

    private Response post(String endpoint, JsonObject body, String token) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + endpoint))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
        if (token != null)
            request.header("Authorization", "Bearer " + token);
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {       // e.g. ConnectException without message: the address tells what failed
            throw new IOException(baseUrl + endpoint + " unreachable (" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")", e);
        }
        JsonObject json;
        try {
            JsonElement parsed = GSON.fromJson(response.body(), JsonElement.class);
            json = parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (JsonParseException e) {
            json = null;
        }
        if (json == null)
            throw new IOException(endpoint + ": HTTP " + response.statusCode() + ", answer is not JSON");
        return new Response(response.statusCode(), json);
    }
}
