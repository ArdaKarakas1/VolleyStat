package com.volleyvolleystat.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int IMPORT = 1;
    private static final int RECORD = 2;
    private static final int INK = Color.rgb(17, 41, 37);
    private static final int ACCENT = Color.rgb(0, 107, 95);
    private static final int PAPER = Color.rgb(241, 246, 244);
    private static final int PAGE = 20;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = () -> refresh(true);
    private SharedPreferences prefs;
    private LinearLayout root, actions, review, eventList;
    private TextView status, message;
    private VideoView player;
    private JSONObject snapshot;
    private String matchId, videoUrl;
    private boolean physical, busy, visible, videoReady;
    private int page;
    private long pausedAt;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("match", MODE_PRIVATE);
        matchId = prefs.getString("id", null);
        physical = prefs.getBoolean("physical", false);
        pausedAt = prefs.getLong("position", 0);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAPER);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        root = column();
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        scroll.addView(root);
        setContentView(scroll);
        label(root, "VOLEYBOL VE VELEYBOL", 12, true).setTextColor(ACCENT);
        label(root, "VolleyStat", 36, true);
        label(root, "Salon voleybolu · 6 × 6", 16, false);
        status = label(root, "", 17, true);
        status.setPadding(0, dp(18), 0, 0);
        message = label(root, "", 14, false);
        actions = column();
        root.addView(actions);
        review = column();
        root.addView(review);
        buildReview();
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        if (matchId != null) refresh(true);
    }

    @Override
    protected void onStop() {
        visible = false;
        handler.removeCallbacks(poll);
        if (videoReady && matchId != null) {
            pausedAt = player.getCurrentPosition();
            prefs.edit().putLong("position", pausedAt).apply();
            player.pause();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        executor.shutdown();
        player.stopPlayback();
        super.onDestroy();
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView label(LinearLayout parent, String text, int size, boolean strong) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(INK);
        if (strong) view.setTypeface(null, Typeface.BOLD);
        parent.addView(view);
        return view;
    }

    private Button button(LinearLayout parent, String title, Runnable action) {
        Button view = new Button(this);
        view.setText(title);
        view.setAllCaps(false);
        view.setEnabled(!busy);
        view.setOnClickListener(v -> {
            if (!busy) action.run();
        });
        parent.addView(view);
        return view;
    }

    private EditText input(LinearLayout parent, String hint, String value, boolean number) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint(hint);
        field.setInputType(number ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
        field.setText(value);
        parent.addView(field);
        return field;
    }

    private Spinner choices(LinearLayout parent, String[] values) {
        Spinner spinner = new Spinner(this);
        parent.addView(spinner);
        options(spinner, values);
        return spinner;
    }

    private void options(Spinner spinner, String[] values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setMinimumHeight(dp(44));
    }

    private JSONObject fields(Object... pairs) throws Exception {
        JSONObject data = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) data.put((String) pairs[i], pairs[i + 1]);
        return data;
    }

    private MatchApi api() {
        return new MatchApi(physical, matchId);
    }

    private File staged() {
        return new File(getFilesDir(), matchId + ".video");
    }

    private String pendingEdit() {
        return "edit_" + matchId;
    }

    private void newMatch(boolean openExisting) {
        LinearLayout panel = column();
        panel.setPadding(dp(20), 0, dp(20), dp(8));
        EditText first = input(panel, openExisting ? "Maç kimliği" : "Ev sahibi takım", "", false);
        EditText second = openExisting ? null : input(panel, "Deplasman takımı", "", false);
        label(panel, "Bağlantı", 14, true);
        Spinner connection = choices(panel, new String[]{"Android emülatörü", "USB ile bağlı telefon"});
        connection.setSelection(physical ? 1 : 0);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(openExisting ? "Maçı yeniden aç" : "Yeni maç")
            .setView(panel).setNegativeButton("Vazgeç", null).setPositiveButton("Devam", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String home = first.getText().toString().trim();
            String away = second == null ? "" : second.getText().toString().trim();
            if (openExisting) {
                try {
                    if (!UUID.fromString(home).toString().equals(home) || UUID.fromString(home).version() != 4) {
                        throw new IllegalArgumentException();
                    }
                } catch (IllegalArgumentException error) {
                    first.setError("Geçerli bir maç kimliği girin.");
                    return;
                }
            } else if (home.isEmpty() || away.isEmpty() || home.length() > 60 || away.length() > 60 || home.equalsIgnoreCase(away)) {
                first.setError("Farklı iki takım adı girin (1–60 karakter).");
                return;
            }
            String id = openExisting ? home : UUID.randomUUID().toString();
            boolean usb = connection.getSelectedItemPosition() == 1;
            if (openExisting) {
                MatchApi candidate = new MatchApi(usb, id);
                dialog.dismiss();
                work("Maç açılıyor…", false, () -> {
                    JSONObject match = candidate.request("GET", "", null);
                    runOnUiThread(() -> {
                        setMatch(id, usb, match.optString("home"), match.optString("away"));
                        snapshot = match;
                    });
                });
                return;
            }
            setMatch(id, usb, home, away);
            dialog.dismiss();
            message.setText("");
            render();
        }));
        dialog.show();
    }

    private void setMatch(String id, boolean usb, String home, String away) {
        if (!id.equals(matchId)) pausedAt = 0;
        matchId = id;
        physical = usb;
        snapshot = null;
        page = 0;
        prefs.edit().putString("id", id).putBoolean("physical", usb)
            .putString("home", home).putString("away", away).putLong("position", pausedAt).apply();
    }

    private void pick(boolean camera) {
        Intent intent = camera ? new Intent(MediaStore.ACTION_VIDEO_CAPTURE)
            : new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*");
        if (camera) intent.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1);
        try {
            startActivityForResult(intent, camera ? RECORD : IMPORT);
        } catch (ActivityNotFoundException error) {
            message.setText(camera ? "Kamera uygulaması bulunamadı. Video seçebilirsiniz." : "Dosya seçici bulunamadı.");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if ((request != IMPORT && request != RECORD) || result != RESULT_OK || matchId == null) return;
        Uri uri = data == null ? null : data.getData();
        if (uri == null) {
            message.setText("Kamera video dosyası döndürmedi. Kaydı dosyalardan seçin.");
            return;
        }
        File destination = staged();
        work("Video telefona hazırlanıyor…", false, () -> {
            File partial = new File(destination + ".part");
            try (InputStream source = getContentResolver().openInputStream(uri);
                 FileOutputStream target = new FileOutputStream(partial)) {
                if (source == null) throw new IOException("Video açılamadı.");
                byte[] buffer = new byte[256 * 1024];
                long size = 0;
                int count;
                while ((count = source.read(buffer)) != -1) {
                    size += count;
                    if (size > MatchApi.MAX_VIDEO) throw new IOException("Video 4 GiB sınırını aşıyor.");
                    target.write(buffer, 0, count);
                }
                if (size == 0) throw new IOException("Video dosyası boş.");
                target.getFD().sync();
                Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(partial.toPath());
            }
        });
    }

    private void upload() {
        MatchApi api = api();
        File file = staged();
        String home = prefs.getString("home", "");
        String away = prefs.getString("away", "");
        prefs.edit().putString("upload_id", matchId).apply();
        work("Video bütünlüğü hesaplanıyor…", false, () -> {
            JSONObject match = api.request("PUT", "", fields("home", home, "away", away));
            if (match.isNull("size")) {
                api.upload(file, percent -> runOnUiThread(() -> {
                    if (!isDestroyed()) message.setText("Video gönderiliyor · %" + percent);
                }));
            }
            file.delete();
            getSnapshot(api);
        });
    }

    private void getSnapshot(MatchApi api) throws Exception {
        JSONObject updated = api.request("GET", "", null);
        runOnUiThread(() -> {
            if (!isDestroyed()) snapshot = updated;
        });
    }

    private void refresh(boolean silent) {
        if (matchId == null || busy) return;
        boolean started = snapshot != null || matchId.equals(prefs.getString("upload_id", ""));
        if (!started && silent) return;
        MatchApi api = api();
        work(silent ? "" : "Maç güncelleniyor…", silent, () -> getSnapshot(api));
    }

    private void command(String method, String path, JSONObject data, String waiting) {
        MatchApi api = api();
        work(waiting, false, () -> {
            api.request(method, path, data);
            getSnapshot(api);
        });
    }

    private void work(String waiting, boolean silent, Job job) {
        if (busy || isDestroyed()) return;
        busy = true;
        handler.removeCallbacks(poll);
        if (!silent) message.setText(waiting);
        render();
        executor.execute(() -> {
            String error = null;
            try {
                job.run();
            } catch (MatchApi.ApiError problem) {
                if (!(silent && problem.status == 404)) error = problem.getMessage();
            } catch (Exception problem) {
                if (!silent) error = problem instanceof IOException && problem.getMessage() != null
                    ? problem.getMessage() : "İşlem tamamlanamadı. Yerel sunucuyu ve bağlantıyı kontrol edin.";
            }
            String outcome = error;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                busy = false;
                if (!silent || outcome != null) message.setText(outcome == null ? "" : outcome);
                render();
                String state = state();
                if (visible && ("queued".equals(state) || "analyzing".equals(state))) {
                    handler.postDelayed(poll, 2000);
                }
            });
        });
    }

    private String state() {
        return snapshot == null ? "awaiting_upload" : snapshot.optString("state");
    }

    private void render() {
        actions.removeAllViews();
        if (matchId == null) {
            status.setText("İlk maçını ekle");
            button(actions, "Yeni maç", () -> newMatch(false));
            button(actions, "Maç kimliğiyle aç", () -> newMatch(true));
            renderReview(false);
            return;
        }
        String title = prefs.getString("home", "") + " – " + prefs.getString("away", "");
        String state = state();
        String detail;
        switch (state) {
            case "queued": detail = "Video sırada"; break;
            case "analyzing": detail = "Video inceleniyor"; break;
            case "needs_review": detail = "Doğrulama bekliyor"; break;
            case "reviewed": detail = "İnceleme tamamlandı"; break;
            case "failed": detail = "Video incelemesi durdu"; break;
            default: detail = staged().isFile() ? "Video yüklemeye hazır" : "Video bekleniyor";
        }
        status.setText(title + "\n" + detail);
        if (state.equals("awaiting_upload")) {
            boolean locked = matchId.equals(prefs.getString("upload_id", ""));
            if (!locked) {
                button(actions, "Kamerayla kaydet", () -> pick(true));
                button(actions, "Dosyalardan video seç", () -> pick(false));
            }
            if (locked || staged().isFile()) {
                button(actions, locked ? "Yüklemeyi yeniden dene" : "Yükle ve incelemeyi başlat", this::upload);
            }
        }
        if (state.equals("failed")) {
            label(actions, snapshot.optString("error"), 14, false);
            button(actions, "Analizi yeniden dene", () -> command("POST", "/analysis/retry", null, "Analiz sıraya alınıyor…"));
        }
        button(actions, "Durumu yenile", () -> refresh(false));
        button(actions, "Maç kimliği ve diğer maçlar", this::matchMenu);
        renderReview(state.equals("needs_review") || state.equals("reviewed"));
    }

    private void matchMenu() {
        new AlertDialog.Builder(this).setTitle("Maç kimliği").setMessage(matchId)
            .setPositiveButton("Kopyala", (dialog, which) -> {
                android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("VolleyStat maç kimliği", matchId));
            })
            .setNeutralButton("Yeni maç", (dialog, which) -> newMatch(false))
            .setNegativeButton("Maç aç", (dialog, which) -> newMatch(true)).show();
    }

    private void buildReview() {
        player = new VideoView(this);
        player.setContentDescription("Maç videosu");
        review.addView(player, new LinearLayout.LayoutParams(-1, dp(200)));
        MediaController controls = new MediaController(this);
        player.setMediaController(controls);
        player.setOnPreparedListener(media -> {
            videoReady = true;
            player.seekTo((int) pausedAt);
        });
        player.setOnErrorListener((media, what, extra) -> {
            videoReady = false;
            videoUrl = null;
            message.setText("Video oynatılamadı. H.264 MP4 ile yeniden deneyin veya durumu yenileyin.");
            return true;
        });
        eventList = column();
        review.addView(eventList);
    }

    private void renderReview(boolean show) {
        review.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            if (videoUrl != null) player.stopPlayback();
            videoUrl = null;
            videoReady = false;
            return;
        }
        String url = api().url + "/video";
        if (!url.equals(videoUrl)) {
            videoUrl = url;
            videoReady = false;
            player.setVideoURI(Uri.parse(url));
        }
        eventList.removeAllViews();
        JSONObject stats = snapshot.optJSONObject("statistics");
        boolean manual = "manual_only".equals(snapshot.optString("mode"));
        label(eventList, manual ? "Elle doğrulama · AI modeli bağlı değil" : "Model önerileri · İnsan doğrulaması gerekli", 14, true);
        boolean pendingEdit = prefs.contains(pendingEdit());
        if (pendingEdit) {
            button(eventList, "Bekleyen olay kaydını yeniden gönder", this::retryEdit);
        } else {
            button(eventList, "Bu video anına olay ekle", () -> eventDialog(null));
        }
        button(eventList, "İki takımın istatistikleri", () -> statistics(stats));
        if ("needs_review".equals(state()) && !pendingEdit) {
            button(eventList, "İncelemeyi tamamla", () -> command("POST", "/review/complete", null, "İnceleme tamamlanıyor…"));
        }
        label(eventList, stats.optInt("verified_events") + " doğrulanan · " + stats.optInt("pending_events") + " bekleyen", 15, true);
        JSONArray events = snapshot.optJSONArray("events");
        java.util.List<JSONObject> ordered = new java.util.ArrayList<>();
        for (String filter : new String[]{"pending", "verified"}) {
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i);
                if (filter.equals(event.optString("status"))) ordered.add(event);
            }
        }
        page = Math.min(page, Math.max(0, (ordered.size() - 1) / PAGE));
        int end = Math.min(ordered.size(), (page + 1) * PAGE);
        for (int i = page * PAGE; i < end; i++) {
            JSONObject event = ordered.get(i);
            String jersey = event.isNull("player") ? "" : " · #" + event.optInt("player");
            String text = stamp(event.optLong("at_ms")) + " · " + team(event.optString("team")) + jersey + "\n"
                + EventOptions.label(event.optString("kind"), event.optString("outcome"))
                + ("pending".equals(event.optString("status")) ? " · Öneri" : " · Doğrulandı");
            button(eventList, text, () -> {
                if (videoReady) {
                    player.seekTo((int) event.optLong("at_ms"));
                    player.start();
                    player.requestFocus();
                }
            });
            if (!pendingEdit) button(eventList, "Olayı incele", () -> eventDialog(event));
        }
        if (page > 0) button(eventList, "Önceki olaylar", () -> { page--; renderReview(true); });
        if (end < ordered.size()) button(eventList, "Sonraki olaylar", () -> { page++; renderReview(true); });
    }

    private String team(String key) {
        return snapshot.optString(key);
    }

    private String stamp(long at) {
        return String.format(Locale.ROOT, "%02d:%02d.%03d", at / 60000, at / 1000 % 60, at % 1000);
    }

    private void eventDialog(JSONObject existing) {
        boolean edit = existing != null;
        if (!edit && !videoReady) {
            message.setText("Olay eklemek için videonun açılmasını bekleyin.");
            return;
        }
        long at = edit ? existing.optLong("at_ms") : Math.min(player.getCurrentPosition(),
            snapshot.optJSONObject("video").optLong("duration_ms") - 1);
        if (videoReady) {
            player.pause();
            if (edit) player.seekTo((int) at);
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = column();
        panel.setPadding(dp(20), 0, dp(20), dp(8));
        scroll.addView(panel);
        label(panel, "Video konumu · milisaniye", 14, true);
        EditText timestamp = input(panel, "Milisaniye", String.valueOf(at), true);
        label(panel, "Takım", 14, true);
        Spinner side = choices(panel, new String[]{team("home"), team("away")});
        if (edit && "away".equals(existing.optString("team"))) side.setSelection(1);
        label(panel, "Set ve ralli", 14, true);
        EditText set = input(panel, "Set (1–5)", edit ? existing.optString("set") : prefs.getString("last_set", "1"), true);
        EditText rally = input(panel, "Ralli numarası", edit ? existing.optString("rally") : prefs.getString("last_rally", "1"), true);
        label(panel, "Olay ve sonucu", 14, true);
        Spinner type = choices(panel, EventOptions.TYPE_LABELS);
        Spinner outcome = choices(panel, EventOptions.OUTCOME_LABELS[0]);
        int original = edit ? EventOptions.index(EventOptions.TYPES, existing.optString("kind")) : 0;
        EditText playerNumber = input(panel, "Forma numarası (isteğe bağlı)",
            edit && !existing.isNull("player") ? existing.optString("player") : "", true);
        type.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean initialized;

            @Override
            public void onItemSelected(AdapterView<?> view, View item, int position, long id) {
                options(outcome, EventOptions.OUTCOME_LABELS[position]);
                playerNumber.setVisibility(position == 0 ? View.GONE : View.VISIBLE);
                if (!initialized && edit && position == original) {
                    outcome.setSelection(EventOptions.index(EventOptions.OUTCOMES[position], existing.optString("outcome")));
                }
                initialized = true;
            }

            @Override public void onNothingSelected(AdapterView<?> view) {}
        });
        type.setSelection(original);
        String id = edit ? existing.optString("id") : UUID.randomUUID().toString();
        int revision = edit ? existing.optInt("revision") : 0;
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(edit ? "Olayı incele · " + stamp(at) : "Olay ekle · " + stamp(at))
            .setView(scroll).setNegativeButton("Vazgeç", null).setPositiveButton("Doğrula", null);
        if (edit) builder.setNeutralButton("Çıkar", (dialog, which) -> {
            new AlertDialog.Builder(this).setTitle("Olay çıkarılsın mı?")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Çıkar", (confirmation, button) -> saveEvent(id, revision, null)).show();
        });
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            int selection = type.getSelectedItemPosition();
            try {
                String jersey = playerNumber.getText().toString().trim();
                int setValue = Integer.parseInt(set.getText().toString());
                int rallyValue = Integer.parseInt(rally.getText().toString());
                long timeValue = Long.parseLong(timestamp.getText().toString());
                Object number = selection == 0 || jersey.isEmpty() ? JSONObject.NULL : Integer.parseInt(jersey);
                if (setValue < 1 || setValue > 5 || rallyValue < 1 || rallyValue > 999
                    || timeValue < 0 || timeValue >= snapshot.optJSONObject("video").optLong("duration_ms")
                    || (number instanceof Integer && ((int) number < 1 || (int) number > 99))) {
                    throw new IllegalArgumentException();
                }
                JSONObject event = fields("team", side.getSelectedItemPosition() == 0 ? "home" : "away",
                    "set", setValue, "rally", rallyValue, "at_ms", timeValue, "player", number,
                    "kind", EventOptions.TYPES[selection],
                    "outcome", EventOptions.OUTCOMES[selection][outcome.getSelectedItemPosition()]);
                prefs.edit().putString("last_set", String.valueOf(setValue))
                    .putString("last_rally", String.valueOf(rallyValue)).apply();
                dialog.dismiss();
                saveEvent(id, revision, event);
            } catch (Exception error) {
                timestamp.setError("Video konumunu, seti, ralliyi ve forma numarasını kontrol edin.");
            }
        }));
        dialog.show();
    }

    private void saveEvent(String id, int revision, JSONObject event) {
        try {
            JSONObject body = fields("action", event == null ? "reject" : "verify", "revision", revision);
            if (event != null) body.put("event", event);
            prefs.edit().putString(pendingEdit(), fields("id", id, "body", body).toString()).apply();
            retryEdit();
        } catch (Exception error) {
            message.setText("Olay hazırlanamadı.");
        }
    }

    private void retryEdit() {
        String key = pendingEdit();
        MatchApi api = api();
        work("Olay kaydediliyor…", false, () -> {
            JSONObject edit = new JSONObject(prefs.getString(key, ""));
            try {
                api.request("PUT", "/events/" + edit.getString("id"), edit.getJSONObject("body"));
            } catch (MatchApi.ApiError problem) {
                if (problem.status < 500) {
                    prefs.edit().remove(key).apply();
                    getSnapshot(api);
                }
                throw problem;
            }
            prefs.edit().remove(key).apply();
            getSnapshot(api);
        });
    }

    private void statistics(JSONObject stats) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = column();
        panel.setPadding(dp(20), dp(8), dp(20), dp(16));
        scroll.addView(panel);
        if (stats.optInt("verified_events") == 0) {
            label(panel, "İstatistik için en az bir olayı doğrulayın.", 16, false);
        } else {
            label(panel, "Doğrulanmış olaylar · " + stats.optInt("verified_events"), 16, true);
            JSONArray sets = stats.optJSONArray("sets");
            for (int i = 0; i < sets.length(); i++) {
                JSONObject set = sets.optJSONObject(i);
                label(panel, "Set " + set.optInt("set") + " · " + team("home") + " " + set.optInt("home")
                    + " – " + set.optInt("away") + " " + team("away"), 15, false);
            }
            for (String key : new String[]{"home", "away"}) {
                JSONObject team = stats.optJSONObject("teams").optJSONObject(key);
                label(panel, "\n" + team(key), 18, true);
                label(panel, metrics(team.optJSONObject("totals")), 15, false);
                JSONArray players = team.optJSONArray("players");
                for (int i = 0; i < players.length(); i++) {
                    JSONObject player = players.optJSONObject(i);
                    label(panel, "\n#" + player.optInt("player"), 15, true);
                    label(panel, metrics(player), 14, false);
                }
            }
        }
        new AlertDialog.Builder(this).setTitle("Maç istatistikleri").setView(scroll)
            .setPositiveButton("Kapat", null).show();
    }

    private String metrics(JSONObject stats) {
        String efficiency = stats.isNull("hitting_percentage") ? "—"
            : String.format(new Locale("tr", "TR"), "%%%.1f", stats.optDouble("hitting_percentage") * 100);
        return "Hücum " + stats.optInt("kills") + " sayı · " + stats.optInt("attack_errors") + " hata · "
            + stats.optInt("attack_attempts") + " deneme · Verim " + efficiency
            + "\nServis " + stats.optInt("aces") + " ace · " + stats.optInt("serve_errors") + " hata · "
            + stats.optInt("serve_attempts") + " deneme"
            + "\nKarşılama " + stats.optInt("reception_attempts") + " · " + stats.optInt("reception_errors") + " hata"
            + "\nBlok " + stats.optInt("solo_blocks") + " tekli · " + stats.optInt("block_assists") + " asist · "
            + stats.optInt("block_errors") + " hata"
            + "\nAsist " + stats.optInt("assists") + " · Savunma " + stats.optInt("digs")
            + " · Top kullanımı hatası " + stats.optInt("handling_errors");
    }

    private interface Job {
        void run() throws Exception;
    }
}
