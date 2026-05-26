package com.example.skiimu;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.provider.MediaStore;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {
    private static final UUID SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID RX_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID TX_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int REQUEST_PERMISSIONS = 42;

    private WebView webView;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner scanner;
    private final Map<String, BoardConnection> boards = new HashMap<>();
    private final Map<String, ScanCallback> scanCallbacks = new HashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoConnectRunnable = this::autoConnectBoards;

    private File currentLogFile;
    private BufferedWriter currentLogWriter;
    private boolean logging;
    private boolean destroying;
    private int pendingLogLines;
    private long lastLogFlushMs;
    private long currentStartedAtMs;
    private String currentSessionName = "未命名记录";

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        destroying = false;

        boards.put("W", new BoardConnection("W", "腰部 IMU"));
        boards.put("L", new BoardConnection("L", "左小腿 IMU"));
        boards.put("R", new BoardConnection("R", "右小腿 IMU"));

        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = manager == null ? null : manager.getAdapter();
        scanner = bluetoothAdapter == null ? null : bluetoothAdapter.getBluetoothLeScanner();

        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                scheduleAutoConnect(700);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm())
                        .setOnCancelListener(dialog -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("确定", (dialog, which) -> result.confirm())
                        .setNegativeButton("取消", (dialog, which) -> result.cancel())
                        .setOnCancelListener(dialog -> result.cancel())
                        .show();
                return true;
            }
        });
        webView.addJavascriptInterface(new AndroidBridge(), "SkiAndroid");
        webView.loadUrl("file:///android_asset/index.html");

        if (ensurePermissions()) {
            scheduleAutoConnect(1200);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroying = true;
        closeLogWriter();
        mainHandler.removeCallbacks(autoConnectRunnable);
        for (BoardConnection board : boards.values()) {
            disconnectBoard(board.id);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) return;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                toast("需要蓝牙权限才能自动连接 IMU");
                return;
            }
        }
        scheduleAutoConnect(400);
    }

    private boolean ensurePermissions() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQUEST_PERMISSIONS);
            return false;
        }
        return true;
    }

    private void toast(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
    }

    private void sendStatus(String text) {
        eval("window.AndroidBle && window.AndroidBle.handleNativeStatus(" + JSONObject.quote(text) + ")");
    }

    private void sendConnection(String side, boolean connected) {
        eval("window.AndroidBle && window.AndroidBle.handleNativeConnection("
                + JSONObject.quote(side) + "," + connected + ")");
    }

    private void sendLine(String side, String json) {
        eval("window.AndroidBle && window.AndroidBle.handleNativeLine("
                + JSONObject.quote(side) + "," + JSONObject.quote(json) + ")");
    }

    private void eval(String script) {
        runOnUiThread(() -> webView.evaluateJavascript(script, null));
    }

    private void scheduleAutoConnect(long delayMs) {
        if (destroying) return;
        mainHandler.removeCallbacks(autoConnectRunnable);
        mainHandler.postDelayed(autoConnectRunnable, delayMs);
    }

    private void autoConnectBoards() {
        if (destroying) return;
        if (!ensurePermissions()) return;
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            sendStatus("请先打开手机蓝牙，打开后会自动连接 IMU");
            return;
        }
        if (scanner == null) scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            sendStatus("无法启动 BLE 扫描");
            return;
        }

        boolean started = false;
        for (String side : new String[]{"L", "R", "W"}) {
            BoardConnection board = boards.get(side);
            if (board == null || board.gatt != null || scanCallbacks.containsKey(side)) continue;
            connectBoard(side);
            started = true;
        }

        sendStatus(started
                ? "正在自动连接左右腿和腰部 IMU..."
                : "IMU 已连接或正在扫描中");
    }

    @SuppressLint("MissingPermission")
    private void connectBoard(String side) {
        if (!ensurePermissions()) return;
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            toast("请先打开手机蓝牙");
            return;
        }
        if (scanner == null) scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            toast("无法启动 BLE 扫描");
            return;
        }

        BoardConnection board = boards.get(side);
        if (board == null) return;
        if (board.gatt != null) {
            disconnectBoard(side);
            return;
        }

        stopScan(side);
        sendStatus("正在扫描 " + board.label + "...");

        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(SERVICE_UUID))
                .build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        ScanCallback callback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                String name = "";
                try {
                    name = device.getName();
                } catch (SecurityException ignored) {
                }
                if ((name == null || name.isEmpty()) && result.getScanRecord() != null) {
                    name = result.getScanRecord().getDeviceName();
                }
                String expectedSuffix = "SKI-IMU-" + side;
                if (name == null || !name.endsWith(expectedSuffix)) {
                    return;
                }

                stopScan(side);
                sendStatus("正在连接 " + (name == null || name.isEmpty() ? board.label : name) + "...");
                board.gatt = device.connectGatt(MainActivity.this, false, board.callback);
            }

            @Override
            public void onScanFailed(int errorCode) {
                sendStatus("BLE 扫描失败：" + errorCode);
            }
        };

        scanCallbacks.put(side, callback);
        List<ScanFilter> filters = new ArrayList<>();
        filters.add(filter);
        scanner.startScan(filters, settings, callback);
    }

    @SuppressLint("MissingPermission")
    private void stopScan(String side) {
        ScanCallback callback = scanCallbacks.remove(side);
        if (callback != null && scanner != null) {
            scanner.stopScan(callback);
        }
    }

    @SuppressLint("MissingPermission")
    private void disconnectBoard(String side) {
        stopScan(side);
        BoardConnection board = boards.get(side);
        if (board == null) return;
        if (board.gatt != null) {
            board.gatt.disconnect();
            board.gatt.close();
        }
        board.clear();
        sendConnection(side, false);
    }

    @SuppressLint("MissingPermission")
    private void sendCalibration() {
        boolean sent = false;
        byte[] payload = "CAL\n".getBytes(StandardCharsets.UTF_8);
        for (BoardConnection board : boards.values()) {
            if (board.rxChar == null || board.gatt == null) continue;
            board.rxChar.setValue(payload);
            board.gatt.writeCharacteristic(board.rxChar);
            sent = true;
        }
        if (sent) {
            sendStatus("已发送静止校准指令");
        } else {
            toast("请先连接至少一个 IMU 节点");
        }
    }

    private String normalizeSessionName(String raw) {
        if (raw == null) return "未命名记录";
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? "未命名记录" : trimmed;
    }

    private String sanitizeFilePart(String raw) {
        String value = normalizeSessionName(raw)
                .replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_")
                .replaceAll("\\s+", "_");
        if (value.length() > 32) {
            value = value.substring(0, 32);
        }
        return value.isEmpty() ? "session" : value;
    }

    private File logsDir() {
        return new File(getFilesDir(), "logs");
    }

    private File safeLogFile(String name) {
        if (name == null || name.contains("/") || name.contains("\\") || name.contains("..")) return null;
        return new File(logsDir(), name);
    }

    private synchronized void startLog(String sessionName) {
        closeLogWriter();
        try {
            File dir = logsDir();
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("无法创建日志目录");
            }
            currentStartedAtMs = System.currentTimeMillis();
            currentSessionName = normalizeSessionName(sessionName);
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(currentStartedAtMs));
            currentLogFile = new File(dir, String.format(
                    Locale.US,
                    "ski_imu_%s_%s.jsonl",
                    stamp,
                    sanitizeFilePart(currentSessionName)
            ));
            currentLogWriter = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(currentLogFile, false),
                    StandardCharsets.UTF_8
            ));
            logging = true;
            pendingLogLines = 0;
            lastLogFlushMs = System.currentTimeMillis();
            sendStatus("正在记录到本机：" + currentLogFile.getName());
        } catch (Exception e) {
            logging = false;
            toast("创建日志失败：" + e.getMessage());
        }
    }

    private synchronized void appendLogLine(String jsonLine) {
        if (!logging || currentLogWriter == null) return;
        try {
            currentLogWriter.write(jsonLine);
            currentLogWriter.newLine();
            pendingLogLines++;
            long now = System.currentTimeMillis();
            if (pendingLogLines >= 25 || now - lastLogFlushMs >= 1000) {
                currentLogWriter.flush();
                pendingLogLines = 0;
                lastLogFlushMs = now;
            }
        } catch (Exception e) {
            toast("写入日志失败：" + e.getMessage());
        }
    }

    private synchronized void stopLog() {
        logging = false;
        closeLogWriter();
        if (currentLogFile != null) {
            sendStatus("记录已保存：" + currentLogFile.getName());
        }
    }

    private synchronized void closeLogWriter() {
        if (currentLogWriter == null) return;
        try {
            currentLogWriter.flush();
            currentLogWriter.close();
        } catch (Exception ignored) {
        }
        currentLogWriter = null;
    }

    private synchronized void exportCurrentLog(String fallbackContent) {
        if (logging) stopLog();
        File source = currentLogFile;
        if ((source == null || !source.exists() || source.length() == 0) && fallbackContent != null) {
            try {
                File dir = logsDir();
                if (!dir.exists()) dir.mkdirs();
                long now = System.currentTimeMillis();
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(now));
                source = new File(dir, String.format(Locale.US, "ski_imu_%s_export.jsonl", stamp));
                try (FileOutputStream out = new FileOutputStream(source)) {
                    out.write(fallbackContent.getBytes(StandardCharsets.UTF_8));
                }
                currentLogFile = source;
            } catch (Exception e) {
                toast("准备导出失败：" + e.getMessage());
                return;
            }
        }
        if (source == null || !source.exists()) {
            toast("没有可导出的日志");
            return;
        }

        exportTrainingPackage(source);
    }

    private synchronized String listLogs() {
        JSONArray array = new JSONArray();
        File dir = logsDir();
        File[] files = dir.listFiles((file, name) -> name.endsWith(".jsonl") || name.endsWith(".json"));
        if (files == null) return array.toString();
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (File file : files) {
            array.put(readLogSummary(file));
        }
        return array.toString();
    }

    private JSONObject readLogSummary(File file) {
        JSONObject obj = new JSONObject();
        long startedAt = file.lastModified();
        String sessionName = file.getName();
        int rawCount = 0;
        int featureCount = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file),
                StandardCharsets.UTF_8
        ))) {
            String line;
            int inspected = 0;
            while ((line = reader.readLine()) != null) {
                inspected++;
                if (line.contains("\"kind\":\"raw\"")) rawCount++;
                if (line.contains("\"kind\":\"features\"")) featureCount++;
                if (line.contains("\"kind\":\"session_meta\"")) {
                    try {
                        JSONObject meta = new JSONObject(line);
                        sessionName = meta.optString("sessionName", sessionName);
                        startedAt = meta.optLong("startedAt", meta.optLong("hostTs", startedAt));
                    } catch (Exception ignored) {
                    }
                }
                if (inspected >= 5000) break;
            }
        } catch (Exception ignored) {
        }

        try {
            obj.put("fileName", file.getName());
            obj.put("sessionName", sessionName);
            obj.put("startedAt", startedAt);
            obj.put("lastModified", file.lastModified());
            obj.put("size", file.length());
            obj.put("rawCount", rawCount);
            obj.put("featureCount", featureCount);
        } catch (Exception ignored) {
        }
        return obj;
    }

    private synchronized String readLog(String name) {
        File file = safeLogFile(name);
        if (file == null) return "";
        if (!file.exists()) return "";
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int offset = 0;
            while (offset < bytes.length) {
                int n = in.read(bytes, offset, bytes.length - offset);
                if (n < 0) break;
                offset += n;
            }
            return new String(bytes, 0, offset, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private synchronized boolean deleteLog(String name) {
        File file = safeLogFile(name);
        if (file == null || !file.exists()) return false;
        if (currentLogFile != null && file.equals(currentLogFile) && logging) return false;
        return file.delete();
    }

    private synchronized boolean renameLog(String name, String newSessionName) {
        File source = safeLogFile(name);
        if (source == null || !source.exists()) return false;
        if (currentLogFile != null && source.equals(currentLogFile) && logging) return false;

        String sessionName = normalizeSessionName(newSessionName);
        JSONObject summary = readLogSummary(source);
        long startedAt = summary.optLong("startedAt", source.lastModified());
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(startedAt));
        File temp = new File(source.getParentFile(), source.getName() + ".tmp");
        File target = new File(source.getParentFile(), String.format(
                Locale.US,
                "ski_imu_%s_%s.jsonl",
                stamp,
                sanitizeFilePart(sessionName)
        ));
        int suffix = 1;
        while (target.exists() && !target.equals(source)) {
            target = new File(source.getParentFile(), String.format(
                    Locale.US,
                    "ski_imu_%s_%s_%d.jsonl",
                    stamp,
                    sanitizeFilePart(sessionName),
                    suffix++
            ));
        }

        boolean replacedMeta = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(source),
                StandardCharsets.UTF_8
        ));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                     new FileOutputStream(temp, false),
                     StandardCharsets.UTF_8
             ))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!replacedMeta && line.contains("\"kind\":\"session_meta\"")) {
                    JSONObject meta;
                    try {
                        meta = new JSONObject(line);
                    } catch (Exception e) {
                        meta = new JSONObject();
                        meta.put("kind", "session_meta");
                    }
                    meta.put("sessionName", sessionName);
                    meta.put("startedAt", startedAt);
                    writer.write(meta.toString());
                    writer.newLine();
                    replacedMeta = true;
                } else {
                    writer.write(line);
                    writer.newLine();
                }
            }
            if (!replacedMeta) {
                JSONObject meta = new JSONObject();
                meta.put("kind", "session_meta");
                meta.put("sessionName", sessionName);
                meta.put("startedAt", startedAt);
                writer.write(meta.toString());
                writer.newLine();
            }
        } catch (Exception e) {
            if (temp.exists()) temp.delete();
            return false;
        }

        if (!source.delete()) {
            temp.delete();
            return false;
        }
        if (!temp.renameTo(target)) {
            temp.delete();
            return false;
        }
        currentLogFile = target;
        currentSessionName = sessionName;
        return true;
    }

    private synchronized void exportLogByName(String name) {
        File source = safeLogFile(name);
        if (source == null || !source.exists()) {
            toast("没有找到该记录");
            return;
        }
        exportTrainingPackage(source);
    }

    private void exportToDownloads(File source) {
        String lowerName = source.getName().toLowerCase(Locale.US);
        String mimeType = lowerName.endsWith(".zip") ? "application/zip" : "application/json";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, source.getName());
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

        Uri uri = null;
        try {
            uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("无法创建 Downloads 文件");
            try (OutputStream out = getContentResolver().openOutputStream(uri);
                 FileInputStream in = new FileInputStream(source)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            }
            toast("已导出到 Downloads/" + source.getName());
        } catch (Exception e) {
            if (uri != null) getContentResolver().delete(uri, null, null);
            toast("导出失败：" + e.getMessage());
            exportLegacyToDownloads(source);
        }
    }

    private void exportLegacyToDownloads(File source) {
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!downloads.exists() && !downloads.mkdirs()) {
            toast("无法打开 Downloads 目录");
            return;
        }
        File target = new File(downloads, source.getName());
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            toast("已导出到 Downloads/" + source.getName());
        } catch (Exception e) {
            toast("导出失败：" + e.getMessage());
        }
    }

    private synchronized void exportTrainingPackage(File source) {
        if (currentLogFile != null && source.equals(currentLogFile) && logging) {
            stopLog();
        }
        try {
            File packageFile = buildTrainingPackage(source);
            if (Build.VERSION.SDK_INT >= 29) {
                exportToDownloads(packageFile);
            } else {
                exportLegacyToDownloads(packageFile);
            }
        } catch (Exception e) {
            toast("导出训练包失败：" + e.getMessage());
        }
    }

    private File buildTrainingPackage(File source) throws Exception {
        File exportDir = new File(getCacheDir(), "training_exports");
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            throw new IllegalStateException("无法创建训练包缓存目录");
        }

        String baseName = source.getName().replaceFirst("\\.(jsonl|json)$", "");
        File rawFile = File.createTempFile("raw_imu_", ".jsonl", exportDir);
        File featuresFile = File.createTempFile("features_", ".jsonl", exportDir);
        File packageFile = new File(exportDir, baseName + "_training.zip");
        if (packageFile.exists() && !packageFile.delete()) {
            throw new IllegalStateException("无法覆盖旧训练包");
        }

        JSONObject summary = readLogSummary(source);
        String sessionName = summary.optString("sessionName", source.getName());
        long startedAt = summary.optLong("startedAt", source.lastModified());
        long exportedAt = System.currentTimeMillis();
        JSONObject sessionMeta = new JSONObject();
        JSONObject sensors = new JSONObject();
        JSONArray calibrationEvents = new JSONArray();
        int rawCount = 0;
        int featureCount = 0;
        int sourceLineCount = 0;
        long firstHostTs = 0;
        long lastHostTs = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(source),
                StandardCharsets.UTF_8
        ));
             BufferedWriter rawWriter = new BufferedWriter(new OutputStreamWriter(
                     new FileOutputStream(rawFile, false),
                     StandardCharsets.UTF_8
             ));
             BufferedWriter featureWriter = new BufferedWriter(new OutputStreamWriter(
                     new FileOutputStream(featuresFile, false),
                     StandardCharsets.UTF_8
             ))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sourceLineCount++;
                JSONObject obj;
                try {
                    obj = new JSONObject(line);
                } catch (Exception ignored) {
                    continue;
                }

                String kind = obj.optString("kind", "");
                if ("session_meta".equals(kind)) {
                    sessionMeta = obj;
                    sessionName = obj.optString("sessionName", sessionName);
                    startedAt = obj.optLong("startedAt", obj.optLong("hostTs", startedAt));
                }

                long hostTs = obj.optLong("hostTs", 0);
                if (hostTs > 0) {
                    if (firstHostTs == 0 || hostTs < firstHostTs) firstHostTs = hostTs;
                    if (hostTs > lastHostTs) lastHostTs = hostTs;
                    if (startedAt > 0 && !obj.has("sessionElapsedMs")) {
                        obj.put("sessionElapsedMs", hostTs - startedAt);
                    }
                }

                if ("raw".equals(kind)) {
                    rawCount++;
                    updateSensorSummary(sensors, obj, startedAt);
                    rawWriter.write(obj.toString());
                    rawWriter.newLine();
                } else if ("features".equals(kind)) {
                    featureCount++;
                    featureWriter.write(obj.toString());
                    featureWriter.newLine();
                } else if ("calibration".equals(kind)) {
                    calibrationEvents.put(obj);
                }
            }
        }

        JSONObject counts = new JSONObject();
        counts.put("sourceLines", sourceLineCount);
        counts.put("raw", rawCount);
        counts.put("features", featureCount);
        counts.put("calibrationEvents", calibrationEvents.length());

        JSONArray files = new JSONArray();
        files.put("metadata.json");
        files.put("raw_imu.jsonl");
        files.put("calibration.json");
        files.put("features.jsonl");
        files.put("labels.json");
        files.put("source_log.jsonl");

        JSONObject timebase = new JSONObject();
        timebase.put("hostTs", "手机接收数据时的 Unix 毫秒时间戳");
        timebase.put("sessionElapsedMs", "hostTs - startedAt，用于标注、回放、视频对齐");
        timebase.put("sensorMs", "IMU 节点启动后的毫秒计时，用于检查丢包和设备时钟漂移");
        timebase.put("alignment", "左右腿和腰部默认按手机 hostTs/sessionElapsedMs 对齐");

        JSONObject metadata = new JSONObject();
        metadata.put("schema", "ski_imu_training_package_v0");
        metadata.put("sourceSchema", sessionMeta.optString("schema", "ski_imu_jsonl_v0"));
        metadata.put("sourceLogFile", source.getName());
        metadata.put("sessionName", sessionName);
        metadata.put("startedAt", startedAt);
        metadata.put("exportedAt", exportedAt);
        metadata.put("firstHostTs", firstHostTs);
        metadata.put("lastHostTs", lastHostTs);
        metadata.put("durationMs", firstHostTs > 0 && lastHostTs >= firstHostTs ? lastHostTs - firstHostTs : 0);
        metadata.put("algorithmVersion", sessionMeta.optString("algorithmVersion", ""));
        metadata.put("counts", counts);
        metadata.put("files", files);
        metadata.put("timebase", timebase);
        metadata.put("sessionMeta", sessionMeta.length() > 0 ? sessionMeta : JSONObject.NULL);
        metadata.put("sensors", sensors);

        JSONObject calibration = new JSONObject();
        calibration.put("schema", "ski_imu_calibration_v0");
        calibration.put("sessionName", sessionName);
        calibration.put("startedAt", startedAt);
        calibration.put("exportedAt", exportedAt);
        calibration.put("events", calibrationEvents);
        calibration.put("sensors", sensors);
        calibration.put("note", "当前固件记录 cal 状态；具体姿态基线未随数据上报，后续可在固件协议中扩展 baseline。");

        JSONObject labels = new JSONObject();
        labels.put("schema", "ski_imu_labels_v0");
        labels.put("sessionName", sessionName);
        labels.put("timebase", "sessionElapsedMs");
        labels.put("labels", new JSONArray());
        JSONObject labelSpec = new JSONObject();
        labelSpec.put("startMs", "动作窗口开始，相对 startedAt 的毫秒");
        labelSpec.put("endMs", "动作窗口结束，相对 startedAt 的毫秒");
        labelSpec.put("action", "动作类型，例如 wedge_turn_left / parallel_turn_right / transition");
        labelSpec.put("quality", "good / normal / bad");
        labelSpec.put("errorType", "错误类型，例如 backward_lean / late_weight_shift / upper_body_rotation");
        labelSpec.put("comment", "教练备注");
        labels.put("labelSpec", labelSpec);

        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(packageFile))) {
            writeZipText(zip, "metadata.json", metadata.toString(2));
            writeZipFile(zip, "raw_imu.jsonl", rawFile);
            writeZipText(zip, "calibration.json", calibration.toString(2));
            writeZipFile(zip, "features.jsonl", featuresFile);
            writeZipText(zip, "labels.json", labels.toString(2));
            writeZipFile(zip, "source_log.jsonl", source);
        } finally {
            rawFile.delete();
            featuresFile.delete();
        }

        return packageFile;
    }

    private void updateSensorSummary(JSONObject sensors, JSONObject raw, long startedAt) throws Exception {
        String id = raw.optString("id", "");
        if (id.isEmpty()) return;
        JSONObject sensor = sensors.optJSONObject(id);
        if (sensor == null) {
            sensor = new JSONObject();
            sensor.put("id", id);
            sensor.put("role", raw.optString("role", id));
            sensor.put("rawCount", 0);
            sensor.put("calibrated", false);
            sensors.put(id, sensor);
        }

        long hostTs = raw.optLong("hostTs", 0);
        long sensorMs = raw.optLong("sensorMs", 0);
        int rawCount = sensor.optInt("rawCount", 0) + 1;
        sensor.put("rawCount", rawCount);
        if (hostTs > 0) {
            if (sensor.optLong("firstHostTs", 0) == 0) sensor.put("firstHostTs", hostTs);
            sensor.put("lastHostTs", hostTs);
        }
        if (sensorMs > 0) {
            if (sensor.optLong("firstSensorMs", 0) == 0) sensor.put("firstSensorMs", sensorMs);
            sensor.put("lastSensorMs", sensorMs);
        }
        if (raw.has("seq")) {
            if (!sensor.has("firstSeq")) sensor.put("firstSeq", raw.optLong("seq", 0));
            sensor.put("lastSeq", raw.optLong("seq", 0));
        }

        int cal = raw.optInt("cal", 0);
        sensor.put("lastCal", cal);
        if (cal > 0 && !sensor.optBoolean("calibrated", false)) {
            sensor.put("calibrated", true);
            sensor.put("firstCalibratedHostTs", hostTs);
            sensor.put("firstCalibratedSensorMs", sensorMs);
            if (startedAt > 0 && hostTs > 0) {
                sensor.put("firstCalibratedSessionElapsedMs", hostTs - startedAt);
            }
        }
    }

    private void writeZipText(ZipOutputStream zip, String entryName, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(entryName));
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        zip.write(bytes, 0, bytes.length);
        zip.closeEntry();
    }

    private void writeZipFile(ZipOutputStream zip, String entryName, File file) throws Exception {
        zip.putNextEntry(new ZipEntry(entryName));
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                zip.write(buf, 0, n);
            }
        }
        zip.closeEntry();
    }

    private final class BoardConnection {
        final String id;
        final String label;
        final StringBuilder buffer = new StringBuilder();
        BluetoothGatt gatt;
        BluetoothGattCharacteristic rxChar;
        BluetoothGattCharacteristic txChar;

        final BluetoothGattCallback callback = new BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    BoardConnection.this.gatt = gatt;
                    sendConnection(id, true);
                    sendStatus(label + " 已连接，正在发现服务...");
                    gatt.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    clear();
                    sendConnection(id, false);
                    sendStatus(label + " 已断开");
                    scheduleAutoConnect(1500);
                }
            }

            @SuppressLint("MissingPermission")
            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                BluetoothGattService service = gatt.getService(SERVICE_UUID);
                if (service == null) {
                    sendStatus(label + " 没有找到 UART 服务");
                    disconnectBoard(id);
                    return;
                }

                txChar = service.getCharacteristic(TX_UUID);
                rxChar = service.getCharacteristic(RX_UUID);
                if (txChar == null || rxChar == null) {
                    sendStatus(label + " UART 特征不完整");
                    disconnectBoard(id);
                    return;
                }

                gatt.setCharacteristicNotification(txChar, true);
                BluetoothGattDescriptor descriptor = txChar.getDescriptor(CCCD_UUID);
                if (descriptor != null) {
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    gatt.writeDescriptor(descriptor);
                }
                sendStatus(label + " 通知已开启");
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                if (!TX_UUID.equals(characteristic.getUuid())) return;
                onBytes(characteristic.getValue());
            }

            @Override
            public void onCharacteristicChanged(
                    BluetoothGatt gatt,
                    BluetoothGattCharacteristic characteristic,
                    byte[] value
            ) {
                if (!TX_UUID.equals(characteristic.getUuid())) return;
                onBytes(value);
            }
        };

        BoardConnection(String id, String label) {
            this.id = id;
            this.label = label;
        }

        void onBytes(byte[] bytes) {
            if (bytes == null || bytes.length == 0) return;
            buffer.append(new String(bytes, StandardCharsets.UTF_8));
            int idx;
            while ((idx = buffer.indexOf("\n")) >= 0) {
                String line = buffer.substring(0, idx).trim();
                buffer.delete(0, idx + 1);
                if (!line.isEmpty()) sendLine(id, line);
            }
            if (buffer.length() > 1500) {
                buffer.delete(0, buffer.length() - 300);
            }
        }

        void clear() {
            gatt = null;
            rxChar = null;
            txChar = null;
            buffer.setLength(0);
        }
    }

    public final class AndroidBridge {
        @JavascriptInterface
        public boolean isAndroidApp() {
            return true;
        }

        @JavascriptInterface
        public void connectBoard(String side) {
            runOnUiThread(() -> MainActivity.this.connectBoard(side));
        }

        @JavascriptInterface
        public void disconnectBoard(String side) {
            runOnUiThread(() -> MainActivity.this.disconnectBoard(side));
        }

        @JavascriptInterface
        public void autoConnectBoards() {
            runOnUiThread(MainActivity.this::autoConnectBoards);
        }

        @JavascriptInterface
        public void sendCalibration() {
            runOnUiThread(MainActivity.this::sendCalibration);
        }

        @JavascriptInterface
        public void startLog(String sessionName) {
            MainActivity.this.startLog(sessionName);
        }

        @JavascriptInterface
        public void stopLog() {
            MainActivity.this.stopLog();
        }

        @JavascriptInterface
        public void appendLogLine(String jsonLine) {
            MainActivity.this.appendLogLine(jsonLine);
        }

        @JavascriptInterface
        public void exportLog(String fallbackContent) {
            runOnUiThread(() -> exportCurrentLog(fallbackContent));
        }

        @JavascriptInterface
        public String listLogs() {
            return MainActivity.this.listLogs();
        }

        @JavascriptInterface
        public String readLog(String name) {
            return MainActivity.this.readLog(name);
        }

        @JavascriptInterface
        public boolean renameLog(String name, String newSessionName) {
            return MainActivity.this.renameLog(name, newSessionName);
        }

        @JavascriptInterface
        public boolean deleteLog(String name) {
            return MainActivity.this.deleteLog(name);
        }

        @JavascriptInterface
        public void exportLogByName(String name) {
            runOnUiThread(() -> MainActivity.this.exportLogByName(name));
        }
    }
}
