package br.com.germanysolar.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Germany Solar: a tela do app (assets/index.html) roda dentro de uma WebView,
 * servida em https://appassets.androidplatform.net para funcionar com o Firebase.
 * Os dados locais ficam em um arquivo privado do app.
 */
public class MainActivity extends Activity {

    private static final String INICIO = "https://appassets.androidplatform.net/assets/index.html";
    private static final String HOST = "appassets.androidplatform.net";
    private static final int ABRIR_ARQUIVO = 1;
    private static final int SALVAR_ARQUIVO = 2;

    private WebView web;
    private ValueCallback<Uri[]> escolhaPendente;
    private String textoPendente;
    private File fotoArquivo;
    private Uri fotoUri;
    private final Object travaDados = new Object();

    @Override
    protected void onCreate(Bundle estado) {
        super.onCreate(estado);
        guardarCopiaAnterior();

        web = new WebView(this);
        web.setBackgroundColor(0xFF17305A);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);

        final WebViewAssetLoader carregador = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return carregador.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (!r.isForMainFrame() || HOST.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException e) {
                    // nenhum app para abrir o link
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (escolhaPendente != null) escolhaPendente.onReceiveValue(null);
                escolhaPendente = cb;
                boolean imagem = false;
                for (String a : p.getAcceptTypes()) if (a != null && a.startsWith("image")) imagem = true;
                Intent i;
                fotoArquivo = null;
                fotoUri = null;
                if (imagem) {
                    // Foto do recibo: câmera ou galeria
                    Intent galeria = new Intent(Intent.ACTION_GET_CONTENT);
                    galeria.addCategory(Intent.CATEGORY_OPENABLE);
                    galeria.setType("image/*");
                    i = Intent.createChooser(galeria, "Foto do recibo");
                    try {
                        File pasta = new File(getCacheDir(), "fotos");
                        pasta.mkdirs();
                        fotoArquivo = new File(pasta, "recibo-" + System.currentTimeMillis() + ".jpg");
                        fotoUri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".arquivos", fotoArquivo);
                        Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        camera.putExtra(MediaStore.EXTRA_OUTPUT, fotoUri);
                        camera.setClipData(ClipData.newRawUri("", fotoUri));
                        int flags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION;
                        camera.addFlags(flags);
                        for (ResolveInfo r : getPackageManager().queryIntentActivities(camera, PackageManager.MATCH_DEFAULT_ONLY)) {
                            grantUriPermission(r.activityInfo.packageName, fotoUri, flags);
                        }
                        i.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
                    } catch (Exception e) {
                        fotoArquivo = null;
                        fotoUri = null;
                    }
                } else {
                    i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                }
                try {
                    startActivityForResult(i, ABRIR_ARQUIVO);
                    return true;
                } catch (ActivityNotFoundException e) {
                    escolhaPendente = null;
                    return false;
                }
            }
        });

        web.addJavascriptInterface(new Ponte(), "AndroidApp");

        if (estado != null) web.restoreState(estado);
        else web.loadUrl(INICIO);
    }

    @Override
    protected void onSaveInstanceState(Bundle saida) {
        super.onSaveInstanceState(saida);
        web.saveState(saida);
    }

    /** Voltar: fecha a janela aberta, depois volta ao Início, depois sai do app. */
    @Override
    public void onBackPressed() {
        web.evaluateJavascript(
                "(function(){var d=document.getElementById('dlg');"
                        + "if(d&&d.open){d.close();return 'f';}"
                        + "if(location.hash&&location.hash!=='#inicio'){location.hash='inicio';return 'n';}"
                        + "return 's';})()",
                v -> {
                    if (v == null || "\"s\"".equals(v)) finish();
                });
    }

    @Override
    protected void onActivityResult(int pedido, int resultado, Intent dados) {
        super.onActivityResult(pedido, resultado, dados);
        Uri uri = (resultado == RESULT_OK && dados != null) ? dados.getData() : null;

        if (pedido == ABRIR_ARQUIVO) {
            if (uri == null && resultado == RESULT_OK && fotoArquivo != null && fotoArquivo.length() > 0) {
                uri = fotoUri;
            }
            fotoArquivo = null;
            fotoUri = null;
            if (escolhaPendente != null) {
                escolhaPendente.onReceiveValue(uri != null ? new Uri[]{uri} : null);
                escolhaPendente = null;
            }
        } else if (pedido == SALVAR_ARQUIVO) {
            if (uri != null && textoPendente != null) {
                try (OutputStream o = getContentResolver().openOutputStream(uri, "wt")) {
                    o.write(textoPendente.getBytes(StandardCharsets.UTF_8));
                    Toast.makeText(this, "Backup salvo.", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Não foi possível salvar o backup.", Toast.LENGTH_LONG).show();
                }
            }
            textoPendente = null;
        }
    }

    private File arquivoDados() {
        return new File(getFilesDir(), "dados-equipe.json");
    }

    private void guardarCopiaAnterior() {
        File f = arquivoDados();
        if (f.length() > 2) {
            try (InputStream in = new FileInputStream(f);
                 OutputStream out = new FileOutputStream(new File(getFilesDir(), "dados-equipe.copia-anterior.json"))) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            } catch (Exception e) {
                // a cópia é só uma precaução
            }
        }
    }

    /** Funções que a página chama como window.AndroidApp. */
    private class Ponte {

        @JavascriptInterface
        public String lerDados() {
            synchronized (travaDados) {
                File f = arquivoDados();
                if (!f.exists()) return "{}";
                try (InputStream in = new FileInputStream(f)) {
                    byte[] b = new byte[(int) f.length()];
                    int lidos = 0;
                    while (lidos < b.length) {
                        int n = in.read(b, lidos, b.length - lidos);
                        if (n < 0) break;
                        lidos += n;
                    }
                    return new String(b, 0, lidos, StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return "{}";
                }
            }
        }

        @JavascriptInterface
        public boolean gravarDados(String json) {
            synchronized (travaDados) {
                File tmp = new File(getFilesDir(), "dados-equipe.tmp");
                try (FileOutputStream o = new FileOutputStream(tmp)) {
                    o.write(json.getBytes(StandardCharsets.UTF_8));
                    o.getFD().sync();
                } catch (Exception e) {
                    return false;
                }
                return tmp.renameTo(arquivoDados());
            }
        }

        @JavascriptInterface
        public void salvarArquivo(final String nome, final String texto) {
            runOnUiThread(() -> {
                textoPendente = texto;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_TITLE, nome);
                try {
                    startActivityForResult(i, SALVAR_ARQUIVO);
                } catch (ActivityNotFoundException e) {
                    textoPendente = null;
                    Toast.makeText(MainActivity.this, "Este celular não tem onde salvar arquivos.", Toast.LENGTH_LONG).show();
                }
            });
        }
    }
}
