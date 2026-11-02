package com.gathin.tvhome;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Wi-Fi pill's panel (wifi_panel): the network in use, the strongest networks nearby, and a row on to the
 * TV's Wi-Fi settings, in the Inputs dropdown's glass under the pill. A saved network joins with one press, an
 * open one at once, a WPA one after its password; WEP and enterprise networks go to the system page. Android 9
 * and older only: from Android 10 apps can no longer add or switch networks, so there the pill keeps opening
 * Wi-Fi settings. Nearby networks need location mode on, as Android 9 hides scan results without it.
 */
@SuppressWarnings("deprecation")
public final class WifiPanelActivity extends Activity {
    /** The pill's right edge and bottom on screen, in pixels, so the panel hangs under it. */
    static final String EXTRA_RIGHT = "right", EXTRA_BOTTOM = "bottom";
    private static final int MAX_NEARBY = 6;

    /** The Wi-Fi panel where it can work (wifi_panel, Android 9 and older), otherwise the TV's Wi-Fi settings. */
    static Intent intent(Context context) {
        if (context.getResources().getBoolean(R.bool.wifi_panel) && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return new Intent(context, WifiPanelActivity.class);
        }
        return SystemSettings.intent(context, Settings.ACTION_WIFI_SETTINGS);
    }

    private WifiManager wifi;
    private LinearLayout rows;
    private View passwordBox;
    private EditText password;
    private ScanResult pending;   // the network whose password is being typed

    private final BroadcastReceiver refresh = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (pending == null) fill();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_wifi_panel);
        findViewById(R.id.scrim).setOnClickListener(v -> finish());
        View panel = findViewById(R.id.panel);
        int right = getIntent().getIntExtra(EXTRA_RIGHT, -1);
        int bottom = getIntent().getIntExtra(EXTRA_BOTTOM, -1);
        if (right > 0 && bottom > 0) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
            lp.setMarginEnd(Math.max(0, getResources().getDisplayMetrics().widthPixels - right));
            lp.topMargin = bottom + Math.round(8 * getResources().getDisplayMetrics().density);
            panel.setLayoutParams(lp);
        }
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        rows = findViewById(R.id.rows);
        passwordBox = findViewById(R.id.passwordBox);
        password = findViewById(R.id.password);
        password.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_UP;
            if (actionId == EditorInfo.IME_ACTION_DONE || enter) joinWithPassword();
            return true;
        });

        IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(refresh, filter);
        if (wifi != null) wifi.startScan();
        fill();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(refresh);
    }

    /** Anything else taking the screen (Home, an app, a screensaver) closes the panel. */
    @Override
    protected void onPause() {
        super.onPause();
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    public void onBackPressed() {
        if (pending != null) {
            pending = null;
            passwordBox.setVisibility(View.GONE);
            rows.setVisibility(View.VISIBLE);
            fill();
            return;
        }
        super.onBackPressed();
    }

    /** Rebuilds the rows, keeping the focus on the same row when a scan comes in mid-navigation. */
    private void fill() {
        int focused = Math.max(0, rows.indexOfChild(rows.getFocusedChild()));
        rows.removeAllViews();
        Intent settings = SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS);

        WifiInfo info = wifi != null ? wifi.getConnectionInfo() : null;
        String current = info != null && info.getNetworkId() != -1 ? unquote(info.getSSID()) : null;
        if (current != null) {
            String band = info.getFrequency() > 4900 ? "5 GHz" : "2.4 GHz";
            addRow(R.drawable.ic_wifi, current, getString(R.string.wifi_connected) + " · " + band, true,
                    v -> open(settings));
        } else {
            addRow(R.drawable.ic_wifi, getString(R.string.wifi_not_connected), null, false, v -> open(settings));
        }

        for (ScanResult r : nearby(current)) {
            String status = isSaved(r) ? getString(R.string.wifi_saved) : bars(r.level);
            addRow(isOpen(r) ? R.drawable.ic_wifi : R.drawable.ic_lock, r.SSID, status, false, v -> join(r));
        }
        addRow(R.drawable.ic_settings, getString(R.string.wifi_all_settings), null, false, v -> open(settings));

        View focus = rows.getChildAt(Math.min(focused, rows.getChildCount() - 1));
        if (focus != null) focus.requestFocus();
    }

    /** The strongest few networks, one row per name, without the one already in use. */
    private List<ScanResult> nearby(String current) {
        List<ScanResult> out = new ArrayList<>();
        if (wifi == null || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return out;
        Map<String, ScanResult> best = new HashMap<>();
        for (ScanResult r : wifi.getScanResults()) {
            if (r.SSID == null || r.SSID.isEmpty() || r.SSID.equals(current)) continue;
            ScanResult seen = best.get(r.SSID);
            if (seen == null || r.level > seen.level) best.put(r.SSID, r);
        }
        out.addAll(best.values());
        out.sort((a, b) -> Integer.compare(b.level, a.level));
        return out.size() > MAX_NEARBY ? out.subList(0, MAX_NEARBY) : out;
    }

    private void join(ScanResult r) {
        WifiConfiguration saved = savedConfig(r.SSID);
        if (saved != null) {
            connect(saved.networkId, r.SSID);
        } else if (isOpen(r)) {
            WifiConfiguration c = new WifiConfiguration();
            c.SSID = quote(r.SSID);
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            connect(wifi.addNetwork(c), r.SSID);
        } else if (r.capabilities.contains("PSK") && !r.capabilities.contains("EAP")) {
            pending = r;
            ((TextView) findViewById(R.id.passwordTitle)).setText(r.SSID);
            rows.setVisibility(View.GONE);
            passwordBox.setVisibility(View.VISIBLE);
            password.setText("");
            password.requestFocus();
        } else {
            open(SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS));
        }
    }

    private void joinWithPassword() {
        String pw = password.getText().toString();
        if (pending == null) return;
        if (pw.length() < 8) {
            Toast.makeText(this, R.string.wifi_password_short, Toast.LENGTH_SHORT).show();
            return;
        }
        WifiConfiguration c = new WifiConfiguration();
        c.SSID = quote(pending.SSID);
        c.preSharedKey = quote(pw);
        connect(wifi.addNetwork(c), pending.SSID);
    }

    private void connect(int networkId, String ssid) {
        if (networkId == -1 || !wifi.enableNetwork(networkId, true)) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        wifi.reconnect();
        Toast.makeText(this, getString(R.string.wifi_connecting, ssid), Toast.LENGTH_SHORT).show();
        finish();
    }

    private WifiConfiguration savedConfig(String ssid) {
        if (wifi == null || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return null;
        List<WifiConfiguration> configs = wifi.getConfiguredNetworks();
        if (configs == null) return null;
        for (WifiConfiguration c : configs) {
            if (ssid.equals(unquote(c.SSID))) return c;
        }
        return null;
    }

    private boolean isSaved(ScanResult r) {
        return savedConfig(r.SSID) != null;
    }

    private static boolean isOpen(ScanResult r) {
        String caps = r.capabilities;
        return !(caps.contains("WEP") || caps.contains("PSK") || caps.contains("EAP") || caps.contains("SAE"));
    }

    private static String bars(int rssi) {
        int level = WifiManager.calculateSignalLevel(rssi, 4);
        return "▂▄▆█".substring(0, level + 1);
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }

    /** WifiInfo and WifiConfiguration quote the name; "<unknown ssid>" means location mode hides it. */
    private static String unquote(String s) {
        if (s == null || s.equals(WifiManager.UNKNOWN_SSID)) return null;
        return s.length() > 1 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    private void addRow(int icon, String title, String status, boolean dot, View.OnClickListener click) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.dot).setVisibility(dot ? View.VISIBLE : View.GONE);
        TextView hint = row.findViewById(R.id.status);
        if (status != null) hint.setText(status);
        else hint.setVisibility(View.GONE);
        row.setOnClickListener(click);
        rows.addView(row);
    }

    private void open(Intent target) {
        try {
            startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
