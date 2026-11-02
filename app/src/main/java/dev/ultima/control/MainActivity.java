package dev.ultima.control;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;

/**
 * Ultima Control — custom companion for the "Ultima Prime Aura" earbuds.
 *
 * Reverse-engineered from the official "Ultima Link" app:
 *   - The control link is CLASSIC BLUETOOTH SPP (RFCOMM), NOT BLE.
 *   - Protocol = Bluetrum "ABMate".
 *
 * SPP: createInsecureRfcommSocketToServiceRecord()
 *        default UUID 00001101-0000-1000-8000-00805F9B34FB
 *        custom  UUID B6632277-0642-458B-A7A0-23FB1DC92C93
 *
 * Frame (5-byte header, no magic, no CRC):
 *   [seq:4bits][cmd][cmdType][frameInfo][len][payload...]
 *   cmdType: 1=request 2=response 3=notification
 *   frameInfo: ((totalFrames-1)<<4) | frameIndex
 *
 * Device-info payload is TLV: [type][len][value...] repeated.
 */
public class MainActivity extends Activity {

    static final UUID SPP_DEFAULT = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    static final UUID SPP_CUSTOM  = UUID.fromString("B6632277-0642-458B-A7A0-23FB1DC92C93");

    // ---- commands (com.bluetrum.devicemanager.cmd.Command) ----
    static final byte CMD_MUSIC = 33, CMD_KEY = 34, CMD_AUTO_SHUTDOWN = 35, CMD_FACTORY_RESET = 36,
            CMD_WORK_MODE = 37, CMD_IN_EAR = 38, CMD_DEVICE_INFO = 39, CMD_NOTIFY = 40,
            CMD_LANGUAGE = 41, CMD_FIND = 42, CMD_AUTO_ANSWER = 43, CMD_ANC_MODE = 44,
            CMD_BT_NAME = 45, CMD_LED = 46, CMD_CLEAR_PAIR = 47, CMD_ANC_GAIN = 48,
            CMD_TRANSPARENCY_GAIN = 49, CMD_SOUND_3D = 50, CMD_ONE_DRAG_TWO = 51,
            CMD_CASE_KEY = 64, CMD_SPACE = 65, CMD_LDAC = 66;

    // ---- device-info / notification types (INFO_*) ----
    static final byte INFO_POWER = 1, INFO_FIRMWARE = 2, INFO_BT_NAME = 3, INFO_EQ = 4,
            INFO_KEY = 5, INFO_VOLUME = 6, INFO_PLAY_STATE = 7, INFO_WORK_MODE = 8,
            INFO_IN_EAR = 9, INFO_LANGUAGE = 10, INFO_AUTO_ANSWER = 11, INFO_ANC_MODE = 12,
            INFO_IS_TWS = 13, INFO_TWS_CONNECTED = 14, INFO_LED = 15, INFO_FW_CHECKSUM = 16,
            INFO_ANC_GAIN = 17, INFO_TRANSPARENCY_GAIN = 18, INFO_ANC_GAIN_NUM = 19,
            INFO_TRANSPARENCY_GAIN_NUM = 20, INFO_ALL_EQ = 21, INFO_MAIN_SIDE = 22,
            INFO_PRODUCT_COLOR = 23, INFO_SOUND_3D = 24, INFO_ONE_DRAG_TWO = 25,
            INFO_CASE_KEY = 48, INFO_SPACE = 49, INFO_LDAC = 50,
            INFO_CAPABILITIES = (byte) 0xFE, INFO_MAX_PACKET = (byte) 0xFF;

    final Handler ui = new Handler(Looper.getMainLooper());
    TextView log, status, info;
    EditText rawHex;
    Slider volBar;
    TextView volLabel, volValue;

    BluetoothAdapter adapter;
    BluetoothSocket socket;
    OutputStream out;
    InputStream in;
    volatile boolean connected = false;
    boolean simulated = false;
    byte txSeq = 0;

    // ---- RX frame assembly ----
    final ByteArrayOutputStream rxBuf = new ByteArrayOutputStream();
    int mTotal = -1;
    byte mCmd, mType;
    final ByteArrayOutputStream mPayload = new ByteArrayOutputStream();

    // ---- live device state ----
    int left = -1, right = -1, kase = -1;
    boolean leftChg, rightChg, caseChg;
    int ancMode = -1, workMode = -1, ancGain = -1, transGain = -1, ancGainNum = -1, transGainNum = -1, volume = -1;
    int sound3d = -1, space = -1, ldac = -1, oneDragTwo = -1, inEar = -1, autoAnswer = -1, led = -1, isTws = -1, twsConnected = -1;
    String fw = null, btName = null;

    // =====================================================================
    //  Material 3 UI helpers
    // =====================================================================
    LinearLayout root;

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    int col(int attr) { return MaterialColors.getColor(this, attr, Color.GRAY); }

    int primary() { return col(com.google.android.material.R.attr.colorPrimary); }
    int onPrimary() { return col(com.google.android.material.R.attr.colorOnPrimary); }
    int muted() { return col(com.google.android.material.R.attr.colorOnSurfaceVariant); }
    int surface() { return col(com.google.android.material.R.attr.colorSurface); }

    LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    LinearLayout card(String title) {
        MaterialCardView c = new MaterialCardView(this);
        c.setCardElevation(0f);
        c.setRadius(dp(20));
        c.setStrokeWidth(dp(1));
        c.setStrokeColor(col(com.google.android.material.R.attr.colorOutline));
        c.setCardBackgroundColor(col(com.google.android.material.R.attr.colorSurfaceContainerLow));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        c.setLayoutParams(lp);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(14), dp(18), dp(16));
        if (title != null) {
            TextView t = new TextView(this);
            t.setText(title);
            t.setTextSize(12);
            t.setLetterSpacing(0.12f);
            t.setTypeface(null, Typeface.BOLD);
            t.setTextColor(primary());
            t.setPadding(0, dp(4), 0, dp(10));
            content.addView(t);
        }
        c.addView(content);
        root.addView(c);
        return content;
    }

    MaterialButton btn(String label, boolean outlined, Runnable r) {
        MaterialButton b = outlined
                ? new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
                : new MaterialButton(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        if (r != null) b.setOnClickListener(v -> r.run());
        return b;
    }

    MaterialButton compact(MaterialButton b) {
        b.setTextSize(13);
        return b;
    }

    void add(LinearLayout row, View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (row.getChildCount() > 0) lp.setMarginStart(dp(8));
        v.setLayoutParams(lp);
        row.addView(v);
    }

    android.graphics.drawable.GradientDrawable rounded(int color, float radius) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    interface BoolFn { void run(boolean b); }

    MaterialSwitch toggle(String label, boolean initial, BoolFn onSet) {
        MaterialSwitch s = new MaterialSwitch(this);
        s.setText(label);
        s.setTextSize(15);
        s.setTextColor(col(com.google.android.material.R.attr.colorOnSurface));
        s.setChecked(initial);
        s.setPadding(0, dp(10), 0, dp(10));
        s.setOnCheckedChangeListener((v, checked) -> onSet.run(checked));
        return s;
    }

    void markSegment(MaterialButton[] seg, int idx) {
        for (int i = 0; i < seg.length; i++) {
            boolean on = i == idx;
            seg[i].setBackgroundTintList(ColorStateList.valueOf(
                    on ? primary() : col(com.google.android.material.R.attr.colorSecondaryContainer)));
            seg[i].setTextColor(on ? onPrimary()
                    : col(com.google.android.material.R.attr.colorOnSecondaryContainer));
        }
    }

    void syncVolume() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int v = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        volBar.setValueTo(max);
        volBar.setValue(v);
        volValue.setText(v + " / " + max);
    }

    // Hardware keys change volume outside the app; the system broadcast keeps the slider honest.
    boolean volRegistered;
    final BroadcastReceiver volReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if ("android.media.VOLUME_CHANGED_ACTION".equals(i.getAction())
                    && i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) == AudioManager.STREAM_MUSIC)
                syncVolume();
        }
    };

    @Override protected void onResume() {
        super.onResume();
        if (volBar != null) syncVolume();
        if (!volRegistered) {
            registerReceiver(volReceiver, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
            volRegistered = true;
        }
    }

    @Override protected void onPause() {
        super.onPause();
        if (volRegistered) {
            unregisterReceiver(volReceiver);
            volRegistered = false;
        }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (getActionBar() != null) getActionBar().hide();
        adapter = BluetoothAdapter.getDefaultAdapter();

        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(32));

        TextView title = new TextView(this);
        title.setText("Ultima Control");
        title.setTextSize(27);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(col(com.google.android.material.R.attr.colorOnSurface));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Ultima Prime Aura  ·  Bluetooth control");
        subtitle.setTextSize(13);
        subtitle.setTextColor(muted());
        subtitle.setPadding(0, dp(2), 0, dp(6));
        root.addView(subtitle);

        status = new TextView(this);
        status.setText("●  Not connected");
        status.setTextSize(13);
        status.setTextColor(muted());
        status.setPadding(0, dp(2), 0, dp(2));
        root.addView(status);

        // ---- connection
        LinearLayout conn = card("CONNECTION");
        LinearLayout connRow = row();
        add(connRow, btn("Connect", false, this::connectReal));
        add(connRow, btn("Disconnect", true, this::disconnect));
        conn.addView(connRow);

        info = new TextView(this);
        info.setTextSize(12);
        info.setTextColor(muted());
        info.setPadding(0, dp(10), 0, 0);
        info.setText("Send-only link: this firmware never replies over SPP.");
        conn.addView(info);

        LinearLayout simRow = row();
        simRow.setPadding(0, dp(10), 0, 0);
        add(simRow, btn("Simulate without hardware", true, this::startSim));
        conn.addView(simRow);

        // ---- volume
        LinearLayout vol = card("VOLUME");
        LinearLayout volRow = row();
        volLabel = new TextView(this);
        volLabel.setText("Media volume");
        volLabel.setTextSize(16);
        volLabel.setTextColor(col(com.google.android.material.R.attr.colorOnSurface));
        volLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        volRow.addView(volLabel);
        volValue = new TextView(this);
        volValue.setTextSize(24);
        volValue.setTypeface(null, Typeface.BOLD);
        volValue.setTextColor(primary());
        volRow.addView(volValue);
        vol.addView(volRow);

        final AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        volBar = new Slider(this);
        volBar.setValueFrom(0);
        volBar.setValueTo(am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        volBar.setStepSize(1);
        volBar.setTickVisible(false);
        volBar.setValue(am.getStreamVolume(AudioManager.STREAM_MUSIC));
        volBar.addOnChangeListener((s, value, fromUser) -> {
            if (fromUser) am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(value), 0);
        });
        vol.addView(volBar);

        // ---- earbud hardware volume (0-127, stored in the buds themselves)
        LinearLayout earbudVol = card("EARBUD VOLUME (HARDWARE)");
        LinearLayout evRow = row();
        TextView evLabel = new TextView(this);
        evLabel.setText("Earbud level");
        evLabel.setTextSize(16);
        evLabel.setTextColor(col(com.google.android.material.R.attr.colorOnSurface));
        evLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        evRow.addView(evLabel);
        final TextView evValue = new TextView(this);
        evValue.setTextSize(24);
        evValue.setTypeface(null, Typeface.BOLD);
        evValue.setTextColor(primary());
        evValue.setText("64");
        evRow.addView(evValue);
        earbudVol.addView(evRow);

        final Slider evBar = new Slider(this);
        evBar.setValueFrom(0);
        evBar.setValueTo(127);
        evBar.setStepSize(1);
        evBar.setTickVisible(false);
        evBar.setValue(64);
        evBar.addOnChangeListener((s, value, fromUser) -> evValue.setText(String.valueOf(Math.round(value))));
        evBar.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override public void onStartTrackingTouch(Slider s) {}
            @Override public void onStopTrackingTouch(Slider s) {
                int v = Math.round(s.getValue());
                cmd(CMD_MUSIC, new byte[]{1, (byte) v});
                append("Earbud volume → " + v + "/127. Power-cycle the buds to see if it stuck.");
            }
        });
        earbudVol.addView(evBar);

        TextView evHint = new TextView(this);
        evHint.setText("This is the buds' own level (0-127), not the phone's. AVRCP absolute volume is not supported by this firmware, so the phone can never set it — this command is the only way. Released = sent.");
        evHint.setTextSize(12);
        evHint.setTextColor(muted());
        earbudVol.addView(evHint);

        // ---- noise control
        LinearLayout noise = card("NOISE CONTROL");
        final MaterialButton[] ancSeg = new MaterialButton[3];
        String[] ancNames = {"Off", "ANC", "Transparency"};
        LinearLayout ancRow = row();
        for (int i = 0; i < ancNames.length; i++) {
            final int mode = i;
            ancSeg[i] = btn(ancNames[i], false, () -> {
                markSegment(ancSeg, mode);
                cmd(CMD_ANC_MODE, new byte[]{(byte) mode});
            });
            ancSeg[i].setTextSize(12);
            ancSeg[i].setSingleLine(true);
            ancSeg[i].setPadding(0, dp(10), 0, dp(10));
            add(ancRow, ancSeg[i]);
        }
        noise.addView(ancRow);
        markSegment(ancSeg, -1);

        // ---- sound
        LinearLayout sound = card("SOUND");
        sound.addView(toggle("Gaming / low latency", false, on -> cmd(CMD_WORK_MODE, new byte[]{(byte) (on ? 1 : 0)})));
        sound.addView(toggle("LDAC high-res", false, on -> cmd(CMD_LDAC, new byte[]{(byte) (on ? 1 : 0)})));
        sound.addView(toggle("3D audio", false, on -> cmd(CMD_SOUND_3D, new byte[]{(byte) (on ? 1 : 0)})));
        sound.addView(toggle("Spatial audio", false, on -> cmd(CMD_SPACE, new byte[]{(byte) (on ? 1 : 0)})));
        sound.addView(toggle("Multipoint (two devices)", false, on -> cmd(CMD_ONE_DRAG_TWO, new byte[]{1, 1, (byte) (on ? 1 : 0)})));
        sound.addView(toggle("In-ear detection", true, on -> cmd(CMD_IN_EAR, new byte[]{(byte) (on ? 1 : 0)})));
        sound.addView(toggle("Auto answer calls", true, on -> cmd(CMD_AUTO_ANSWER, new byte[]{(byte) (on ? 1 : 0)})));
        sound.addView(toggle("LED indicator", true, on -> cmd(CMD_LED, new byte[]{(byte) (on ? 1 : 0)})));

        // ---- playback
        LinearLayout play = card("PLAYBACK");
        LinearLayout mediaTop = row();
        add(mediaTop, btn("Previous", true, () -> cmd(CMD_MUSIC, new byte[]{4})));
        add(mediaTop, btn("Next", true, () -> cmd(CMD_MUSIC, new byte[]{5})));
        play.addView(mediaTop);

        LinearLayout mediaBottom = row();
        mediaBottom.setPadding(0, dp(8), 0, 0);
        add(mediaBottom, btn("Play", false, () -> cmd(CMD_MUSIC, new byte[]{2})));
        add(mediaBottom, btn("Pause", true, () -> cmd(CMD_MUSIC, new byte[]{3})));
        play.addView(mediaBottom);

        // ---- find my earbuds
        LinearLayout findCard = card("FIND MY EARBUDS");
        LinearLayout find = row();
        add(find, btn("Ring", false, () -> cmd(CMD_FIND, new byte[]{1})));
        add(find, btn("Stop", true, () -> cmd(CMD_FIND, new byte[]{0})));
        findCard.addView(find);

        // ---- equalizer
        LinearLayout eq = card("EQUALIZER");
        ChipGroup chips = new ChipGroup(this);
        chips.setSingleSelection(true);
        String[] eqNames = {"Flat", "Pop", "Rock", "Jazz", "Classical", "Custom"};
        for (int i = 0; i < eqNames.length; i++) {
            final int preset = i;
            Chip c = new Chip(this);
            c.setText(eqNames[i]);
            c.setCheckable(true);
            c.setOnClickListener(v -> eqPreset(preset));
            chips.addView(c);
        }
        eq.addView(chips);

        // ---- advanced (collapsed by default)
        LinearLayout advCard = card("ADVANCED");
        final LinearLayout adv = new LinearLayout(this);
        adv.setOrientation(LinearLayout.VERTICAL);
        adv.setVisibility(View.GONE);

        LinearLayout gainRow = row();
        gainRow.setPadding(0, dp(10), 0, 0);
        add(gainRow, compact(btn("ANC −", true, () -> gain(CMD_ANC_GAIN, -1))));
        add(gainRow, compact(btn("ANC +", true, () -> gain(CMD_ANC_GAIN, +1))));
        adv.addView(gainRow);

        LinearLayout transpRow = row();
        transpRow.setPadding(0, dp(8), 0, 0);
        add(transpRow, compact(btn("Transparency −", true, () -> gain(CMD_TRANSPARENCY_GAIN, -1))));
        add(transpRow, compact(btn("Transparency +", true, () -> gain(CMD_TRANSPARENCY_GAIN, +1))));
        adv.addView(transpRow);

        LinearLayout langRow = row();
        langRow.setPadding(0, dp(8), 0, 0);
        add(langRow, compact(btn("Language EN", true, () -> cmd(CMD_LANGUAGE, new byte[]{0}))));
        add(langRow, compact(btn("中文", true, () -> cmd(CMD_LANGUAGE, new byte[]{1}))));
        adv.addView(langRow);

        LinearLayout offRow = row();
        offRow.setPadding(0, dp(8), 0, 0);
        add(offRow, compact(btn("Auto-off now", true, () -> cmd(CMD_AUTO_SHUTDOWN, new byte[]{0}))));
        add(offRow, compact(btn("Cancel auto-off", true, () -> cmd(CMD_AUTO_SHUTDOWN, new byte[]{(byte) -1}))));
        adv.addView(offRow);

        LinearLayout dangerRow = row();
        dangerRow.setPadding(0, dp(8), 0, 0);
        add(dangerRow, compact(btn("Clear pairings", true, () -> confirm("Clear pairing records on the earbuds?", () -> cmd(CMD_CLEAR_PAIR, new byte[0])))));
        add(dangerRow, compact(btn("Factory reset", true, () -> confirm("Factory reset the earbuds?", () -> cmd(CMD_FACTORY_RESET, new byte[0])))));
        adv.addView(dangerRow);

        // The buds' own volume is the only level that actually controls output.
        LinearLayout remapRow = row();
        remapRow.setPadding(0, dp(8), 0, 0);
        add(remapRow, compact(btn("Long-press: vol −", true, () ->
                confirm("Make LEFT long-press = volume down? This replaces what it does now.",
                        () -> cmd(CMD_KEY, new byte[]{7, 1, 6})))));
        add(remapRow, compact(btn("Long-press: vol +", true, () ->
                confirm("Make RIGHT long-press = volume up? This replaces what it does now.",
                        () -> cmd(CMD_KEY, new byte[]{8, 1, 5})))));
        adv.addView(remapRow);

        final MaterialButton advToggle = btn("Show advanced", true, null);
        advToggle.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        advToggle.setOnClickListener(v -> {
            boolean show = adv.getVisibility() != View.VISIBLE;
            adv.setVisibility(show ? View.VISIBLE : View.GONE);
            advToggle.setText(show ? "Hide advanced" : "Show advanced");
        });
        advCard.addView(advToggle);
        advCard.addView(adv);

        // ---- debug (collapsed by default)
        LinearLayout dbgCard = card("DEBUG");
        final LinearLayout dbg = new LinearLayout(this);
        dbg.setOrientation(LinearLayout.VERTICAL);
        dbg.setVisibility(View.GONE);

        LinearLayout refreshRow = row();
        add(refreshRow, btn("Refresh all info", true, this::requestAllInfo));
        dbg.addView(refreshRow);

        LinearLayout raw = row();
        raw.setPadding(0, dp(10), 0, 0);
        rawHex = new EditText(this);
        rawHex.setHint("Hex frame, e.g. 00 2C 01 00 01 01");
        rawHex.setTextSize(13);
        rawHex.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        raw.addView(rawHex);
        MaterialButton send = btn("Send", false, () -> sendRawHex(rawHex.getText().toString()));
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sendLp.setMarginStart(dp(8));
        send.setLayoutParams(sendLp);
        raw.addView(send);
        dbg.addView(raw);

        log = new TextView(this);
        log.setTextSize(11);
        log.setTextColor(muted());
        log.setTypeface(Typeface.MONOSPACE);
        log.setPadding(dp(12), dp(10), dp(12), dp(10));
        log.setBackground(rounded(col(com.google.android.material.R.attr.colorSurfaceVariant), dp(12)));
        ScrollView logScroll = new ScrollView(this);
        logScroll.addView(log);
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220));
        logLp.topMargin = dp(10);
        dbg.addView(logScroll, logLp);

        final MaterialButton dbgToggle = btn("Show debug log", true, null);
        dbgToggle.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dbgToggle.setOnClickListener(v -> {
            boolean show = dbg.getVisibility() != View.VISIBLE;
            dbg.setVisibility(show ? View.VISIBLE : View.GONE);
            dbgToggle.setText(show ? "Hide debug log" : "Show debug log");
        });
        dbgCard.addView(dbgToggle);
        dbgCard.addView(dbg);

        scroll.addView(root);
        setContentView(scroll);
        requestPerms();
        append("Ready. Connect the earbuds, or Simulate to try the controls without hardware.");
    }

    void eqPreset(int preset) {
        // EqRequest: [segmentNum=10][eqMode][10 gains]
        byte[] p = new byte[12];
        p[0] = 10;
        p[1] = (byte) (preset >= 5 ? 0 : preset);
        cmd(CMD_EQ(), p);
    }

    static byte CMD_EQ() { return 32; }

    void confirm(String msg, Runnable onYes) {
        new AlertDialog.Builder(this).setMessage(msg)
                .setPositiveButton("Yes", (d, w) -> onYes.run())
                .setNegativeButton("Cancel", null).show();
    }

    void gain(byte cmd, int delta) {
        int cur = (cmd == CMD_ANC_GAIN ? ancGain : transGain);
        if (cur < 0) cur = 5;
        int nv = Math.max(0, Math.min(10, cur + delta));
        cmd(cmd, new byte[]{(byte) nv});
    }

    // ---- permissions ----
    void requestPerms() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                p.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                p.add(Manifest.permission.BLUETOOTH_SCAN);
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!p.isEmpty()) requestPermissions(p.toArray(new String[0]), 1);
    }

    boolean hasPerms() {
        if (Build.VERSION.SDK_INT >= 31)
            return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        for (int i = 0; i < perms.length; i++)
            append("perm " + perms[i] + " = " + (res[i] == PackageManager.PERMISSION_GRANTED ? "granted" : "DENIED"));
    }

    // =====================================================================
    //  connection
    // =====================================================================
    void connectReal() {
        if (adapter == null) { append("No Bluetooth adapter (emulator has none — use Simulate)."); return; }
        if (!adapter.isEnabled()) { append("Bluetooth is OFF — enable it."); return; }
        if (!hasPerms()) { append("Grant Bluetooth permission first."); requestPerms(); return; }
        Set<BluetoothDevice> bonded;
        try { bonded = adapter.getBondedDevices(); } catch (Exception e) { append("getBondedDevices: " + e); return; }
        final List<BluetoothDevice> list = new ArrayList<>();
        for (BluetoothDevice d : bonded) list.add(d);
        if (list.isEmpty()) { append("No paired devices. Pair the earbuds in system Bluetooth first."); return; }
        // put likely earbuds first
        Collections.sort(list, (a, c) -> name(c).compareToIgnoreCase(name(a)));
        String[] labels = new String[list.size()];
        for (int i = 0; i < list.size(); i++) labels[i] = name(list.get(i)) + "\n" + list.get(i).getAddress();
        new AlertDialog.Builder(this).setTitle("Pick earbud (classic/BR-EDR)")
                .setItems(labels, (d, w) -> connectTo(list.get(w))).show();
    }

    String name(BluetoothDevice d) {
        try { String n = d.getName(); return n == null ? "(unknown)" : n; } catch (Exception e) { return "(?)"; }
    }

    void connectTo(final BluetoothDevice dev) {
        simulated = false;
        new Thread(() -> {
            try {
                append("Connecting SPP → " + dev.getAddress() + " (" + name(dev) + ")");
                try { adapter.cancelDiscovery(); } catch (Exception ignored) {}
                BluetoothSocket s = null;
                try {
                    s = dev.createInsecureRfcommSocketToServiceRecord(SPP_DEFAULT);
                    s.connect();
                    append("SPP connected (standard SPP UUID).");
                } catch (Exception e1) {
                    append("standard UUID failed (" + e1.getMessage() + ") — trying custom UUID…");
                    try { if (s != null) s.close(); } catch (Exception ignored) {}
                    s = dev.createInsecureRfcommSocketToServiceRecord(SPP_CUSTOM);
                    s.connect();
                    append("SPP connected (custom UUID).");
                }
                socket = s;
                out = s.getOutputStream();
                in = s.getInputStream();
                connected = true;
                txSeq = 0;
                resetRx();
                ui.post(() -> setStatus("Connected: " + dev.getAddress()));
                ui.post(this::requestAllInfo);
                readLoop();
            } catch (Exception e) {
                append("SPP connect FAILED: " + e);
                connected = false;
                ui.post(() -> setStatus("Disconnected"));
            }
        }, "spp-connect").start();
    }

    void readLoop() {
        byte[] buf = new byte[1024];
        try {
            while (connected) {
                int n = in.read(buf);
                if (n < 0) break;
                feedRx(Arrays.copyOf(buf, n));
            }
        } catch (Exception e) {
            if (connected) append("read ended: " + e);
        }
        connected = false;
        ui.post(() -> setStatus("Disconnected"));
        append("Link closed.");
    }

    void disconnect() {
        connected = false;
        simulated = false;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket = null; out = null; in = null;
        setStatus("Not connected");
        append("Disconnected.");
    }

    void startSim() {
        if (socket != null) { try { socket.close(); } catch (Exception ignored) {} socket = null; }
        simulated = true;
        connected = true;
        txSeq = 0;
        simSeq = 0;
        resetRx();
        resetSimState();
        setStatus("SIMULATED earbud (no real Bluetooth)");
        append("Simulated earbud online — commands are answered locally.");
        requestAllInfo();
    }

    void setStatus(String s) { ui.post(() -> status.setText("●  " + s)); }

    // =====================================================================
    //  TX
    // =====================================================================
    // Vendor SDK splits payloads at 15 bytes (RequestSplitter(15)); a single oversized frame is ignored.
    List<byte[]> buildFrames(byte cmd, byte cmdType, byte[] payload) {
        final int max = 15;
        int fragNum = payload.length == 0 ? 1 : (payload.length + max - 1) / max;
        List<byte[]> frames = new ArrayList<>();
        for (int i = 0; i < fragNum; i++) {
            int off = i * max;
            int len = Math.min(max, payload.length - off);
            byte[] f = new byte[5 + len];
            f[0] = (byte) (txSeq & 15);
            f[1] = cmd;
            f[2] = cmdType;
            f[3] = (byte) ((((fragNum - 1) << 4) & 0xF0) | (i & 15));
            f[4] = (byte) len;
            if (len > 0) System.arraycopy(payload, off, f, 5, len);
            txSeq = (byte) ((txSeq + 1) & 15);
            frames.add(f);
        }
        return frames;
    }

    void cmd(byte c, byte[] payload) {
        if (!connected) { append("Not connected — use Simulate or Connect."); return; }
        List<byte[]> frames = buildFrames(c, (byte) 1, payload);
        if (simulated) {
            for (byte[] f : frames) append(">> " + hex(f));
            final byte cc = c; final byte[] pp = payload;
            ui.postDelayed(() -> simHandle(cc, pp), 100);
            return;
        }
        for (byte[] f : frames) {
            append(">> " + hex(f));
            try { out.write(f); out.flush(); }
            catch (Exception e) { append("write failed: " + e); disconnect(); return; }
        }
    }

    void requestAllInfo() {
        // The vendor app asks for one info type per request (new DeviceInfoRequest((byte) type)),
        // spaced out, rather than batching everything into one payload.
        byte[] types = {INFO_POWER, INFO_FIRMWARE, INFO_BT_NAME, INFO_EQ, INFO_KEY, INFO_VOLUME,
                INFO_PLAY_STATE, INFO_WORK_MODE, INFO_IN_EAR, INFO_LANGUAGE, INFO_AUTO_ANSWER,
                INFO_ANC_MODE, INFO_IS_TWS, INFO_TWS_CONNECTED, INFO_LED, INFO_ANC_GAIN,
                INFO_TRANSPARENCY_GAIN, INFO_ANC_GAIN_NUM, INFO_TRANSPARENCY_GAIN_NUM,
                INFO_MAIN_SIDE, INFO_PRODUCT_COLOR, INFO_SOUND_3D, INFO_ONE_DRAG_TWO,
                INFO_SPACE, INFO_LDAC, INFO_CAPABILITIES};
        for (int i = 0; i < types.length; i++) {
            final byte t = types[i];
            ui.postDelayed(() -> cmd(CMD_DEVICE_INFO, new byte[]{t, 0}), i * 150L);
        }
    }

    void sendRawHex(String s) {
        byte[] f = fromHex(s);
        if (f == null || f.length == 0) { append("Bad hex."); return; }
        if (!connected) { append("Not connected."); return; }
        append(">> raw " + hex(f));
        if (simulated) { feedRx(f); return; }
        try { out.write(f); out.flush(); } catch (Exception e) { append("write failed: " + e); }
    }

    // =====================================================================
    //  RX
    // =====================================================================
    void resetRx() {
        synchronized (rxBuf) { rxBuf.reset(); }
        mTotal = -1;
        mPayload.reset();
    }

    void feedRx(byte[] data) {
        byte[] all;
        synchronized (rxBuf) {
            rxBuf.write(data, 0, data.length);
            all = rxBuf.toByteArray();
        }
        int i = 0;
        while (all.length - i >= 5) {
            int len = all[i + 4] & 0xFF;
            if (all.length - i < 5 + len) break;
            byte[] payload = Arrays.copyOfRange(all, i + 5, i + 5 + len);
            handleFrame(all[i], all[i + 1], all[i + 2], all[i + 3], payload);
            i += 5 + len;
        }
        synchronized (rxBuf) {
            rxBuf.reset();
            rxBuf.write(all, i, all.length - i);
        }
    }

    void handleFrame(byte seqB, byte c, byte type, byte frameInfo, byte[] payload) {
        int idx = frameInfo & 15;
        int total = ((frameInfo >> 4) & 15) + 1;
        if (mTotal == -1) { mTotal = total; mCmd = c; mType = type; mPayload.reset(); }
        mPayload.write(payload, 0, payload.length);
        if (idx != total - 1) return;
        byte[] full = mPayload.toByteArray();
        byte cc = mCmd, tt = mType;
        mTotal = -1;
        mPayload.reset();
        onMessage(tt, cc, full);
    }

    void onMessage(byte type, byte c, byte[] payload) {
        String kind = type == 3 ? "notify" : type == 2 ? "resp" : ("type" + type);
        append("<< [" + kind + "] cmd=" + (c & 0xFF) + "  " + hex(payload));
        if (c == CMD_DEVICE_INFO || c == CMD_NOTIFY) parseTlv(payload);
        else onEcho(c, payload);
    }

    void onEcho(byte c, byte[] payload) {
        // For simple on/off commands, mirror state so the UI stays in sync.
        int v = payload.length >= 1 ? (payload[0] & 0xFF) : -1;
        if (c == CMD_ANC_MODE && v >= 0) ancMode = v;
        else if (c == CMD_WORK_MODE && v >= 0) workMode = v;
        else if (c == CMD_LDAC && v >= 0) ldac = v;
        else if (c == CMD_SOUND_3D && v >= 0) sound3d = v;
        else if (c == CMD_SPACE && v >= 0) space = v;
        else if (c == CMD_IN_EAR && v >= 0) inEar = v;
        else if (c == CMD_AUTO_ANSWER && v >= 0) autoAnswer = v;
        else if (c == CMD_LED && v >= 0) led = v;
        else if (c == CMD_ANC_GAIN && v >= 0) ancGain = v;
        else if (c == CMD_TRANSPARENCY_GAIN && v >= 0) transGain = v;
        else if (c == CMD_ONE_DRAG_TWO && payload.length >= 3) oneDragTwo = payload[2] & 0xFF;
        updateInfo();
    }

    void parseTlv(byte[] d) {
        int i = 0;
        while (i + 2 <= d.length) {
            int t = d[i] & 0xFF;
            int l = d[i + 1] & 0xFF;
            if (i + 2 + l > d.length) break;
            byte[] v = Arrays.copyOfRange(d, i + 2, i + 2 + l);
            applyInfo(t, v);
            append("   " + infoName(t) + " = " + decodeInfo(t, v) + "   [" + hex(v) + "]");
            i += 2 + l;
        }
        updateInfo();
    }

    void applyInfo(int t, byte[] v) {
        if (t == INFO_POWER && v.length >= 1) {
            left = v[0] & 127; leftChg = (v[0] & 128) != 0;
            if (v.length >= 2) { right = v[1] & 127; rightChg = (v[1] & 128) != 0; }
            if (v.length >= 3) { kase = v[2] & 127; caseChg = (v[2] & 128) != 0; }
        } else if (t == INFO_ANC_MODE && v.length >= 1) ancMode = v[0] & 0xFF;
        else if (t == INFO_WORK_MODE && v.length >= 1) workMode = v[0] & 0xFF;
        else if (t == INFO_LDAC && v.length >= 1) ldac = v[0] & 0xFF;
        else if (t == INFO_SOUND_3D && v.length >= 1) sound3d = v[0] & 0xFF;
        else if (t == INFO_SPACE && v.length >= 1) space = v[0] & 0xFF;
        else if (t == INFO_IN_EAR && v.length >= 1) inEar = v[0] & 0xFF;
        else if (t == INFO_AUTO_ANSWER && v.length >= 1) autoAnswer = v[0] & 0xFF;
        else if (t == INFO_LED && v.length >= 1) led = v[0] & 0xFF;
        else if (t == INFO_ANC_GAIN && v.length >= 1) ancGain = v[0] & 0xFF;
        else if (t == INFO_TRANSPARENCY_GAIN && v.length >= 1) transGain = v[0] & 0xFF;
        else if (t == INFO_ANC_GAIN_NUM && v.length >= 1) ancGainNum = v[0] & 0xFF;
        else if (t == INFO_TRANSPARENCY_GAIN_NUM && v.length >= 1) transGainNum = v[0] & 0xFF;
        else if (t == INFO_VOLUME && v.length >= 1) volume = v[0] & 0xFF;
        else if (t == INFO_IS_TWS && v.length >= 1) isTws = v[0] & 0xFF;
        else if (t == INFO_TWS_CONNECTED && v.length >= 1) twsConnected = v[0] & 0xFF;
        else if (t == INFO_ONE_DRAG_TWO && v.length >= 1) oneDragTwo = v[0] & 0xFF;
        else if (t == INFO_BT_NAME) btName = new String(v);
        else if (t == INFO_FIRMWARE) fw = hex(v);
    }

    String infoName(int t) {
        switch (t) {
            case 1: return "Power";
            case 2: return "Firmware";
            case 3: return "BT name";
            case 4: return "EQ";
            case 5: return "Key settings";
            case 6: return "Volume";
            case 7: return "Play state";
            case 8: return "Work mode";
            case 9: return "In-ear";
            case 10: return "Language";
            case 11: return "Auto-answer";
            case 12: return "ANC mode";
            case 13: return "Is TWS";
            case 14: return "TWS connected";
            case 15: return "LED";
            case 16: return "FW checksum";
            case 17: return "ANC gain";
            case 18: return "Transparency gain";
            case 19: return "ANC gain num";
            case 20: return "Transparency gain num";
            case 21: return "All EQ";
            case 22: return "Main side";
            case 23: return "Product color";
            case 24: return "3D effect";
            case 25: return "One-drag-two";
            case 48: return "Case key";
            case 49: return "Space audio";
            case 50: return "LDAC";
            case 0xFE: return "Capabilities";
            case 0xFF: return "Max packet";
            default: return "Info#" + t;
        }
    }

    String decodeInfo(int t, byte[] v) {
        if (t == INFO_POWER && v.length >= 1) {
            StringBuilder sb = new StringBuilder();
            sb.append("L ").append(v[0] & 127).append((v[0] & 128) != 0 ? "⚡" : "");
            if (v.length >= 2) sb.append("  R ").append(v[1] & 127).append((v[1] & 128) != 0 ? "⚡" : "");
            if (v.length >= 3) sb.append("  Case ").append(v[2] & 127).append((v[2] & 128) != 0 ? "⚡" : "");
            return sb.toString();
        }
        if (t == INFO_BT_NAME) return new String(v);
        if (t == INFO_ANC_MODE && v.length >= 1) return v[0] == 0 ? "Off" : v[0] == 1 ? "On" : v[0] == 2 ? "Transparency" : (v[0] & 0xFF) + "";
        if (t == INFO_WORK_MODE && v.length >= 1) return v[0] == 0 ? "Normal" : v[0] == 1 ? "Gaming" : (v[0] & 0xFF) + "";
        if (v.length == 1) return (v[0] & 0xFF) + "";
        return hex(v);
    }

    void updateInfo() {
        StringBuilder sb = new StringBuilder();
        if (left >= 0) {
            sb.append("Battery  L ").append(left).append("%").append(leftChg ? "⚡" : "");
            if (right >= 0) sb.append("  R ").append(right).append("%").append(rightChg ? "⚡" : "");
            if (kase >= 0) sb.append("  Case ").append(kase).append("%").append(caseChg ? "⚡" : "");
            sb.append("\n");
        }
        if (ancMode >= 0) sb.append("ANC: ").append(ancMode == 0 ? "Off" : ancMode == 1 ? "On" : "Transparency").append("  ");
        if (workMode >= 0) sb.append("Work: ").append(workMode == 0 ? "Normal" : "Gaming").append("  ");
        if (ldac >= 0) sb.append("LDAC: ").append(ldac == 1 ? "on" : "off").append("  ");
        if (sound3d >= 0) sb.append("3D: ").append(sound3d == 1 ? "on" : "off").append("  ");
        if (space >= 0) sb.append("Space: ").append(space == 1 ? "on" : "off").append("  ");
        if (oneDragTwo >= 0) sb.append("Multipoint: ").append(oneDragTwo == 1 ? "on" : "off").append("  ");
        if (volume >= 0) sb.append("Vol: ").append(volume).append("  ");
        if (btName != null) sb.append("\n").append(btName).append(fw != null ? "  fw " + fw : "");
        ui.post(() -> info.setText(sb.length() == 0 ? "—" : sb.toString()));
    }

    // =====================================================================
    //  simulated earbud
    // =====================================================================
    byte simSeq = 0;
    int sAnc, sWork, sAncGain, sTransGain, sVolume;
    boolean sLdac, s3d, sSpace, sInEar, sAutoAnswer, sLed, sOdd;
    int sLeft, sRight, sCase;

    void resetSimState() {
        sAnc = 0; sWork = 0; sAncGain = 5; sTransGain = 5; sVolume = 8;
        sLdac = false; s3d = false; sSpace = false; sInEar = true; sAutoAnswer = true; sLed = true; sOdd = false;
        sLeft = 80; sRight = 75; sCase = 60;
        left = right = kase = -1; ancMode = workMode = ldac = sound3d = space = oneDragTwo = inEar = autoAnswer = led = volume = -1;
        btName = null; fw = null;
    }

    void simHandle(byte c, byte[] payload) {
        byte[] resp;
        if (c == CMD_DEVICE_INFO) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            int i = 0;
            while (i + 2 <= payload.length) {
                int t = payload[i] & 0xFF;
                byte[] v = simInfoValue(t);
                o.write(t); o.write(v.length); o.write(v, 0, v.length);
                i += 2;
            }
            resp = o.toByteArray();
        } else {
            applySimCommand(c, payload);
            resp = payload;
        }
        sendSimFrame(c, (byte) 2, resp);
    }

    void applySimCommand(byte c, byte[] p) {
        int v = p.length >= 1 ? (p[0] & 0xFF) : -1;
        if (c == CMD_ANC_MODE && v >= 0) sAnc = v;
        else if (c == CMD_WORK_MODE && v >= 0) sWork = v;
        else if (c == CMD_LDAC && v >= 0) sLdac = v == 1;
        else if (c == CMD_SOUND_3D && v >= 0) s3d = v == 1;
        else if (c == CMD_SPACE && v >= 0) sSpace = v == 1;
        else if (c == CMD_IN_EAR && v >= 0) sInEar = v == 1;
        else if (c == CMD_AUTO_ANSWER && v >= 0) sAutoAnswer = v == 1;
        else if (c == CMD_LED && v >= 0) sLed = v == 1;
        else if (c == CMD_ANC_GAIN && v >= 0) sAncGain = v;
        else if (c == CMD_TRANSPARENCY_GAIN && v >= 0) sTransGain = v;
        else if (c == CMD_ONE_DRAG_TWO && p.length >= 3) sOdd = p[2] == 1;
        else if (c == CMD_MUSIC && p.length >= 2 && (p[0] & 0xFF) == 1) sVolume = p[1] & 0xFF;
        else if (c == CMD_CLEAR_PAIR || c == CMD_FACTORY_RESET) append("   (sim) earbuds " + (c == CMD_FACTORY_RESET ? "factory reset" : "pairings cleared"));
        else if (c == CMD_FIND) append("   (sim) ring " + (v == 1 ? "ON" : "OFF"));
    }

    byte[] simInfoValue(int t) {
        switch (t) {
            case 1: return new byte[]{(byte) (sLeft | (leftChg ? 128 : 0)), (byte) (sRight | (rightChg ? 128 : 0)), (byte) (sCase | (caseChg ? 128 : 0))};
            case 2: return new byte[]{1, 2, 3};
            case 3: return "Ultima Prime Aura".getBytes();
            case 6: return new byte[]{(byte) sVolume};
            case 7: return new byte[]{0};
            case 8: return new byte[]{(byte) sWork};
            case 9: return new byte[]{(byte) (sInEar ? 1 : 0)};
            case 10: return new byte[]{0};
            case 11: return new byte[]{(byte) (sAutoAnswer ? 1 : 0)};
            case 12: return new byte[]{(byte) sAnc};
            case 13: return new byte[]{1};
            case 14: return new byte[]{1};
            case 15: return new byte[]{(byte) (sLed ? 1 : 0)};
            case 16: return new byte[]{(byte) 0xAB, (byte) 0xCD};
            case 17: return new byte[]{(byte) sAncGain};
            case 18: return new byte[]{(byte) sTransGain};
            case 19: return new byte[]{10};
            case 20: return new byte[]{10};
            case 21: return new byte[22];
            case 22: return new byte[]{0};
            case 23: return new byte[]{0};
            case 24: return new byte[]{(byte) (s3d ? 1 : 0)};
            case 25: return new byte[]{(byte) (sOdd ? 1 : 0)};
            case 48: return new byte[0];
            case 49: return new byte[]{(byte) (sSpace ? 1 : 0)};
            case 50: return new byte[]{(byte) (sLdac ? 1 : 0)};
            case 0xFE: return new byte[]{(byte) 0xFF, (byte) 0xFF};
            default: return new byte[]{0};
        }
    }

    void sendSimFrame(byte c, byte type, byte[] payload) {
        byte[] f = new byte[5 + payload.length];
        f[0] = (byte) (simSeq & 15);
        f[1] = c;
        f[2] = type;
        f[3] = 0;
        f[4] = (byte) payload.length;
        System.arraycopy(payload, 0, f, 5, payload.length);
        simSeq = (byte) ((simSeq + 1) & 15);
        feedRx(f);
    }

    // =====================================================================
    static String hex(byte[] b) {
        if (b == null) return "(null)";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X ", x));
        return sb.toString().trim();
    }

    static byte[] fromHex(String s) {
        s = s.replaceAll("[^0-9A-Fa-f]", "");
        if (s.length() % 2 != 0) return null;
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    void append(String s) {
        android.util.Log.d("UltimaControl", s);
        ui.post(() -> log.append(s + "\n"));
    }
}
