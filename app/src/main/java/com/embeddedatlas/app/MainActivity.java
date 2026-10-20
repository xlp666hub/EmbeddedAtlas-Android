package com.embeddedatlas.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.AtomicFile;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQUEST_IMPORT = 101;
    private static final int REQUEST_EXPORT = 102;
    private static final String BASE_URL = "https://appassets.androidplatform.net/";

    private WebView webView;
    private ValueCallback<Uri[]> uploadCallback;
    private String pendingExportPayload;
    private String pendingExportToken;
    private File notesFile;
    private File previousFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        notesFile = new File(getFilesDir(), "notes.json");
        previousFile = new File(getFilesDir(), "notes.previous.json");

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        // Android 15 requires handling edge-to-edge system bars for target API 35.
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true); // Android document picker URIs only
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setDefaultTextEncodingName("UTF-8");
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        webView.addJavascriptInterface(new AtlasBridge(), "AtlasAndroid");

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri.toString().startsWith(BASE_URL)) return false;
                // Do not navigate the trusted app WebView to arbitrary websites: the JS bridge
                // must remain available only to our own bundled HTML.
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) { }
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (uploadCallback != null) uploadCallback.onReceiveValue(null);
                uploadCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"application/json", "text/plain", "application/octet-stream"});
                try {
                    startActivityForResult(intent, REQUEST_IMPORT);
                    return true;
                } catch (ActivityNotFoundException e) {
                    uploadCallback.onReceiveValue(null);
                    uploadCallback = null;
                    return false;
                }
            }
        });

        try {
            String html = readAll(getAssets().open("atlas.html"));
            String notes = loadNotes();
            if (notes != null) {
                // Avoid HTML parser interpretation of a literal </script> inside user data.
                String safeData = notes.replace("<", "\\u003c").replace(">", "\\u003e");
                String marker = "</script><script>/* Embedded Atlas";
                if (!html.contains(marker)) {
                    throw new IOException("HTML bootstrap marker missing");
                }
                html = html.replace(marker,
                    "</script><script>window.ATLAS_NATIVE_DATA=" + safeData + ";</script><script>/* Embedded Atlas");
            }
            String marker = "</script><script>/* Embedded Atlas";
            String bridge = "</script><script>" +
                "window.__atlasExportPending={};" +
                "window.__atlasExportDone=function(id,r){const f=window.__atlasExportPending[id];if(f){delete window.__atlasExportPending[id];f(r);}};" +
                "window.pywebview={api:{" +
                "save_data:function(j){return Promise.resolve(JSON.parse(AtlasAndroid.saveData(j)));}," +
                "export_data:function(j){return new Promise(function(resolve){const id='x'+Date.now()+'_'+Math.random();window.__atlasExportPending[id]=resolve;AtlasAndroid.requestExport(j,id);});}" +
                "}}};" +
                "</script><script>/* Embedded Atlas";
            if (!html.contains(marker)) throw new IOException("HTML bridge marker missing");
            html = html.replace(marker, bridge);
            webView.loadDataWithBaseURL(BASE_URL, html, "text/html", "UTF-8", null);
        } catch (IOException e) {
            Toast.makeText(this, "知识库加载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static String readAll(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static boolean validNotes(String s) {
        try {
            JSONObject obj = new JSONObject(s);
            JSONArray modules = obj.optJSONArray("modules");
            return modules != null;
        } catch (JSONException e) { return false; }
    }

    private String loadNotes() {
        for (File file : new File[]{notesFile, previousFile}) {
            try {
                String s = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                if (validNotes(s)) return s;
            } catch (IOException ignored) { }
        }
        return null;
    }

    private final class AtlasBridge {
        @JavascriptInterface public String saveData(String json) {
            if (!validNotes(json)) return "{\"ok\":false,\"error\":\"Invalid JSON schema\"}";
            try {
                synchronized (MainActivity.this) {
                    if (notesFile.isFile()) {
                        String old = new String(Files.readAllBytes(notesFile.toPath()), StandardCharsets.UTF_8);
                        if (validNotes(old)) Files.copy(notesFile.toPath(), previousFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                    AtomicFile target = new AtomicFile(notesFile);
                    FileOutputStream out = target.startWrite();
                    try {
                        out.write(json.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        out.getFD().sync();
                        target.finishWrite(out);
                    } catch (Exception e) {
                        target.failWrite(out);
                        throw e;
                    }
                }
                return "{\"ok\":true}";
            } catch (Exception e) {
                JSONObject failure = new JSONObject();
                try { failure.put("ok", false); failure.put("error", e.toString()); } catch (JSONException ignored) { }
                return failure.toString();
            }
        }

        @JavascriptInterface public void requestExport(String json, String requestId) {
            if (!validNotes(json)) {
                respondExport(requestId, "{\"ok\":false,\"error\":\"Invalid JSON schema\"}");
                return;
            }
            runOnUiThread(() -> {
                if (pendingExportToken != null) {
                    respondExport(requestId, "{\"ok\":false,\"error\":\"Export in progress\"}");
                    return;
                }
                pendingExportPayload = json;
                pendingExportToken = requestId;
                String defaultName = "EmbeddedAtlas-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".json";
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                intent.putExtra(Intent.EXTRA_TITLE, defaultName);
                try { startActivityForResult(intent, REQUEST_EXPORT); }
                catch (ActivityNotFoundException ex) {
                    pendingExportPayload = null;
                    pendingExportToken = null;
                    respondExport(requestId, "{\"ok\":false,\"error\":\"No file picker\"}");
                }
            });
        }
    }

    private void respondExport(String token, String resultJson) {
        runOnUiThread(() -> {
            if (webView == null) return;
            webView.evaluateJavascript("window.__atlasExportDone(" + JSONObject.quote(token) + "," + resultJson + ");", null);
        });
    }

    @Override @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT) {
            if (uploadCallback != null) {
                Uri[] uris = resultCode == RESULT_OK && data != null && data.getData() != null
                    ? new Uri[]{data.getData()} : null;
                uploadCallback.onReceiveValue(uris);
                uploadCallback = null;
            }
        } else if (requestCode == REQUEST_EXPORT) {
            String token = pendingExportToken;
            String payload = pendingExportPayload;
            pendingExportPayload = null;
            pendingExportToken = null;
            if (token == null) return;
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                respondExport(token, "{\"ok\":false,\"cancelled\":true}");
                return;
            }
            try (OutputStream stream = getContentResolver().openOutputStream(data.getData(), "wt")) {
                if (stream == null) throw new IOException("Cannot open destination");
                stream.write(payload.getBytes(StandardCharsets.UTF_8));
                stream.flush();
                respondExport(token, "{\"ok\":true,\"path\":\"所选文件夹\"}");
            } catch (IOException ex) {
                JSONObject error = new JSONObject();
                try { error.put("ok", false); error.put("error", ex.toString()); } catch (JSONException ignored) { }
                respondExport(token, error.toString());
            }
        }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("AtlasAndroid");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
