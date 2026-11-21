package dk.meningspunktet.unofficial.app;

import android.Manifest;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Plain WebView host for https://meningspunktet.dk.
 *
 * Edge-to-edge (enforced on targetSdk 35+). Native side pads the WebView's
 * top/left/right with system-bar + display-cutout insets and zeroes those
 * types before the insets reach the WebView, so the page never double-pads;
 * the bottom inset passes through untouched so the page's
 * env(safe-area-inset-bottom) CSS still clears the gesture area. The strip
 * above the WebView is painted #18181b (the site's chrome color).
 */
public class MainActivity extends ComponentActivity {

    private static final String SITE_URL = "https://meningspunktet.dk";
    private static final String SITE_HOST = "meningspunktet.dk";
    /** Bounded redirect walk when validating a download URL before it can carry cookies. */
    private static final int MAX_REDIRECTS = 5;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private ActivityResultLauncher<Intent> fileChooser;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(0xFF18181B);
        container.addView(webView);
        setContentView(container);
        ViewCompat.setOnApplyWindowInsetsListener(container, (view, insets) -> {
            int types = WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout();
            Insets padding = insets.getInsets(types);
            view.setPadding(padding.left, padding.top, padding.right, 0);

            WindowInsetsCompat.Builder remaining = new WindowInsetsCompat.Builder(insets);
            int[] insetTypes = {
                WindowInsetsCompat.Type.statusBars(),
                WindowInsetsCompat.Type.navigationBars(),
                WindowInsetsCompat.Type.captionBar(),
                WindowInsetsCompat.Type.displayCutout()
            };
            for (int type : insetTypes) {
                Insets original = insets.getInsets(type);
                remaining.setInsets(type, Insets.of(0, 0, 0, original.bottom));
            }
            return remaining.build();
        });

        // The padded strip above the WebView shows the WebView's own
        // background; paint it the site's dark chrome color (#18181b) so the
        // status bar matches the site header in both light and dark themes.
        // (Default WebView background is white - that's the white band.)
        webView.setBackgroundColor(0xFF18181B);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // No file://, content:// or data: document loads: this is a remote-site
        // wrapper. Also keeps a crafted intent extra (see notificationUrlFrom)
        // from turning into a local-file reader.
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        WebView.setWebContentsDebuggingEnabled(false);
        // The stock WebView UA advertises "; wv", which meningspunktet.dk's
        // app-detection reacts to; present as plain Chrome instead.
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));
        // First-party site only; nothing embedded needs cross-site cookies.
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);

        fileChooser = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (filePathCallback == null) {
                    return;
                }
                Uri[] uris = null;
                Intent data = result.getData();
                if (result.getResultCode() == RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        android.content.ClipData clip = data.getClipData();
                        uris = new Uri[clip.getItemCount()];
                        for (int i = 0; i < clip.getItemCount(); i++) {
                            uris[i] = clip.getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        uris = new Uri[] { data.getData() };
                    }
                }
                filePathCallback.onReceiveValue(uris);
                filePathCallback = null;
            });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    fileChooser.launch(params.createIntent());
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isSiteUrl(uri)) {
                    return false; // same site: keep in WebView
                }
                // Everything else (other hosts, other schemes) belongs to the OS.
                openExternally(uri.toString());
                return true;
            }

            /**
             * Main-frame POSTs never reach shouldOverrideUrlLoading, so check the
             * committed URL too: anything off-site is stopped and handed to the
             * OS instead of rendering inside the wrapper.
             */
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                Uri uri = Uri.parse(url);
                if (isSiteUrl(uri) || "about".equals(uri.getScheme())) {
                    return;
                }
                view.stopLoading();
                openExternally(url);
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                Uri uri = Uri.parse(url);
                if (Build.VERSION.SDK_INT < 29 || !isSiteUrl(uri)) {
                    // Pre-scoped-storage would need WRITE_EXTERNAL_STORAGE, and the
                    // session cookie must never follow a download off-site (redirects
                    // included) - hand non-site downloads to the browser instead.
                    openExternally(url);
                    return;
                }
                // Content-Disposition is remote input; strip any path components so
                // it cannot escape the Downloads directory.
                String fileName = new File(
                    URLUtil.guessFileName(url, contentDisposition, mimeType)).getName();
                if (fileName.isEmpty() || ".".equals(fileName) || "..".equals(fileName)) {
                    fileName = "download";
                }
                // DownloadManager follows redirects itself but still sends whatever
                // headers we set, so a redirect could carry the session cookie to
                // another host. Walk the chain ourselves (bounded); only enqueue if
                // every hop stays on-site, then point DownloadManager at the resolved
                // URL so it never needs to follow a cross-site hop. Anything else
                // goes to the browser with no credentials attached.
                String resolvedUrl = resolveWithinSite(uri);
                if (resolvedUrl == null) {
                    openExternally(url);
                    return;
                }
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(resolvedUrl));
                req.setTitle(fileName);
                if (mimeType != null) {
                    req.setMimeType(mimeType);
                }
                String cookie = CookieManager.getInstance().getCookie(resolvedUrl);
                if (cookie != null) {
                    req.addRequestHeader("Cookie", cookie);
                }
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                try {
                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    dm.enqueue(req);
                    Toast.makeText(MainActivity.this, "Starter download: " + fileName,
                        Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    openExternally(url);
                }
            }
        });

        // Browser-style back: WebView history first, finish only at the root.
        getOnBackPressedDispatcher().addCallback(
            this,
            new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    if (webView != null && webView.canGoBack()) {
                        webView.goBack();
                        return;
                    }
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        );

        // Notification posting requires runtime consent on Android 13+.
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 4210);
        }

        // Keep the background notification poller alive and informed of UI state.
        NotificationPollerService.setAppForeground(this, true);

        String openUrl = notificationUrlFrom(getIntent());
        webView.loadUrl(openUrl != null ? openUrl : SITE_URL);
    }

    @Override
    public void onStart() {
        super.onStart();
        NotificationPollerService.setAppForeground(this, true);
    }

    @Override
    public void onStop() {
        super.onStop();
        NotificationPollerService.setAppForeground(this, false);
        CookieManager.getInstance().flush(); // persist session cookies promptly
    }

    @Override
    protected void onDestroy() {
        // Release the WebView's native peers (renderers, Chromium resources)
        // instead of waiting for finalizers; a stale pending file-chooser
        // callback must not receive a result for a dead WebView.
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String url = notificationUrlFrom(intent);
        if (url != null) {
            webView.loadUrl(url);
        }
    }

    /**
     * URL carried by a notification tap (cold start or singleTask re-entry).
     *
     * MainActivity is exported (LAUNCHER), so this extra is attacker-controlled:
     * any installed app can start us with mp_open_url set. Only site-relative
     * paths and https://meningspunktet.dk URLs are accepted - in particular a
     * javascript: URL would otherwise run in the authenticated page's origin.
     */
    private String notificationUrlFrom(Intent intent) {
        if (intent == null) {
            return null;
        }
        String url = intent.getStringExtra(NotificationPollerService.EXTRA_OPEN_URL);
        if (url == null || url.isEmpty()) {
            return null;
        }
        intent.removeExtra(NotificationPollerService.EXTRA_OPEN_URL);
        Uri uri;
        try {
            uri = Uri.parse(url);
        } catch (Exception e) {
            return null;
        }
        if (uri.isRelative()) {
            // "/notifications" (also "//host", which becomes a path on our own host).
            return SITE_URL + url;
        }
        return isSiteUrl(uri) ? uri.toString() : null;
    }

    /**
     * True only for https on meningspunktet.dk itself or one of its subdomains.
     *
     * Trust assumption: we delegate to every *.meningspunktet.dk host because the
     * site uses subdomains for its own infrastructure. If the operator ever lets
     * third parties register subdomains (hosted-blogs style), tighten this to the
     * exact hosts that legitimately serve app content - a user-registerable
     * subdomain would otherwise be treated as first-party for navigation,
     * downloads and intent extras.
     */
    private static boolean isSiteUrl(Uri uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        return host.equals(SITE_HOST) || host.endsWith("." + SITE_HOST);
    }

    /**
     * Follows 3xx redirects with the request method preserved (GET; download
     * URLs here are GET), bounded by {@link #MAX_REDIRECTS}. Returns the final
     * URL if every hop is a site URL, or null when the chain leaves the site
     * or any hop fails - callers must then not attach session credentials.
     */
    @Nullable
    private static String resolveWithinSite(Uri start) {
        String current = start.toString();
        try {
            for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
                if (!isSiteUrl(Uri.parse(current))) {
                    return null;
                }
                HttpURLConnection conn =
                    (HttpURLConnection) new URL(current).openConnection();
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                // Just enough to get the redirect target; nothing meaningful
                // is downloaded here.
                conn.setRequestProperty("Range", "bytes=0-0");
                try {
                    int code = conn.getResponseCode();
                    String location = conn.getHeaderField("Location");
                    if (code >= 300 && code < 400 && location != null) {
                        // java.net.URL resolves relative Locations against the
                        // current URL (RFC 7231 relative references).
                        current = new URL(new URL(current), location).toString();
                        continue;
                    }
                    // 200/204/404/etc: no further redirect, chain stays on-site.
                    return current;
                } finally {
                    conn.disconnect();
                }
            }
            return null; // redirect loop / too many hops
        } catch (Exception e) {
            return null;
        }
    }

    /** Hands http(s)/mailto/tel URLs to the OS; ignores every other scheme. */
    private void openExternally(String url) {
        Uri uri;
        try {
            uri = Uri.parse(url);
        } catch (Exception e) {
            return;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return;
        }
        switch (scheme.toLowerCase(Locale.ROOT)) {
            case "http":
            case "https":
            case "mailto":
            case "tel":
                break;
            default:
                return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
            // no browser installed; nothing sensible left to do
        }
    }
}
