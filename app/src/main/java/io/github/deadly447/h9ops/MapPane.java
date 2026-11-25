package io.github.deadly447.h9ops;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import io.github.deadly447.h9ops.map.MapFile;
import io.github.deadly447.h9ops.map.MapJson;
import io.github.deadly447.h9ops.map.Terrain;
import io.github.deadly447.h9ops.map.TerrainKind;
import io.github.deadly447.h9ops.map.TileSource;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The Map tab: a WebView showing the bundled map page (Leaflet and
 * protomaps-leaflet, in assets/map) over the offline map file.
 *
 * Nothing here touches the network. The app has no INTERNET permission, and
 * every request the page makes is answered by {@link Client} from the APK's
 * assets or the map file (as plain z/x/y tiles); anything else is refused. The page can't navigate
 * anywhere, and the bridge ({@link Bridge}) only takes taps and the map view.
 */
final class MapPane {
    interface Host {
        void mapStation(String name);
        void mapNavigate(double lat, double lon, String label);
        void mapMe();
        void mapFull();
        void mapGone();
        void mapLongPress(double lat, double lon);
        void mapRemoveObject(String name);
        /** The "no map yet" note was tapped: show how to get one. */
        void mapGetMap();
    }

    static final String HOST = "appassets.androidplatform.net";
    private static final String PAGE = "https://" + HOST + "/map/index.html";
    private static final long PUSH_DELAY_MS = 400;

    static File mapFile(Context c) {
        return new File(new File(c.getFilesDir(), "maps"), "area.pmtiles");
    }

    /** The terrain or topo file (PMTiles or MBTiles; which one is read from the file itself). */
    static File terrainFile(Context c) {
        return new File(new File(c.getFilesDir(), "maps"), "terrain.tiles");
    }

    /** Remembers what a newly added terrain file holds, so it isn't worked out on every start. */
    static void saveTerrainInfo(Context c, Terrain.Info info) {
        c.getSharedPreferences("map", Context.MODE_PRIVATE).edit()
                .putString("terrain_kind", info.kind.id).putInt("terrain_px", info.px)
                .putBoolean("terrain_on", true).apply();
    }

    final WebView web;
    private final Activity act;
    private final Host host;
    private final Supplier<String> stations, areas, overlays;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile MapFile file;          // read by the WebView's request threads
    private volatile TileSource terrain;    // likewise
    private volatile String config = "{}";
    private boolean ready, shown, dirty;
    private final List<String> queued = new ArrayList<>();
    private final Runnable push = this::pushStations;
    private int pageErrors;                 // logged per page load, at most MAX_PAGE_ERRORS
    private static final int MAX_PAGE_ERRORS = 20;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    MapPane(Activity a, Host host, Supplier<String> stations, Supplier<String> areas, Supplier<String> overlays) {
        this.act = a;
        this.host = host;
        this.stations = stations;
        this.areas = areas;
        this.overlays = overlays;
        this.prefs = a.getSharedPreferences("map", Context.MODE_PRIVATE);
        web = new WebView(a);
        web.setBackgroundColor(Ui.BG);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setLongClickable(false);
        web.setOnLongClickListener(v -> true);   // no text-selection bar over the map
        web.setHapticFeedbackEnabled(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);          // the ◎ button asks the app for one fix instead
        s.setDomStorageEnabled(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setMediaPlaybackRequiresUserGesture(true);
        if (Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(false);   // it would only try the network
        web.addJavascriptInterface(new Bridge(), "H9");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage m) {
                logPage(m);
                return true;
            }
        });
        load();
    }

    /** (Re)opens the map file and loads the page. Call again after the file changes. */
    void load() {
        MapFile old = file;
        file = null;
        if (old != null) try { old.close(); } catch (IOException ignored) { }
        File f = mapFile(act);
        if (f.isFile()) {
            try { file = MapFile.open(f); } catch (IOException e) { file = null; }
        }
        TileSource oldT = terrain;
        terrain = null;
        if (oldT != null) try { oldT.close(); } catch (IOException ignored) { }
        File tf = terrainFile(act);
        if (tf.isFile()) {
            try {
                terrain = Terrain.open(tf);
                if (!prefs.contains("terrain_kind")) saveTerrainInfo(act, Terrain.detect(terrain));
            } catch (IOException | RuntimeException e) {
                terrain = null;
                Repo.get(act).dev.log("MAP", "terrain file can't be read: " + e.getMessage());
            }
        }
        config = config();
        pageErrors = 0;
        ready = false;
        queued.clear();
        dirty = true;
        web.loadUrl(PAGE);
    }

    MapFile file() { return file; }

    /**
     * Map page errors go to the developer log, with every number replaced by
     * # (tile numbers and coordinates would say where the map was looking).
     */
    private void logPage(ConsoleMessage m) {
        if (m.messageLevel() != ConsoleMessage.MessageLevel.ERROR
                && m.messageLevel() != ConsoleMessage.MessageLevel.WARNING) return;
        if (pageErrors++ >= MAX_PAGE_ERRORS) return;
        String text = String.valueOf(m.message()).replaceAll("[0-9]+", "#");
        if (text.length() > 200) text = text.substring(0, 200) + "…";
        Repo.get(act).dev.log("MAP", (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR ? "page error: " : "page warning: ")
                + text + (pageErrors == MAX_PAGE_ERRORS ? " (no more map errors logged until it reloads)" : ""));
    }

    private String config() {
        MapFile f = file;
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"tiles\":").append(f != null);
        if (f != null) {
            MapFile.Header h = f.header;
            sb.append(",\"v\":").append(MapJson.q(f.version()))
                    .append(",\"maxZoom\":").append(h.maxZoom)
                    .append(",\"minZoom\":").append(h.minZoom)
                    .append(",\"bounds\":[").append(num(h.minLat)).append(',').append(num(h.minLon)).append(',')
                    .append(num(h.maxLat)).append(',').append(num(h.maxLon)).append(']');
        }
        if (prefs.contains("lat")) {
            sb.append(",\"view\":[").append(num(prefs.getFloat("lat", 0))).append(',')
                    .append(num(prefs.getFloat("lon", 0))).append(',').append(prefs.getInt("zoom", 10)).append(']');
        } else if (f != null && f.header.centerZoom > 0) {
            sb.append(",\"view\":[").append(num(f.header.centerLat)).append(',')
                    .append(num(f.header.centerLon)).append(',').append(f.header.centerZoom).append(']');
        }
        TileSource t = terrain;
        if (t != null) {
            double[] b = t.bounds();
            sb.append(",\"terrain\":{\"kind\":").append(MapJson.q(prefs.getString("terrain_kind", "picture")))
                    .append(",\"px\":").append(prefs.getInt("terrain_px", 256))
                    .append(",\"min\":").append(t.minZoom()).append(",\"max\":").append(t.maxZoom())
                    .append(",\"v\":").append(MapJson.q(t.version()))
                    .append(",\"bounds\":[").append(num(b[0])).append(',').append(num(b[1])).append(',')
                    .append(num(b[2])).append(',').append(num(b[3])).append(']')
                    .append(",\"attr\":").append(MapJson.q(attribution(t.metadata())))
                    .append(",\"on\":").append(prefs.getBoolean("terrain_on", true)).append('}');
        }
        sb.append(",\"night\":").append(Ui.night)
                .append(",\"accent\":").append(MapJson.q(css(Ui.accent)))
                .append(",\"text\":").append(MapJson.q(css(Ui.text)))
                .append(",\"muted\":").append(MapJson.q(css(Ui.muted)));
        return sb.append('}').toString();
    }

    /** The file's own credit line, as plain text (tags removed), at most 80 characters. */
    static String attribution(String metadata) {
        String a = "";
        try {
            a = new org.json.JSONObject(metadata).optString("attribution", "");
        } catch (org.json.JSONException | RuntimeException ignored) { }
        a = a.replaceAll("<[^>]*>", "").replace("&copy;", "©").replace("&amp;", "&").replaceAll("\\s+", " ").trim();
        return a.length() > 80 ? a.substring(0, 80) + "…" : a;
    }

    private static String css(int argb) {
        return String.format(Locale.US, "#%06x", argb & 0xFFFFFF);
    }

    private static String num(double v) {
        return String.format(Locale.US, "%.6f", v);
    }

    // ---------------------------------------------------------------- from the app

    /** The tab is showing (or not): only a visible map is kept up to date. */
    void show(boolean on) {
        shown = on;
        if (on) {
            web.onResume();
            if (dirty && ready) pushStations();   // right away, so a focus() that follows finds the station
        } else {
            web.onPause();
        }
    }

    /** New packets: the stations are sent to the page shortly (a burst becomes one update). */
    void stationsChanged() {
        dirty = true;
        if (!ready || !shown) return;
        ui.removeCallbacks(push);
        ui.postDelayed(push, PUSH_DELAY_MS);
    }

    private void pushStations() {
        if (!ready || !shown) return;
        dirty = false;
        web.evaluateJavascript("H9map.setAreas(" + areas.get() + ");H9map.setStations(" + stations.get() + ")", null);
    }

    void focus(String name, double lat, double lon) {
        call("H9map.focus(" + MapJson.q(name) + "," + num(lat) + "," + num(lon) + ")");
    }

    void setMe(double lat, double lon, float accuracyM) {
        call("H9map.setMe(" + num(lat) + "," + num(lon) + "," + Math.round(accuracyM) + ")");
    }

    void meFailed() { call("H9map.meFailed()"); }

    void setTrack(String json) { call("H9map.setTrack(" + json + ")"); }

    /** Redraws the overlays; {@code fitId} (or null) zooms to that one. */
    void overlaysChanged(String fitId) {
        call("H9map.setOverlays(" + overlays.get() + "," + MapJson.q(fitId) + ")");
    }

    /** Runs now if the page is ready, otherwise as soon as it is (after the stations). */
    private void call(String js) {
        if (ready) web.evaluateJavascript(js, null);
        else queued.add(js);
    }

    void destroy() {
        ui.removeCallbacks(push);
        web.destroy();
        MapFile f = file;
        file = null;
        if (f != null) try { f.close(); } catch (IOException ignored) { }
        TileSource t = terrain;
        terrain = null;
        if (t != null) try { t.close(); } catch (IOException ignored) { }
    }

    // ---------------------------------------------------------------- from the page

    /** Called by the page. Runs on a WebView thread; anything touching views goes to the UI thread. */
    private final class Bridge {
        @JavascriptInterface public String config() { return config; }

        @JavascriptInterface public void ready() {
            ui.post(() -> {
                ready = true;
                web.evaluateJavascript("H9map.setOverlays(" + overlays.get() + ")", null);
                if (shown) pushStations();
                for (String js : queued) web.evaluateJavascript(js, null);
                queued.clear();
            });
        }

        @JavascriptInterface public void station(String name) {
            if (name != null) ui.post(() -> host.mapStation(name));
        }

        @JavascriptInterface public void navigate(double lat, double lon, String label) {
            ui.post(() -> host.mapNavigate(lat, lon, label == null ? "" : label));
        }

        @JavascriptInterface public void view(double lat, double lon, double zoom) {
            if (Double.isNaN(lat) || Double.isNaN(lon) || Double.isNaN(zoom)) return;
            prefs.edit().putFloat("lat", (float) lat).putFloat("lon", (float) lon)
                    .putInt("zoom", (int) Math.round(zoom)).apply();
        }

        @JavascriptInterface public void me() { ui.post(host::mapMe); }

        @JavascriptInterface public void getMap() { ui.post(host::mapGetMap); }

        @JavascriptInterface public void longPress(double lat, double lon) {
            if (!Double.isNaN(lat) && !Double.isNaN(lon)) ui.post(() -> host.mapLongPress(lat, lon));
        }

        @JavascriptInterface public void removeObject(String name) {
            if (name != null) ui.post(() -> host.mapRemoveObject(name));
        }

        @JavascriptInterface public void full() { ui.post(host::mapFull); }

        @JavascriptInterface public void terrain(boolean on) {
            prefs.edit().putBoolean("terrain_on", on).apply();
        }
    }

    // ---------------------------------------------------------------- requests

    private final class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (!"https".equals(u.getScheme()) || !HOST.equals(u.getHost())) return status(403, "Forbidden");
            String path = u.getPath();
            if (path == null || path.contains("..")) return status(404, "Not Found");
            if (path.startsWith("/tiles/")) return tile(path);
            if (path.startsWith("/terrain/")) return terrainTile(path);
            if (path.startsWith("/map/") || path.startsWith("/fonts/")) return asset(path.substring(1));
            return status(404, "Not Found");
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            return true;    // the page never leaves itself
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // The page's renderer crashed or was killed for memory: drop this WebView, not the app.
            ui.post(host::mapGone);
            return true;
        }
    }

    private WebResourceResponse asset(String path) {
        String mime = mime(path);
        try {
            InputStream in = act.getAssets().open(path);
            Map<String, String> h = headers();
            if (mime.equals("text/html")) {
                h.put("Content-Security-Policy", "default-src 'self'; img-src 'self' data: blob:; "
                        + "style-src 'self' 'unsafe-inline'; font-src 'self'; script-src 'self'; "
                        + "connect-src 'self'; worker-src 'self' blob:; base-uri 'none'; form-action 'none'");
            }
            boolean text = mime.startsWith("text/") || mime.equals("application/javascript");
            return new WebResourceResponse(mime, text ? "utf-8" : null, 200, "OK", h, in);
        } catch (IOException e) {
            return status(404, "Not Found");
        }
    }

    /** One tile, "/tiles/z/x/y.mvt", uncompressed. An empty tile where the map has none. */
    private WebResourceResponse tile(String path) {
        MapFile f = file;
        if (f == null) return status(404, "Not Found");
        String[] p = path.substring("/tiles/".length()).split("/");
        if (p.length != 3 || !p[2].endsWith(".mvt")) return status(404, "Not Found");
        byte[] b;
        try {
            int z = Integer.parseInt(p[0]), x = Integer.parseInt(p[1]);
            int y = Integer.parseInt(p[2].substring(0, p[2].length() - 4));
            b = f.tile(z, x, y);
        } catch (NumberFormatException e) {
            return status(404, "Not Found");
        } catch (IOException e) {
            return status(500, "Error");
        }
        if (b == null) b = new byte[0];
        Map<String, String> h = headers();
        h.put("Content-Length", Integer.toString(b.length));
        return new WebResourceResponse("application/x-protobuf", null, 200, "OK", h, new ByteArrayInputStream(b));
    }

    /** One terrain or topo tile, "/terrain/z/x/y", as the picture it is stored as. 404 where there's none. */
    private WebResourceResponse terrainTile(String path) {
        TileSource t = terrain;
        if (t == null) return status(404, "Not Found");
        String[] p = path.substring("/terrain/".length()).split("/");
        if (p.length != 3) return status(404, "Not Found");
        byte[] b;
        try {
            b = t.tile(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        } catch (NumberFormatException e) {
            return status(404, "Not Found");
        } catch (IOException e) {
            return status(500, "Error");
        }
        if (b == null || b.length == 0) return status(404, "Not Found");
        Map<String, String> h = headers();
        h.put("Content-Length", Integer.toString(b.length));
        return new WebResourceResponse(t.mime(), null, 200, "OK", h, new ByteArrayInputStream(b));
    }

    private static Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        h.put("X-Content-Type-Options", "nosniff");
        return h;
    }

    private static WebResourceResponse status(int code, String reason) {
        return new WebResourceResponse("text/plain", "utf-8", code, reason, headers(),
                new ByteArrayInputStream(new byte[0]));
    }

    private static String mime(String path) {
        String p = path.toLowerCase(Locale.US);
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }
}
