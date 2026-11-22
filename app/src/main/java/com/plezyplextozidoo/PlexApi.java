package com.plezyplextozidoo;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Plex Media Server HTTP API client for Plezy intent handling, metadata resolution,
 * marker-based intro/credits skip, timeline progress, and scrobble.
 */
public class PlexApi {

    private static final String TAG = "PlexApi";

    /** Plezy/Plex part stream URL: /library/parts/{partId}/{id}/file.ext */
    private static final Pattern PART_ID_PATTERN = Pattern.compile(
            "/library/parts/(\\d+)/",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "(?i)[?&]X-Plex-Token=([^&\\s]*)"
    );

    private static volatile OkHttpClient client;
    private static volatile Handler mainHandler;

    private static volatile String clientName = "PlezyPlexToZidoo";
    private static volatile String deviceName = "Zidoo";
    private static volatile String deviceId = "plezyplextozidoo";
    private static volatile String appVersion = "1.0.0";

    /** In-memory partId → ratingKey cache for the process lifetime. */
    private static final Map<String, String> PART_TO_RATING_KEY = new ConcurrentHashMap<>();

    private static OkHttpClient getClient() {
        if (client == null) {
            synchronized (PlexApi.class) {
                if (client == null) {
                    client = new OkHttpClient.Builder()
                            .connectTimeout(10, TimeUnit.SECONDS)
                            .readTimeout(15, TimeUnit.SECONDS)
                            .build();
                }
            }
        }
        return client;
    }

    private static Handler getMainHandler() {
        if (mainHandler == null) {
            synchronized (PlexApi.class) {
                if (mainHandler == null) {
                    mainHandler = new Handler(Looper.getMainLooper());
                }
            }
        }
        return mainHandler;
    }

    private PlexApi() {
        // Prevent instantiation
    }

    public static void setClientIdentity(String deviceName, String deviceId, String appVersion) {
        if (deviceName != null && !deviceName.trim().isEmpty()) {
            PlexApi.deviceName = deviceName.trim();
        }
        if (deviceId != null && !deviceId.trim().isEmpty()) {
            PlexApi.deviceId = deviceId.trim();
        }
        if (appVersion != null && !appVersion.trim().isEmpty()) {
            PlexApi.appVersion = appVersion.trim();
        }
    }

    static void resetClientIdentity() {
        clientName = "PlezyPlexToZidoo";
        deviceName = "Zidoo";
        deviceId = "plezyplextozidoo";
        appVersion = "1.0.0";
    }

    static void clearPartCache() {
        PART_TO_RATING_KEY.clear();
    }

    // --- Callbacks ---

    public interface SimpleCallback {
        void onSuccess(String message);
        void onError(String error);
    }

    public interface MetadataCallback {
        void onSuccess(MetadataResult result);
        void onError(String error);
    }

    public interface ResolveCallback {
        void onResolved(String ratingKey);
        void onError(String error);
    }

    public interface NextUpDetailCallback {
        void onResult(String nextRatingKey, String seriesName, String episodeName,
                      int seasonNumber, int episodeNumber, String seriesRatingKey, String serverPath);
        void onNoNextEpisode();
    }

    public interface SearchByPathCallback {
        void onFound(String ratingKey);
        void onNotFound(String error);
    }

    // --- Result types ---

    /** Parsed Plezy ACTION_VIEW URI pieces. */
    public static class ParsedIntent {
        public final String serverUrl;
        public final String partId;
        public final String token;
        public final String rawUrl;

        public ParsedIntent(String serverUrl, String partId, String token, String rawUrl) {
            this.serverUrl = serverUrl;
            this.partId = partId;
            this.token = token;
            this.rawUrl = rawUrl;
        }
    }

    public static class MetadataResult {
        public final String ratingKey;
        public final String filePath;
        public final long viewOffsetMs;
        public final long durationMs;
        public final String title;
        public final String seriesTitle;
        public final String seriesRatingKey;
        public final String seasonRatingKey;
        public final int seasonNumber;
        public final int episodeNumber;
        public final boolean isEpisode;
        public final long introStartMs;
        public final long introEndMs;
        public final long creditStartMs;
        public final long creditEndMs;
        public final String thumb;
        public final String art;
        public final String metadataKey;

        public MetadataResult(String ratingKey, String filePath, long viewOffsetMs, long durationMs,
                              String title, String seriesTitle, String seriesRatingKey,
                              String seasonRatingKey, int seasonNumber, int episodeNumber,
                              boolean isEpisode, long introStartMs, long introEndMs,
                              long creditStartMs, long creditEndMs, String thumb, String art,
                              String metadataKey) {
            this.ratingKey = ratingKey;
            this.filePath = filePath;
            this.viewOffsetMs = viewOffsetMs;
            this.durationMs = durationMs;
            this.title = title;
            this.seriesTitle = seriesTitle;
            this.seriesRatingKey = seriesRatingKey;
            this.seasonRatingKey = seasonRatingKey;
            this.seasonNumber = seasonNumber;
            this.episodeNumber = episodeNumber;
            this.isEpisode = isEpisode;
            this.introStartMs = introStartMs;
            this.introEndMs = introEndMs;
            this.creditStartMs = creditStartMs;
            this.creditEndMs = creditEndMs;
            this.thumb = thumb;
            this.art = art;
            this.metadataKey = metadataKey;
        }
    }

    public static class MarkerResult {
        public final long introStartMs;
        public final long introEndMs;
        public final long creditStartMs;
        public final long creditEndMs;

        public MarkerResult(long introStartMs, long introEndMs,
                            long creditStartMs, long creditEndMs) {
            this.introStartMs = introStartMs;
            this.introEndMs = introEndMs;
            this.creditStartMs = creditStartMs;
            this.creditEndMs = creditEndMs;
        }

        long introStartMs() { return introStartMs; }
        long introEndMs() { return introEndMs; }
        long creditStartMs() { return creditStartMs; }
        long creditEndMs() { return creditEndMs; }
    }

    private static final MarkerResult EMPTY_MARKERS = new MarkerResult(-1, -1, -1, -1);

    // --- URI parsing ---

    /**
     * Parses a Plezy/Plex part stream URL into server base, partId, and token.
     * Returns null when the URL is not a Plex library parts URL.
     */
    public static ParsedIntent parsePlezyUri(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        Matcher partMatcher = PART_ID_PATTERN.matcher(url);
        if (!partMatcher.find()) {
            return null;
        }
        String partId = partMatcher.group(1);

        String token = null;
        Matcher tokenMatcher = TOKEN_PATTERN.matcher(url);
        if (tokenMatcher.find()) {
            token = tokenMatcher.group(1);
            try {
                token = URLDecoder.decode(token, StandardCharsets.UTF_8.name());
            } catch (Exception ignored) {
                // keep raw
            }
        }

        String serverUrl = extractBaseUrl(url);
        if (serverUrl == null || serverUrl.isEmpty()) {
            return null;
        }
        return new ParsedIntent(serverUrl, partId, token, url);
    }

    /**
     * Extracts scheme://host[:port] from a URL string without Android Uri.
     */
    static String extractBaseUrl(String url) {
        if (url == null) return null;
        try {
            int schemeEnd = url.indexOf("://");
            if (schemeEnd < 0) return null;
            int pathStart = url.indexOf('/', schemeEnd + 3);
            if (pathStart < 0) {
                int query = url.indexOf('?', schemeEnd + 3);
                return query >= 0 ? url.substring(0, query) : url;
            }
            return url.substring(0, pathStart);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts the part id from a Plex parts URL, or null.
     */
    public static String extractPartId(String url) {
        if (url == null) return null;
        Matcher matcher = PART_ID_PATTERN.matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }

    static String extractTokenFromUrl(String url) {
        if (url == null) return null;
        Matcher matcher = TOKEN_PATTERN.matcher(url);
        if (!matcher.find()) return null;
        try {
            return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return matcher.group(1);
        }
    }

    static String baseUrl(String serverUrl) {
        if (serverUrl == null) return "";
        String trimmed = serverUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /**
     * True when playback has reached the watched / scrobble threshold (≥90%).
     */
    public static boolean isWatched(long positionMs, long durationMs) {
        return durationMs > 0 && positionMs > (long) (durationMs * 0.9);
    }

    // Kept for Play.java compatibility where "ticks" historically meant Jellyfin ticks.
    // For Plex everything is already milliseconds, so these are identity helpers.
    public static long ticksToMs(long ticksOrMs) {
        return ticksOrMs;
    }

    public static long msToTicks(long ms) {
        return ms;
    }

    // --- Marker parsing ---

    /**
     * Parses Marker array from a Plex metadata JSON object (or MediaContainer wrapper).
     * Marker types: intro, credits (also accepts outro). Offsets are milliseconds.
     */
    static MarkerResult parseMarkers(JsonObject metadataOrRoot) {
        try {
            JsonObject meta = unwrapMetadata(metadataOrRoot);
            if (meta == null) return EMPTY_MARKERS;

            if (!meta.has("Marker") || !meta.get("Marker").isJsonArray()) {
                return EMPTY_MARKERS;
            }
            JsonArray markers = meta.getAsJsonArray("Marker");
            long introStart = -1, introEnd = -1, creditStart = -1, creditEnd = -1;
            long introStartBest = Long.MAX_VALUE, creditStartBest = Long.MAX_VALUE;

            for (int i = 0; i < markers.size(); i++) {
                JsonElement el = markers.get(i);
                if (el == null || !el.isJsonObject()) continue;
                JsonObject m = el.getAsJsonObject();
                String type = optString(m, "type").toLowerCase(Locale.ENGLISH);
                long start = optLong(m, "startTimeOffset", -1);
                long end = optLong(m, "endTimeOffset", -1);
                if (start < 0 || end <= 0) continue;

                if ("intro".equals(type)) {
                    if (start < introStartBest) {
                        introStartBest = start;
                        introStart = start;
                        introEnd = end;
                    }
                } else if ("credits".equals(type) || "outro".equals(type)) {
                    if (start < creditStartBest) {
                        creditStartBest = start;
                        creditStart = start;
                        creditEnd = end;
                    }
                }
            }
            return new MarkerResult(introStart, introEnd, creditStart, creditEnd);
        } catch (Exception e) {
            return EMPTY_MARKERS;
        }
    }

    static MarkerResult parseMarkersFromBody(String jsonBody) {
        try {
            if (jsonBody == null || jsonBody.trim().isEmpty()) return EMPTY_MARKERS;
            JsonElement root = JsonParser.parseString(jsonBody);
            if (root == null || !root.isJsonObject()) return EMPTY_MARKERS;
            return parseMarkers(root.getAsJsonObject());
        } catch (Exception e) {
            return EMPTY_MARKERS;
        }
    }

    /**
     * Parses a full /library/metadata/{id}?includeMarkers=1 response.
     */
    static MetadataResult parseMetadataResponse(String jsonBody, String fallbackRatingKey) throws Exception {
        JsonObject root = JsonParser.parseString(jsonBody).getAsJsonObject();
        JsonObject meta = unwrapMetadata(root);
        if (meta == null) {
            throw new IllegalStateException("No Metadata in response");
        }

        String ratingKey = optString(meta, "ratingKey");
        if (ratingKey.isEmpty()) ratingKey = fallbackRatingKey != null ? fallbackRatingKey : "";

        String type = optString(meta, "type");
        boolean isEpisode = "episode".equalsIgnoreCase(type);

        String title = optString(meta, "title");
        String seriesTitle = optString(meta, "grandparentTitle");
        if (!seriesTitle.isEmpty() && isEpisode) {
            int season = (int) optLong(meta, "parentIndex", 0);
            int episode = (int) optLong(meta, "index", 0);
            if (season > 0 && episode > 0) {
                title = String.format(Locale.ENGLISH, "%s - S%02dE%02d - %s",
                        seriesTitle, season, episode, optString(meta, "title"));
            }
        }

        long viewOffset = optLong(meta, "viewOffset", 0);
        long duration = optLong(meta, "duration", 0);

        String filePath = extractFilePath(meta);
        String seriesRatingKey = optString(meta, "grandparentRatingKey");
        String seasonRatingKey = optString(meta, "parentRatingKey");
        int seasonNumber = (int) optLong(meta, "parentIndex", 0);
        int episodeNumber = (int) optLong(meta, "index", 0);

        MarkerResult markers = parseMarkers(meta);
        String thumb = optString(meta, "thumb");
        String art = optString(meta, "art");
        if (art.isEmpty()) art = optString(meta, "grandparentArt");
        String metadataKey = optString(meta, "key");
        if (metadataKey.isEmpty() && !ratingKey.isEmpty()) {
            metadataKey = "/library/metadata/" + ratingKey;
        }

        return new MetadataResult(
                ratingKey, filePath, viewOffset, duration, title, seriesTitle,
                seriesRatingKey.isEmpty() ? null : seriesRatingKey,
                seasonRatingKey.isEmpty() ? null : seasonRatingKey,
                seasonNumber, episodeNumber, isEpisode,
                markers.introStartMs, markers.introEndMs,
                markers.creditStartMs, markers.creditEndMs,
                thumb, art, metadataKey
        );
    }

    private static String extractFilePath(JsonObject meta) {
        if (!meta.has("Media") || !meta.get("Media").isJsonArray()) {
            return "";
        }
        JsonArray media = meta.getAsJsonArray("Media");
        for (int i = 0; i < media.size(); i++) {
            JsonElement el = media.get(i);
            if (el == null || !el.isJsonObject()) continue;
            JsonObject m = el.getAsJsonObject();
            if (!m.has("Part") || !m.get("Part").isJsonArray()) continue;
            JsonArray parts = m.getAsJsonArray("Part");
            for (int j = 0; j < parts.size(); j++) {
                JsonElement pe = parts.get(j);
                if (pe == null || !pe.isJsonObject()) continue;
                String file = optString(pe.getAsJsonObject(), "file");
                if (!file.isEmpty()) return file;
            }
        }
        return "";
    }

    /**
     * Finds ratingKey whose Media.Part.id matches partId inside a MediaContainer JSON body.
     */
    static String findRatingKeyForPartId(String jsonBody, String partId) {
        if (jsonBody == null || partId == null) return null;
        try {
            JsonObject root = JsonParser.parseString(jsonBody).getAsJsonObject();
            JsonArray metadata = getMetadataArray(root);
            if (metadata == null) return null;

            for (int i = 0; i < metadata.size(); i++) {
                JsonElement el = metadata.get(i);
                if (el == null || !el.isJsonObject()) continue;
                JsonObject meta = el.getAsJsonObject();
                if (metadataContainsPartId(meta, partId)) {
                    String rk = optString(meta, "ratingKey");
                    return rk.isEmpty() ? null : rk;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "findRatingKeyForPartId parse error: " + e.getMessage());
        }
        return null;
    }

    private static boolean metadataContainsPartId(JsonObject meta, String partId) {
        if (!meta.has("Media") || !meta.get("Media").isJsonArray()) return false;
        JsonArray media = meta.getAsJsonArray("Media");
        for (int i = 0; i < media.size(); i++) {
            JsonElement el = media.get(i);
            if (el == null || !el.isJsonObject()) continue;
            JsonObject m = el.getAsJsonObject();
            if (!m.has("Part") || !m.get("Part").isJsonArray()) continue;
            JsonArray parts = m.getAsJsonArray("Part");
            for (int j = 0; j < parts.size(); j++) {
                JsonElement pe = parts.get(j);
                if (pe == null || !pe.isJsonObject()) continue;
                JsonObject part = pe.getAsJsonObject();
                String id = optString(part, "id");
                if (id.isEmpty() && part.has("id")) {
                    try { id = String.valueOf(part.get("id").getAsLong()); } catch (Exception ignored) {}
                }
                if (partId.equals(id)) return true;
            }
        }
        return false;
    }

    private static JsonObject unwrapMetadata(JsonObject root) {
        if (root == null) return null;
        if (root.has("MediaContainer")) {
            JsonObject mc = root.getAsJsonObject("MediaContainer");
            if (mc.has("Metadata") && mc.get("Metadata").isJsonArray()) {
                JsonArray arr = mc.getAsJsonArray("Metadata");
                if (arr.size() > 0 && arr.get(0).isJsonObject()) {
                    return arr.get(0).getAsJsonObject();
                }
            }
            return null;
        }
        if (root.has("Metadata") && root.get("Metadata").isJsonArray()) {
            JsonArray arr = root.getAsJsonArray("Metadata");
            if (arr.size() > 0 && arr.get(0).isJsonObject()) {
                return arr.get(0).getAsJsonObject();
            }
        }
        // Already a bare Metadata object
        if (root.has("ratingKey") || root.has("Marker") || root.has("Media")) {
            return root;
        }
        return null;
    }

    private static JsonArray getMetadataArray(JsonObject root) {
        if (root.has("MediaContainer") && root.get("MediaContainer").isJsonObject()) {
            JsonObject mc = root.getAsJsonObject("MediaContainer");
            if (mc.has("Metadata") && mc.get("Metadata").isJsonArray()) {
                return mc.getAsJsonArray("Metadata");
            }
        }
        if (root.has("Metadata") && root.get("Metadata").isJsonArray()) {
            return root.getAsJsonArray("Metadata");
        }
        return null;
    }

    // --- Path helpers (shared with Zidoo binge path) ---

    static String normalizeZidooPath(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(path, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            decoded = path;
        }
        String mountPrefix = "/data/system/smb/";
        if (decoded.startsWith(mountPrefix)) {
            String remainder = decoded.substring(mountPrefix.length());
            int hashIndex = remainder.indexOf('#');
            if (hashIndex >= 0) {
                String host = remainder.substring(0, hashIndex);
                String rest = remainder.substring(hashIndex + 1);
                return "smb://" + host + "/" + rest;
            }
            return "smb://" + remainder;
        }
        return decoded.replaceFirst("smb://[^@]+@", "smb://");
    }

    static String reverseSubstitution(String zidooPath, String[][] rules) {
        String stripped = normalizeZidooPath(zidooPath);
        if (stripped == null) {
            return null;
        }
        for (String[] rule : rules) {
            if (rule.length < 2) continue;
            String pathToReplace = rule[0];
            String replacedWith = rule[1];
            if (!replacedWith.isEmpty() && stripped.startsWith(replacedWith)) {
                return pathToReplace + stripped.substring(replacedWith.length());
            }
        }
        return null;
    }

    static boolean isSameZidooFile(String a, String b) {
        String normalizedA = normalizeZidooPath(a);
        String normalizedB = normalizeZidooPath(b);
        return normalizedA != null && normalizedA.equals(normalizedB);
    }

    static boolean isZidooFileChange(String nowPath, String currentPath) {
        String normalizedNow = normalizeZidooPath(nowPath);
        String normalizedCurrent = normalizeZidooPath(currentPath);
        return normalizedNow != null && normalizedCurrent != null
                && !normalizedNow.equals(normalizedCurrent);
    }

    static String extractSearchName(String serverPath) {
        if (serverPath == null || serverPath.isEmpty()) {
            return null;
        }
        int lastSlash = serverPath.lastIndexOf('/');
        String filename = (lastSlash >= 0) ? serverPath.substring(lastSlash + 1) : serverPath;
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            filename = filename.substring(0, lastDot);
        }
        return filename;
    }

    // --- HTTP helpers ---

    private static Request.Builder plexRequest(String url, String token) {
        Request.Builder b = new Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("X-Plex-Client-Identifier", deviceId)
                .addHeader("X-Plex-Product", clientName)
                .addHeader("X-Plex-Version", appVersion)
                .addHeader("X-Plex-Device", deviceName)
                .addHeader("X-Plex-Device-Name", deviceName)
                .addHeader("X-Plex-Platform", "Android")
                .addHeader("X-Plex-Provides", "player");
        if (token != null && !token.isEmpty()) {
            b.addHeader("X-Plex-Token", token);
        }
        return b;
    }

    private static void getJson(String url, String token, SimpleCallback callback) {
        Request request = plexRequest(url, token).get().build();
        getClient().newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "GET failed: " + url, e);
                getMainHandler().post(() -> callback.onError("Network error: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String body = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        final String msg = "HTTP error " + response.code();
                        getMainHandler().post(() -> callback.onError(msg));
                        return;
                    }
                    getMainHandler().post(() -> callback.onSuccess(body));
                } finally {
                    response.close();
                }
            }
        });
    }

    // --- Public API methods ---

    /**
     * Resolves a Plex partId to a ratingKey via cache, then onDeck → recentlyAdded → sections.
     */
    public static void resolvePartId(String serverUrl, String token, String partId,
                                     ResolveCallback callback) {
        if (partId == null || partId.isEmpty()) {
            getMainHandler().post(() -> callback.onError("Missing partId"));
            return;
        }
        String cached = PART_TO_RATING_KEY.get(partId);
        if (cached != null) {
            getMainHandler().post(() -> callback.onResolved(cached));
            return;
        }

        String base = baseUrl(serverUrl);
        resolveFromUrl(base, token, partId, base + "/library/onDeck", () ->
                resolveFromUrl(base, token, partId, base + "/library/recentlyAdded", () ->
                        resolveViaSections(base, token, partId, callback)
                , callback)
        , callback);
    }

    private interface NextStep {
        void run();
    }

    private static void resolveFromUrl(String base, String token, String partId, String url,
                                       NextStep next, ResolveCallback callback) {
        getJson(url, token, new SimpleCallback() {
            @Override
            public void onSuccess(String body) {
                String rk = findRatingKeyForPartId(body, partId);
                if (rk != null) {
                    PART_TO_RATING_KEY.put(partId, rk);
                    callback.onResolved(rk);
                } else {
                    next.run();
                }
            }

            @Override
            public void onError(String error) {
                next.run();
            }
        });
    }

    private static void resolveViaSections(String base, String token, String partId,
                                           ResolveCallback callback) {
        getJson(base + "/library/sections", token, new SimpleCallback() {
            @Override
            public void onSuccess(String body) {
                try {
                    JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                    JsonArray dirs = null;
                    if (root.has("MediaContainer") && root.get("MediaContainer").isJsonObject()) {
                        JsonObject mc = root.getAsJsonObject("MediaContainer");
                        if (mc.has("Directory") && mc.get("Directory").isJsonArray()) {
                            dirs = mc.getAsJsonArray("Directory");
                        }
                    }
                    if (dirs == null || dirs.size() == 0) {
                        callback.onError("partId " + partId + " not found in library");
                        return;
                    }
                    java.util.ArrayList<String> sectionUrls = new java.util.ArrayList<>();
                    for (int i = 0; i < dirs.size(); i++) {
                        JsonElement el = dirs.get(i);
                        if (el == null || !el.isJsonObject()) continue;
                        JsonObject dir = el.getAsJsonObject();
                        String key = optString(dir, "key");
                        String type = optString(dir, "type");
                        if (key.isEmpty()) continue;
                        // type=1 movies, type=4 episodes
                        if ("movie".equalsIgnoreCase(type)) {
                            sectionUrls.add(base + "/library/sections/" + key + "/all?type=1");
                        } else if ("show".equalsIgnoreCase(type)) {
                            sectionUrls.add(base + "/library/sections/" + key + "/all?type=4");
                        }
                    }
                    searchSectionsSequentially(base, token, partId, sectionUrls, 0, callback);
                } catch (Exception e) {
                    callback.onError("Failed to parse sections: " + e.getMessage());
                }
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        });
    }

    private static void searchSectionsSequentially(String base, String token, String partId,
                                                    java.util.List<String> urls, int index,
                                                    ResolveCallback callback) {
        if (index >= urls.size()) {
            callback.onError("partId " + partId + " not found in library sections");
            return;
        }
        getJson(urls.get(index), token, new SimpleCallback() {
            @Override
            public void onSuccess(String body) {
                String rk = findRatingKeyForPartId(body, partId);
                if (rk != null) {
                    PART_TO_RATING_KEY.put(partId, rk);
                    callback.onResolved(rk);
                } else {
                    searchSectionsSequentially(base, token, partId, urls, index + 1, callback);
                }
            }

            @Override
            public void onError(String error) {
                searchSectionsSequentially(base, token, partId, urls, index + 1, callback);
            }
        });
    }

    /**
     * Fetches metadata including markers for a ratingKey.
     */
    public static void getMetadata(String serverUrl, String token, String ratingKey,
                                   MetadataCallback callback) {
        String url = baseUrl(serverUrl) + "/library/metadata/" + ratingKey + "?includeMarkers=1";
        getJson(url, token, new SimpleCallback() {
            @Override
            public void onSuccess(String body) {
                try {
                    MetadataResult result = parseMetadataResponse(body, ratingKey);
                    callback.onSuccess(result);
                } catch (Exception e) {
                    callback.onError("Parse error: " + e.getMessage());
                }
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        });
    }

    /**
     * Reports playback state to Plex via /:/timeline.
     * time and duration are milliseconds.
     */
    public static void reportTimeline(String serverUrl, String token, String ratingKey,
                                      String metadataKey, String state,
                                      long timeMs, long durationMs, SimpleCallback callback) {
        String key = metadataKey != null && !metadataKey.isEmpty()
                ? metadataKey : "/library/metadata/" + ratingKey;
        HttpUrl.Builder urlBuilder = HttpUrl.parse(baseUrl(serverUrl) + "/:/timeline").newBuilder()
                .addQueryParameter("ratingKey", ratingKey)
                .addQueryParameter("key", key)
                .addQueryParameter("state", state)
                .addQueryParameter("time", String.valueOf(Math.max(0, timeMs)))
                .addQueryParameter("duration", String.valueOf(Math.max(0, durationMs)))
                .addQueryParameter("playbackTime", String.valueOf(Math.max(0, timeMs)))
                .addQueryParameter("X-Plex-Client-Identifier", deviceId);
        if (token != null && !token.isEmpty()) {
            urlBuilder.addQueryParameter("X-Plex-Token", token);
        }

        Request request = plexRequest(urlBuilder.build().toString(), token).get().build();
        getClient().newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "timeline failed: " + e.getMessage());
                if (callback != null) {
                    getMainHandler().post(() -> callback.onError("Network error: " + e.getMessage()));
                }
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (callback != null) {
                        if (response.isSuccessful()) {
                            getMainHandler().post(() -> callback.onSuccess("timeline ok"));
                        } else {
                            final String msg = "HTTP error " + response.code();
                            getMainHandler().post(() -> callback.onError(msg));
                        }
                    }
                } finally {
                    response.close();
                }
            }
        });
    }

    /**
     * Marks an item watched via Plex scrobble endpoint.
     */
    public static void scrobble(String serverUrl, String token, String ratingKey,
                                SimpleCallback callback) {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(baseUrl(serverUrl) + "/:/scrobble").newBuilder()
                .addQueryParameter("key", ratingKey)
                .addQueryParameter("identifier", "com.plexapp.plugins.library");
        if (token != null && !token.isEmpty()) {
            urlBuilder.addQueryParameter("X-Plex-Token", token);
        }
        Request request = plexRequest(urlBuilder.build().toString(), token).get().build();
        getClient().newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "scrobble failed: " + e.getMessage());
                getMainHandler().post(() -> callback.onError("Network error: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (response.isSuccessful()) {
                        getMainHandler().post(() -> callback.onSuccess("scrobble ok"));
                    } else {
                        final String msg = "HTTP error " + response.code();
                        getMainHandler().post(() -> callback.onError(msg));
                    }
                } finally {
                    response.close();
                }
            }
        });
    }

    /**
     * Convenience: timeline stop, then scrobble when ≥90% watched.
     */
    public static void reportPlaybackStopped(String serverUrl, String token, String ratingKey,
                                             String metadataKey, long positionMs, long durationMs,
                                             SimpleCallback callback) {
        reportTimeline(serverUrl, token, ratingKey, metadataKey, "stopped",
                positionMs, durationMs, new SimpleCallback() {
                    @Override
                    public void onSuccess(String message) {
                        if (isWatched(positionMs, durationMs)) {
                            scrobble(serverUrl, token, ratingKey, callback);
                        } else if (callback != null) {
                            callback.onSuccess(message);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        if (callback != null) callback.onError(error);
                    }
                });
    }

    /**
     * Finds the next episode in the same season after the current episode index.
     */
    public static void getNextEpisode(String serverUrl, String token,
                                      String seasonRatingKey, int currentEpisodeIndex,
                                      String seriesTitle, String seriesRatingKey,
                                      NextUpDetailCallback callback) {
        if (seasonRatingKey == null || seasonRatingKey.isEmpty()) {
            getMainHandler().post(callback::onNoNextEpisode);
            return;
        }
        String url = baseUrl(serverUrl) + "/library/metadata/" + seasonRatingKey + "/children";
        getJson(url, token, new SimpleCallback() {
            @Override
            public void onSuccess(String body) {
                try {
                    JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                    JsonArray metadata = getMetadataArray(root);
                    if (metadata == null) {
                        callback.onNoNextEpisode();
                        return;
                    }
                    JsonObject next = null;
                    int bestIndex = Integer.MAX_VALUE;
                    for (int i = 0; i < metadata.size(); i++) {
                        JsonElement el = metadata.get(i);
                        if (el == null || !el.isJsonObject()) continue;
                        JsonObject ep = el.getAsJsonObject();
                        if (!"episode".equalsIgnoreCase(optString(ep, "type"))) continue;
                        int index = (int) optLong(ep, "index", -1);
                        if (index > currentEpisodeIndex && index < bestIndex) {
                            bestIndex = index;
                            next = ep;
                        }
                    }
                    if (next == null) {
                        callback.onNoNextEpisode();
                        return;
                    }
                    String nextKey = optString(next, "ratingKey");
                    String epTitle = optString(next, "title");
                    int seasonNum = (int) optLong(next, "parentIndex", 0);
                    int epNum = (int) optLong(next, "index", 0);
                    String path = extractFilePath(next);
                    String seriesName = seriesTitle != null ? seriesTitle : optString(next, "grandparentTitle");
                    String seriesKey = seriesRatingKey != null ? seriesRatingKey
                            : optString(next, "grandparentRatingKey");
                    callback.onResult(nextKey, seriesName, epTitle, seasonNum, epNum, seriesKey, path);
                } catch (Exception e) {
                    Log.w(TAG, "getNextEpisode parse error: " + e.getMessage());
                    callback.onNoNextEpisode();
                }
            }

            @Override
            public void onError(String error) {
                callback.onNoNextEpisode();
            }
        });
    }

    /**
     * Searches library for an item whose Part.file matches the server path (binge advance).
     */
    public static void searchItemByPath(String serverUrl, String token, String serverPath,
                                        SearchByPathCallback callback) {
        String searchName = extractSearchName(serverPath);
        if (searchName == null || searchName.isEmpty()) {
            getMainHandler().post(() -> callback.onNotFound("Could not extract search name from path"));
            return;
        }
        String encoded;
        try {
            encoded = URLEncoder.encode(searchName, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            encoded = searchName;
        }
        String url = baseUrl(serverUrl) + "/hubs/search?query=" + encoded + "&limit=50";
        getJson(url, token, new SimpleCallback() {
            @Override
            public void onSuccess(String body) {
                String rk = findRatingKeyByFilePath(body, serverPath);
                if (rk != null) {
                    callback.onFound(rk);
                } else {
                    // Fallback: scan movie + episode sections
                    searchPathViaSections(baseUrl(serverUrl), token, serverPath, callback);
                }
            }

            @Override
            public void onError(String error) {
                searchPathViaSections(baseUrl(serverUrl), token, serverPath, callback);
            }
        });
    }

    private static void searchPathViaSections(String base, String token, String serverPath,
                                              SearchByPathCallback callback) {
        getJson(base + "/library/sections", token, new SimpleCallback() {
            @Override
            public void onSuccess(String body) {
                try {
                    JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                    JsonArray dirs = null;
                    if (root.has("MediaContainer") && root.get("MediaContainer").isJsonObject()) {
                        JsonObject mc = root.getAsJsonObject("MediaContainer");
                        if (mc.has("Directory") && mc.get("Directory").isJsonArray()) {
                            dirs = mc.getAsJsonArray("Directory");
                        }
                    }
                    java.util.ArrayList<String> urls = new java.util.ArrayList<>();
                    if (dirs != null) {
                        for (int i = 0; i < dirs.size(); i++) {
                            JsonElement el = dirs.get(i);
                            if (el == null || !el.isJsonObject()) continue;
                            JsonObject dir = el.getAsJsonObject();
                            String key = optString(dir, "key");
                            String type = optString(dir, "type");
                            if (key.isEmpty()) continue;
                            if ("movie".equalsIgnoreCase(type)) {
                                urls.add(base + "/library/sections/" + key + "/all?type=1");
                            } else if ("show".equalsIgnoreCase(type)) {
                                urls.add(base + "/library/sections/" + key + "/all?type=4");
                            }
                        }
                    }
                    searchPathSectionsSequentially(token, serverPath, urls, 0, callback);
                } catch (Exception e) {
                    callback.onNotFound("Parse error: " + e.getMessage());
                }
            }

            @Override
            public void onError(String error) {
                callback.onNotFound(error);
            }
        });
    }

    private static void searchPathSectionsSequentially(String token, String serverPath,
                                                       java.util.List<String> urls, int index,
                                                       SearchByPathCallback callback) {
        if (index >= urls.size()) {
            callback.onNotFound("No item found matching path");
            return;
        }
        getJson(urls.get(index), token, new SimpleCallback() {
            @Override
            public void onSuccess(String body) {
                String rk = findRatingKeyByFilePath(body, serverPath);
                if (rk != null) {
                    callback.onFound(rk);
                } else {
                    searchPathSectionsSequentially(token, serverPath, urls, index + 1, callback);
                }
            }

            @Override
            public void onError(String error) {
                searchPathSectionsSequentially(token, serverPath, urls, index + 1, callback);
            }
        });
    }

    static String findRatingKeyByFilePath(String jsonBody, String expectedPath) {
        if (jsonBody == null || expectedPath == null) return null;
        try {
            JsonObject root = JsonParser.parseString(jsonBody).getAsJsonObject();
            // hubs/search nests Metadata under Hub
            if (root.has("MediaContainer") && root.get("MediaContainer").isJsonObject()) {
                JsonObject mc = root.getAsJsonObject("MediaContainer");
                if (mc.has("Hub") && mc.get("Hub").isJsonArray()) {
                    JsonArray hubs = mc.getAsJsonArray("Hub");
                    for (int h = 0; h < hubs.size(); h++) {
                        JsonElement he = hubs.get(h);
                        if (he == null || !he.isJsonObject()) continue;
                        JsonObject hub = he.getAsJsonObject();
                        if (!hub.has("Metadata") || !hub.get("Metadata").isJsonArray()) continue;
                        String rk = matchPathInMetadataArray(hub.getAsJsonArray("Metadata"), expectedPath);
                        if (rk != null) return rk;
                    }
                }
            }
            JsonArray metadata = getMetadataArray(root);
            if (metadata != null) {
                return matchPathInMetadataArray(metadata, expectedPath);
            }
        } catch (Exception e) {
            Log.w(TAG, "findRatingKeyByFilePath: " + e.getMessage());
        }
        return null;
    }

    private static String matchPathInMetadataArray(JsonArray metadata, String expectedPath) {
        for (int i = 0; i < metadata.size(); i++) {
            JsonElement el = metadata.get(i);
            if (el == null || !el.isJsonObject()) continue;
            JsonObject meta = el.getAsJsonObject();
            String path = extractFilePath(meta);
            if (expectedPath.equals(path)) {
                String rk = optString(meta, "ratingKey");
                return rk.isEmpty() ? null : rk;
            }
        }
        return null;
    }

    public static void testConnection(String serverUrl, String token, SimpleCallback callback) {
        getJson(baseUrl(serverUrl) + "/identity", token, new SimpleCallback() {
            @Override
            public void onSuccess(String body) {
                String name = "Plex Server";
                try {
                    JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                    JsonObject mc = root.has("MediaContainer")
                            ? root.getAsJsonObject("MediaContainer") : root;
                    String n = optString(mc, "machineIdentifier");
                    if (!n.isEmpty()) name = "Plex (" + n + ")";
                    String friendly = optString(mc, "friendlyName");
                    if (!friendly.isEmpty()) name = friendly;
                } catch (Exception ignored) {
                }
                final String display = name;
                callback.onSuccess("Connected to " + display);
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        });
    }

    /**
     * Builds a Plex image URL with token for Glide.
     */
    public static String buildImageUrl(String serverUrl, String path, String token) {
        if (path == null || path.isEmpty()) return "";
        String base = baseUrl(serverUrl);
        String url = path.startsWith("http") ? path : base + path;
        if (token != null && !token.isEmpty()) {
            url += (url.contains("?") ? "&" : "?") + "X-Plex-Token=" + token;
        }
        return url;
    }

    // --- JSON helpers ---

    private static String optString(JsonObject object, String member) {
        if (object == null || !object.has(member) || object.get(member).isJsonNull()) {
            return "";
        }
        JsonElement element = object.get(member);
        if (!element.isJsonPrimitive()) {
            return "";
        }
        return element.getAsString();
    }

    private static long optLong(JsonObject object, String member, long fallback) {
        if (object == null || !object.has(member) || object.get(member).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(member).getAsLong();
        } catch (Exception e) {
            return fallback;
        }
    }
}
