package com.plezyplextozidoo;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.IOException;
import java.io.StringReader;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

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

    public interface ScreensaverCallback {
        void onReady(List<ScreensaverItem> items);
        void onError(String error);
    }

    /** One Plex library item suitable for the artwork screensaver. */
    public static class ScreensaverItem {
        public final String ratingKey;
        public final String title;
        public final String artPath;
        public final String logoPath;
        public final String originallyAvailableAt;
        public final String type;

        public ScreensaverItem(String ratingKey, String title, String artPath, String logoPath,
                               String originallyAvailableAt, String type) {
            this.ratingKey = ratingKey;
            this.title = title;
            this.artPath = artPath;
            this.logoPath = logoPath;
            this.originallyAvailableAt = originallyAvailableAt;
            this.type = type;
        }

        public boolean hasTransparentLogo() {
            return logoPath != null && !logoPath.isEmpty();
        }
    }

    /** One library section from {@code /library/sections} (JSON or XML). */
    public static class LibrarySection {
        public final String key;
        public final String type;

        public LibrarySection(String key, String type) {
            this.key = key != null ? key : "";
            this.type = type != null ? type : "";
        }
    }

    /** Genuine 4K artwork thresholds (never treat upscaled images as 4K). */
    public static final int GENUINE_4K_MIN_WIDTH = 3840;
    public static final int GENUINE_4K_MIN_HEIGHT = 2160;

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
        // Strip trailing slashes.
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        // Browser "Plex Web" paths are not the PMS API root; /web/... returns HTML.
        if (trimmed.toLowerCase(Locale.ROOT).endsWith("/web/index.html")) {
            trimmed = trimmed.substring(0, trimmed.length() - "/web/index.html".length());
        } else if (trimmed.toLowerCase(Locale.ROOT).endsWith("/web")) {
            trimmed = trimmed.substring(0, trimmed.length() - "/web".length());
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** Default Plex Media Server listen port (LAN). */
    public static final int DEFAULT_PLEX_PORT = 32400;

    /**
     * When {@code serverUrl} is a bare LAN IP/host with no explicit port, returns the same
     * URL with {@code :32400} after the host. Returns {@code null} when no suggestion should
     * be offered (already has a port, non-LAN / reverse-proxy style URL, or unparseable).
     * Settings must warn and offer — never silently rewrite custom ports or proxy URLs.
     */
    public static String suggestAppendDefaultPlexPort(String serverUrl) {
        if (serverUrl == null) return null;
        String trimmed = serverUrl.trim();
        if (trimmed.isEmpty()) return null;

        HttpUrl parsed = HttpUrl.parse(trimmed);
        boolean addedScheme = false;
        if (parsed == null) {
            parsed = HttpUrl.parse("http://" + trimmed);
            addedScheme = parsed != null;
        }
        if (parsed == null) return null;

        String host = parsed.host();
        if (host == null || host.isEmpty()) return null;
        if (!isLanPlexHost(host)) return null;

        // Explicit port in the typed URL — never rewrite custom ports.
        String authoritySource = addedScheme ? trimmed : trimmed;
        if (hasExplicitPort(authoritySource, host)) return null;

        // Path beyond "/" usually means reverse proxy — never rewrite those.
        String path = parsed.encodedPath();
        if (path != null && path.length() > 1) return null;

        HttpUrl suggested = parsed.newBuilder()
                .port(DEFAULT_PLEX_PORT)
                .encodedPath("/")
                .query(null)
                .fragment(null)
                .build();
        String out = suggested.toString();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    /** True when the typed URL includes an explicit {@code :port} after the host. */
    static boolean hasExplicitPort(String rawUrl, String host) {
        if (rawUrl == null || host == null || host.isEmpty()) return false;
        String lower = rawUrl.toLowerCase(Locale.ROOT);
        String hostLower = host.toLowerCase(Locale.ROOT);
        int hostAt = lower.indexOf('[' + hostLower + ']');
        int hostLen;
        if (hostAt >= 0) {
            hostLen = hostLower.length() + 2; // brackets
        } else {
            hostAt = lower.indexOf(hostLower);
            if (hostAt < 0) return false;
            hostLen = hostLower.length();
        }
        int after = hostAt + hostLen;
        return after < rawUrl.length() && rawUrl.charAt(after) == ':';
    }

    /**
     * LAN-ish hosts where PMS commonly listens on :32400 without a reverse proxy.
     * Public DNS names are treated as reverse-proxy candidates (no port suggestion).
     */
    static boolean isLanPlexHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home")) {
            return true;
        }
        if (!h.contains(".") && !h.contains(":")) {
            return true; // single-label LAN hostname
        }
        if (isPrivateIpv4(h)) return true;
        // IPv6 unique-local / link-local (HttpUrl host is unbracketed).
        if (h.startsWith("fd") || h.startsWith("fc") || h.startsWith("fe80:")) {
            return true;
        }
        return false;
    }

    static boolean isPrivateIpv4(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;
        try {
            int[] oct = new int[4];
            for (int i = 0; i < 4; i++) {
                oct[i] = Integer.parseInt(parts[i]);
                if (oct[i] < 0 || oct[i] > 255) return false;
            }
            if (oct[0] == 10) return true;
            if (oct[0] == 127) return true;
            if (oct[0] == 192 && oct[1] == 168) return true;
            if (oct[0] == 172 && oct[1] >= 16 && oct[1] <= 31) return true;
            return false;
        } catch (NumberFormatException e) {
            return false;
        }
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
            JsonArray arr = memberAsArray(mc, "Metadata");
            if (arr.size() > 0) return arr;
        }
        JsonArray top = memberAsArray(root, "Metadata");
        return top.size() > 0 ? top : null;
    }

    /**
     * Plex JSON often emits a bare object when a collection has exactly one entry.
     * Never returns null.
     */
    static JsonArray memberAsArray(JsonObject parent, String name) {
        if (parent == null || name == null || !parent.has(name) || parent.get(name).isJsonNull()) {
            return new JsonArray();
        }
        JsonElement el = parent.get(name);
        if (el.isJsonArray()) {
            return el.getAsJsonArray();
        }
        if (el.isJsonObject()) {
            JsonArray arr = new JsonArray();
            arr.add(el);
            return arr;
        }
        return new JsonArray();
    }

    /** Strips a leading UTF-8 BOM so sniffing/parsing see the real payload. */
    static String stripBom(String body) {
        if (body != null && !body.isEmpty() && body.charAt(0) == '\uFEFF') {
            return body.substring(1);
        }
        return body;
    }

    /**
     * Classifies a Plex HTTP body without logging contents.
     * Values: empty | json | xml | html | other
     */
    static String classifyBodyKind(String body) {
        String s = stripBom(body);
        if (s == null) return "empty";
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        if (i >= s.length()) return "empty";
        char c = s.charAt(i);
        if (c == '{' || c == '[') return "json";
        if (c == '<') {
            // HTML gate / Plex Web — must not be fed to the PMS XML parser (11.4 failure mode).
            String head = s.substring(i, Math.min(i + 64, s.length())).toLowerCase(Locale.ROOT);
            if (head.startsWith("<!doctype html")
                    || head.startsWith("<html")
                    || head.startsWith("<head")
                    || head.startsWith("<body")
                    || head.contains("<html")) {
                return "html";
            }
            return "xml";
        }
        return "other";
    }

    /** True when the body looks like XML (PMS default when Accept: json is ignored). */
    static boolean looksLikeXml(String body) {
        return "xml".equals(classifyBodyKind(body));
    }

    /** True when the body is an HTML page (proxy gate, /web UI, etc.). */
    static boolean looksLikeHtml(String body) {
        return "html".equals(classifyBodyKind(body));
    }

    private static Document parseXmlDocument(String body) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
        } catch (Exception ignored) {
            // Some Android DocumentBuilderFactory implementations omit these features.
        }
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(stripBom(body))));
    }

    /**
     * Parses {@code /library/sections} as JSON or XML.
     * Does not log the body (may contain library titles); never logs tokens.
     * Rejects HTML explicitly — the 11.4 XML path mis-handled HTML as XML.
     */
    static List<LibrarySection> parseLibrarySections(String body) {
        List<LibrarySection> out = new ArrayList<>();
        String kind = classifyBodyKind(body);
        if ("empty".equals(kind)) {
            return out;
        }
        if ("html".equals(kind)) {
            throw new IllegalArgumentException(
                    "Plex returned HTML instead of library sections");
        }
        if ("xml".equals(kind)) {
            return parseLibrarySectionsXml(stripBom(body));
        }
        if ("json".equals(kind)) {
            return parseLibrarySectionsJson(stripBom(body));
        }
        throw new IllegalArgumentException("Unrecognized Plex sections payload (" + kind + ")");
    }

    private static List<LibrarySection> parseLibrarySectionsJson(String body) {
        List<LibrarySection> out = new ArrayList<>();
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonObject mc = root.has("MediaContainer") && root.get("MediaContainer").isJsonObject()
                ? root.getAsJsonObject("MediaContainer") : root;
        JsonArray dirs = memberAsArray(mc, "Directory");
        for (int i = 0; i < dirs.size(); i++) {
            JsonElement el = dirs.get(i);
            if (el == null || !el.isJsonObject()) continue;
            JsonObject dir = el.getAsJsonObject();
            String key = optString(dir, "key");
            if (key.isEmpty()) continue;
            out.add(new LibrarySection(key, optString(dir, "type")));
        }
        return out;
    }

    private static List<LibrarySection> parseLibrarySectionsXml(String body) {
        List<LibrarySection> out = new ArrayList<>();
        try {
            Document doc = parseXmlDocument(body);
            NodeList dirs = doc.getElementsByTagName("Directory");
            for (int i = 0; i < dirs.getLength(); i++) {
                Node n = dirs.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element el = (Element) n;
                // Only top-level section directories (skip nested Location etc. without type/key).
                String key = el.getAttribute("key");
                if (key == null || key.isEmpty()) continue;
                String type = el.getAttribute("type");
                out.add(new LibrarySection(key, type != null ? type : ""));
            }
        } catch (Exception e) {
            Log.w(TAG, "parseLibrarySectionsXml: " + e.getClass().getSimpleName());
            throw new IllegalArgumentException("Invalid Plex XML sections response");
        }
        return out;
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

    /**
     * Ensures {@code X-Plex-Token} is present as a query parameter.
     * Playback / image URLs already use query tokens; header-only GETs fail when a
     * reverse proxy strips custom headers and returns an HTML gate with HTTP 200.
     * Never log the returned URL (contains the token).
     */
    static String urlWithQueryToken(String url, String token) {
        if (url == null || url.isEmpty()) return url == null ? "" : url;
        if (token == null || token.isEmpty()) return url;
        if (TOKEN_PATTERN.matcher(url).find()) return url;
        return url + (url.contains("?") ? "&" : "?") + "X-Plex-Token=" + token;
    }

    /** Host+path only for logs — strips query (tokens) and user-info. */
    static String safeUrlForLog(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            HttpUrl parsed = HttpUrl.parse(url);
            if (parsed != null) {
                return parsed.newBuilder().username("").password("").query(null).fragment(null).build().toString();
            }
        } catch (Exception ignored) {
        }
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) : url;
    }

    private static Request.Builder plexRequest(String url, String token) {
        // Dual auth: header (Plex preferred) + query (matches playback/image URLs; proxy-safe).
        String urlForRequest = urlWithQueryToken(url, token);
        Request.Builder b = new Request.Builder()
                .url(urlForRequest)
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
        final String logUrl = safeUrlForLog(url);
        Request request = plexRequest(url, token).get().build();
        getClient().newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "GET failed: " + logUrl + " " + e.getClass().getSimpleName());
                getMainHandler().post(() -> callback.onError("Network error: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String contentType = response.header("Content-Type");
                    if (contentType == null || contentType.isEmpty()) {
                        if (response.body() != null && response.body().contentType() != null) {
                            contentType = response.body().contentType().toString();
                        } else {
                            contentType = "";
                        }
                    }
                    String body = response.body() != null ? response.body().string() : "";
                    int code = response.code();
                    int len = body != null ? body.length() : 0;
                    String kind = classifyBodyKind(body);
                    // Safe diagnostics: never log tokens or body contents.
                    Log.i(TAG, "GET " + logUrl
                            + " status=" + code
                            + " contentType=" + contentType
                            + " len=" + len
                            + " bodyKind=" + kind
                            + " redirects=" + (response.priorResponse() != null));
                    if (!response.isSuccessful()) {
                        final String msg = "HTTP error " + code;
                        getMainHandler().post(() -> callback.onError(msg));
                        return;
                    }
                    if ("html".equals(kind)) {
                        Log.w(TAG, "GET " + logUrl + " returned HTML (not Plex API JSON/XML)");
                        getMainHandler().post(() -> callback.onError(
                                "Plex returned HTML (check Server URL; use http://host:32400, not /web)"));
                        return;
                    }
                    final String okBody = body;
                    getMainHandler().post(() -> callback.onSuccess(okBody));
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
                    List<LibrarySection> sections = parseLibrarySections(body);
                    if (sections.isEmpty()) {
                        callback.onError("partId " + partId + " not found in library");
                        return;
                    }
                    java.util.ArrayList<String> sectionUrls = new java.util.ArrayList<>();
                    for (LibrarySection section : sections) {
                        String key = section.key;
                        if (key.isEmpty()) continue;
                        // type=1 movies, type=4 episodes
                        if ("movie".equalsIgnoreCase(section.type)) {
                            sectionUrls.add(base + "/library/sections/" + key + "/all?type=1");
                        } else if ("show".equalsIgnoreCase(section.type)) {
                            sectionUrls.add(base + "/library/sections/" + key + "/all?type=4");
                        }
                    }
                    searchSectionsSequentially(base, token, partId, sectionUrls, 0, callback);
                } catch (Exception e) {
                    Log.w(TAG, "resolveViaSections parse: " + e.getClass().getSimpleName());
                    callback.onError("Failed to parse sections");
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
                    List<LibrarySection> sections = parseLibrarySections(body);
                    java.util.ArrayList<String> urls = new java.util.ArrayList<>();
                    for (LibrarySection section : sections) {
                        String key = section.key;
                        if (key.isEmpty()) continue;
                        if ("movie".equalsIgnoreCase(section.type)) {
                            urls.add(base + "/library/sections/" + key + "/all?type=1");
                        } else if ("show".equalsIgnoreCase(section.type)) {
                            urls.add(base + "/library/sections/" + key + "/all?type=4");
                        }
                    }
                    searchPathSectionsSequentially(token, serverPath, urls, 0, callback);
                } catch (Exception e) {
                    Log.w(TAG, "searchPathViaSections parse: " + e.getClass().getSimpleName());
                    callback.onNotFound("Failed to parse sections");
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

    /**
     * True when decoded pixel dimensions are genuine 4K (or larger). Does not accept upscales.
     */
    public static boolean meetsGenuine4k(int width, int height) {
        return width >= GENUINE_4K_MIN_WIDTH && height >= GENUINE_4K_MIN_HEIGHT;
    }

    /**
     * Extracts a clearLogo path from a Metadata object Image array, or empty.
     */
    static String extractClearLogoPath(JsonObject meta) {
        if (meta == null) return "";
        JsonArray images = memberAsArray(meta, "Image");
        for (int i = 0; i < images.size(); i++) {
            JsonElement el = images.get(i);
            if (el == null || !el.isJsonObject()) continue;
            JsonObject img = el.getAsJsonObject();
            if ("clearLogo".equalsIgnoreCase(optString(img, "type"))) {
                String url = optString(img, "url");
                if (!url.isEmpty()) return url;
            }
        }
        return "";
    }

    /**
     * Parses movie/show Metadata entries that have background art into screensaver items.
     * Accepts JSON or XML (PMS may ignore Accept: application/json).
     * Logo is optional (transparent clearLogo only — callers must not invent text logos).
     */
    static List<ScreensaverItem> parseScreensaverItems(String body) {
        List<ScreensaverItem> out = new ArrayList<>();
        if (body == null || body.isEmpty()) return out;
        try {
            String kind = classifyBodyKind(body);
            if ("html".equals(kind) || "empty".equals(kind) || "other".equals(kind)) {
                Log.w(TAG, "parseScreensaverItems skip bodyKind=" + kind);
                return out;
            }
            if ("xml".equals(kind)) {
                return parseScreensaverItemsXml(stripBom(body));
            }
            JsonObject root = JsonParser.parseString(stripBom(body)).getAsJsonObject();
            JsonObject mc = root.has("MediaContainer") && root.get("MediaContainer").isJsonObject()
                    ? root.getAsJsonObject("MediaContainer") : root;
            JsonArray metadata = memberAsArray(mc, "Metadata");
            for (int i = 0; i < metadata.size(); i++) {
                JsonElement el = metadata.get(i);
                if (el == null || !el.isJsonObject()) continue;
                JsonObject meta = el.getAsJsonObject();
                String type = optString(meta, "type");
                if (!"movie".equalsIgnoreCase(type) && !"show".equalsIgnoreCase(type)) continue;
                String art = optString(meta, "art");
                if (art.isEmpty()) continue;
                String ratingKey = optString(meta, "ratingKey");
                if (ratingKey.isEmpty()) continue;
                String title = optString(meta, "title");
                String date = optString(meta, "originallyAvailableAt");
                if (date.isEmpty()) {
                    long year = optLong(meta, "year", 0);
                    if (year > 0) date = String.valueOf(year);
                }
                String logo = extractClearLogoPath(meta);
                out.add(new ScreensaverItem(ratingKey, title, art, logo, date, type));
            }
        } catch (Exception e) {
            // Do not log body (may be large); never log tokens.
            Log.w(TAG, "parseScreensaverItems: " + e.getClass().getSimpleName());
        }
        return out;
    }

    private static List<ScreensaverItem> parseScreensaverItemsXml(String body) throws Exception {
        List<ScreensaverItem> out = new ArrayList<>();
        Document doc = parseXmlDocument(body);
        // JSON Metadata maps to Video (movies) or Directory (shows) in PMS XML.
        collectScreensaverXmlElements(doc.getElementsByTagName("Video"), out);
        collectScreensaverXmlElements(doc.getElementsByTagName("Directory"), out);
        collectScreensaverXmlElements(doc.getElementsByTagName("Metadata"), out);
        return out;
    }

    private static void collectScreensaverXmlElements(NodeList nodes, List<ScreensaverItem> out) {
        if (nodes == null) return;
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            String type = el.getAttribute("type");
            if (type == null || type.isEmpty()) {
                if ("Video".equals(el.getTagName())) type = "movie";
            }
            if (!"movie".equalsIgnoreCase(type) && !"show".equalsIgnoreCase(type)) continue;
            String art = el.getAttribute("art");
            if (art == null || art.isEmpty()) continue;
            String ratingKey = el.getAttribute("ratingKey");
            if (ratingKey == null || ratingKey.isEmpty()) continue;
            String title = el.getAttribute("title");
            if (title == null) title = "";
            String date = el.getAttribute("originallyAvailableAt");
            if (date == null || date.isEmpty()) {
                String year = el.getAttribute("year");
                if (year != null && !year.isEmpty()) date = year;
                else date = "";
            }
            String logo = "";
            NodeList images = el.getElementsByTagName("Image");
            for (int j = 0; j < images.getLength(); j++) {
                Node in = images.item(j);
                if (in.getNodeType() != Node.ELEMENT_NODE) continue;
                Element img = (Element) in;
                if ("clearLogo".equalsIgnoreCase(img.getAttribute("type"))) {
                    String url = img.getAttribute("url");
                    if (url != null && !url.isEmpty()) {
                        logo = url;
                        break;
                    }
                }
            }
            out.add(new ScreensaverItem(ratingKey, title, art, logo, date, type));
        }
    }

    /**
     * Loads screensaver candidates from movie + show library sections (Plex direct).
     * Callers are responsible for genuine-4K dimension filtering before display.
     */
    public static void listScreensaverCandidates(String serverUrl, String token,
                                                 ScreensaverCallback callback) {
        if (serverUrl == null || serverUrl.trim().isEmpty()
                || token == null || token.isEmpty()) {
            getMainHandler().post(() -> callback.onError("Plex server URL or token missing"));
            return;
        }
        String base = baseUrl(serverUrl);
        Log.i(TAG, "listScreensaverCandidates GET " + safeUrlForLog(base + "/library/sections"));
        getJson(base + "/library/sections", token, new SimpleCallback() {
            @Override
            public void onSuccess(String body) {
                List<String> sectionUrls = new ArrayList<>();
                String kind = classifyBodyKind(body);
                try {
                    List<LibrarySection> sections = parseLibrarySections(body);
                    for (LibrarySection section : sections) {
                        // movie libraries type=movie; show libraries type=show
                        if (!"movie".equalsIgnoreCase(section.type)
                                && !"show".equalsIgnoreCase(section.type)) {
                            continue;
                        }
                        String key = section.key;
                        if (key.isEmpty()) continue;
                        String path = key.startsWith("/") ? key : "/library/sections/" + key;
                        sectionUrls.add(base + path + "/all");
                    }
                } catch (Exception e) {
                    // Avoid leaking response snippets (Gson may include them) or tokens.
                    Log.w(TAG, "listScreensaverCandidates sections: "
                            + e.getClass().getSimpleName()
                            + " bodyKind=" + kind
                            + " msg=" + (e.getMessage() != null ? e.getMessage() : ""));
                    if ("html".equals(kind)
                            || (e.getMessage() != null && e.getMessage().contains("HTML"))) {
                        callback.onError(
                                "Plex returned HTML (check Server URL; use http://host:32400, not /web)");
                    } else {
                        callback.onError("Failed to parse library sections");
                    }
                    return;
                }
                if (sectionUrls.isEmpty()) {
                    Log.w(TAG, "listScreensaverCandidates: no movie/show sections bodyKind=" + kind);
                    callback.onError("No movie/show libraries found");
                    return;
                }
                fetchScreensaverSections(token, sectionUrls, 0, new ArrayList<>(), callback);
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        });
    }

    private static void fetchScreensaverSections(String token, List<String> urls, int index,
                                                 List<ScreensaverItem> acc,
                                                 ScreensaverCallback callback) {
        if (index >= urls.size()) {
            Collections.shuffle(acc);
            callback.onReady(acc);
            return;
        }
        getJson(urls.get(index), token, new SimpleCallback() {
            @Override
            public void onSuccess(String body) {
                acc.addAll(parseScreensaverItems(body));
                fetchScreensaverSections(token, urls, index + 1, acc, callback);
            }

            @Override
            public void onError(String error) {
                // Skip failed sections; continue so one bad library doesn't blank the dream.
                Log.w(TAG, "Screensaver section failed: " + error);
                fetchScreensaverSections(token, urls, index + 1, acc, callback);
            }
        });
    }

    /** Formats originallyAvailableAt / year for on-screen display; empty if unknown. */
    public static String formatReleaseLabel(String originallyAvailableAt) {
        if (originallyAvailableAt == null) return "";
        String s = originallyAvailableAt.trim();
        if (s.isEmpty()) return "";
        // YYYY-MM-DD → prefer year for lean-back; keep full date if not ISO.
        if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return s.substring(0, 4);
        }
        if (s.matches("\\d{4}")) {
            return s;
        }
        return s;
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
