package com.plezyplextozidoo;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public class Play extends AppCompatActivity
{
    private static final int UP_NEXT_REQUEST_CODE = 99;

    private static volatile okhttp3.OkHttpClient localClient;
    private static okhttp3.OkHttpClient getLocalClient() {
        if (localClient == null) {
            synchronized (Play.class) {
                if (localClient == null) {
                    localClient = new okhttp3.OkHttpClient.Builder()
                            .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                            .build();
                }
            }
        }
        return localClient;
    }

    /**
     * Seeks Zidoo player to a position via REST API. Fire-and-forget.
     * Note: "positon" is a real typo in the Zidoo API.
     */
    private void seekZidoo(long positionMs) {
        new Thread(() -> {
            try {
                okhttp3.Request request = new okhttp3.Request.Builder()
                        .url("http://127.0.0.1:9529/ZidooVideoPlay/seekTo?positon=" + positionMs)
                        .build();
                getLocalClient().newCall(request).execute().close();
            } catch (Exception e) {
                Log.w("Play", "seekZidoo failed: " + e.getMessage());
            }
        }).start();
    }

    private Intent originalIntent;
    private Intent newIntent;
    private int viewOffset = 0;
    private String directPath = "";
    private String videoTitle = "";
    private boolean audioSelected = false;
    private int selectedAudioIndex = -1;
    private boolean subtitleSelected = false;
    private int selectedSubtitleIndex = -1;
    private String password = "";
    private String message = "";
    private boolean foundSubstitution = false;
    private String videoPath = "";
    private volatile String plexRatingKey = "";
    private String originalRatingKey = "";
    private volatile long resultPositionMs = 0;
    private String plexApiPath = "";
    private boolean zdmc = false;
    private String callerPackage = "";
    private String serverUrl = "";
    private String accessToken = "";
    private volatile String metadataKey = "";
    private volatile long durationMs = 0;
    private volatile long lastKnownPositionMs = 0;
    private volatile long lastKnownDurationMs = 0;
    private volatile String currentPlayingPath = null;
    private String seriesRatingKey = "";
    private String seriesTitle = "";
    private String seasonRatingKey = "";
    private int episodeIndex = 0;
    private volatile boolean upNextTriggered = false;
    private volatile boolean waitingForUpNext = false;
    private volatile boolean handlingPlaybackResult = false;
    private volatile boolean awaitingPlaybackResult = false;
    private volatile java.util.concurrent.ScheduledExecutorService progressPoller = null;
    private volatile long lastTimelineReportMs = 0;

    // Intro/credit skip state
    private volatile boolean introSkipArmed = true;
    private volatile boolean introOverlayDismissed = false;
    private volatile boolean creditOverlayDismissed = false;
    private volatile boolean introCountdownCancelled = false;
    private volatile boolean creditCountdownCancelled = false;
    private volatile long introStartMs = -1, introEndMs = -1;
    /** Earliest credits start (diagnostic / legacy); active segment resolved per poll. */
    private volatile long creditStartMs = -1, creditEndMs = -1;
    private final List<PlexApi.CreditSegment> creditSegments = new ArrayList<>();
    /** Indices into {@link #creditSegments} that are done (skipped / dismissed / seek-disarmed). */
    private final Set<Integer> inactiveCreditIndices = new HashSet<>();
    /** Index of the credits segment currently shown / being skipped; -1 if none. */
    private volatile int activeCreditIndex = -1;
    private volatile long lastPollPositionMs = -1;
    private volatile boolean zidooPlaybackActive = false;

    private SkipOverlay skipOverlay;

    private TextView textView1;
    private TextView textView2;
    private Button playButton;

    private boolean useNewZdiooPlayer = false;

    /** Skip behavior preference values. */
    static final String SKIP_BEHAVIOR_MANUAL = "manual";
    static final String SKIP_BEHAVIOR_COUNTDOWN = "countdown";
    static final String SKIP_BEHAVIOR_INSTANT = "instant";
    /** Hide overlay this many ms before marker end. */
    static final long SKIP_OVERLAY_END_MARGIN_MS = 1500L;
    /**
     * After a credits seek, treat the destination as true media end when within this
     * many ms of duration (handles markers that end at/just before duration).
     */
    static final long CREDITS_NEAR_MEDIA_END_MS = 2000L;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        useNewZdiooPlayer = useNewZdiooPlayer();

        SecureStorage.removeLegacyPlaintextPassword(getApplicationContext());
        SecureStorage.migrateSmbPasswords(getApplicationContext());

        String deviceId = SecureStorage.getDeviceId(getApplicationContext());
        PlexApi.setClientIdentity(Build.MODEL, deviceId, BuildConfig.VERSION_NAME);

        setContentView(R.layout.activity_play);

        skipOverlay = new SkipOverlay(this);
        skipOverlay.setListener(new SkipOverlay.Listener() {
            @Override
            public void onSkipConfirmed(String segment) {
                handleOverlaySkipConfirmed(segment);
            }

            @Override
            public void onSkipDismissed(String segment) {
                handleOverlaySkipDismissed(segment);
            }

            @Override
            public void onCountdownCancelled(String segment) {
                handleOverlayCountdownCancelled(segment);
            }
        });
        maybePromptOverlayPermission();
    }

    private void maybePromptOverlayPermission()
    {
        String behavior = PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
                .getString("skip_behavior", SKIP_BEHAVIOR_MANUAL);
        if (SKIP_BEHAVIOR_INSTANT.equals(behavior))
        {
            return;
        }
        if (SkipOverlay.canDrawOverlays(this))
        {
            return;
        }
        try
        {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            Toast.makeText(this,
                    "Allow display over other apps for the Skip Intro/Credits button",
                    Toast.LENGTH_LONG).show();
        }
        catch (Exception e)
        {
            Log.w("Play", "Could not open overlay permission settings: " + e.getMessage());
        }
    }

    private String getSkipBehavior()
    {
        String behavior = PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
                .getString("skip_behavior", SKIP_BEHAVIOR_MANUAL);
        if (behavior == null || behavior.isEmpty())
        {
            return SKIP_BEHAVIOR_MANUAL;
        }
        return behavior;
    }

    private void resetSkipOverlayState()
    {
        introOverlayDismissed = false;
        creditOverlayDismissed = false;
        introCountdownCancelled = false;
        creditCountdownCancelled = false;
        activeCreditIndex = -1;
        if (skipOverlay != null)
        {
            skipOverlay.hide();
        }
    }

    /** Loads credits markers from metadata and logs a safe diagnostic (no tokens). */
    private void applyCreditMarkersFromMetadata(PlexApi.MetadataResult result)
    {
        creditStartMs = result.creditStartMs;
        creditEndMs = result.creditEndMs;
        creditSegments.clear();
        inactiveCreditIndices.clear();
        activeCreditIndex = -1;
        if (result.creditSegments != null)
        {
            creditSegments.addAll(result.creditSegments);
        }
        Log.d("Play", "Credits markers loaded: count=" + creditSegments.size()
                + " segments=" + PlexApi.formatCreditSegmentsForLog(creditSegments)
                + " earliestStart=" + creditStartMs + " earliestEnd=" + creditEndMs
                + " durationMs=" + result.durationMs);
    }

    private void handleOverlaySkipConfirmed(String segment)
    {
        if ("intro".equals(segment))
        {
            introSkipArmed = false;
            if (introEndMs >= 0)
            {
                seekZidoo(introEndMs);
            }
        }
        else if ("credits".equals(segment))
        {
            int idx = activeCreditIndex;
            if (idx < 0)
            {
                idx = findEligibleCreditSegmentIndex(lastPollPositionMs, creditSegments,
                        inactiveCreditIndices);
            }
            skipCreditsSegmentAt(idx, lastPollPositionMs);
        }
    }

    private void handleOverlaySkipDismissed(String segment)
    {
        if ("intro".equals(segment))
        {
            introOverlayDismissed = true;
            introSkipArmed = false;
        }
        else if ("credits".equals(segment))
        {
            // Back cancels the current segment only; later segments stay eligible.
            creditOverlayDismissed = false;
            creditCountdownCancelled = false;
            int idx = activeCreditIndex;
            if (idx < 0)
            {
                idx = findEligibleCreditSegmentIndex(lastPollPositionMs, creditSegments,
                        inactiveCreditIndices);
            }
            if (idx >= 0)
            {
                inactiveCreditIndices.add(idx);
                Log.d("Play", "Credits segment " + idx + " dismissed (Back); later segments still eligible");
            }
            activeCreditIndex = -1;
        }
    }

    private void handleOverlayCountdownCancelled(String segment)
    {
        if ("intro".equals(segment))
        {
            introCountdownCancelled = true;
            introSkipArmed = false;
        }
        else if ("credits".equals(segment))
        {
            creditCountdownCancelled = false;
            creditOverlayDismissed = false;
            int idx = activeCreditIndex;
            if (idx < 0)
            {
                idx = findEligibleCreditSegmentIndex(lastPollPositionMs, creditSegments,
                        inactiveCreditIndices);
            }
            if (idx >= 0)
            {
                inactiveCreditIndices.add(idx);
                Log.d("Play", "Credits segment " + idx + " countdown cancelled; later segments still eligible");
            }
            activeCreditIndex = -1;
        }
    }

    /**
     * Seeks past the current credits segment end. If that end is at/near true media
     * duration, completes playback explicitly (watched sync + Up Next for TV).
     */
    private void skipCreditsSegmentAt(int index, long positionAtTriggerMs)
    {
        if (index < 0 || index >= creditSegments.size())
        {
            Log.w("Play", "Credits skip ignored: no eligible segment (pos=" + positionAtTriggerMs + ")");
            return;
        }
        PlexApi.CreditSegment segment = creditSegments.get(index);
        inactiveCreditIndices.add(index);
        activeCreditIndex = -1;
        runOnUiThread(this::removeSkipOverlay);

        long mediaDuration = durationMs > 0 ? durationMs
                : (lastKnownDurationMs > 0 ? lastKnownDurationMs : 0);
        Log.d("Play", "Credits skip: segment=" + index
                + " start=" + segment.startMs + " end=" + segment.endMs
                + " final=" + segment.isFinal
                + " posAtTrigger=" + positionAtTriggerMs
                + " durationMs=" + mediaDuration
                + " all=" + PlexApi.formatCreditSegmentsForLog(creditSegments));

        if (!isValidCreditSegment(segment.startMs, segment.endMs))
        {
            Log.w("Play", "Credits skip aborted: invalid segment end; preserving playback");
            return;
        }

        if (isCreditsSeekAtMediaEnd(segment.endMs, mediaDuration))
        {
            // Genuine end of media — do not rely solely on seek-to-duration completing Zidoo.
            triggerTrueMediaEndFromCredits(positionAtTriggerMs, segment.endMs, mediaDuration);
        }
        else
        {
            // Remaining content after this segment (mid/post-credit scenes).
            seekZidoo(segment.endMs);
        }
    }

    /**
     * Closes Zidoo and runs the existing episode-complete path (scrobble at duration,
     * then Up Next once for TV). Never marks watched solely because a credits marker
     * was reached earlier mid-title.
     */
    private void triggerTrueMediaEndFromCredits(long positionAtTriggerMs, long seekEndMs,
                                                long mediaDuration)
    {
        if (upNextTriggered || waitingForUpNext || handlingPlaybackResult)
        {
            Log.d("Play", "Credits end-of-media ignored (already completing)");
            return;
        }
        Log.d("Play", "Credits seek at/near media end (pos=" + positionAtTriggerMs
                + " seekEnd=" + seekEndMs + " duration=" + mediaDuration
                + "); completing playback explicitly");
        upNextTriggered = true;
        stopProgressPoller();
        runOnUiThread(() -> {
            try
            {
                finishActivity(98);
            }
            catch (Exception e)
            {
                Log.w("Play", "Credits end-of-media: finishActivity failed: " + e.getMessage()
                        + "; completing directly");
                upNextTriggered = false;
                handleEpisodeCompleted();
            }
        });
    }

    private void removeSkipOverlay()
    {
        if (skipOverlay != null)
        {
            skipOverlay.hide();
        }
    }

    private boolean useNewZdiooPlayer()
    {
        try
        {
            PackageInfo packageInfo;
            try
            {
                packageInfo = getPackageManager().getPackageInfo("com.zidoo.player", 0);
            }
            catch (Exception e)
            {
                packageInfo = null;
            }

            if (packageInfo != null)
            {
                return true;
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return false;
    }

    @Override
    protected void onRestart()
    {
        super.onRestart();
        if (awaitingPlaybackResult || upNextTriggered || waitingForUpNext || handlingPlaybackResult) {
            return;
        }
        this.finishWithResult();
    }

    private static final Pattern SMB_CREDENTIALS_PATTERN = Pattern.compile("smb://([^:/@]+):[^@/]*@");
    private static final Pattern API_KEY_PATTERN = Pattern.compile("(?i)([?&](?:X-Plex-Token|api_key|ApiKey)=)[^&\\s]*");
    private static final Pattern LITERAL_TOKEN_PLACEHOLDER =
            Pattern.compile("(?i)([?&](?:X-Plex-Token|api_key|ApiKey)=)(?:<token>|\\*\\*\\*)");

    static String maskCredentials(String value)
    {
        if (value == null || value.isEmpty())
        {
            return value;
        }
        return SMB_CREDENTIALS_PATTERN.matcher(value).replaceAll("smb://$1:****@");
    }

    static String maskTokens(String value)
    {
        if (value == null || value.isEmpty())
        {
            return value;
        }
        // On-screen debug must never show a real token, and must not look like a literal URI value.
        return API_KEY_PATTERN.matcher(value).replaceAll("$1***");
    }

    /**
     * When path substitution fails, fall back to the original Intent.data URI (which already has
     * the real X-Plex-Token) or inject the resolved plex token. Never leaves a literal
     * {@code X-Plex-Token=<token>} / {@code ***} placeholder in the playback URI.
     */
    static String resolveFallbackPlaybackUri(String originalDataUri, String plexToken)
    {
        if (originalDataUri == null || originalDataUri.isEmpty())
        {
            return "";
        }

        String uri = LITERAL_TOKEN_PLACEHOLDER.matcher(originalDataUri).replaceAll("$1");
        // Clean dangling ? or & left by placeholder stripping
        uri = uri.replaceAll("\\?&", "?").replaceAll("[?&]$", "");

        String existing = PlexApi.extractTokenFromUrl(uri);
        if (existing != null && !existing.isEmpty()
                && !"<token>".equals(existing) && !"***".equals(existing))
        {
            return uri;
        }

        if (plexToken == null || plexToken.isEmpty())
        {
            return uri;
        }

        // Strip any empty/placeholder token param before injecting the real one
        uri = API_KEY_PATTERN.matcher(uri).replaceAll("$1");
        uri = uri.replaceAll("\\?&", "?").replaceAll("[?&]$", "");
        if (uri.contains("?"))
        {
            return uri + (uri.endsWith("?") || uri.endsWith("&") ? "" : "&")
                    + "X-Plex-Token=" + plexToken;
        }
        return uri + "?X-Plex-Token=" + plexToken;
    }

    static boolean isWithinSkipOverlayWindow(long positionMs, long startMs, long endMs)
    {
        if (startMs < 0 || endMs < 0 || endMs <= startMs)
        {
            return false;
        }
        long hideAt = endMs - SKIP_OVERLAY_END_MARGIN_MS;
        if (hideAt <= startMs)
        {
            hideAt = endMs;
        }
        return positionMs >= startMs && positionMs < hideAt;
    }

    static boolean isValidCreditSegment(long startMs, long endMs)
    {
        return startMs >= 0 && endMs > startMs;
    }

    /**
     * True when a credits seek destination is at or within {@link #CREDITS_NEAR_MEDIA_END_MS}
     * of media duration (true end). Invalid/missing duration → false (preserve playback).
     */
    static boolean isCreditsSeekAtMediaEnd(long seekEndMs, long durationMs)
    {
        if (durationMs <= 0 || seekEndMs < 0)
        {
            return false;
        }
        return seekEndMs >= durationMs - CREDITS_NEAR_MEDIA_END_MS;
    }

    /**
     * First eligible credits segment containing {@code positionMs}, or -1.
     * Inactive (skipped/dismissed) indices are ignored so later segments remain skippable.
     */
    static int findEligibleCreditSegmentIndex(long positionMs,
                                              List<PlexApi.CreditSegment> segments,
                                              Set<Integer> inactiveIndices)
    {
        if (segments == null || segments.isEmpty() || positionMs < 0)
        {
            return -1;
        }
        for (int i = 0; i < segments.size(); i++)
        {
            if (inactiveIndices != null && inactiveIndices.contains(i))
            {
                continue;
            }
            PlexApi.CreditSegment segment = segments.get(i);
            if (!isValidCreditSegment(segment.startMs, segment.endMs))
            {
                continue;
            }
            if (isWithinSkipOverlayWindow(positionMs, segment.startMs, segment.endMs))
            {
                return i;
            }
        }
        return -1;
    }

    /**
     * Instant mode: first eligible segment whose start has been reached and whose end
     * has not yet been passed.
     */
    static int findInstantCreditSegmentIndex(long positionMs,
                                             List<PlexApi.CreditSegment> segments,
                                             Set<Integer> inactiveIndices)
    {
        if (segments == null || segments.isEmpty() || positionMs < 0)
        {
            return -1;
        }
        for (int i = 0; i < segments.size(); i++)
        {
            if (inactiveIndices != null && inactiveIndices.contains(i))
            {
                continue;
            }
            PlexApi.CreditSegment segment = segments.get(i);
            if (!isValidCreditSegment(segment.startMs, segment.endMs))
            {
                continue;
            }
            if (positionMs >= segment.startMs && positionMs < segment.endMs)
            {
                return i;
            }
        }
        return -1;
    }

    /**
     * On a large backward seek, mark the segment that contained the previous position
     * inactive when the new position is before that segment's start. Later segments stay eligible.
     */
    static void disarmCreditSegmentsOnBackwardSeek(long previousPositionMs, long currentPositionMs,
                                                   List<PlexApi.CreditSegment> segments,
                                                   Set<Integer> inactiveIndices)
    {
        if (segments == null || inactiveIndices == null || previousPositionMs < 0)
        {
            return;
        }
        for (int i = 0; i < segments.size(); i++)
        {
            if (inactiveIndices.contains(i))
            {
                continue;
            }
            PlexApi.CreditSegment segment = segments.get(i);
            if (!isValidCreditSegment(segment.startMs, segment.endMs))
            {
                continue;
            }
            boolean wasInOrPastStart = previousPositionMs >= segment.startMs;
            if (wasInOrPastStart && currentPositionMs < segment.startMs)
            {
                inactiveIndices.add(i);
            }
        }
    }

    /**
     * Zidoo play-status helpers: treat playing and paused as active; stop/idle as inactive.
     * When status is absent, a non-empty path with a position is assumed active.
     */
    static boolean isZidooPlaybackActive(Integer statusCode, String statusText,
            String path, boolean hasPosition)
    {
        if (statusText != null && !statusText.isEmpty())
        {
            String s = statusText.trim().toLowerCase(Locale.ENGLISH);
            if (s.contains("play")) return true;
            if (s.contains("pause")) return true;
            if (s.contains("stop") || s.contains("idle") || s.contains("end")) return false;
        }
        if (statusCode != null)
        {
            // Observed Zidoo values: 0=stop/idle, 1=playing, 2=paused (also accept 3=pause variants)
            if (statusCode == 0) return false;
            if (statusCode == 1 || statusCode == 2 || statusCode == 3) return true;
        }
        return hasPosition && path != null && !path.trim().isEmpty();
    }

    private void updateDebugPage()
    {
        String originalIntentToPrint = intentToString(originalIntent);
        String newIntentToPrint = intentToString(newIntent);
        String pathToPrint = maskCredentials(directPath);

        if(pathToPrint != null && !password.isEmpty())
        {
            pathToPrint = pathToPrint.replaceFirst(":" + Pattern.quote(password) + "@", ":********@");
        }

        if(!foundSubstitution && message.isEmpty())
        {
            message = "ERROR: No substitution found";
        }

        if(!message.isEmpty())
        {
            textView1.setVisibility(View.VISIBLE);
            textView1.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
            if(message.contains("WARNING"))
            {
                textView1.setBackgroundColor(0xFFFFDB58);
            }
            textView1.setText(String.format(Locale.ENGLISH, message + "\n"));
        }

        String debugText = String.format(Locale.ENGLISH,
            "Intent: %s\n\n" +
            "Plex Rating Key: %s\n\n" +
            "API Path: %s\n\n" +
            "Path Substitution: %s\n\n" +
            "Video Path: %s\n\n" +
            "View Offset: %d ms\n\n" +
            "Selected Audio Index: %d\n\n" +
            "Selected Subtitle Index: %d\n\n" +
            "New Zidoo Player: %b\n\n" +
            "New Intent: %s",
            originalIntentToPrint, plexRatingKey, plexApiPath,
            pathToPrint, videoPath, viewOffset,
            selectedAudioIndex, selectedSubtitleIndex,
            useNewZdiooPlayer, newIntentToPrint);
        textView2.setText(maskTokens(debugText));
    }

    private void applySubstitutionFallbackIfNeeded()
    {
        if (foundSubstitution)
        {
            return;
        }
        String originalData = originalIntent != null ? originalIntent.getDataString() : null;
        if (originalData == null || originalData.isEmpty())
        {
            originalData = directPath;
        }
        directPath = resolveFallbackPlaybackUri(originalData, accessToken);
    }

    private void showDebugPageOrSendIntent()
    {
        applySubstitutionFallbackIfNeeded();

        if(PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getBoolean("useZidooPlayer", true))
        {
            buildZidooIntent(directPath, viewOffset);
        }
        else
        {
            buildDefaultIntent(directPath);
        }

        if(PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getBoolean("debug", false))
        {
            updateDebugPage();

            playButton.setEnabled(true);
            playButton.setVisibility(View.VISIBLE);
        }
        else
        {
            playButton.callOnClick();
        }
    }

    private void doSubstitution(String path)
    {
        if (path == null)
        {
            return;
        }

        String[] pref_index = {"", "_02", "_03", "_04", "_05", "_06", "_07", "_08", "_09", "_10"};
        for (String s: pref_index)
        {
            String[] path_to_replace_array = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getString("path_to_replace" + s, "").split("\\s*,\\s*");
            String[] replaced_with_array = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getString("replaced_with" + s, "").split("\\s*,\\s*");
            String smb_username = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getString("smbUsername" + s, "");
            // Dual-read encrypted SMB password (migrates plaintext → secure; keeps plaintext until verified)
            String smb_password = SecureStorage.getSmbPassword(getApplicationContext(), s);
            if (smb_username == null) smb_username = "";
            if (smb_password == null) smb_password = "";

            if (path_to_replace_array.length > 0 && replaced_with_array.length > 0 && path_to_replace_array.length == replaced_with_array.length)
            {
                for (int i = 0; i < path_to_replace_array.length; i++)
                {
                    if (!path_to_replace_array[i].isEmpty() && path.startsWith(path_to_replace_array[i]))
                    {
                        path = path.replaceFirst(Pattern.quote(path_to_replace_array[i]),
                                Matcher.quoteReplacement(replaced_with_array[i])).replace("\\", "/");

                        if (path.contains("nfs://") || directPath.contains("/mnt/nfs/"))
                        {
                            if (!useNewZdiooPlayer)
                            {
                                path = path.replaceAll("nfs://(.*?)/", "/mnt/nfs/$1#");
                            }
                        }
                        else
                        {
                            path = Uri.encode(path, "/ :");
                        }

                        if (!smb_username.isEmpty())
                        {
                            password = Uri.encode(smb_password);
                            path = path.replace("smb://", "smb://" + Uri.encode(smb_username) + ":" + password + "@");
                        }

                        foundSubstitution = true;
                        directPath = path;

                        return;
                    }
                }
            }
        }
    }

    @Override
    protected void onStart()
    {
        super.onStart();

        if (awaitingPlaybackResult || handlingPlaybackResult || waitingForUpNext || upNextTriggered) {
            Log.d("Play", "onStart: skipping re-init");
            return;
        }

        String caller = getCallingPackage();
        if (caller == null) {
            android.net.Uri referrer = getReferrer();
            if (referrer != null && "android-app".equals(referrer.getScheme())) {
                caller = referrer.getHost();
            }
        }
        if (caller != null && !caller.isEmpty()) {
            callerPackage = caller;
        }

        originalIntent = getIntent();

        String inputString = originalIntent.getDataString();
        if (inputString == null)
        {
            Log.i("Play", "Incoming intent has no data string");
            inputString = "";
        }
        directPath = inputString;
        textView1 = findViewById(R.id.textView1);
        textView2 = findViewById(R.id.textView2);
        playButton = findViewById(R.id.play_button);
        playButton.setOnClickListener(v ->
        {
            if(PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getBoolean("useZidooPlayer", true))
            {
                awaitingPlaybackResult = true;
                startActivityForResult(newIntent, 98);
                if (!plexRatingKey.isEmpty() && !serverUrl.isEmpty() && !accessToken.isEmpty()) {
                    PlexApi.reportTimeline(serverUrl, accessToken, plexRatingKey, metadataKey,
                            "playing", viewOffset, durationMs, new PlexApi.SimpleCallback() {
                                @Override
                                public void onSuccess(String msg) {
                                    Log.d("Play", "Playback start reported");
                                    startProgressPoller();
                                }
                                @Override
                                public void onError(String error) {
                                    Log.w("Play", "Failed to report playback start: " + error);
                                    startProgressPoller();
                                }
                            });
                } else {
                    startProgressPoller();
                }
            }
            else
            {
                startActivity(newIntent);
            }
        });

        try
        {
            // External ZDMC/Kodi contract — keep PlexToZidoo- parameter prefix.
            Pattern p = Pattern.compile("[?&]PlexToZidoo-([^=]+)=([^&]+)");
            Matcher m = p.matcher(inputString);
            boolean pathMapped = false;
            while(m.find())
            {
                zdmc = true;
                if(m.group(1).equals("ViewOffset"))
                {
                    viewOffset = Integer.parseInt(m.group(2));
                }
                else if(m.group(1).equals("AudioIndex"))
                {
                    audioSelected = true;
                    selectedAudioIndex = Integer.parseInt(m.group(2));
                }
                else if(m.group(1).equals("SubtitleIndex"))
                {
                    subtitleSelected = true;
                    selectedSubtitleIndex = Integer.parseInt(m.group(2));
                }
                else if(m.group(1).equals("Title"))
                {
                    videoTitle = URLDecoder.decode(m.group(2), StandardCharsets.UTF_8.toString());
                }
                else if(m.group(1).equals("Path"))
                {
                    videoPath = URLDecoder.decode(m.group(2), StandardCharsets.UTF_8.toString());
                }
                else if(m.group(1).equals("PathMapped"))
                {
                    pathMapped = true;
                }
            }

            if(zdmc)
            {
                directPath = m.replaceAll("");
                if(pathMapped)
                {
                    if (directPath.contains("nfs://") || directPath.contains("/mnt/nfs/"))
                    {
                        directPath = Uri.decode(directPath);
                        directPath = directPath.replaceAll("nfs://(.*?)/", "/mnt/nfs/$1#");
                    }

                    foundSubstitution = true;
                    showDebugPageOrSendIntent();
                    return;
                }
                else if(!videoPath.isEmpty())
                {
                    doSubstitution(videoPath);
                    showDebugPageOrSendIntent();
                    return;
                }
            }
        }
        catch (Exception e)
        {
            message = "ERROR 1.0: " + e;
            showDebugPageOrSendIntent();
            return;
        }

        // Plezy / Plex intent handling
        if(!zdmc)
        {
            PlexApi.ParsedIntent parsed = PlexApi.parsePlezyUri(inputString);

            if(parsed != null)
            {
                boolean hasPosition = false;
                int intentPosition = 0;
                try
                {
                    hasPosition = originalIntent.hasExtra("position");
                    if(hasPosition)
                    {
                        intentPosition = originalIntent.getIntExtra("position", 0);
                    }
                }
                catch(Exception e)
                {
                    hasPosition = false;
                    intentPosition = 0;
                }
                resultPositionMs = intentPosition;
                final boolean hasIntentPos = hasPosition;
                final int intentPos = intentPosition;

                String settingsUrl = PreferenceManager.getDefaultSharedPreferences(
                    getApplicationContext()).getString("plex_server_url", "");
                String settingsToken = SecureStorage.getInstance(getApplicationContext())
                    .getString("plex_access_token", "");

                serverUrl = (parsed.serverUrl != null && !parsed.serverUrl.isEmpty())
                        ? parsed.serverUrl : (settingsUrl != null ? settingsUrl : "");
                accessToken = (parsed.token != null && !parsed.token.isEmpty())
                        ? parsed.token : (settingsToken != null ? settingsToken : "");

                if(serverUrl.isEmpty() || accessToken.isEmpty())
                {
                    message = "ERROR: Plex server URL or token missing. "
                            + "The Plezy URL should include X-Plex-Token, or set fallbacks in Settings.";
                    showDebugPageOrSendIntent();
                    return;
                }

                final String partId = parsed.partId;

                PlexApi.resolvePartId(serverUrl, accessToken, partId, new PlexApi.ResolveCallback()
                {
                    @Override
                    public void onResolved(String ratingKey)
                    {
                        plexRatingKey = ratingKey;
                        originalRatingKey = ratingKey;
                        loadMetadataAndPlay(hasIntentPos, intentPos);
                    }

                    @Override
                    public void onError(String error)
                    {
                        message = "ERROR: Could not resolve partId " + partId + ": " + error;
                        showDebugPageOrSendIntent();
                    }
                });
            }
            else
            {
                // Not a Plex parts URL — try direct substitution (fallback)
                doSubstitution(directPath);
                showDebugPageOrSendIntent();
            }
        }
    }

    private void loadMetadataAndPlay(boolean hasIntentPos, int intentPos)
    {
        PlexApi.getMetadata(serverUrl, accessToken, plexRatingKey, new PlexApi.MetadataCallback()
        {
            @Override
            public void onSuccess(PlexApi.MetadataResult result)
            {
                plexApiPath = result.filePath;
                videoPath = result.filePath;
                videoTitle = result.title;
                durationMs = result.durationMs;
                metadataKey = result.metadataKey;
                introStartMs = result.introStartMs;
                introEndMs = result.introEndMs;
                applyCreditMarkersFromMetadata(result);

                if (result.isEpisode) {
                    seriesRatingKey = result.seriesRatingKey != null ? result.seriesRatingKey : "";
                    seriesTitle = result.seriesTitle != null ? result.seriesTitle : "";
                    seasonRatingKey = result.seasonRatingKey != null ? result.seasonRatingKey : "";
                    episodeIndex = result.episodeNumber;
                } else {
                    seriesRatingKey = "";
                    seasonRatingKey = "";
                }

                viewOffset = resolveStartPositionMs(hasIntentPos, intentPos, result.viewOffsetMs);
                resultPositionMs = viewOffset;

                doSubstitution(result.filePath);
                showDebugPageOrSendIntent();
            }

            @Override
            public void onError(String error)
            {
                message = "ERROR: " + error;
                showDebugPageOrSendIntent();
            }
        });
    }

    protected void buildDefaultIntent(String path)
    {
        newIntent = new Intent(Intent.ACTION_VIEW);
        newIntent.setDataAndTypeAndNormalize(Uri.parse(path != null ? path : ""), "video/*" );
    }

    /**
     * Decides where playback starts, in milliseconds.
     * A present intent "position" extra always wins (including 0 for play from start).
     * Otherwise use the server's saved viewOffset.
     */
    static int resolveStartPositionMs(boolean hasIntentPosition, int intentPositionMs, long serverPositionMs)
    {
        if(hasIntentPosition)
        {
            return Math.max(intentPositionMs, 0);
        }
        if(serverPositionMs > 0)
        {
            return (int) Math.min(serverPositionMs, Integer.MAX_VALUE);
        }
        return 0;
    }

    static long resolveResultPositionMs(String originalKey, String currentKey,
            long finalPositionMs, long durationMs, long fallbackMs)
    {
        if(currentKey == null || !currentKey.equals(originalKey))
        {
            return 0;
        }
        if(PlexApi.isWatched(finalPositionMs, durationMs))
        {
            return 0;
        }
        if(finalPositionMs > 0)
        {
            return finalPositionMs;
        }
        return Math.max(0, fallbackMs);
    }

    static boolean isPlayerPaused(long lastPollPositionMs, long currentPositionMs)
    {
        return lastPollPositionMs >= 0 && currentPositionMs == lastPollPositionMs;
    }

    protected void buildZidooIntent(String path, int viewOffset)
    {
        newIntent = new Intent(Intent.ACTION_VIEW);

        String safePath = (path != null) ? path : "";

        if(safePath.startsWith("/") && new File(safePath).exists())
        {
            newIntent.setDataAndTypeAndNormalize(Uri.fromFile(new File(safePath)), "video/*");
        }
        else
        {
            newIntent.setDataAndTypeAndNormalize(Uri.parse(safePath), "video/*");
        }

        newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        newIntent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        if (useNewZdiooPlayer)
        {
            newIntent.setPackage("com.zidoo.player");
            newIntent.setClassName("com.zidoo.player", "com.zidoo.player.activity.PlayerActivity");
        }
        else
        {
            newIntent.setPackage("com.android.gallery3d");
            newIntent.setClassName("com.android.gallery3d", "com.android.gallery3d.app.MovieActivity");
        }

        if (PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getBoolean("showTitle", true))
        {
            newIntent.putExtra("title", videoTitle);
        }
        else
        {
            newIntent.putExtra("title", "");
        }

        if(viewOffset > 0)
        {
            resultPositionMs = viewOffset;
            newIntent.putExtra("from_start", false);
            newIntent.putExtra("position", viewOffset);
        }
        else
        {
            resultPositionMs = 0;
            newIntent.putExtra("from_start", true);
        }

        if(audioSelected)
        {
            newIntent.putExtra("audio_idx", selectedAudioIndex);
        }

        if(subtitleSelected)
        {
            newIntent.putExtra("subtitle_idx", selectedSubtitleIndex);
        }

        newIntent.putExtra("return_result", true);
    }

    private void startProgressPoller() {
        if (progressPoller != null) return;

        java.util.concurrent.ScheduledExecutorService poller =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        progressPoller = poller;
        lastTimelineReportMs = 0;
        scheduleNextPoll(poller, 3000);
    }

    private void scheduleNextPoll(final java.util.concurrent.ScheduledExecutorService poller, long delayMs) {
        if (poller == null || poller.isShutdown()) return;
        if (progressPoller != poller) return;

        poller.schedule(() -> {
            if (progressPoller != poller) {
                return;
            }
            long nextDelay = 1000; // Poll Zidoo ~1s for skip responsiveness
            try {
                okhttp3.Request request = new okhttp3.Request.Builder()
                        .url("http://127.0.0.1:9529/ZidooVideoPlay/getPlayStatus")
                        .build();

                try (okhttp3.Response response = getLocalClient().newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(
                                response.body().string()).getAsJsonObject();
                        if (root.has("video")) {
                            com.google.gson.JsonObject video = root.getAsJsonObject("video");

                            String nowPlayingPath = video.has("path") ? video.get("path").getAsString() : null;

                            if (nowPlayingPath != null && nowPlayingPath.trim().isEmpty()) {
                                Log.d("Play", "Poll: player between files (empty path), skipping");
                            } else {
                                if (PlexApi.isZidooFileChange(nowPlayingPath, currentPlayingPath)) {
                                    Log.d("Play", "Zidoo advanced to next file: " + maskCredentials(nowPlayingPath));
                                    currentPlayingPath = nowPlayingPath;

                                    final String previousKey = plexRatingKey;
                                    final String previousMetaKey = metadataKey;
                                    final long previousPositionMs = lastKnownPositionMs;
                                    final long previousDurationMs = durationMs > 0 ? durationMs : lastKnownDurationMs;
                                    if (!previousKey.isEmpty() && !serverUrl.isEmpty() && !accessToken.isEmpty()) {
                                        PlexApi.reportPlaybackStopped(serverUrl, accessToken, previousKey,
                                                previousMetaKey, previousPositionMs, previousDurationMs,
                                                new PlexApi.SimpleCallback() {
                                                    @Override public void onSuccess(String msg) { }
                                                    @Override public void onError(String error) {
                                                        Log.w("Play", "Failed to report stop for previous file: " + error);
                                                    }
                                                });
                                    }
                                    plexRatingKey = "";
                                    metadataKey = "";

                                    introSkipArmed = true;
                                    introStartMs = -1; introEndMs = -1;
                                    creditStartMs = -1; creditEndMs = -1;
                                    creditSegments.clear();
                                    inactiveCreditIndices.clear();
                                    activeCreditIndex = -1;
                                    lastPollPositionMs = -1;
                                    runOnUiThread(this::resetSkipOverlayState);

                                    String[][] subRules = getSubstitutionRules();
                                    String reversedPath = PlexApi.reverseSubstitution(nowPlayingPath, subRules);
                                    if (reversedPath != null && !serverUrl.isEmpty() && !accessToken.isEmpty()) {
                                        PlexApi.searchItemByPath(serverUrl, accessToken, reversedPath,
                                                new PlexApi.SearchByPathCallback() {
                                                    @Override
                                                    public void onFound(String ratingKey) {
                                                        Log.d("Play", "Binge episode resolved: " + ratingKey);
                                                        plexRatingKey = ratingKey;
                                                        durationMs = 0;
                                                        upNextTriggered = false;
                                                        PlexApi.getMetadata(serverUrl, accessToken, ratingKey,
                                                                new PlexApi.MetadataCallback() {
                                                            @Override
                                                            public void onSuccess(PlexApi.MetadataResult result) {
                                                                durationMs = result.durationMs;
                                                                videoTitle = result.title;
                                                                metadataKey = result.metadataKey;
                                                                introStartMs = result.introStartMs;
                                                                introEndMs = result.introEndMs;
                                                                applyCreditMarkersFromMetadata(result);
                                                                if (result.isEpisode) {
                                                                    seriesRatingKey = result.seriesRatingKey != null ? result.seriesRatingKey : "";
                                                                    seriesTitle = result.seriesTitle != null ? result.seriesTitle : "";
                                                                    seasonRatingKey = result.seasonRatingKey != null ? result.seasonRatingKey : "";
                                                                    episodeIndex = result.episodeNumber;
                                                                }
                                                                PlexApi.reportTimeline(serverUrl, accessToken, plexRatingKey,
                                                                        metadataKey, "playing", 0, durationMs,
                                                                        new PlexApi.SimpleCallback() {
                                                                    @Override public void onSuccess(String msg) { }
                                                                    @Override public void onError(String error) {
                                                                        Log.w("Play", "Failed to report start for binge ep: " + error);
                                                                    }
                                                                });
                                                            }
                                                            @Override
                                                            public void onError(String error) {
                                                                Log.w("Play", "Failed to get binge episode details: " + error);
                                                            }
                                                        });
                                                    }

                                                    @Override
                                                    public void onNotFound(String error) {
                                                        Log.w("Play", "Binge episode not found by path: " + error);
                                                        plexRatingKey = "";
                                                        metadataKey = "";
                                                    }
                                                });
                                    } else {
                                        Log.w("Play", "Could not reverse the new file path, progress reporting paused");
                                        plexRatingKey = "";
                                        metadataKey = "";
                                    }
                                }

                                if (nowPlayingPath != null && !nowPlayingPath.isEmpty() && currentPlayingPath == null) {
                                    currentPlayingPath = nowPlayingPath;
                                }

                                if (video.has("duration")) {
                                    lastKnownDurationMs = video.get("duration").getAsLong();
                                }

                                Integer statusCode = null;
                                String statusText = null;
                                if (video.has("status")) {
                                    try {
                                        if (video.get("status").isJsonPrimitive()
                                                && video.get("status").getAsJsonPrimitive().isNumber()) {
                                            statusCode = video.get("status").getAsInt();
                                        } else {
                                            statusText = video.get("status").getAsString();
                                        }
                                    } catch (Exception ignored) {
                                    }
                                }

                                if (video.has("currentPosition")) {
                                    long currentPositionMs = video.get("currentPosition").getAsLong();
                                    lastKnownPositionMs = currentPositionMs;

                                    boolean playerPaused = isPlayerPaused(lastPollPositionMs, currentPositionMs);
                                    zidooPlaybackActive = isZidooPlaybackActive(statusCode, statusText,
                                            nowPlayingPath, true);

                                    // Report timeline to Plex ~every 10s
                                    long nowWall = System.currentTimeMillis();
                                    if (!plexRatingKey.isEmpty() && !serverUrl.isEmpty() && !accessToken.isEmpty()
                                            && (nowWall - lastTimelineReportMs >= 10000 || lastTimelineReportMs == 0)) {
                                        lastTimelineReportMs = nowWall;
                                        long reportDuration = durationMs > 0 ? durationMs : lastKnownDurationMs;
                                        String state = playerPaused ? "paused" : "playing";
                                        PlexApi.reportTimeline(serverUrl, accessToken, plexRatingKey,
                                                metadataKey, state, currentPositionMs, reportDuration,
                                                new PlexApi.SimpleCallback() {
                                                    @Override public void onSuccess(String msg) { }
                                                    @Override public void onError(String error) {
                                                        Log.w("Play", "Timeline report failed: " + error);
                                                    }
                                                });
                                    }

                                    long remainingMs = lastKnownDurationMs - currentPositionMs;
                                    Log.d("Play", "Poll: pos=" + currentPositionMs + " dur=" + lastKnownDurationMs
                                            + " remaining=" + remainingMs + "ms active=" + zidooPlaybackActive);

                                    android.content.SharedPreferences prefs =
                                            androidx.preference.PreferenceManager.getDefaultSharedPreferences(Play.this);
                                    boolean skipIntrosEnabled = prefs.getBoolean("skip_intros", true);
                                    boolean skipCreditsEnabled = prefs.getBoolean("skip_credits", true);
                                    String skipBehavior = getSkipBehavior();

                                    if (lastPollPositionMs >= 0) {
                                        long positionDelta = currentPositionMs - lastPollPositionMs;
                                        boolean likelyManualSeek = positionDelta < -3000 || positionDelta > 30000;

                                        if (likelyManualSeek) {
                                            if (introSkipArmed && currentPositionMs < introEndMs) {
                                                introSkipArmed = false;
                                                Log.d("Play", "Intro skip disarmed (manual seek to " + currentPositionMs + "ms)");
                                            }
                                            if (positionDelta < -3000) {
                                                disarmCreditSegmentsOnBackwardSeek(lastPollPositionMs,
                                                        currentPositionMs, creditSegments, inactiveCreditIndices);
                                                activeCreditIndex = -1;
                                                Log.d("Play", "Credit skip: backward seek disarm check at "
                                                        + currentPositionMs + "ms; inactive="
                                                        + inactiveCreditIndices);
                                            }
                                            runOnUiThread(this::removeSkipOverlay);
                                        }
                                    }

                                    handleSkipDuringPoll(currentPositionMs, skipIntrosEnabled, skipCreditsEnabled,
                                            skipBehavior);

                                    lastPollPositionMs = currentPositionMs;
                                } else if (!isZidooPlaybackActive(statusCode, statusText, nowPlayingPath, false)) {
                                    zidooPlaybackActive = false;
                                    runOnUiThread(this::removeSkipOverlay);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w("Play", "Zidoo poll failed: " + e.getMessage());
            }

            scheduleNextPoll(poller, nextDelay);
        }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void handleEpisodeCompleted() {
        resultPositionMs = 0;
        handlingPlaybackResult = true;
        runOnUiThread(() -> {
            stopProgressPoller();

            if (plexRatingKey.isEmpty() || serverUrl.isEmpty() || accessToken.isEmpty()) {
                handlingPlaybackResult = false;
                finishWithResult();
                return;
            }

            long finalMs = durationMs > 0 ? durationMs
                    : (lastKnownDurationMs > 0 ? lastKnownDurationMs : lastKnownPositionMs);
            long reportDuration = durationMs > 0 ? durationMs : lastKnownDurationMs;

            PlexApi.reportPlaybackStopped(serverUrl, accessToken, plexRatingKey, metadataKey,
                    finalMs, reportDuration, new PlexApi.SimpleCallback() {
                @Override
                public void onSuccess(String msg) {
                    Log.d("Play", "Episode completed / scrobbled");
                    if (!seriesRatingKey.isEmpty() && !seasonRatingKey.isEmpty()) {
                        launchUpNext();
                    } else {
                        runOnUiThread(() -> finishWithResult());
                    }
                }
                @Override
                public void onError(String error) {
                    Log.w("Play", "Failed to report stop on episode complete: " + error);
                    if (!seriesRatingKey.isEmpty() && !seasonRatingKey.isEmpty()) {
                        launchUpNext();
                    } else {
                        runOnUiThread(() -> finishWithResult());
                    }
                }
            });
        });
    }

    private void launchUpNext() {
        Log.d("Play", "launchUpNext called, seriesRatingKey=" + seriesRatingKey);
        if (seriesRatingKey.isEmpty() || seasonRatingKey.isEmpty()
                || serverUrl.isEmpty() || accessToken.isEmpty()) {
            runOnUiThread(this::finishWithResult);
            return;
        }

        PlexApi.getNextEpisode(serverUrl, accessToken, seasonRatingKey, episodeIndex,
                seriesTitle, seriesRatingKey, new PlexApi.NextUpDetailCallback() {
                @Override
                public void onResult(String nextRatingKey, String seriesName, String episodeName,
                                     int seasonNumber, int episodeNumber, String sid, String serverPath) {
                    Log.d("Play", "NextUp found: " + seriesName + " S" + seasonNumber + "E" + episodeNumber);
                    runOnUiThread(() -> {
                        waitingForUpNext = true;
                        Intent upNextIntent = new Intent(Play.this, UpNextActivity.class);
                        upNextIntent.putExtra("nextItemId", nextRatingKey);
                        upNextIntent.putExtra("seriesName", seriesName);
                        upNextIntent.putExtra("episodeName", episodeName);
                        upNextIntent.putExtra("seasonNumber", seasonNumber);
                        upNextIntent.putExtra("episodeNumber", episodeNumber);
                        upNextIntent.putExtra("seriesId", sid);
                        upNextIntent.putExtra("seasonId", seasonRatingKey);
                        upNextIntent.putExtra("serverPath", serverPath);
                        upNextIntent.putExtra("serverUrl", serverUrl);
                        upNextIntent.putExtra("accessToken", accessToken);
                        // Art URL resolved in UpNext via Plex thumb/art after metadata fetch if needed
                        startActivityForResult(upNextIntent, UP_NEXT_REQUEST_CODE);
                    });
                }

                @Override
                public void onNoNextEpisode() {
                    Log.d("Play", "No next episode (series finale), finishing");
                    runOnUiThread(() -> finishWithResult());
                }
            });
    }

    private String[][] getSubstitutionRules() {
        String[] prefIndex = {"", "_02", "_03", "_04", "_05", "_06", "_07", "_08", "_09", "_10"};
        List<String[]> rules = new ArrayList<>();
        android.content.SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        for (String s : prefIndex) {
            String pathToReplaceSlot = prefs.getString("path_to_replace" + s, "");
            String replacedWithSlot = prefs.getString("replaced_with" + s, "");
            if (pathToReplaceSlot == null) pathToReplaceSlot = "";
            if (replacedWithSlot == null) replacedWithSlot = "";

            String[] pathToReplaceArray = pathToReplaceSlot.split("\\s*,\\s*");
            String[] replacedWithArray = replacedWithSlot.split("\\s*,\\s*");
            if (pathToReplaceArray.length != replacedWithArray.length) {
                continue;
            }

            for (int i = 0; i < pathToReplaceArray.length; i++) {
                String pathToReplace = pathToReplaceArray[i].trim();
                String replacedWith = replacedWithArray[i].trim();
                if (!pathToReplace.isEmpty() && !replacedWith.isEmpty()) {
                    rules.add(new String[]{pathToReplace, replacedWith});
                }
            }
        }
        return rules.toArray(new String[0][]);
    }

    private void stopProgressPoller() {
        if (progressPoller != null) {
            progressPoller.shutdownNow();
            progressPoller = null;
        }
        removeSkipOverlay();
    }

    /**
     * Intro/credits skip based on Settings "Skip Behavior".
     * Credits: all modes seek past the <em>current</em> segment end (films and TV).
     * Up Next is only a consequence of true media end after that seek — never binge-at-marker.
     * Skip Intro is unchanged (seek to intro end).
     */
    private void handleSkipDuringPoll(long currentPositionMs, boolean skipIntrosEnabled,
                                      boolean skipCreditsEnabled, String skipBehavior) {
        if (!zidooPlaybackActive || lastPollPositionMs < 0) {
            runOnUiThread(this::removeSkipOverlay);
            return;
        }

        boolean inIntro = skipIntrosEnabled && introSkipArmed && !introOverlayDismissed
                && !introCountdownCancelled
                && isWithinSkipOverlayWindow(currentPositionMs, introStartMs, introEndMs);
        int eligibleCreditIndex = skipCreditsEnabled
                ? findEligibleCreditSegmentIndex(currentPositionMs, creditSegments, inactiveCreditIndices)
                : -1;
        boolean inCreditsWindow = eligibleCreditIndex >= 0;

        // Instant: auto-seek past current segment (no overlay). Same destination as Manual/Countdown.
        if (SKIP_BEHAVIOR_INSTANT.equals(skipBehavior)) {
            runOnUiThread(this::removeSkipOverlay);

            if (skipIntrosEnabled && introSkipArmed && introStartMs >= 0
                    && currentPositionMs >= introStartMs && currentPositionMs < introEndMs) {
                Log.d("Play", "Skipping intro: seeking from " + currentPositionMs + "ms to " + introEndMs + "ms");
                seekZidoo(introEndMs);
                introSkipArmed = false;
            }

            if (skipCreditsEnabled && !upNextTriggered) {
                int instantIdx = findInstantCreditSegmentIndex(currentPositionMs, creditSegments,
                        inactiveCreditIndices);
                if (instantIdx >= 0) {
                    Log.d("Play", "Instant credits skip at pos=" + currentPositionMs
                            + " segment=" + instantIdx);
                    skipCreditsSegmentAt(instantIdx, currentPositionMs);
                }
            }
            return;
        }

        // Past the marker window → hide overlay
        if (!inIntro && !inCreditsWindow) {
            if (activeCreditIndex >= 0) {
                activeCreditIndex = -1;
            }
            runOnUiThread(this::removeSkipOverlay);
            return;
        }

        // Prefer intro over credits if somehow overlapping
        final String segment = inIntro ? "intro" : "credits";
        final String label = inIntro ? "Skip Intro" : "Skip Credits";
        final String kindLabel = inIntro ? "Intro" : "Credits";
        if (!inIntro && inCreditsWindow) {
            activeCreditIndex = eligibleCreditIndex;
        }
        final boolean overlayAllowed = SkipOverlay.canDrawOverlays(getApplicationContext());

        if (!overlayAllowed) {
            // Without overlay permission, fall back to instant seek for this segment once
            if (inIntro && introSkipArmed) {
                Log.d("Play", "No overlay permission; instant-skipping intro");
                seekZidoo(introEndMs);
                introSkipArmed = false;
            } else if (inCreditsWindow) {
                Log.d("Play", "No overlay permission; instant-skipping credits segment "
                        + eligibleCreditIndex);
                skipCreditsSegmentAt(eligibleCreditIndex, currentPositionMs);
            }
            return;
        }

        if (SKIP_BEHAVIOR_COUNTDOWN.equals(skipBehavior)) {
            runOnUiThread(() -> {
                if (skipOverlay == null) return;
                if (skipOverlay.isShowing()
                        && segment.equals(skipOverlay.getActiveSegment())
                        && skipOverlay.isCountdownMode()) {
                    return; // already counting this segment
                }
                skipOverlay.showCountdown(segment, kindLabel, 3);
            });
            return;
        }

        // Manual (default)
        runOnUiThread(() -> {
            if (skipOverlay == null) return;
            if (skipOverlay.isShowing()
                    && segment.equals(skipOverlay.getActiveSegment())
                    && !skipOverlay.isCountdownMode()) {
                return;
            }
            skipOverlay.showManual(segment, label);
        });
    }

    private void finishWithResult() {
        removeSkipOverlay();
        if (!plexRatingKey.isEmpty() && !serverUrl.isEmpty()) {
            Intent resultData = new Intent();
            resultData.setData(Uri.parse(serverUrl + "/library/metadata/" + plexRatingKey));
            resultData.putExtra("itemId", plexRatingKey);
            resultData.putExtra("ratingKey", plexRatingKey);
            resultData.putExtra("position", (int) resultPositionMs);
            setResult(RESULT_OK, resultData);
        }
        finish();
    }

    @Override
    protected void onDestroy()
    {
        removeSkipOverlay();
        if (skipOverlay != null) {
            skipOverlay.destroy();
            skipOverlay = null;
        }
        stopProgressPoller();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);
        removeSkipOverlay();

        if (requestCode == UP_NEXT_REQUEST_CODE) {
            waitingForUpNext = false;
            if (resultCode == Activity.RESULT_OK && data != null) {
                String nextItemId = data.getStringExtra("nextItemId");
                String nextServerPath = data.getStringExtra("serverPath");
                String nextSeriesId = data.getStringExtra("seriesId");
                String nextSeasonId = data.getStringExtra("seasonId");
                int nextEpIndex = data.getIntExtra("episodeNumber", 0);

                plexRatingKey = nextItemId;
                seriesRatingKey = nextSeriesId != null ? nextSeriesId : seriesRatingKey;
                if (nextSeasonId != null) seasonRatingKey = nextSeasonId;
                episodeIndex = nextEpIndex;
                durationMs = 0;
                lastKnownPositionMs = 0;
                lastKnownDurationMs = 0;
                currentPlayingPath = null;
                upNextTriggered = false;

                introSkipArmed = true;
                introStartMs = -1; introEndMs = -1;
                creditStartMs = -1; creditEndMs = -1;
                creditSegments.clear();
                inactiveCreditIndices.clear();
                activeCreditIndex = -1;
                lastPollPositionMs = -1;
                lastTimelineReportMs = 0;
                resetSkipOverlayState();

                Log.d("Play", "Play Now: nextServerPath=" + nextServerPath);
                foundSubstitution = false;
                doSubstitution(nextServerPath);
                final String resolvedSmbPath = directPath;
                if (foundSubstitution) {
                    PlexApi.getMetadata(serverUrl, accessToken, nextItemId, new PlexApi.MetadataCallback() {
                        @Override
                        public void onSuccess(PlexApi.MetadataResult result) {
                            durationMs = result.durationMs;
                            videoTitle = result.title;
                            metadataKey = result.metadataKey;
                            introStartMs = result.introStartMs;
                            introEndMs = result.introEndMs;
                            applyCreditMarkersFromMetadata(result);
                            if (result.seasonRatingKey != null) seasonRatingKey = result.seasonRatingKey;
                            episodeIndex = result.episodeNumber;

                            PlexApi.reportTimeline(serverUrl, accessToken, plexRatingKey,
                                    metadataKey, "playing", 0, durationMs,
                                    new PlexApi.SimpleCallback() {
                                @Override public void onSuccess(String msg) { }
                                @Override public void onError(String error) {
                                    Log.w("Play", "Failed to report start for next ep: " + error);
                                }
                            });
                            runOnUiThread(() -> {
                                handlingPlaybackResult = false;
                                awaitingPlaybackResult = true;
                                buildZidooIntent(resolvedSmbPath, 0);
                                startActivityForResult(newIntent, 98);
                                startProgressPoller();
                            });
                        }
                        @Override
                        public void onError(String error) {
                            Log.w("Play", "Failed to get next episode details: " + error);
                            runOnUiThread(() -> {
                                handlingPlaybackResult = false;
                                awaitingPlaybackResult = true;
                                buildZidooIntent(resolvedSmbPath, 0);
                                startActivityForResult(newIntent, 98);
                                startProgressPoller();
                            });
                        }
                    });
                } else {
                    Log.e("Play", "No substitution rule matched for next episode path: " + nextServerPath);
                    Toast.makeText(getApplicationContext(), "Cannot resolve next episode path", Toast.LENGTH_LONG).show();
                    finishWithResult();
                }
            } else {
                handlingPlaybackResult = false;
                finishWithResult();
            }
            return;
        }

        awaitingPlaybackResult = false;

        if (handlingPlaybackResult) {
            Log.d("Play", "Ignoring stale Zidoo result");
            return;
        }

        stopProgressPoller();
        handlingPlaybackResult = true;

        if (upNextTriggered) {
            upNextTriggered = false;
            handleEpisodeCompleted();
            return;
        }

        if (plexRatingKey.isEmpty() || serverUrl.isEmpty() || accessToken.isEmpty() || zdmc) {
            handlingPlaybackResult = false;
            finishWithResult();
            return;
        }

        long finalPositionMs = 0;
        if (resultCode == Activity.RESULT_OK && requestCode == 98 && data != null) {
            finalPositionMs = data.getIntExtra("position", 0);
        }
        if (finalPositionMs <= 0) {
            finalPositionMs = lastKnownPositionMs;
        }

        final long effectiveDurationMs = durationMs > 0 ? durationMs
                : (lastKnownDurationMs > 0 ? lastKnownDurationMs : 0);

        resultPositionMs = resolveResultPositionMs(originalRatingKey, plexRatingKey, finalPositionMs,
                effectiveDurationMs, resultPositionMs);

        final long reportPosition = finalPositionMs;
        PlexApi.reportPlaybackStopped(serverUrl, accessToken, plexRatingKey, metadataKey,
                reportPosition, effectiveDurationMs, new PlexApi.SimpleCallback() {
            @Override
            public void onSuccess(String msg) {
                if (PlexApi.isWatched(reportPosition, effectiveDurationMs)) {
                    Log.d("Play", "Marked as watched / scrobbled");
                    if (!seriesRatingKey.isEmpty() && !seasonRatingKey.isEmpty()) {
                        launchUpNext();
                    } else {
                        handlingPlaybackResult = false;
                        runOnUiThread(() -> finishWithResult());
                    }
                } else {
                    handlingPlaybackResult = false;
                    runOnUiThread(() -> finishWithResult());
                }
            }
            @Override
            public void onError(String error) {
                handlingPlaybackResult = false;
                runOnUiThread(() -> {
                    Toast.makeText(getApplicationContext(),
                            "Couldn't update progress or watched status",
                            Toast.LENGTH_LONG).show();
                    finishWithResult();
                });
            }
        });
    }

    public static String intentToString(Intent intent)
    {
        if (intent == null)
        {
            return "";
        }

        StringBuilder stringBuilder = new StringBuilder("action: ")
                .append(intent.getAction())
                .append(" type: ")
                .append(intent.getType())
                .append(" data: ")
                .append(intent.getDataString())
                ;
        if(intent.getExtras() != null)
        {
            stringBuilder.append(" extras: ");
            for (String key : intent.getExtras().keySet())
            {
                stringBuilder.append(key).append("=").append(intent.getExtras().get(key)).append(" ");
            }
        }

        return maskCredentials(maskTokens(stringBuilder.toString()));
    }
}
