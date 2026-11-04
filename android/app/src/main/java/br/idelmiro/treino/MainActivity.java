package br.idelmiro.treino;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public class MainActivity extends Activity {

    static final String CHANNEL_ID = "descanso";
    static final String RELEASES_API = "https://api.github.com/repos/idelmiro/Treino/releases/latest";
    static volatile boolean inForeground = false;

    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long pendingDownload = -1;
    private BroadcastReceiver downloadReceiver;
    private AudioFocusRequest focusReq;
    private boolean hasFocus = false;
    private long focusUntil = 0;
    private final Runnable focusRelease = this::releaseFocusIfDue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        createChannel();
        askNotificationPermission();

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setSupportMultipleWindows(false);
        web.addJavascriptInterface(new Bridge(), "TreinoNative");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }
        });
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl("file:///android_asset/index.html");

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("pt", "BR"));
                ttsReady = true;
            }
        });

        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == pendingDownload) installDownloaded();
            }
        };
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadReceiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(downloadReceiver, f);

        checkUpdate(false);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        inForeground = true;
        web.onResume();
        web.evaluateJavascript("window.dispatchEvent(new Event('focus'))", null);
        if (pendingDownload != -1 && Build.VERSION.SDK_INT >= 26 && getPackageManager().canRequestPackageInstalls()) {
            installDownloaded();
        }
    }

    @Override
    protected void onPause() {
        inForeground = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
        if (tts != null) tts.shutdown();
        if (hasFocus && focusReq != null) getSystemService(AudioManager.class).abandonAudioFocusRequest(focusReq);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /* ---------- notificações de descanso ---------- */

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Fim do descanso", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Avisa quando o descanso entre séries termina");
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 400, 150, 400});
        ch.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private PendingIntent restIntent(String title, String body) {
        Intent i = new Intent(this, RestReceiver.class);
        i.putExtra("title", title);
        i.putExtra("body", body);
        return PendingIntent.getBroadcast(this, 42, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    void scheduleRest(int seconds, String title, String body) {
        AlarmManager am = getSystemService(AlarmManager.class);
        long at = SystemClock.elapsedRealtime() + seconds * 1000L;
        PendingIntent pi = restIntent(title, body);
        am.cancel(pi);
        boolean exact = true;
        if (Build.VERSION.SDK_INT >= 31) exact = am.canScheduleExactAlarms();
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
    }

    void cancelRest() {
        getSystemService(AlarmManager.class).cancel(restIntent("", ""));
        getSystemService(NotificationManager.class).cancel(RestReceiver.NOTIF_ID);
    }

    /* ---------- prioridade de áudio: pausa a música durante os avisos ---------- */

    static AudioFocusRequest buildFocusRequest() {
        return new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setOnAudioFocusChangeListener(change -> {})
                .build();
    }

    void focusFor(long ms) {
        long until = SystemClock.uptimeMillis() + Math.max(500, Math.min(ms, 20000));
        if (until > focusUntil) focusUntil = until;
        if (!hasFocus) {
            focusReq = buildFocusRequest();
            hasFocus = getSystemService(AudioManager.class).requestAudioFocus(focusReq) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        main.removeCallbacks(focusRelease);
        main.postAtTime(focusRelease, focusUntil);
    }

    private void releaseFocusIfDue() {
        if (SystemClock.uptimeMillis() + 50 < focusUntil) {
            main.postAtTime(focusRelease, focusUntil);
            return;
        }
        if (hasFocus && focusReq != null) getSystemService(AudioManager.class).abandonAudioFocusRequest(focusReq);
        hasFocus = false;
    }

    /* ---------- atualização pelo GitHub ---------- */

    private int currentVersion() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) return (int) pi.getLongVersionCode();
            return pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    void checkUpdate(boolean manual) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(RELEASES_API).openConnection();
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "Treino-app");
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
                JSONObject rel = new JSONObject(sb.toString());
                int latest = Integer.parseInt(rel.getString("tag_name").replaceAll("[^0-9]", ""));
                String url = null;
                JSONArray assets = rel.getJSONArray("assets");
                for (int k = 0; k < assets.length(); k++) {
                    JSONObject a = assets.getJSONObject(k);
                    if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url");
                }
                final String apk = url;
                final String notes = rel.optString("body", "");
                main.post(() -> {
                    if (latest > currentVersion() && apk != null) offerUpdate(latest, apk, notes);
                    else if (manual) Toast.makeText(this, "Você já está na versão mais recente (" + currentVersion() + ").", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                if (manual) main.post(() -> Toast.makeText(this, "Não foi possível verificar atualizações.", Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void offerUpdate(int version, String url, String notes) {
        if (isFinishing()) return;
        String msg = "A versão " + version + " está disponível. Você está na versão " + currentVersion() + ".";
        if (notes != null && !notes.trim().isEmpty()) msg += "\n\n" + notes.trim();
        new AlertDialog.Builder(this)
                .setTitle("Nova versão do Treino")
                .setMessage(msg)
                .setPositiveButton("Atualizar", (d, w) -> download(version, url))
                .setNegativeButton("Depois", null)
                .show();
    }

    private void download(int version, String url) {
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url))
                .setTitle("Treino versão " + version)
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "treino-" + version + ".apk");
        pendingDownload = getSystemService(DownloadManager.class).enqueue(req);
        Toast.makeText(this, "Baixando a atualização...", Toast.LENGTH_SHORT).show();
    }

    private void installDownloaded() {
        if (pendingDownload == -1) return;
        DownloadManager dm = getSystemService(DownloadManager.class);
        Uri uri = dm.getUriForDownloadedFile(pendingDownload);
        if (uri == null) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "Permita instalar apps desta fonte e volte para o Treino.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        pendingDownload = -1;
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    /* ---------- ponte com a página ---------- */

    class Bridge {
        @JavascriptInterface
        public void scheduleRest(int seconds, String title, String body) {
            MainActivity.this.scheduleRest(seconds, title, body);
        }

        @JavascriptInterface
        public void cancelRest() {
            MainActivity.this.cancelRest();
        }

        @JavascriptInterface
        public void speak(String text) {
            if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "treino");
        }

        @JavascriptInterface
        public void stopSpeak() {
            if (ttsReady) tts.stop();
        }

        @JavascriptInterface
        public void vibrate(String patternJson) {
            try {
                JSONArray a = new JSONArray(patternJson);
                long[] p = new long[a.length() + 1];
                for (int k = 0; k < a.length(); k++) p[k + 1] = a.getLong(k);
                Vibrator v = getSystemService(Vibrator.class);
                if (v != null) v.vibrate(VibrationEffect.createWaveform(p, -1));
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void focusFor(int ms) {
            main.post(() -> MainActivity.this.focusFor(ms));
        }

        @JavascriptInterface
        public int version() {
            return currentVersion();
        }

        @JavascriptInterface
        public void checkUpdate() {
            main.post(() -> MainActivity.this.checkUpdate(true));
        }
    }
}
