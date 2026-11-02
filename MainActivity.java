package com.mahfoud.bibliotheque;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
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

public class MainActivity extends Activity {

    private static final int REQ_FILES = 1;
    private static final int REQ_FOLDER = 2;
    private static final int REQ_CSV = 3;
    private static final int REQ_SAVE = 4;

    private WebView web;
    private String pendingSaveText;

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
                } else if ("application/pdf".equals(mime)
                        || (name != null && name.toLowerCase().endsWith(".pdf"))) {
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
                i.setType("application/pdf");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(i, REQ_FILES);
            });
        }

        @JavascriptInterface
        public void pickFolder() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(i, REQ_FOLDER);
            });
        }

        @JavascriptInterface
        public void pickCsv() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_CSV);
            });
        }

        /** Ouvre le PDF dans le lecteur intégré (avec mode sombre). */
        @JavascriptInterface
        public void openPdf(final String uri) {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(MainActivity.this, ReaderActivity.class);
                    i.setData(Uri.parse(uri));
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(i);
                } catch (Exception e) {
                    toast("Impossible d'ouvrir le lecteur.");
                }
            });
        }

        /** Ouvre le PDF avec une autre application installée sur le téléphone. */
        @JavascriptInterface
        public void openPdfExternal(final String uri) {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(Uri.parse(uri), "application/pdf");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(i);
                } catch (ActivityNotFoundException e) {
                    toast("Aucune application de lecture PDF n'est installée.");
                } catch (Exception e) {
                    toast("Fichier introuvable ou accès perdu. Réimportez le dossier des PDF.");
                }
            });
        }

        @JavascriptInterface
        public void saveText(final String name, final String text) {
            runOnUiThread(() -> {
                pendingSaveText = text;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("text/csv");
                i.putExtra(Intent.EXTRA_TITLE, name);
                startActivityForResult(i, REQ_SAVE);
            });
        }

        @JavascriptInterface
        public void saveData(String json) {
            try {
                File tmp = new File(getFilesDir(), "data.json.tmp");
                File dst = new File(getFilesDir(), "data.json");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
                if (!tmp.renameTo(dst)) {
                    try (FileOutputStream out = new FileOutputStream(dst)) {
                        out.write(json.getBytes(StandardCharsets.UTF_8));
                    }
                    tmp.delete();
                }
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public String loadData() {
            try {
                File f = new File(getFilesDir(), "data.json");
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

        private int dp(int v) {
            return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            getWindow().setStatusBarColor(0xFF121212);
            getWindow().setNavigationBarColor(0xFF121212);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

            final Uri uri = getIntent().getData();
            if (uri == null) {
                finish();
                return;
            }
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
}
