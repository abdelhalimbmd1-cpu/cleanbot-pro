package com.cleanbot.app;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int REQ_PERMISSIONS = 101;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private OutputStream outputStream;
    private InputStream inputStream;
    private Thread workerThread;
    private volatile boolean isConnected = false;

    private TextView tvStatus, tvLog;
    private Button btnConnect, btnStartAuto, btnEmergencyStop;
    private Button btnModeRows, btnModePerimeter, btnModeHybrid;
    private Button btnToggleBrushes, btnToggleVent;
    private ScrollView scrollLog;

    private boolean brushesState = false;
    private boolean ventState = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvLog = findViewById(R.id.tvLog);
        scrollLog = findViewById(R.id.scrollLog);
        btnConnect = findViewById(R.id.btnConnect);
        btnStartAuto = findViewById(R.id.btnStartAuto);
        btnEmergencyStop = findViewById(R.id.btnEmergencyStop);
        btnModeRows = findViewById(R.id.btnModeRows);
        btnModePerimeter = findViewById(R.id.btnModePerimeter);
        btnModeHybrid = findViewById(R.id.btnModeHybrid);
        btnToggleBrushes = findViewById(R.id.btnToggleBrushes);
        btnToggleVent = findViewById(R.id.btnToggleVent);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        btnConnect.setOnClickListener(v -> checkPermissionsAndConnect());

        // Autonomous Mission Start
        btnStartAuto.setOnClickListener(v -> sendCommand("A", "▶ NETTOYAGE AUTO LANCE"));

        // Emergency Stop All
        btnEmergencyStop.setOnClickListener(v -> sendCommand("S", "🛑 ARRET D'URGENCE"));

        // Strategies
        btnModeRows.setOnClickListener(v -> sendCommand("1", "Mode: Lignes (1)"));
        btnModePerimeter.setOnClickListener(v -> sendCommand("2", "Mode: Murs (2)"));
        btnModeHybrid.setOnClickListener(v -> sendCommand("3", "Mode: Hybride (3)"));

        // Actuators
        btnToggleBrushes.setOnClickListener(v -> {
            brushesState = !brushesState;
            sendCommand(brushesState ? "W" : "w", brushesState ? "Brosses ON" : "Brosses OFF");
            btnToggleBrushes.setText(brushesState ? "Brosses: ON" : "Brosses: OFF");
            btnToggleBrushes.setBackgroundColor(brushesState ? Color.parseColor("#238636") : Color.parseColor("#30363D"));
        });

        btnToggleVent.setOnClickListener(v -> {
            ventState = !ventState;
            sendCommand(ventState ? "U" : "u", ventState ? "Suction ON" : "Suction OFF");
            btnToggleVent.setText(ventState ? "Suction: ON" : "Suction: OFF");
            btnToggleVent.setBackgroundColor(ventState ? Color.parseColor("#238636") : Color.parseColor("#30363D"));
        });
    }

    private void checkPermissionsAndConnect() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth non supporté", Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> neededPermissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        if (!neededPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toArray(new String[0]), REQ_PERMISSIONS);
        } else {
            showDevicePicker();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            boolean allGranted = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                showDevicePicker();
            } else {
                Toast.makeText(this, "Permission Bluetooth refusée", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showDevicePicker() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return;
        }

        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        if (pairedDevices == null || pairedDevices.isEmpty()) {
            Toast.makeText(this, "Aucun module appairé. Appairez HC-05 d'abord dans les paramètres Bluetooth Android.", Toast.LENGTH_LONG).show();
            return;
        }

        List<BluetoothDevice> deviceList = new ArrayList<>(pairedDevices);
        String[] names = new String[deviceList.size()];
        int defaultIndex = -1;

        for (int i = 0; i < deviceList.size(); i++) {
            BluetoothDevice dev = deviceList.get(i);
            String name = dev.getName();
            names[i] = (name != null ? name : "Inconnu") + "\n" + dev.getAddress();
            if (name != null && name.contains("HC-05")) {
                defaultIndex = i;
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Sélectionner le CleanBot HC-05");
        builder.setItems(names, (dialog, which) -> connectToDevice(deviceList.get(which)));
        builder.show();
    }

    private void connectToDevice(BluetoothDevice device) {
        logText("\n[CONNEXION] En cours vers " + device.getName() + "...");
        tvStatus.setText("● Connexion en cours...");
        tvStatus.setTextColor(Color.parseColor("#E3B341"));

        new Thread(() -> {
            try {
                if (bluetoothSocket != null) {
                    try { bluetoothSocket.close(); } catch (Exception ignored) {}
                }

                // Safe Samsung Insecure Socket Connection (Bypasses Error 507)
                try {
                    Method m = device.getClass().getMethod("createInsecureRfcommSocketToServiceRecord", UUID.class);
                    bluetoothSocket = (BluetoothSocket) m.invoke(device, SPP_UUID);
                } catch (Exception e) {
                    bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                }

                if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED ||
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    bluetoothAdapter.cancelDiscovery();
                }

                bluetoothSocket.connect();
                outputStream = bluetoothSocket.getOutputStream();
                inputStream = bluetoothSocket.getInputStream();
                isConnected = true;

                mainHandler.post(() -> {
                    tvStatus.setText("● Connecté à " + device.getName());
                    tvStatus.setTextColor(Color.parseColor("#3FB950"));
                    btnConnect.setText("CONNECTÉ");
                    logText("\n[SUCCES] Connecté à " + device.getName() + "! Prêt à nettoyer.");
                });

                startReaderThread();

            } catch (Exception e) {
                isConnected = false;
                mainHandler.post(() -> {
                    tvStatus.setText("● Échec connexion");
                    tvStatus.setTextColor(Color.parseColor("#F85149"));
                    logText("\n[ERREUR] Impossible de se connecter: " + e.getMessage());
                });
            }
        }).start();
    }

    private void startReaderThread() {
        workerThread = new Thread(() -> {
            byte[] buffer = new byte[1024];
            int bytes;
            while (isConnected && !Thread.currentThread().isInterrupted()) {
                try {
                    if (inputStream != null && (bytes = inputStream.read(buffer)) > 0) {
                        String incoming = new String(buffer, 0, bytes);
                        mainHandler.post(() -> logText(incoming));
                    }
                } catch (IOException e) {
                    break;
                }
            }
        });
        workerThread.start();
    }

    private void sendCommand(String cmd, String description) {
        if (!isConnected || outputStream == null) {
            Toast.makeText(this, "Veuillez connecter le Bluetooth d'abord !", Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            try {
                outputStream.write(cmd.getBytes());
                outputStream.flush();
                mainHandler.post(() -> logText("\n>> COMMANDE ENVOYEE: " + cmd + " (" + description + ")"));
            } catch (IOException e) {
                mainHandler.post(() -> logText("\n[ERREUR ENVOI] " + e.getMessage()));
            }
        }).start();
    }

    private void logText(String msg) {
        tvLog.append(msg);
        scrollLog.post(() -> scrollLog.fullScroll(ScrollView.FOCUS_DOWN));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isConnected = false;
        if (bluetoothSocket != null) {
            try { bluetoothSocket.close(); } catch (Exception ignored) {}
        }
    }
}
