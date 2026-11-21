package dk.meningspunktet.unofficial.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;
import android.util.LruCache;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Persistent foreground service: polls the meningspunktet.dk Inertia notifications
 * page with the WebView's session cookie and posts native notifications for new
 * items while the app UI is backgrounded. Runs in the same process as MainActivity,
 * so CookieManager shares the WebView cookie store.
 *
 * This is an unofficial Meningspunktet app: notifications are delivered by polling
 * the site's own endpoints directly, deliberately bypassing Google (FCM), Apple
 * (APNs) and Mozilla (auto-generated/Rust push) servers entirely - none of them
 * ever see Meningspunktet activity. The cost is battery: the periodic wake-ups
 * consume more power than OS push, which rides on connections the OS keeps open
 * anyway. This tradeoff is intentional and documented in README.md.
 */
public class NotificationPollerService extends Service {

    static final String ACTION_START = "dk.meningspunktet.unofficial.app.action.START";
    static final String ACTION_APP_FOREGROUND = "dk.meningspunktet.unofficial.app.action.APP_FOREGROUND";
    static final String EXTRA_IN_FOREGROUND = "in_foreground";
    static final String EXTRA_OPEN_URL = "mp_open_url";

    private static final String TAG = "MpPoller";
    private static final String POLL_URL = "https://meningspunktet.dk/notifications";
    private static final String CONVERSATIONS_URL = "https://meningspunktet.dk/conversations/load-page?conversationsPage=1";
    private static final String COOKIE_URL = "https://meningspunktet.dk";
    private static final long POLL_INTERVAL_MS = 60_000L;
    private static final long BACKOFF_BASE_MS = 5 * 60_000L;
    private static final long BACKOFF_CAP_MS = 15 * 60_000L;
    private static final long IDLE_INTERVAL_MS = 2 * 60_000L;
    private static final int MAX_NOTIFY_PER_TICK = 8;
    private static final int SEEN_CAP = 200;
    /** Cap on any buffered response/avatar: the service shares the UI process, so
     *  an oversized body would OOM the Activity too. */
    private static final int MAX_BODY_CHARS = 4 * 1024 * 1024;
    private static final int MAX_AVATAR_BYTES = 4 * 1024 * 1024;

    private static final String CHANNEL_SERVICE = "mp_service";
    private static final String CHANNEL_ALERTS = "mp_alerts";
    private static final int ONGOING_ID = 1;

    private static final String PREFS = "mp_poller";
    // v2: first seed now notifies for unread items; bumping the key re-seeds once on upgrade.
    private static final String KEY_SEEN = "seen_ids_v2";
    private static final String KEY_CONV_SEEN = "conv_seen_v5"; // bump to re-seed once per body-source fix
    private static final String KEY_MY_USER_ID = "my_user_id";

    private HandlerThread thread;
    private Handler work;
    private volatile boolean appInForeground = false;
    private boolean startedForeground = false;
    private int consecutiveUnauth = 0;
    private int consecutiveFailures = 0;
    private boolean loggedShape = false;
    /** Verbose logs dump message content; keep them out of release logcat. */
    private boolean debugBuild;
    /** Last app version the server accepted; refreshed from the 409's X-Inertia-Version header. */
    private volatile String inertiaVersion = null;
    /** Own user id (props.auth.user.id), needed to pick the counterpart's avatar in DMs. */
    private volatile int myUserId = 0;
    /** Own avatar file uuid, excluded when picking a notification's imageIds avatar. */
    private volatile String myAvatarFileId = null;
    private final LruCache<String, Bitmap> avatarCache = new LruCache<>(24);

    private final Runnable pollRunnable = this::pollOnce;

    /** Start helper shared by MainActivity/BootReceiver. */
    static void start(Context ctx, String action, boolean inForeground) {
        Intent i = new Intent(ctx, NotificationPollerService.class);
        i.setAction(action);
        if (ACTION_APP_FOREGROUND.equals(action)) {
            i.putExtra(EXTRA_IN_FOREGROUND, inForeground);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    static void setAppForeground(Context ctx, boolean in) {
        start(ctx, ACTION_APP_FOREGROUND, in);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        debugBuild = (getApplicationInfo().flags
            & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        myUserId = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_MY_USER_ID, 0);
        createChannels();
        thread = new HandlerThread("MpPoller");
        thread.start();
        work = new Handler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Must claim foreground immediately, before any work.
        if (!startedForeground) {
            startForeground(ONGOING_ID, buildOngoing());
            startedForeground = true;
        }
        if (intent != null && ACTION_APP_FOREGROUND.equals(intent.getAction())) {
            appInForeground = intent.getBooleanExtra(EXTRA_IN_FOREGROUND, false);
        }
        work.removeCallbacks(pollRunnable);
        work.post(pollRunnable);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (work != null) {
            work.removeCallbacksAndMessages(null);
        }
        if (thread != null) {
            thread.quitSafely();
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- polling

    private void pollOnce() {
        String cookie = android.webkit.CookieManager.getInstance().getCookie(COOKIE_URL);
        if (cookie == null || cookie.trim().isEmpty()) {
            Log.i(TAG, "no cookie; idle");
            work.removeCallbacks(pollRunnable);
            work.postDelayed(pollRunnable, IDLE_INTERVAL_MS);
            return;
        }
        try {
            fetchAndDiff(cookie);
            consecutiveFailures = 0;
            work.removeCallbacks(pollRunnable);
            work.postDelayed(pollRunnable, POLL_INTERVAL_MS);
        } catch (UnauthenticatedException e) {
            consecutiveUnauth++;
            Log.w(TAG, "unauth x" + consecutiveUnauth);
            if (consecutiveUnauth >= 3) {
                Log.w(TAG, "unauth x3, stopping");
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return;
            }
            scheduleBackoff();
        } catch (Exception e) {
            consecutiveFailures++;
            consecutiveUnauth = 0;
            Log.w(TAG, "poll failed: " + e);
            scheduleBackoff();
        }
    }

    private void scheduleBackoff() {
        long delay = Math.min(BACKOFF_CAP_MS, BACKOFF_BASE_MS * Math.max(1, consecutiveFailures));
        work.removeCallbacks(pollRunnable);
        work.postDelayed(pollRunnable, delay);
    }

    private static class UnauthenticatedException extends Exception {}

    private void fetchAndDiff(String cookie) throws Exception {
        if (inertiaVersion == null) {
            inertiaVersion = bootstrapVersion();
        }
        handleNotifications(fetchInertia(cookie, POLL_URL));
        handleConversations(fetchConversations(cookie), cookie);
    }

    private JSONObject fetchInertia(String cookie, String url) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(20_000);
            conn.setRequestProperty("X-Inertia", "true");
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            conn.setRequestProperty("Accept", "text/html, application/xhtml+xml");
            conn.setRequestProperty("Cookie", cookie);
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
            );
            if (inertiaVersion != null) {
                conn.setRequestProperty("X-Inertia-Version", inertiaVersion);
            }
            try {
                int code = conn.getResponseCode();
                if (code == 409) {
                    // Version mismatch or missing; body/header carry nothing usable.
                    // Force a fresh bootstrap (and thus immediate retry) on next attempt.
                    if (attempt == 1) {
                        throw new Exception("inertia 409 persisted after bootstrap");
                    }
                    inertiaVersion = null;
                    Log.i(TAG, "409; re-bootstrapping version");
                    inertiaVersion = bootstrapVersion();
                    continue;
                }
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    if (loc != null && loc.contains("/login")) {
                        throw new UnauthenticatedException();
                    }
                    throw new Exception("unexpected redirect to " + loc);
                }
                if (code != 200) {
                    String err = "";
                    try {
                        err = readBody(conn.getErrorStream());
                        if (err.length() > 300) {
                            err = err.substring(0, 300);
                        }
                    } catch (Exception ignored) {
                    }
                    throw new Exception("http " + code + (err.isEmpty() ? "" : " body=" + err));
                }
                String json = readBody(conn.getInputStream());
                JSONObject root = new JSONObject(json);
                String version = root.optString("version", "");
                if (!version.isEmpty()) {
                    inertiaVersion = version;
                }
                return root;
            } finally {
                conn.disconnect();
            }
        }
        throw new Exception("inertia retry exhausted");
    }

    /**
     * The server rejects Inertia requests whose X-Inertia-Version doesn't match its
     * asset hash (409 with empty body). The public homepage embeds the live hash in
     * its boot payload as "version":"&lt;hash&gt;"; scrape it (no auth needed).
     */
    private String bootstrapVersion() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("https://meningspunktet.dk/").openConnection();
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(20_000);
        conn.setRequestProperty("Accept", "text/html");
        // Bot filter: browser-style UAs without a session cookie get a 370-byte
        // redirect stub with no version hash; plain tool UAs get the full page.
        conn.setRequestProperty("User-Agent", "curl/8.5.0");
        try {
            if (conn.getResponseCode() != 200) {
                throw new Exception("version bootstrap http " + conn.getResponseCode());
            }
            char[] buf = new char[8192];
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                int n;
                int total = 0;
                while ((n = r.read(buf)) > 0 && total < 2_000_000) { // version sits late in the doc
                    sb.append(buf, 0, n);
                    total += n;
                    java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\"version\":\"([0-9a-f]{32,64})\"").matcher(sb);
                    if (m.find()) {
                        String v = m.group(1);
                        Log.i(TAG, "bootstrapped inertia version " + v);
                        return v;
                    }
                }
            }
            throw new Exception("version hash not found; got " + sb.length() + " bytes: "
                + sb.substring(0, Math.min(240, sb.length())).replace('\n', ' '));
        } finally {
            conn.disconnect();
        }
    }

    private static String readBody(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
                if (sb.length() > MAX_BODY_CHARS) {
                    throw new Exception("response too large (> " + MAX_BODY_CHARS + " chars)");
                }
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- parsing

    private void handleNotifications(JSONObject root) throws Exception {
        JSONObject props = root.optJSONObject("props");
        if (props == null) {
            throw new Exception("no props in payload");
        }
        JSONObject auth = props.optJSONObject("auth");
        JSONObject me = auth != null ? auth.optJSONObject("user") : null;
        if (me != null && me.optInt("id", 0) > 0) {
            myUserId = me.optInt("id");
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_MY_USER_ID, myUserId).apply();
            JSONObject avatar = me.optJSONObject("avatar");
            myAvatarFileId = avatar != null ? pick(avatar, "id") : null;
        }
        JSONArray items = findNotificationArray(props);
        if (items == null) {
            if (!loggedShape) {
                Log.w(TAG, "no notification array found; component=" + root.optString("component")
                    + " propsKeys=" + joinKeys(props));
                loggedShape = true;
            }
            return;
        }
        if (!loggedShape) {
            Log.i(TAG, "poll ok component=" + root.optString("component") + " notifKey=" + notifKey + " n=" + items.length());
            loggedShape = true;
        }
        diffAndNotify(items, KEY_SEEN, /* unreadFreshSeed */ item ->
            !item.has("readAt") || item.isNull("readAt"));
    }

    /** The conversation list lives behind a POST (Laravel CSRF). */
    private JSONObject fetchConversations(String cookie) throws Exception {
        return postJson(cookie, CONVERSATIONS_URL);
    }

    /**
     * Empty-body POST expecting JSON; the XSRF-TOKEN cookie, URL-decoded, must be
     * echoed in X-XSRF-TOKEN. Returns null on non-200 after logging.
     */
    private JSONObject postJson(String cookie, String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setInstanceFollowRedirects(false);
        conn.setDoOutput(true);
        conn.setFixedLengthStreamingMode(0);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(20_000);
        conn.setRequestProperty("X-XSRF-TOKEN", xsrfFrom(cookie));
        conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Cookie", cookie);
        conn.setRequestProperty("Origin", "https://meningspunktet.dk");
        conn.setRequestProperty("Referer", "https://meningspunktet.dk/conversations");
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        );
        try {
            int code = conn.getResponseCode();
            if (code == 401 || code == 419 || code == 412
                || (code >= 300 && code < 400)) {
                // CSRF/session failure or auth redirect; transient, surfaced via poll log.
                throw new Exception("post http " + code + " to " + url);
            }
            if (code != 200) {
                String err = "";
                try {
                    err = readBody(conn.getErrorStream());
                    if (err.length() > 200) {
                        err = err.substring(0, 200);
                    }
                } catch (Exception ignored) {
                }
                throw new Exception("post http " + code + " to " + url
                    + (err.isEmpty() ? "" : " body=" + err));
            }
            return new JSONObject(readBody(conn.getInputStream()));
        } finally {
            conn.disconnect();
        }
    }

    /** New conversation messages: load-page paginator {data:[...],meta}, unread flags per item. */
    private void handleConversations(JSONObject root, String cookie) throws Exception {
        JSONArray convs = root.optJSONArray("data");
        if (convs == null) {
            JSONObject pag = root.optJSONObject("data");
            if (pag != null) {
                convs = pag.optJSONArray("data");
            }
        }
        if (convs == null) {
            Log.w(TAG, "no conversations array; keys=" + joinKeys(root));
            return;
        }
        // Without our own user id we cannot tell our messages from inbound ones, and
        // the one thing this poller must never do is notify about our own text.
        // myUserId comes from the /notifications props and is cached in prefs.
        if (myUserId == 0) {
            Log.w(TAG, "own user id unknown; skipping conversation alerts this round");
            return;
        }
        // Flatten to one synthetic alert item per conversation with its newest message.
        JSONArray flat = new JSONArray();
        for (int i = 0; i < convs.length(); i++) {
            JSONObject conv = convs.optJSONObject(i);
            if (conv == null) {
                continue;
            }
            JSONObject last = conv.optJSONObject("lastMessage");
            String uuid = conv.optString("uuid", "");
            if (last == null || uuid.isEmpty()) {
                continue;
            }
            // Never alert for a message we wrote. Sending a reply changes
            // lastMessage.id, so without this check every message we send mints a
            // new alert id and notifies us back with our own text. Only a reply
            // that arrived after our message can still be news.
            boolean mine = messageAuthorId(last) == myUserId;
            if (mine) {
                String lastAt = pickOrDefault(last, "", "createdAt", "created_at");
                String replyAt = pickOrDefault(last, "", "lastReplyAt");
                if (replyAt.isEmpty() || (!lastAt.isEmpty() && replyAt.compareTo(lastAt) <= 0)) {
                    continue;
                }
            }
            // Unread signals, in descending reliability:
            //  lastMessage.isUnread - the newest message itself is inbound & unread:
            //    its body is safe to show (it IS the message being notified about).
            //  hasUnreadReplies / hasUnreadMessages - unread exists somewhere in the
            //    thread tree, but the list endpoint doesn't inline that text and
            //    lastMessage may be an older read one. Then we must NOT show
            //    lastMessage.body (it would be a false quote) - use a generic line
            //    and let name + avatar carry it.
            boolean lastUnread = last.optBoolean("isUnread") && !mine;
            boolean deliver = lastUnread
                || conv.optBoolean("hasUnreadReplies")
                || conv.optBoolean("hasUnreadMessages");
            JSONObject alert = new JSONObject();
            // "self" keeps the id stable across our own sends; a newer reply still
            // changes the reply stamp below and re-alerts.
            String lastId = mine ? "self" : String.valueOf(last.opt("id"));
            // Reply-only updates leave lastMessage.id untouched; fold the surfaced
            // reply fields in so a newer reply re-alerts instead of collapsing.
            String replyStamp = conv.optBoolean("hasUnreadReplies")
                ? ":" + pickOrDefault(last, "-", "lastReplyAt") + ":" + last.optInt("replyCount", 0)
                : "";
            alert.put("id", uuid + ":" + lastId + replyStamp);
            alert.put("unread", deliver);
            String title = pick(conv, "customTitle", "title");
            if (title == null) {
                title = "Ny besked";
            }
            JSONObject group = conv.optJSONObject("group");
            String groupName = group != null ? pick(group, "title") : null;
            if ("group".equals(conv.optString("type")) && groupName != null) {
                title = title + " · " + groupName;
            }
            alert.put("title", title);
            alert.put("body", pickOrDefault(last, "", "body"));
            alert.put("url", "https://meningspunktet.dk/conversations/" + uuid);
            // lastMessage may be our own or an old read one; then its body is NOT
            // what's unread. Deep-fetch that conversation's messages (and unread
            // reply threads) and quote the actual newest inbound unread message.
            if (deliver && !lastUnread) {
                resolveRealUnread(alert, cookie, uuid, pick(conv, "lastReadAt"));
            }
            // Avatar: DM → counterpart's avatar; group → group image; served by public file/serve.
            String avatarFileId = null;
            if ("group".equals(conv.optString("type")) && group != null) {
                avatarFileId = pick(group, "imageId");
            } else {
                JSONArray members = conv.optJSONArray("members");
                if (members != null) {
                    for (int m = 0; m < members.length(); m++) {
                        JSONObject member = members.optJSONObject(m);
                        JSONObject user = member != null ? member.optJSONObject("user") : null;
                        if (user == null || user.optInt("id", -1) == myUserId) {
                            continue;
                        }
                        JSONObject av = user.optJSONObject("avatar");
                        avatarFileId = av != null ? pick(av, "id") : null;
                        if (avatarFileId != null) {
                            break;
                        }
                    }
                }
            }
            if (avatarFileId != null) {
                alert.put("avatarFileId", avatarFileId);
            }
            flat.put(alert);
        }
        diffAndNotify(flat, KEY_CONV_SEEN, item -> item.optBoolean("unread"));
    }

    private String notifKey = "notifications";

    private JSONArray findNotificationArray(JSONObject props) {
        JSONArray direct = props.optJSONArray("notifications");
        if (direct == null) {
            // Laravel paginators wrap items as {"data":[...],"links":{...}}.
            JSONObject wrapped = props.optJSONObject("notifications");
            if (wrapped != null) {
                direct = wrapped.optJSONArray("data");
                if (direct != null) {
                    Log.i(TAG, "notifications is a paginator; keys=" + joinKeys(wrapped));
                }
            }
        }
        if (direct != null) {
            notifKey = "notifications";
            if (debugBuild && direct.length() > 0 && !loggedShape) {
                JSONObject first = direct.optJSONObject(0);
                Log.i(TAG, "item0: " + (first != null ? first.toString() : String.valueOf(direct.opt(0))));
            }
            return direct;
        }
        for (Iterator<String> it = props.keys(); it.hasNext();) {
            String key = it.next();
            Object v = props.opt(key);
            if (v instanceof JSONArray) {
                JSONArray arr = (JSONArray) v;
                if (arr.length() > 0 && arr.optJSONObject(0) != null
                    && (arr.optJSONObject(0).has("created_at") || arr.optJSONObject(0).has("createdAt"))) {
                    notifKey = key;
                    return arr;
                }
            }
        }
        return null;
    }

    private static String joinKeys(JSONObject o) {
        StringBuilder sb = new StringBuilder();
        for (Iterator<String> it = o.keys(); it.hasNext();) {
            sb.append(it.next()).append(',');
        }
        return sb.toString();
    }

    /**
     * For conversations where unread ≠ lastMessage: POST load-messages (same route
     * the web app uses) and replace the alert body with the newest inbound unread
     * message's text. Falls back to a generic line when the shape doesn't cooperate.
     */
    private boolean loggedMsgShape = false;

    private void resolveRealUnread(JSONObject alert, String cookie, String uuid, String convLastReadAt) {
        try {
            String base = "https://meningspunktet.dk/conversations/" + uuid + "/load-messages?messagesPage=";
            JSONObject root = postJson(cookie, base + "1");
            JSONArray msgs = root != null ? paginatorArray(root) : null;
            if (msgs == null) {
                Log.w(TAG, "load-messages no array; keys=" + (root != null ? joinKeys(root) : "null"));
                return;
            }
            if (!loggedMsgShape && msgs.length() > 0 && msgs.optJSONObject(0) != null) {
                Log.i(TAG, "msg0 keys=" + joinKeys(msgs.optJSONObject(0)));
                loggedMsgShape = true;
            }
            String[] scan = newestInbound(msgs, null, "", convLastReadAt);
            // Page 1 is the OLDEST window; the unread message usually lives on the
            // last page. Scan that too when paginated.
            JSONObject meta = root.optJSONObject("meta");
            int lastPage = meta != null ? meta.optInt("last_page", 1) : 1;
            if (lastPage > 1) {
                JSONObject lastRoot = postJson(cookie, base + lastPage);
                JSONArray lastMsgs = lastRoot != null ? paginatorArray(lastRoot) : null;
                if (lastMsgs != null) {
                    scan = newestInbound(lastMsgs, scan[0], scan[1], convLastReadAt);
                }
            }
            if (scan[0] != null) {
                alert.put("body", scan[0]);
            } else {
                // Unread lives in thread replies we don't fetch: don't quote a wrong body.
                alert.put("body", "Ny besked");
            }
        } catch (Exception e) {
            Log.w(TAG, "resolveRealUnread failed: " + e);
            try {
                alert.put("body", "Ny besked");
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Newest message authored by someone else after the conversation's lastReadAt.
     * isUnread is unreliable for messages inside reply threads; server timestamps
     * and the per-conversation lastReadAt are the ground truth.
     */
    private String[] newestInbound(JSONArray msgs, String best, String bestAt, String lastReadAt) {
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) {
                continue;
            }
            String body = pick(m, "body");
            if (body == null) {
                continue;
            }
            // Only quote messages we can positively attribute to someone else.
            // An unknown author might be us, and we never notify about our own text.
            int uid = messageAuthorId(m);
            if (uid <= 0 || uid == myUserId) {
                continue;
            }
            String at = pickOrDefault(m, "", "createdAt", "created_at");
            if (lastReadAt != null && !at.isEmpty() && at.compareTo(lastReadAt) <= 0) {
                continue;
            }
            if (best == null || at.compareTo(bestAt) >= 0) {
                best = body;
                bestAt = at;
            }
        }
        return new String[] { best, bestAt };
    }

    /**
     * Author id of a message/notification object, or -1 when the payload does not
     * expose one (user.id relation, then camel/snake scalar ids).
     */
    private static int messageAuthorId(JSONObject m) {
        JSONObject user = m.optJSONObject("user");
        if (user != null) {
            int id = user.optInt("id", -1);
            if (id > 0) {
                return id;
            }
        }
        int id = m.optInt("userId", -1);
        if (id > 0) {
            return id;
        }
        return m.optInt("user_id", -1);
    }

    private static String xsrfFrom(String cookie) throws Exception {
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.startsWith("XSRF-TOKEN=")) {
                return java.net.URLDecoder.decode(p.substring("XSRF-TOKEN=".length()), "UTF-8");
            }
        }
        throw new Exception("no XSRF-TOKEN cookie");
    }

    private static JSONArray paginatorArray(JSONObject root) {
        JSONArray arr = root.optJSONArray("data");
        if (arr == null) {
            JSONObject pag = root.optJSONObject("data");
            if (pag != null) {
                arr = pag.optJSONArray("data");
            }
        }
        return arr;
    }

    private static String pick(JSONObject o, String... keys) {
        for (String k : keys) {
            Object v = o.opt(k);
            // Android's org.json turns JSON null into the string "null" via
            // optString; only real strings/numbers count.
            if (v == null || v == JSONObject.NULL) {
                continue;
            }
            String s = String.valueOf(v);
            if (!s.isEmpty()) {
                return s;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- diffing

    /** Decides which items count as "genuinely unread" when seeding from scratch. */
    private interface UnreadRule {
        boolean isUnread(JSONObject item) throws org.json.JSONException;
    }

    private void diffAndNotify(JSONArray items, String keySeen, UnreadRule unreadRule) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        JSONArray seen;
        try {
            seen = new JSONArray(prefs.getString(keySeen, "[]"));
        } catch (Exception e) {
            seen = new JSONArray();
        }
        boolean freshInstall = !prefs.contains(keySeen);

        List<JSONObject> news = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String id = itemId(item);
            if (!contains(seen, id)) {
                news.add(item);
                seen.put(id);
            }
        }
        while (seen.length() > SEEN_CAP) {
            JSONArray trimmed = new JSONArray();
            for (int i = seen.length() - SEEN_CAP; i < seen.length(); i++) {
                trimmed.put(seen.opt(i));
            }
            seen = trimmed;
        }
        // While the UI is visible on a fresh install, persist nothing yet: the next
        // background poll still sees freshInstall and can deliver unread items.
        if (!(freshInstall && appInForeground)) {
            prefs.edit().putString(keySeen, seen.toString()).apply();
        }

        if (freshInstall) {
            if (appInForeground) {
                // Defer persisting the seen-list while the UI is visible, so the first
                // background poll still delivers genuinely unread items.
                Log.i(TAG, "seed deferred (foreground)");
                return;
            }
            // Don't burst with everything, but don't swallow genuinely unread items either:
            // unread notifications still get delivered (while backgrounded).
            news.clear();
            for (int i = 0; i < items.length() && news.size() < MAX_NOTIFY_PER_TICK; i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                try {
                    if (unreadRule.isUnread(item)) {
                        news.add(item);
                    }
                } catch (Exception ignored) {
                }
            }
            Log.i(TAG, "seeded " + items.length() + " ids key=" + keySeen + " (" + news.size() + " unread)");
        }
        if (news.isEmpty()) {
            return;
        }
        if (appInForeground) {
            Log.i(TAG, "suppressed " + news.size() + " (foreground)");
            return;
        }
        int count = 0;
        for (JSONObject item : news) {
            if (count++ >= MAX_NOTIFY_PER_TICK) {
                break;
            }
            postAlert(item);
        }
    }

    private static String itemId(JSONObject item) {
        String id = pick(item, "id", "uuid");
        if (id != null) {
            return id;
        }
        String body = pickOrDefault(item, "", "body", "description", "text");
        String createdAt = pickOrDefault(item, "", "created_at", "createdAt");
        return Integer.toString((body + "|" + createdAt).hashCode());
    }

    private static String pickOrDefault(JSONObject o, String fallback, String... keys) {
        String v = pick(o, keys);
        return v != null ? v : fallback;
    }

    private static String itemUrl(JSONObject item) {
        String url = pick(item, "url", "link");
        if (url == null) {
            JSONObject data = item.optJSONObject("data");
            if (data != null) {
                url = pick(data, "url", "link");
            }
        }
        return url != null ? url : "/notifications";
    }

    private static boolean contains(JSONArray arr, String value) {
        for (int i = 0; i < arr.length(); i++) {
            if (value.equals(arr.optString(i))) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- notify

    /**
     * Avatar for an alert: conversation items carry avatarFileId; site notification
     * items carry imageIds (first non-own entry = the counterpart). Public endpoint,
     * cached in memory. Called on the worker thread; failures just mean no picture.
     */
    private Bitmap avatarFor(JSONObject item) {
        String fileId = pick(item, "avatarFileId");
        if (fileId == null) {
            JSONArray ids = item.optJSONArray("imageIds");
            if (ids != null) {
                for (int i = 0; i < ids.length(); i++) {
                    String cand = ids.optString(i);
                    if (!cand.isEmpty() && !cand.equals(myAvatarFileId)) {
                        fileId = cand;
                        break;
                    }
                }
            }
        }
        if (fileId == null) {
            return null;
        }
        Bitmap cached = avatarCache.get(fileId);
        if (cached != null) {
            return cached;
        }
        try {
            HttpURLConnection conn = (HttpURLConnection)
                new URL("https://meningspunktet.dk/file/serve/" + fileId).openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Accept", "image/*");
            byte[] raw;
            try {
                if (conn.getResponseCode() != 200) {
                    return null;
                }
                try (InputStream in = conn.getInputStream()) {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (bos.size() + n > MAX_AVATAR_BYTES) {
                            Log.w(TAG, "avatar too large; skipping");
                            return null;
                        }
                        bos.write(buf, 0, n);
                    }
                    raw = bos.toByteArray();
                }
            } finally {
                conn.disconnect();
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
            int sample = 1;
            while (opts.outWidth / (sample * 2) >= 128 && opts.outHeight / (sample * 2) >= 128) {
                sample *= 2;
            }
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
            if (bmp != null) {
                avatarCache.put(fileId, bmp);
            }
            return bmp;
        } catch (Exception e) {
            Log.d(TAG, "avatar fetch failed: " + e);
            return null;
        }
    }

    private void postAlert(JSONObject item) {
        String id = itemId(item);
        String title = pickOrDefault(item, "Meningspunktet", "title", "subject", "message");
        String body = pickOrDefault(item, "", "body", "description", "text");
        String url = itemUrl(item);
        Log.i(TAG, debugBuild ? "notify id=" + id + " url=" + url : "notify id=" + id);
        try {
            int reqCode = 1000 + Math.abs(id.hashCode() % 100000);
            Intent tap = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_OPEN_URL, url);
            PendingIntent pi = PendingIntent.getActivity(
                this, reqCode, tap, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setContentIntent(pi);
            Bitmap avatar = avatarFor(item);
            if (avatar != null) {
                b.setLargeIcon(avatar);
            }
            Notification n = b.build();
            NotificationManagerCompat.from(this).notify(id.hashCode(), n);
        } catch (Exception e) {
            Log.w(TAG, "notify failed: " + e);
        }
    }

    private Notification buildOngoing() {
        Intent open = new Intent(this, MainActivity.class)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
            this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Meningspunktet")
            .setContentText("Kører i baggrunden")
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pi);
        if (Build.VERSION.SDK_INT >= 29) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel service = new NotificationChannel(
            CHANNEL_SERVICE, "Meningspunktet baggrund", NotificationManager.IMPORTANCE_LOW);
        service.setShowBadge(false);
        service.setDescription("Holder øje med nye notifikationer ved at spørge meningspunktet.dk "
            + "direkte hvert minut - helt uden Google-, Apple- eller Mozilla-servere. "
            + "Det koster lidt ekstra batteri sammenlignet med almindelig push.");
        NotificationChannel alerts = new NotificationChannel(
            CHANNEL_ALERTS, "Notifikationer", NotificationManager.IMPORTANCE_DEFAULT);
        alerts.setDescription("Nye notifikationer og beskeder fra meningspunktet.dk, hentet direkte "
            + "fra sitet uden tredjepartsservere undervejs.");
        nm.createNotificationChannel(service);
        nm.createNotificationChannel(alerts);
    }
}
