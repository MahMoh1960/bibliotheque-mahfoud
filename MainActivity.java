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

        @JavascriptInterface
        public void openPdf(final String uri) {
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
}
