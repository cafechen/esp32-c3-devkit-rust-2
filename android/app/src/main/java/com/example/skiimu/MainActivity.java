package com.example.skiimu;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
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
import android.os.ParcelUuid;
import android.provider.MediaStore;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

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

    private File currentLogFile;
    private BufferedWriter currentLogWriter;
    private boolean logging;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boards.put("L", new BoardConnection("L"));
        boards.put("R", new BoardConnection("R"));

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
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new AndroidBridge(), "SkiAndroid");
        webView.loadUrl("file:///android_asset/index.html");

        ensurePermissions();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeLogWriter();
        for (BoardConnection board : boards.values()) {
            disconnectBoard(board.id);
        }
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
        sendStatus("正在扫描 " + side + " 板...");

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
                String expectedSuffix = "SKI-IMU-" + side;
                if (name != null && !name.isEmpty() && !name.endsWith(expectedSuffix)) {
                    return;
                }

                stopScan(side);
                sendStatus("正在连接 " + (name == null || name.isEmpty() ? side + " 板" : name) + "...");
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

    private synchronized void startLog() {
        closeLogWriter();
        try {
            File dir = new File(getFilesDir(), "logs");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("无法创建日志目录");
            }
            currentLogFile = new File(dir, String.format(Locale.US, "ski_imu_log_%d.jsonl", System.currentTimeMillis()));
            currentLogWriter = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(currentLogFile, false),
                    StandardCharsets.UTF_8
            ));
            logging = true;
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
            currentLogWriter.flush();
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
                File dir = new File(getFilesDir(), "logs");
                if (!dir.exists()) dir.mkdirs();
                source = new File(dir, String.format(Locale.US, "ski_imu_log_%d.jsonl", System.currentTimeMillis()));
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

        if (Build.VERSION.SDK_INT >= 29) {
            exportToDownloads(source);
        } else {
            exportLegacyToDownloads(source);
        }
    }

    private synchronized String listLogs() {
        JSONArray array = new JSONArray();
        File dir = new File(getFilesDir(), "logs");
        File[] files = dir.listFiles((file, name) -> name.endsWith(".jsonl") || name.endsWith(".json"));
        if (files == null) return array.toString();
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (File file : files) {
            array.put(file.getName());
        }
        return array.toString();
    }

    private synchronized String readLog(String name) {
        if (name == null || name.contains("/") || name.contains("\\")) return "";
        File file = new File(new File(getFilesDir(), "logs"), name);
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

    private void exportToDownloads(File source) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, source.getName());
        values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
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

    private final class BoardConnection {
        final String id;
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
                    sendStatus(id + " 板已连接，正在发现服务...");
                    gatt.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    clear();
                    sendConnection(id, false);
                    sendStatus(id + " 板已断开");
                }
            }

            @SuppressLint("MissingPermission")
            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                BluetoothGattService service = gatt.getService(SERVICE_UUID);
                if (service == null) {
                    sendStatus(id + " 板没有找到 UART 服务");
                    disconnectBoard(id);
                    return;
                }

                txChar = service.getCharacteristic(TX_UUID);
                rxChar = service.getCharacteristic(RX_UUID);
                if (txChar == null || rxChar == null) {
                    sendStatus(id + " 板 UART 特征不完整");
                    disconnectBoard(id);
                    return;
                }

                gatt.setCharacteristicNotification(txChar, true);
                BluetoothGattDescriptor descriptor = txChar.getDescriptor(CCCD_UUID);
                if (descriptor != null) {
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    gatt.writeDescriptor(descriptor);
                }
                sendStatus(id + " 板通知已开启");
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

        BoardConnection(String id) {
            this.id = id;
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
        public void sendCalibration() {
            runOnUiThread(MainActivity.this::sendCalibration);
        }

        @JavascriptInterface
        public void startLog() {
            runOnUiThread(MainActivity.this::startLog);
        }

        @JavascriptInterface
        public void stopLog() {
            runOnUiThread(MainActivity.this::stopLog);
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
    }
}
