package com.fuoconero.tvbridge;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.OutputStream;

/**
 * Minimal TV Provider probe. It publishes one app preview channel and two
 * clearly labelled demo cards. NO Trakt credentials and NO playback URLs.
 *
 * Proof of concept only: pressing a demo card launches Veezie's HOME.
 */
public final class MainActivity extends Activity {
    private static final String PREF = "bridge_state";
    private static final String CHANNEL_KEY = "channel_id";
    private static final String OPEN_VEEZIE = "com.fuoconero.tvbridge.OPEN_VEEZIE";
    private static final int DARK = Color.rgb(19, 24, 35);
    private static final int ORANGE = Color.rgb(255, 103, 55);
    private TextView status;
    private boolean working;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (OPEN_VEEZIE.equals(getIntent().getAction())) {
            openVeezie();
            finish();
            return;
        }
        renderScreen();
    }

    private void renderScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(65, 35, 65, 30);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setBackgroundColor(DARK);

        TextView title = new TextView(this);
        title.setText("FUOCONERO  •  TV BRIDGE");
        title.setTextSize(32);
        title.setTextColor(ORANGE);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView intro = new TextView(this);
        intro.setText("Prima prova: pubblicare una riga nella home di Android TV / Projectivy.\n"
            + "Le due schede sono dimostrative: aprono la HOME di Veezie, non un episodio.\n"
            + "Trakt verra' integrato dopo aver verificato questa prima riga.");
        intro.setTextSize(19);
        intro.setTextColor(Color.WHITE);
        intro.setPadding(0, 15, 0, 25);
        root.addView(intro);

        Button add = addButton(root, "1  •  Crea / aggiorna la riga di prova");
        add.setOnClickListener(v -> publishAsync());

        Button launch = addButton(root, "2  •  Prova apertura Veezie");
        launch.setOnClickListener(v -> openVeezie());

        Button remove = addButton(root, "3  •  Elimina la nostra riga di prova");
        remove.setOnClickListener(v -> removeAsync());

        status = new TextView(this);
        status.setText("Pronto. Non vengono modificati Veezie, Projectivy o lo storico.");
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(18);
        status.setPadding(0, 22, 0, 0);
        root.addView(status);
        setContentView(root);
    }

    private Button addButton(LinearLayout root, String title) {
        Button b = new Button(this);
        b.setText(title);
        b.setTextSize(19);
        b.setAllCaps(false);
        b.setFocusable(true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 4, 0, 5);
        root.addView(b, p);
        return b;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREF, MODE_PRIVATE);
    }

    private void report(String msg) {
        runOnUiThread(() -> {
            if (status != null) status.setText(msg);
            working = false;
        });
    }

    private void publishAsync() {
        if (working) return;
        working = true;
        status.setText("Pubblicazione canale in corso...");
        new Thread(() -> {
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
                    throw new IllegalStateException("Serve Android TV 8.0 (API 26) o superiore.");

                long id = getExistingChannel();
                boolean fresh = id < 0;
                if (fresh) {
                    ContentValues values = new ContentValues();
                    values.put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW);
                    values.put(TvContract.Channels.COLUMN_DISPLAY_NAME, "Fuoconero • Continua a guardare [TEST]");
                    Intent app = new Intent(this, MainActivity.class);
                    values.put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI,
                               app.toUri(Intent.URI_INTENT_SCHEME));
                    Uri uri = getContentResolver().insert(TvContract.Channels.CONTENT_URI, values);
                    if (uri == null) throw new IllegalStateException("Android ha rifiutato il canale.");
                    id = ContentUris.parseId(uri);
                    writeChannelLogo(id);
                    prefs().edit().putLong(CHANNEL_KEY, id).apply();
                }
                removeOldPrograms(id);
                addDemoProgram(id, "TEST 1 • Apri Veezie", "Scheda dimostrativa; apre soltanto Veezie.");
                addDemoProgram(id, "TEST 2 • Verifica Projectivy", "Anche questa scheda apre la home di Veezie.");

                final long channel = id;
                if (fresh) {
                    runOnUiThread(() -> {
                        try { TvContract.requestChannelBrowsable(this, channel); }
                        catch (Exception e) { report("Canale creato, ma richiesta visibilita': " + e.getMessage()); }
                    });
                }
                report("Canale pubblicato! ID " + id + ". Vai su Projectivy → Impostazioni → Modifica canali e attiva Fuoconero.");
            } catch (Exception e) {
                report("Errore pubblicazione: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }, "Bridge-Publish").start();
    }

    private long getExistingChannel() {
        long id = prefs().getLong(CHANNEL_KEY, -1L);
        if (id < 0) return -1;
        try (Cursor cursor = getContentResolver().query(TvContract.buildChannelUri(id),
                                  new String[]{TvContract.Channels._ID}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return id;
        } catch (Exception ignored) {}
        prefs().edit().remove(CHANNEL_KEY).apply();
        return -1;
    }

    private void writeChannelLogo(long id) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(DARK);
        Paint brush = new Paint(Paint.ANTI_ALIAS_FLAG);
        brush.setColor(ORANGE);
        brush.setTextSize(45f);
        brush.setFakeBoldText(true);
        canvas.drawText("FUOCONERO", 22f, 88f, brush);
        brush.setColor(Color.WHITE);
        brush.setTextSize(29f);
        canvas.drawText("TV BRIDGE", 22f, 133f, brush);
        try (OutputStream out = getContentResolver().openOutputStream(TvContract.buildChannelLogoUri(id))) {
            if (out == null) throw new IllegalStateException("Impossibile salvare il logo del canale.");
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                throw new IllegalStateException("Logo PNG non salvato.");
        } finally {
            bitmap.recycle();
        }
    }

    private void addDemoProgram(long id, String name, String desc) {
        ContentValues p = new ContentValues();
        p.put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, id);
        p.put(TvContract.PreviewPrograms.COLUMN_TYPE, TvContract.PreviewPrograms.TYPE_MOVIE);
        p.put(TvContract.PreviewPrograms.COLUMN_TITLE, name);
        p.put(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION, desc);
        p.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
               "android.resource://" + getPackageName() + "/drawable/bridge_poster");
        Intent card = new Intent(this, MainActivity.class);
        card.setAction(OPEN_VEEZIE);
        card.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        p.put(TvContract.PreviewPrograms.COLUMN_INTENT_URI, card.toUri(Intent.URI_INTENT_SCHEME));
        Uri saved = getContentResolver().insert(TvContract.PreviewPrograms.CONTENT_URI, p);
        if (saved == null) throw new IllegalStateException("Android ha rifiutato la scheda.");
    }

    private void removeOldPrograms(long channelId) {
        ContentResolver resolver = getContentResolver();
        try (Cursor c = resolver.query(TvContract.PreviewPrograms.CONTENT_URI,
                          new String[]{TvContract.PreviewPrograms._ID},
                          TvContract.PreviewPrograms.COLUMN_CHANNEL_ID + "=?",
                          new String[]{String.valueOf(channelId)}, null)) {
            if (c == null) return;
            while (c.moveToNext())
                resolver.delete(TvContract.buildPreviewProgramUri(c.getLong(0)), null, null);
        }
    }

    private void removeAsync() {
        if (working) return;
        working = true;
        status.setText("Eliminazione in corso...");
        new Thread(() -> {
            try {
                long id = getExistingChannel();
                if (id >= 0) getContentResolver().delete(TvContract.buildChannelUri(id), null, null);
                prefs().edit().remove(CHANNEL_KEY).apply();
                report("Riga Fuoconero eliminata. Nessun dato di Veezie modificato.");
            } catch (Exception e) {
                report("Errore eliminazione: " + e.getMessage());
            }
        }, "Bridge-Remove").start();
    }

    private void openVeezie() {
        try {
            Intent deeplink = new Intent(Intent.ACTION_VIEW, Uri.parse("veezie://test"));
            deeplink.setPackage("st.veezie");
            startActivity(deeplink);
        } catch (Exception e) {
            try {
                Intent fallback = getPackageManager().getLaunchIntentForPackage("st.veezie");
                if (fallback == null) throw new IllegalStateException("Veezie non risulta installato.");
                startActivity(fallback);
            } catch (Exception nested) {
                if (status != null) status.setText("Non riesco ad aprire Veezie: " + nested.getMessage());
            }
        }
    }
}
