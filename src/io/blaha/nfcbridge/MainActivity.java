package io.blaha.nfcbridge;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Foreground screen for the bridge. While this screen is open and the phone is
 * unlocked, every tag the phone sees is handed to {@link Bridge}, which serves
 * commands on a loopback-only socket that adb forwards to.
 */
public class MainActivity extends Activity implements NfcAdapter.ReaderCallback {
    static final int PORT = 47800;
    static final String VERSION = "0.1";

    private NfcAdapter nfc;
    private Bridge bridge;
    private TextView prompt;
    private TextView status;
    private TextView log;
    private final ArrayDeque<String> lines = new ArrayDeque<>();

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(18, 18, 20));
        int pad = (int) (20 * dp);
        root.setPadding(pad, (int) (64 * dp), pad, pad);

        TextView title = new TextView(this);
        title.setText("NFC Bridge " + VERSION);
        title.setTextColor(Color.rgb(160, 160, 170));
        title.setTextSize(14);
        root.addView(title);

        prompt = new TextView(this);
        prompt.setText("Waiting for a client…");
        prompt.setTextColor(Color.WHITE);
        prompt.setTextSize(28);
        prompt.setTypeface(Typeface.DEFAULT_BOLD);
        prompt.setGravity(Gravity.START);
        prompt.setPadding(0, (int) (16 * dp), 0, (int) (16 * dp));
        root.addView(prompt);

        status = new TextView(this);
        status.setTextColor(Color.rgb(120, 200, 140));
        status.setTextSize(16);
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        log = new TextView(this);
        log.setTextColor(Color.rgb(190, 190, 200));
        log.setTextSize(13);
        log.setTypeface(Typeface.MONOSPACE);
        log.setPadding(0, (int) (16 * dp), 0, 0);
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        nfc = NfcAdapter.getDefaultAdapter(this);
        String token = newToken();
        try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "token"))) {
            out.write(token.getBytes("UTF-8"));
        } catch (Exception e) {
            addLog("token write failed: " + e);
        }
        bridge = new Bridge(this, token, PORT);
        bridge.start();
        if (nfc != null) enableReader(); // prime; see enableReader()
        setStatus(nfc == null ? "This device has no NFC" : "Listening on 127.0.0.1:" + PORT);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfc == null) return;
        if (!nfc.isEnabled()) setStatus("NFC is turned OFF in settings");
        enableReader();
        addLog("reader mode on");
    }

    /**
     * Android's NfcActivityManager starts tracking this activity's lifecycle on the
     * first reader-mode call. If that first call happens inside onResume, it never saw
     * the resume and silently leaves reader mode off until the NEXT resume (observed
     * 2026-11-19: tags went to normal dispatch until the app was backgrounded once).
     * Priming it from onCreate makes the first resume apply it.
     */
    private void enableReader() {
        Bundle opts = new Bundle();
        opts.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250);
        nfc.enableReaderMode(this, this,
                NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                        | NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_NFC_V
                        | NfcAdapter.FLAG_READER_NFC_BARCODE
                        | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                opts);
    }

    @Override
    protected void onPause() {
        if (nfc != null) nfc.disableReaderMode(this);
        addLog("reader mode off (screen left)");
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (bridge != null) bridge.stop();
        super.onDestroy();
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        bridge.onTag(tag);
        buzz(40); // the platform tone is off (FLAG_READER_NO_PLATFORM_SOUNDS); a tick confirms the read
        addLog("tag " + Bridge.hex(tag.getId()));
    }

    boolean nfcEnabled() {
        return nfc != null && nfc.isEnabled();
    }

    void showPrompt(final String text) {
        runOnUiThread(() -> prompt.setText(text));
        buzz(120);
    }

    private void buzz(long ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {
        }
    }

    void setStatus(final String text) {
        runOnUiThread(() -> status.setText(text));
    }

    void addLog(String text) {
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        final String joined;
        synchronized (lines) {
            lines.addFirst(stamp + "  " + text);
            while (lines.size() > 60) lines.removeLast();
            joined = String.join("\n", lines);
        }
        runOnUiThread(() -> log.setText(joined));
    }

    private static String newToken() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return Bridge.hex(b);
    }
}
