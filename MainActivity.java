package com.mahfoud.bibliotheque;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.ColorDrawable;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;

public class MainActivity extends Activity {

    private static final int REQ_FILES = 1;
    private static final int REQ_FOLDER = 2;
    private static final int REQ_CSV = 3;
    private static final int REQ_SAVE = 4;
    private static final int REQ_READER = 5;

    // État de verrouillage (code PIN) partagé avec le lecteur PDF.
    static boolean pinOn = false;
    static boolean secureOn = false;
    static boolean forceLock = false;
    static long lockDelayMs = 60000L;
    static long readerLeftAt = 0L;

    private WebView web;
    private String pendingSaveText;
    private boolean internal = false;   // true quand on part vers nos propres écrans ou le sélecteur de fichiers
    private long leftAt = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(0xFF121212);
        getWindow().setNavigationBarColor(0xFF121212);

        web = new WebView(this);
        web.setBackgroundColor(0xFF121212);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Aucune navigation vers l'extérieur : l'application reste sur sa page locale.
                return !request.getUrl().toString().startsWith("file:///android_asset/");
            }
        });
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!internal) {
            leftAt = System.currentTimeMillis();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        boolean lock = forceLock;
        if (!internal && leftAt > 0 && System.currentTimeMillis() - leftAt >= lockDelayMs) {
            lock = true;
        }
        internal = false;
        leftAt = 0L;
        forceLock = false;
        if (pinOn && lock && web != null) {
            js("window.lockNow&&window.lockNow()");
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.handleBack?window.handleBack():false", value -> {
            if (!"true".equals(value)) {
                finish();
            }
        });
    }

    private void js(final String code) {
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    private void openReader(String uri, int page) {
        try {
            String nm = displayName(Uri.parse(uri)).toLowerCase();
            if (nm.endsWith(".doc") || nm.endsWith(".rtf")) {
                openExternal(uri);   // formats que le lecteur intégré ne sait pas lire
                return;
            }
            Class<?> target = ReaderActivity.class;
            if (nm.endsWith(".docx")) {
                target = DocxActivity.class;
            } else if (nm.endsWith(".txt") || nm.endsWith(".md")) {
                target = TextReaderActivity.class;
            }
            Intent i = new Intent(MainActivity.this, target);
            i.setData(Uri.parse(uri));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (page > 0) {
                i.putExtra("page", page);
            }
            internal = true;
            startActivityForResult(i, REQ_READER);
        } catch (Exception e) {
            internal = false;
            toast("Impossible d'ouvrir le lecteur.");
        }
    }

    static boolean isDocName(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".pdf") || n.endsWith(".docx") || n.endsWith(".doc") || n.endsWith(".txt")
                || n.endsWith(".md") || n.endsWith(".rtf");
    }

    private void openExternal(String uri) {
        try {
            Uri u = Uri.parse(uri);
            String nm = displayName(u).toLowerCase();
            String mime = "application/pdf";
            if (nm.endsWith(".docx")) {
                mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            } else if (nm.endsWith(".doc")) {
                mime = "application/msword";
            } else if (nm.endsWith(".rtf")) {
                mime = "application/rtf";
            } else if (nm.endsWith(".txt") || nm.endsWith(".md")) {
                mime = "text/plain";
            }
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("Aucune application installée ne peut ouvrir ce fichier.");
        } catch (Exception e) {
            toast("Fichier introuvable ou accès perdu. Réimportez le dossier.");
        }
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                name = c.getString(0);
            }
        } catch (Exception ignored) {
        }
        if (name == null) {
            String last = uri.getLastPathSegment();
            name = last == null ? "fichier.pdf" : last;
        }
        return name;
    }

    private void keepAccess(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
            // limite du système atteinte ou permission non persistante : on continue
        }
    }

    private void listPdfs(Uri tree, String parentId, JSONArray out, int depth) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] cols = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor c = getContentResolver().query(children, cols, null, null, null)) {
            if (c == null) {
                return;
            }
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (depth < 6) {
                        listPdfs(tree, id, out, depth + 1);
                    }
                } else if ("application/pdf".equals(mime) || (name != null && isDocName(name))) {
                    Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    JSONObject o = new JSONObject();
                    o.put("name", name);
                    o.put("uri", doc.toString());
                    out.put(o);
                }
            }
        }
    }

    private String readText(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while (in != null && (n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            if (requestCode == REQ_SAVE) {
                pendingSaveText = null;
            }
            return;
        }
        try {
            if (requestCode == REQ_FILES) {
                ArrayList<Uri> uris = new ArrayList<>();
                ClipData clip = data.getClipData();
                if (clip != null) {
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        uris.add(clip.getItemAt(i).getUri());
                    }
                } else if (data.getData() != null) {
                    uris.add(data.getData());
                }
                JSONArray arr = new JSONArray();
                for (Uri u : uris) {
                    keepAccess(u);
                    JSONObject o = new JSONObject();
                    o.put("name", displayName(u));
                    o.put("uri", u.toString());
                    arr.put(o);
                }
                js("window.onPdfsPicked(" + arr.toString() + ")");

            } else if (requestCode == REQ_FOLDER) {
                final Uri tree = data.getData();
                if (tree == null) {
                    return;
                }
                keepAccess(tree);
                toast("Lecture du dossier…");
                new Thread(() -> {
                    try {
                        JSONArray arr = new JSONArray();
                        listPdfs(tree, DocumentsContract.getTreeDocumentId(tree), arr, 0);
                        js("window.onPdfsPicked(" + arr.toString() + ")");
                    } catch (Exception e) {
                        toast("Impossible de lire ce dossier.");
                    }
                }).start();

            } else if (requestCode == REQ_CSV) {
                String text = readText(data.getData());
                js("window.onCsvPicked(" + JSONObject.quote(text) + ")");

            } else if (requestCode == REQ_READER) {
                // retour du lecteur : la page mémorise la page lue (statut) et, après 📝, ouvre une note
                String u = data.getStringExtra("uri");
                int page = data.getIntExtra("page", 0);
                int total = data.getIntExtra("total", 0);
                boolean note = data.getBooleanExtra("note", false);
                if (u != null) {
                    js("window.onReadProgress(" + JSONObject.quote(u) + "," + page + "," + total + "," + note + ")");
                }
            } else if (requestCode == REQ_SAVE) {
                Uri dest = data.getData();
                if (dest != null && pendingSaveText != null) {
                    try (OutputStream out = getContentResolver().openOutputStream(dest)) {
                        if (out != null) {
                            out.write(pendingSaveText.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                    toast("Fichier enregistré.");
                }
                pendingSaveText = null;
            }
        } catch (Exception e) {
            toast("Opération impossible : " + e.getMessage());
        }
    }

    /** Fonctions appelables depuis la page (objet « Android »). */
    private class Bridge {

        @JavascriptInterface
        public void pickFiles() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/pdf",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/msword", "application/rtf", "text/rtf",
                        "text/plain", "text/markdown"});
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                internal = true;
                startActivityForResult(i, REQ_FILES);
            });
        }

        @JavascriptInterface
        public void pickFolder() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                internal = true;
                startActivityForResult(i, REQ_FOLDER);
            });
        }

        @JavascriptInterface
        public void pickCsv() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                internal = true;
                startActivityForResult(i, REQ_CSV);
            });
        }

        /** Ouvre le PDF dans le lecteur intégré (avec mode sombre). */
        @JavascriptInterface
        public void openPdf(final String uri) {
            runOnUiThread(() -> {
                openReader(uri, 0);
            });
        }

        /** Ouvre le PDF dans le lecteur intégré, directement à la page indiquée (1 = première page). */
        @JavascriptInterface
        public void openPdfAt(final String uri, final int page) {
            runOnUiThread(() -> openReader(uri, page));
        }

        /** Ouvre le PDF avec une autre application installée sur le téléphone. */
        @JavascriptInterface
        public void openPdfExternal(final String uri) {
            runOnUiThread(() -> openExternal(uri));
        }

        @JavascriptInterface
        public void saveText(final String name, final String text) {
            runOnUiThread(() -> {
                pendingSaveText = text;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                String low = name.toLowerCase();
                i.setType(low.endsWith(".txt") ? "text/plain" : (low.endsWith(".json") ? "application/json" : "text/csv"));
                i.putExtra(Intent.EXTRA_TITLE, name);
                internal = true;
                startActivityForResult(i, REQ_SAVE);
            });
        }

        @JavascriptInterface
        public void saveData(String json) {
            writeFile("data.json", json);
        }

        @JavascriptInterface
        public String loadData() {
            return readFile("data.json");
        }

        @JavascriptInterface
        public void saveSettings(String json) {
            writeFile("settings.json", json);
        }

        @JavascriptInterface
        public String loadSettings() {
            return readFile("settings.json");
        }

        /** Copie un texte (référence, citation) dans le presse-papiers. */
        @JavascriptInterface
        public void copyText(final String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("Bibliothèque", text));
                }
            });
        }

        /** Informe le système du réglage du code PIN : verrouillage automatique et masquage de l'aperçu. */
        @JavascriptInterface
        public void setLock(final boolean on, final int seconds, final boolean secure) {
            pinOn = on;
            secureOn = on && secure;
            lockDelayMs = Math.max(0, seconds) * 1000L;
            runOnUiThread(() -> {
                if (secureOn) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                }
            });
        }
    }

    private void writeFile(String name, String text) {
        try {
            File tmp = new File(getFilesDir(), name + ".tmp");
            File dst = new File(getFilesDir(), name);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(dst)) {
                try (FileOutputStream out = new FileOutputStream(dst)) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                }
                tmp.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private String readFile(String name) {
        try {
            File f = new File(getFilesDir(), name);
            if (!f.exists()) {
                return "";
            }
            try (FileInputStream in = new FileInputStream(f)) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                }
                return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Lecteur PDF intégré : pages affichées en continu, mode sombre en option,
     * zoom, accès direct à une page, reprise à la dernière page lue.
     */
    public static class ReaderActivity extends Activity {

        private static final float[] ZOOMS = {1.0f, 1.4f, 2.0f};

        // Inversion adoucie : page blanche -> gris très foncé, texte noir -> gris clair.
        private static final ColorMatrixColorFilter DARK_FILTER = new ColorMatrixColorFilter(new float[]{
                -0.8f, 0, 0, 0, 224,
                0, -0.8f, 0, 0, 224,
                0, 0, -0.8f, 0, 224,
                0, 0, 0, 1, 0});

        private final Object lock = new Object();
        private volatile PdfRenderer renderer;
        private volatile ParcelFileDescriptor pfd;
        private volatile boolean closed = false;
        private volatile int generation = 0;
        private volatile int centerPos = 0;

        private float[] ratios;
        private int pageCount;
        private int zoomIndex = 0;
        private boolean dark = true;
        private int screenW;
        private int pageW;

        private HorizontalScrollView hsv;
        private ListView list;
        private TextView indicator;
        private TextView darkBtn;
        private PageAdapter adapter;
        private LruCache<Integer, Bitmap> cache;
        private final ExecutorService pool = Executors.newSingleThreadExecutor();
        private final HashSet<Integer> pending = new HashSet<>();
        private SharedPreferences prefs;
        private String prefKey;
        private String uriString;
        private boolean resultSet = false;

        private int dp(int v) {
            return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            getWindow().setStatusBarColor(0xFF121212);
            getWindow().setNavigationBarColor(0xFF121212);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (MainActivity.pinOn && MainActivity.secureOn) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
            MainActivity.readerLeftAt = 0L;

            final Uri uri = getIntent().getData();
            if (uri == null) {
                finish();
                return;
            }
            uriString = uri.toString();
            prefs = getSharedPreferences("lecteur", MODE_PRIVATE);
            dark = prefs.getBoolean("dark", true);
            prefKey = "page_" + uri.toString().hashCode();
            cache = new LruCache<Integer, Bitmap>(Math.max(8 * 1024 * 1024,
                    (int) (Runtime.getRuntime().maxMemory() / 5))) {
                @Override
                protected int sizeOf(Integer key, Bitmap value) {
                    return value.getByteCount();
                }
            };

            buildUi();

            new Thread(() -> {
                try {
                    ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "r");
                    PdfRenderer r = new PdfRenderer(fd);
                    int n = r.getPageCount();
                    float[] rt = new float[n];
                    for (int i = 0; i < n; i++) {
                        PdfRenderer.Page p = r.openPage(i);
                        rt[i] = p.getHeight() / (float) p.getWidth();
                        p.close();
                    }
                    synchronized (lock) {
                        if (closed) {
                            r.close();
                            fd.close();
                            return;
                        }
                        pfd = fd;
                        renderer = r;
                    }
                    ratios = rt;
                    pageCount = n;
                    runOnUiThread(this::showPages);
                } catch (SecurityException e) {
                    failAndFinish("Impossible d'ouvrir ce PDF (protégé par mot de passe ou accès perdu). "
                            + "Essayez « Ouvrir avec une autre application » dans la fiche, ou réimportez le dossier.");
                } catch (Exception e) {
                    failAndFinish("Impossible d'ouvrir ce PDF.");
                }
            }).start();
        }

        private void failAndFinish(final String msg) {
            runOnUiThread(() -> {
                Toast.makeText(ReaderActivity.this, msg, Toast.LENGTH_LONG).show();
                finish();
            });
        }

        private TextView barButton(String text, View.OnClickListener l) {
            TextView b = new TextView(this);
            b.setText(text);
            b.setTextSize(24);
            b.setTextColor(0xFFE8E4DC);
            b.setGravity(Gravity.CENTER);
            b.setMinWidth(dp(52));
            b.setMinHeight(dp(48));
            b.setOnClickListener(l);
            return b;
        }

        private void buildUi() {
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);

            LinearLayout bar = new LinearLayout(this);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setBackgroundColor(0xFF1E1E1E);
            bar.setPadding(dp(8), dp(2), dp(8), dp(2));

            indicator = new TextView(this);
            indicator.setText("Ouverture…");
            indicator.setTextColor(0xFFF4A259);
            indicator.setTextSize(18);
            indicator.setPadding(dp(8), dp(10), dp(8), dp(10));
            indicator.setOnClickListener(v -> askPage());
            bar.addView(indicator, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            bar.addView(barButton("📝", v -> noteAndExit()));
            bar.addView(barButton("−", v -> zoom(-1)));
            bar.addView(barButton("+", v -> zoom(1)));
            darkBtn = barButton("◐", v -> toggleDark());
            bar.addView(darkBtn);

            root.addView(bar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            hsv = new HorizontalScrollView(this);
            hsv.setFillViewport(true);
            hsv.setHorizontalScrollBarEnabled(false);
            list = new ListView(this);
            hsv.addView(list, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(hsv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            setContentView(root);
            screenW = getResources().getDisplayMetrics().widthPixels;
            applyTheme();
        }

        private void applyTheme() {
            int bg = dark ? 0xFF121212 : 0xFFBDBDBD;
            hsv.setBackgroundColor(bg);
            list.setBackgroundColor(bg);
            list.setDivider(new ColorDrawable(bg));
            list.setDividerHeight(dp(dark ? 4 : 8));
        }

        private void recalcWidth() {
            pageW = (int) (screenW * ZOOMS[zoomIndex]);
            list.setLayoutParams(new FrameLayout.LayoutParams(pageW, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        private void showPages() {
            if (pageCount == 0) {
                Toast.makeText(this, "Ce PDF ne contient aucune page.", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            adapter = new PageAdapter();
            recalcWidth();
            list.setAdapter(adapter);
            list.setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(AbsListView view, int scrollState) {
                }

                @Override
                public void onScroll(AbsListView view, int first, int visible, int total) {
                    centerPos = first + visible / 2;
                    updateIndicator(first);
                }
            });
            int saved = prefs.getInt(prefKey, 0);
            int wanted = getIntent().getIntExtra("page", 0);
            if (wanted > 0) {
                saved = wanted - 1;
            }
            if (saved < 0 || saved >= pageCount) {
                saved = 0;
            }
            centerPos = saved;
            list.setSelection(saved);
            updateIndicator(saved);
        }

        private void updateIndicator(int first) {
            indicator.setText((first + 1) + " / " + pageCount);
        }

        private void resetRendering() {
            generation++;
            cache.evictAll();
            pending.clear();
            recalcWidth();
            adapter.notifyDataSetChanged();
        }

        private void zoom(int delta) {
            if (adapter == null) {
                return;
            }
            int ni = Math.max(0, Math.min(ZOOMS.length - 1, zoomIndex + delta));
            if (ni == zoomIndex) {
                return;
            }
            zoomIndex = ni;
            int first = list.getFirstVisiblePosition();
            resetRendering();
            list.setSelection(first);
        }

        /** Revient à la bibliothèque avec une nouvelle note liée à ce livre et à la page affichée. */
        private void noteAndExit() {
            if (adapter == null) {
                return;
            }
            int page = list.getFirstVisiblePosition() + 1;
            prefs.edit().putInt(prefKey, page - 1).apply();
            Intent r = new Intent();
            r.putExtra("uri", uriString);
            r.putExtra("page", page);
            r.putExtra("total", pageCount);
            r.putExtra("note", true);
            setResult(RESULT_OK, r);
            resultSet = true;
            finish();
        }

        /** À la fermeture, transmet la page lue à la bibliothèque (statut de lecture). */
        @Override
        public void finish() {
            if (!resultSet && adapter != null && uriString != null) {
                Intent r = new Intent();
                r.putExtra("uri", uriString);
                r.putExtra("page", list.getFirstVisiblePosition() + 1);
                r.putExtra("total", pageCount);
                r.putExtra("note", false);
                setResult(RESULT_OK, r);
                resultSet = true;
            }
            super.finish();
        }

        @Override
        protected void onStop() {
            super.onStop();
            if (!isFinishing()) {
                MainActivity.readerLeftAt = System.currentTimeMillis();
            }
        }

        @Override
        protected void onStart() {
            super.onStart();
            long left = MainActivity.readerLeftAt;
            MainActivity.readerLeftAt = 0L;
            if (MainActivity.pinOn && left > 0
                    && System.currentTimeMillis() - left >= MainActivity.lockDelayMs) {
                // retour après le délai de verrouillage : on ferme le lecteur, la bibliothèque demandera le code
                MainActivity.forceLock = true;
                finish();
            }
        }

        private void toggleDark() {
            dark = !dark;
            prefs.edit().putBoolean("dark", dark).apply();
            applyTheme();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }

        private void askPage() {
            if (adapter == null) {
                return;
            }
            final EditText et = new EditText(this);
            et.setInputType(InputType.TYPE_CLASS_NUMBER);
            et.setHint("1 – " + pageCount);
            new AlertDialog.Builder(this)
                    .setTitle("Aller à la page")
                    .setView(et)
                    .setPositiveButton("OK", (d, w) -> {
                        try {
                            int n = Integer.parseInt(et.getText().toString().trim());
                            n = Math.max(1, Math.min(pageCount, n));
                            list.setSelection(n - 1);
                        } catch (Exception ignored) {
                        }
                    })
                    .setNegativeButton("Annuler", null)
                    .show();
        }

        @Override
        public void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);
            screenW = getResources().getDisplayMetrics().widthPixels;
            if (adapter != null) {
                int first = list.getFirstVisiblePosition();
                resetRendering();
                list.setSelection(first);
            }
        }

        @Override
        protected void onPause() {
            super.onPause();
            if (adapter != null && prefs != null) {
                prefs.edit().putInt(prefKey, list.getFirstVisiblePosition()).apply();
            }
        }

        @Override
        protected void onDestroy() {
            super.onDestroy();
            closed = true;
            pool.shutdownNow();
            synchronized (lock) {
                try {
                    if (renderer != null) {
                        renderer.close();
                    }
                    if (pfd != null) {
                        pfd.close();
                    }
                } catch (Exception ignored) {
                }
            }
        }

        private Bitmap renderPage(int position, int w, int h) {
            try {
                synchronized (lock) {
                    if (closed || renderer == null) {
                        return null;
                    }
                    PdfRenderer.Page page = renderer.openPage(position);
                    try {
                        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        bmp.eraseColor(Color.WHITE);
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        return bmp;
                    } finally {
                        page.close();
                    }
                }
            } catch (Throwable t) {
                return null;
            }
        }

        private void requestRender(final int position, final int w, final int h) {
            if (!pending.add(position)) {
                return;
            }
            final int g = generation;
            pool.execute(() -> {
                Bitmap bmp = null;
                if (!closed && g == generation && Math.abs(position - centerPos) <= 8) {
                    bmp = renderPage(position, w, h);
                }
                final Bitmap result = bmp;
                runOnUiThread(() -> {
                    pending.remove(position);
                    if (closed) {
                        return;
                    }
                    if (result != null && g == generation) {
                        cache.put(position, result);
                        if (adapter != null) {
                            adapter.notifyDataSetChanged();
                        }
                    }
                });
            });
        }

        private class PageAdapter extends BaseAdapter {

            @Override
            public int getCount() {
                return pageCount;
            }

            @Override
            public Object getItem(int position) {
                return null;
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                ImageView iv;
                if (convertView instanceof ImageView) {
                    iv = (ImageView) convertView;
                } else {
                    iv = new ImageView(ReaderActivity.this);
                    iv.setScaleType(ImageView.ScaleType.FIT_XY);
                }
                int w = pageW;
                int h = Math.max(1, (int) (w * ratios[position]));
                iv.setLayoutParams(new AbsListView.LayoutParams(w, h));
                iv.setColorFilter(dark ? DARK_FILTER : null);
                Bitmap b = cache.get(position);
                if (b != null) {
                    iv.setImageBitmap(b);
                } else {
                    iv.setImageDrawable(null);
                    requestRender(position, w, h);
                }
                return iv;
            }
        }
    }

    /**
     * Lecteur de texte intégré : fichiers .txt / .md et documents Word (.docx).
     * Le texte est affiché paragraphe par paragraphe (liste), thème sombre, taille réglable.
     * Les « pages » sont approximatives (environ 1800 caractères) pour alimenter la progression et les notes.
     */
    public static class TextReaderActivity extends Activity {

        private static final int[] SIZES = {14, 16, 18, 20, 24, 28};
        private static final int CHARS_PER_PAGE = 1800;

        private final ArrayList<String> paras = new ArrayList<>();
        private final ArrayList<Boolean> heads = new ArrayList<>();
        private long[] cum = new long[0];
        private int pageCount = 1;
        private int sizeIndex = 2;
        private boolean dark = true;
        private boolean ready = false;
        private boolean resultSet = false;

        private ListView list;
        private TextView indicator;
        private BaseAdapter adapter;
        private SharedPreferences prefs;
        private String prefKey;
        private String uriString;

        private int dp(int v) {
            return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            getWindow().setStatusBarColor(0xFF121212);
            getWindow().setNavigationBarColor(0xFF121212);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (MainActivity.pinOn && MainActivity.secureOn) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
            MainActivity.readerLeftAt = 0L;

            final Uri uri = getIntent().getData();
            if (uri == null) {
                finish();
                return;
            }
            uriString = uri.toString();
            prefs = getSharedPreferences("lecteur", MODE_PRIVATE);
            dark = prefs.getBoolean("dark", true);
            sizeIndex = Math.max(0, Math.min(SIZES.length - 1, prefs.getInt("tsize", 2)));
            prefKey = "page_" + uriString.hashCode();
            buildUi();

            new Thread(() -> {
                try {
                    byte[] bytes = readAll(uri);
                    boolean isZip = bytes.length > 4 && bytes[0] == 'P' && bytes[1] == 'K';
                    if (isZip) {
                        if (!parseDocx(bytes)) {
                            failAndFinish("Ce document Word ne peut pas être lu (format non reconnu).");
                            return;
                        }
                    } else if (bytes.length > 4 && (bytes[0] & 0xFF) == 0xD0 && (bytes[1] & 0xFF) == 0xCF) {
                        failAndFinish("Ancien format Word (.doc) : utilisez « Ouvrir avec une autre application ».");
                        return;
                    } else {
                        parseText(decode(bytes));
                    }
                    computeIndex();
                    runOnUiThread(this::showText);
                } catch (Throwable t) {
                    failAndFinish("Impossible d'ouvrir ce fichier.");
                }
            }).start();
        }

        private byte[] readAll(Uri uri) throws Exception {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[16384];
                int n;
                while (in != null && (n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                }
                return buf.toByteArray();
            }
        }

        private static String decode(byte[] b) {
            if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
                return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
            }
            if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
                return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
            }
            int off = (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) ? 3 : 0;
            try {
                CharsetDecoder d = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
                return d.decode(ByteBuffer.wrap(b, off, b.length - off)).toString();
            } catch (Exception e) {
                try {
                    // anciens fichiers : windows-1256 couvre l'arabe et les lettres accentuées françaises
                    return new String(b, off, b.length - off, "windows-1256");
                } catch (Exception e2) {
                    return new String(b, off, b.length - off, StandardCharsets.ISO_8859_1);
                }
            }
        }

        private void addPara(String s, boolean head) {
            String t = s.replace("\r", "");
            while (t.endsWith(" ") || t.endsWith("\t")) {
                t = t.substring(0, t.length() - 1);
            }
            if (t.isEmpty() && (paras.isEmpty() || paras.get(paras.size() - 1).isEmpty())) {
                return;   // pas de paragraphes vides consécutifs
            }
            paras.add(t);
            heads.add(head);
        }

        private void parseText(String text) {
            String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
            for (String line : lines) {
                addPara(line, false);
            }
        }

        private boolean parseDocx(byte[] bytes) throws Exception {
            ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes));
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if ("word/document.xml".equals(e.getName())) {
                    parseDocXml(zin);
                    return true;
                }
            }
            return false;
        }

        private void parseDocXml(InputStream in) throws Exception {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, null);
            StringBuilder cur = new StringBuilder();
            boolean head = false;
            boolean inT = false;
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String n = p.getName();
                    if ("w:p".equals(n)) {
                        cur.setLength(0);
                        head = false;
                    } else if ("w:t".equals(n)) {
                        inT = true;
                    } else if ("w:tab".equals(n)) {
                        cur.append('\t');
                    } else if ("w:br".equals(n) || "w:cr".equals(n)) {
                        cur.append('\n');
                    } else if ("w:pStyle".equals(n)) {
                        String v = p.getAttributeValue(null, "w:val");
                        if (v != null) {
                            String l = v.toLowerCase();
                            if (l.startsWith("heading") || l.startsWith("titre") || l.equals("title")) {
                                head = true;
                            }
                        }
                    }
                } else if (ev == XmlPullParser.TEXT) {
                    if (inT) {
                        cur.append(p.getText());
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    String n = p.getName();
                    if ("w:t".equals(n)) {
                        inT = false;
                    } else if ("w:p".equals(n)) {
                        addPara(cur.toString(), head);
                        cur.setLength(0);
                    }
                }
                ev = p.next();
            }
        }

        private void computeIndex() {
            cum = new long[paras.size() + 1];
            for (int i = 0; i < paras.size(); i++) {
                cum[i + 1] = cum[i] + paras.get(i).length() + 1;
            }
            pageCount = (int) Math.max(1, (cum[paras.size()] + CHARS_PER_PAGE - 1) / CHARS_PER_PAGE);
        }

        private int pageOfPara(int i) {
            if (i < 0 || i >= cum.length) {
                return 1;
            }
            return (int) Math.min(pageCount, cum[i] / CHARS_PER_PAGE + 1);
        }

        private int paraOfPage(int page) {
            long target = (long) (Math.max(1, page) - 1) * CHARS_PER_PAGE;
            int lo = 0;
            int hi = Math.max(0, paras.size() - 1);
            while (lo < hi) {
                int mid = (lo + hi + 1) / 2;
                if (cum[mid] <= target) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return lo;
        }

        private void failAndFinish(final String msg) {
            runOnUiThread(() -> {
                Toast.makeText(TextReaderActivity.this, msg, Toast.LENGTH_LONG).show();
                finish();
            });
        }

        private TextView barButton(String text, View.OnClickListener l) {
            TextView b = new TextView(this);
            b.setText(text);
            b.setTextSize(24);
            b.setTextColor(0xFFE8E4DC);
            b.setGravity(Gravity.CENTER);
            b.setMinWidth(dp(52));
            b.setMinHeight(dp(48));
            b.setOnClickListener(l);
            return b;
        }

        private void buildUi() {
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);

            LinearLayout bar = new LinearLayout(this);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setBackgroundColor(0xFF1E1E1E);
            bar.setPadding(dp(8), dp(2), dp(8), dp(2));

            indicator = new TextView(this);
            indicator.setText("Ouverture…");
            indicator.setTextColor(0xFFF4A259);
            indicator.setTextSize(18);
            indicator.setPadding(dp(8), dp(10), dp(8), dp(10));
            indicator.setOnClickListener(v -> askPage());
            bar.addView(indicator, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            bar.addView(barButton("📝", v -> noteAndExit()));
            bar.addView(barButton("A−", v -> resize(-1)));
            bar.addView(barButton("A+", v -> resize(1)));
            bar.addView(barButton("◐", v -> toggleDark()));
            root.addView(bar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            list = new ListView(this);
            list.setDivider(null);
            list.setDividerHeight(0);
            root.addView(list, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            setContentView(root);
            applyTheme();
        }

        private void applyTheme() {
            list.setBackgroundColor(dark ? 0xFF121212 : 0xFFF2EFE6);
        }

        private void showText() {
            if (paras.isEmpty()) {
                Toast.makeText(this, "Ce document ne contient aucun texte.", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            adapter = new BaseAdapter() {
                @Override
                public int getCount() {
                    return paras.size();
                }

                @Override
                public Object getItem(int position) {
                    return null;
                }

                @Override
                public long getItemId(int position) {
                    return position;
                }

                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    TextView tv = convertView instanceof TextView ? (TextView) convertView
                            : new TextView(TextReaderActivity.this);
                    String s = paras.get(position);
                    boolean head = heads.get(position);
                    tv.setText(s.isEmpty() ? " " : s);
                    tv.setTextColor(dark ? 0xFFE0DCD2 : 0xFF111111);
                    tv.setTextSize(head ? SIZES[sizeIndex] * 1.2f : SIZES[sizeIndex]);
                    tv.setTypeface(null, head ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                    tv.setLineSpacing(0, 1.25f);
                    tv.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
                    tv.setPadding(dp(16), dp(s.isEmpty() ? 2 : 6), dp(16), dp(s.isEmpty() ? 2 : 6));
                    tv.setLayoutParams(new AbsListView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    return tv;
                }
            };
            list.setAdapter(adapter);
            list.setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(AbsListView view, int scrollState) {
                }

                @Override
                public void onScroll(AbsListView view, int first, int visible, int total) {
                    updateIndicator(first);
                }
            });
            int pos = prefs.getInt(prefKey, 0);
            int wanted = getIntent().getIntExtra("page", 0);
            if (wanted > 0) {
                pos = paraOfPage(wanted);
            }
            if (pos < 0 || pos >= paras.size()) {
                pos = 0;
            }
            ready = true;
            list.setSelection(pos);
            updateIndicator(pos);
        }

        private void updateIndicator(int first) {
            indicator.setText(pageOfPara(first) + " / " + pageCount);
        }

        private void resize(int delta) {
            if (!ready) {
                return;
            }
            int ni = Math.max(0, Math.min(SIZES.length - 1, sizeIndex + delta));
            if (ni == sizeIndex) {
                return;
            }
            sizeIndex = ni;
            prefs.edit().putInt("tsize", sizeIndex).apply();
            int first = list.getFirstVisiblePosition();
            list.setAdapter(adapter);
            list.setSelection(first);
        }

        private void toggleDark() {
            dark = !dark;
            prefs.edit().putBoolean("dark", dark).apply();
            applyTheme();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }

        private void askPage() {
            if (!ready) {
                return;
            }
            final EditText et = new EditText(this);
            et.setInputType(InputType.TYPE_CLASS_NUMBER);
            et.setHint("1 – " + pageCount);
            new AlertDialog.Builder(this)
                    .setTitle("Aller à la page (approximative)")
                    .setView(et)
                    .setPositiveButton("OK", (d, w) -> {
                        try {
                            int n = Integer.parseInt(et.getText().toString().trim());
                            n = Math.max(1, Math.min(pageCount, n));
                            list.setSelection(paraOfPage(n));
                        } catch (Exception ignored) {
                        }
                    })
                    .setNegativeButton("Annuler", null)
                    .show();
        }

        private void noteAndExit() {
            if (!ready) {
                return;
            }
            int first = list.getFirstVisiblePosition();
            prefs.edit().putInt(prefKey, first).apply();
            Intent r = new Intent();
            r.putExtra("uri", uriString);
            r.putExtra("page", pageOfPara(first));
            r.putExtra("total", pageCount);
            r.putExtra("note", true);
            setResult(RESULT_OK, r);
            resultSet = true;
            finish();
        }

        @Override
        public void finish() {
            if (!resultSet && ready && uriString != null) {
                Intent r = new Intent();
                r.putExtra("uri", uriString);
                r.putExtra("page", pageOfPara(list.getFirstVisiblePosition()));
                r.putExtra("total", pageCount);
                r.putExtra("note", false);
                setResult(RESULT_OK, r);
                resultSet = true;
            }
            super.finish();
        }

        @Override
        protected void onPause() {
            super.onPause();
            if (ready && prefs != null) {
                prefs.edit().putInt(prefKey, list.getFirstVisiblePosition()).apply();
            }
        }

        @Override
        protected void onStop() {
            super.onStop();
            if (!isFinishing()) {
                MainActivity.readerLeftAt = System.currentTimeMillis();
            }
        }

        @Override
        protected void onStart() {
            super.onStart();
            long left = MainActivity.readerLeftAt;
            MainActivity.readerLeftAt = 0L;
            if (MainActivity.pinOn && left > 0
                    && System.currentTimeMillis() - left >= MainActivity.lockDelayMs) {
                MainActivity.forceLock = true;
                finish();
            }
        }
    }

    /**
     * Lecteur Word (.docx) fidèle : une page locale (viewer.html + docx-preview) affiche le document
     * avec ses tableaux, images et styles, sans Internet. En cas d'échec, le lecteur de texte prend le relais.
     */
    public static class DocxActivity extends Activity {

        private static final String HOST = "appassets.local";

        private WebView wv;
        private TextView indicator;
        private Uri docUri;
        private String uriString;
        private SharedPreferences prefs;
        private String prefKey;
        private boolean dark = true;
        private volatile int page = 1;
        private volatile int total = 0;
        private boolean ready = false;
        private boolean resultSet = false;
        private boolean child = false;

        private int dp(int v) {
            return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            getWindow().setStatusBarColor(0xFF121212);
            getWindow().setNavigationBarColor(0xFF121212);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (MainActivity.pinOn && MainActivity.secureOn) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
            MainActivity.readerLeftAt = 0L;

            docUri = getIntent().getData();
            if (docUri == null) {
                finish();
                return;
            }
            uriString = docUri.toString();
            prefs = getSharedPreferences("lecteur", MODE_PRIVATE);
            dark = prefs.getBoolean("dark", true);
            prefKey = "page_" + uriString.hashCode();

            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            LinearLayout bar = new LinearLayout(this);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setBackgroundColor(0xFF1E1E1E);
            bar.setPadding(dp(8), dp(2), dp(8), dp(2));

            indicator = new TextView(this);
            indicator.setText("Ouverture…");
            indicator.setTextColor(0xFFF4A259);
            indicator.setTextSize(18);
            indicator.setPadding(dp(8), dp(10), dp(8), dp(10));
            indicator.setOnClickListener(v -> askPage());
            bar.addView(indicator, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            bar.addView(barButton("📝", v -> noteAndExit()));
            bar.addView(barButton("−", v -> runJs("zoomBy(-1)")));
            bar.addView(barButton("+", v -> runJs("zoomBy(1)")));
            bar.addView(barButton("◐", v -> toggleDark()));
            root.addView(bar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            wv = new WebView(this);
            wv.setBackgroundColor(0xFF121212);
            root.addView(wv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            setContentView(root);

            WebSettings s = wv.getSettings();
            s.setJavaScriptEnabled(true);
            s.setAllowFileAccess(false);
            s.setAllowContentAccess(false);
            wv.addJavascriptInterface(new ViewerBridge(), "Viewer");
            wv.setWebViewClient(new WebViewClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    return serve(request);
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                    if (request.isForMainFrame()) {
                        fallbackToText();
                    }
                }

                @Override
                public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse resp) {
                    if (request.isForMainFrame()) {
                        fallbackToText();
                    }
                }
            });
            wv.loadUrl("https://" + HOST + "/viewer.html");
        }

        private WebResourceResponse notFound() {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not found",
                    new java.util.HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
        }

        private WebResourceResponse serve(WebResourceRequest request) {
            try {
                Uri u = request.getUrl();
                if (!HOST.equals(u.getHost())) {
                    return notFound();   // aucun accès réseau
                }
                String path = u.getPath();
                if ("/doc".equals(path)) {
                    InputStream in = getContentResolver().openInputStream(docUri);
                    if (in == null) {
                        return notFound();
                    }
                    return new WebResourceResponse("application/octet-stream", null, in);
                }
                if ("/viewer.html".equals(path)) {
                    return new WebResourceResponse("text/html", "utf-8", getAssets().open("viewer.html"));
                }
                if ("/jszip.min.js".equals(path)) {
                    return new WebResourceResponse("application/javascript", "utf-8", getAssets().open("jszip.min.js"));
                }
                if ("/docx-preview.min.js".equals(path)) {
                    return new WebResourceResponse("application/javascript", "utf-8", getAssets().open("docx-preview.min.js"));
                }
            } catch (Exception ignored) {
            }
            return notFound();
        }

        private class ViewerBridge {
            @JavascriptInterface
            public void ready() {
                runOnUiThread(() -> {
                    ready = true;
                    int start = prefs.getInt(prefKey, 1);
                    int wanted = getIntent().getIntExtra("page", 0);
                    if (wanted > 0) {
                        start = wanted;
                    }
                    runJs("setDark(" + dark + ");goPage(" + Math.max(1, start) + ")");
                });
            }

            @JavascriptInterface
            public void progress(final int p, final int t) {
                page = p;
                total = t;
                runOnUiThread(() -> indicator.setText(p + " / " + t));
            }

            @JavascriptInterface
            public void failed(final String msg) {
                runOnUiThread(() -> fallbackToText());
            }
        }

        private void fallbackToText() {
            if (child || isFinishing()) {
                return;
            }
            child = true;
            try {
                Intent i = new Intent(this, TextReaderActivity.class);
                i.setData(docUri);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                int wanted = getIntent().getIntExtra("page", 0);
                if (wanted > 0) {
                    i.putExtra("page", wanted);
                }
                Toast.makeText(this, "Affichage simplifié (texte seul).", Toast.LENGTH_LONG).show();
                startActivityForResult(i, 1);
            } catch (Exception e) {
                finish();
            }
        }

        @Override
        protected void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            if (data != null) {
                setResult(resultCode, data);
                resultSet = true;
            }
            finish();
        }

        private void runJs(String code) {
            if (wv != null) {
                wv.evaluateJavascript(code, null);
            }
        }

        private TextView barButton(String text, View.OnClickListener l) {
            TextView b = new TextView(this);
            b.setText(text);
            b.setTextSize(24);
            b.setTextColor(0xFFE8E4DC);
            b.setGravity(Gravity.CENTER);
            b.setMinWidth(dp(52));
            b.setMinHeight(dp(48));
            b.setOnClickListener(l);
            return b;
        }

        private void toggleDark() {
            dark = !dark;
            prefs.edit().putBoolean("dark", dark).apply();
            runJs("setDark(" + dark + ")");
        }

        private void askPage() {
            if (!ready) {
                return;
            }
            final EditText et = new EditText(this);
            et.setInputType(InputType.TYPE_CLASS_NUMBER);
            et.setHint("1 – " + total);
            new AlertDialog.Builder(this)
                    .setTitle("Aller à la page")
                    .setView(et)
                    .setPositiveButton("OK", (d, w) -> {
                        try {
                            int n = Integer.parseInt(et.getText().toString().trim());
                            runJs("goPage(" + n + ")");
                        } catch (Exception ignored) {
                        }
                    })
                    .setNegativeButton("Annuler", null)
                    .show();
        }

        private void noteAndExit() {
            if (!ready) {
                return;
            }
            prefs.edit().putInt(prefKey, page).apply();
            Intent r = new Intent();
            r.putExtra("uri", uriString);
            r.putExtra("page", page);
            r.putExtra("total", total);
            r.putExtra("note", true);
            setResult(RESULT_OK, r);
            resultSet = true;
            finish();
        }

        @Override
        public void finish() {
            if (!resultSet && ready && uriString != null) {
                Intent r = new Intent();
                r.putExtra("uri", uriString);
                r.putExtra("page", page);
                r.putExtra("total", total);
                r.putExtra("note", false);
                setResult(RESULT_OK, r);
                resultSet = true;
            }
            super.finish();
        }

        @Override
        protected void onPause() {
            super.onPause();
            if (ready && prefs != null) {
                prefs.edit().putInt(prefKey, page).apply();
            }
        }

        @Override
        protected void onStop() {
            super.onStop();
            if (!isFinishing() && !child) {
                MainActivity.readerLeftAt = System.currentTimeMillis();
            }
        }

        @Override
        protected void onStart() {
            super.onStart();
            long left = MainActivity.readerLeftAt;
            MainActivity.readerLeftAt = 0L;
            if (MainActivity.pinOn && left > 0 && !child
                    && System.currentTimeMillis() - left >= MainActivity.lockDelayMs) {
                MainActivity.forceLock = true;
                finish();
            }
        }

        @Override
        protected void onDestroy() {
            super.onDestroy();
            if (wv != null) {
                wv.destroy();
            }
        }
    }
}
