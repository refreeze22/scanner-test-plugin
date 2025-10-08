package com.oktal.localbarcodescanner;

import android.Manifest;
import android.os.Build;
import android.content.pm.PackageManager;

import org.apache.cordova.*;
import org.json.*;

import com.rscja.deviceapi.Barcode2D;                // legacy (PDA)
import com.rscja.deviceapi.RFIDWithUHFBLE;         // R6 BLE
import com.rscja.deviceapi.RFIDWithUHFBLEManage;
import com.rscja.deviceapi.entity.UHFTAGInfo;
import com.rscja.deviceapi.interfaces.*;

public class LocalBarcodeScanner extends CordovaPlugin {

    // --- legacy path (built-in 2D engine on PDA) ---
    private Barcode2D scanner;

    // --- R6 BLE path ---
    private RFIDWithUHFBLE r6;
    private boolean useR6 = false;
    private CallbackContext inventoryCb;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext cb) throws JSONException {
        switch (action) {
            // keep existing names
            case "initScanner":
                // optional arg: macOrName for R6
                String macOrName = args != null && args.length() > 0 ? args.optString(0, "") : "";
                if (macOrName != null && macOrName.length() > 0) {
                    useR6 = true;
                    ensureBlePermsThen(() -> initR6(macOrName, cb), cb);
                } else {
                    useR6 = false;
                    cordova.getThreadPool().execute(() -> initBarcode2D(cb));
                }
                return true;

            case "startScan":
                if (useR6) {
                    cordova.getThreadPool().execute(() -> startInventory(cb));
                } else {
                    cordova.getThreadPool().execute(() -> startBarcodeScan(cb));
                }
                return true;

            case "stopScan":
                if (useR6) {
                    cordova.getThreadPool().execute(() -> stopInventory(cb));
                } else {
                    cordova.getThreadPool().execute(() -> stopBarcodeScan(cb));
                }
                return true;

            // new extras for R6
            case "scanBle":
                ensureBlePermsThen(() -> scanBle(cb), cb);
                return true;

            case "disconnect":
                if (useR6) {
                    cordova.getThreadPool().execute(() -> disconnectR6(cb));
                } else {
                    cb.success(); // nothing to do for legacy
                }
                return true;

            case "getBatteryLevel":
                if (useR6) {
                    cordova.getThreadPool().execute(() -> getR6Battery(cb));
                } else {
                    cb.error("Not supported in legacy mode");
                }
                return true;
        }
        return false;
    }

    // ===== Legacy PDA path (what you already had, just safer) =====
    private void initBarcode2D(CallbackContext cb) {
        try {
            scanner = Barcode2D.getInstance();
            boolean ok = scanner != null && scanner.open(cordova.getActivity().getApplicationContext());
            if (!ok) { scanner = null; cb.error("open() returned false"); return; }
            cb.success("initialized");
        } catch (Throwable t) {
            scanner = null;
            cb.error("init failed: " + (t.getMessage()==null?t.toString():t.getMessage()));
        }
    }

    private void startBarcodeScan(CallbackContext cb) {
        if (scanner == null) { cb.error("Scanner not initialized"); return; }
        try {
            scanner.startScan(code -> {
                // send each decoded barcode to JS, keep callback
                PluginResult pr = new PluginResult(PluginResult.Status.OK, code);
                pr.setKeepCallback(true);
                cb.sendPluginResult(pr);
            });
        } catch (Throwable t) {
            cb.error("Start scan failed: " + t);
        }
    }

    private void stopBarcodeScan(CallbackContext cb) {
        try {
            if (scanner != null) { scanner.stopScan(); scanner.close(); scanner = null; }
            cb.success("stopped");
        } catch (Throwable t) { cb.error("Stop failed: " + t); }
    }

    // ===== R6 BLE path =====
    private void initR6(String macOrName, CallbackContext cb) {
        try {
            r6 = RFIDWithUHFBLE.getInstance();
            r6.setConnectionStatusCallback(status -> {
                // optional: forward status via a separate event channel
            });
            boolean ok = r6.connect(macOrName); // mac or name works in this AAR
            if (ok) { cb.success("connected"); }
            else    { r6 = null; cb.error("connect failed"); }
        } catch (Throwable t) {
            r6 = null;
            cb.error("R6 init failed: " + t);
        }
    }

    private void startInventory(CallbackContext cb) {
        if (r6 == null) { cb.error("R6 not connected"); return; }
        try {
            inventoryCb = cb;
            r6.setInventoryCallback(new IUHFInventoryCallback() {
                @Override public void callback(UHFTAGInfo tag) {
                    try {
                        JSONObject obj = new JSONObject();
                        obj.put("epc", tag.getEpc());
                        obj.put("rssi", tag.getRssi());
                        // add tid/user if you configure reads
                        PluginResult pr = new PluginResult(PluginResult.Status.OK, obj);
                        pr.setKeepCallback(true);
                        inventoryCb.sendPluginResult(pr);
                    } catch (JSONException ignore) {}
                }
            });
            // optional: r6.setPower(30);
            r6.startInventoryTag();
        } catch (Throwable t) {
            cb.error("startInventory failed: " + t);
        }
    }

    private void stopInventory(CallbackContext cb) {
        try {
            if (r6 != null) r6.stopInventory();
            cb.success();
        } catch (Throwable t) { cb.error("stopInventory failed: " + t); }
    }

    private void scanBle(CallbackContext cb) {
        try {
            RFIDWithUHFBLEManage mgr = RFIDWithUHFBLEManage.getInstance();
            mgr.startScanBTDevices(new ScanBTCallback() {
                @Override public void getDevices(java.util.List<?> list) {
                    JSONArray arr = new JSONArray();
                    for (Object o : list) {
                        // cast to the SDK's BleDevice if needed
                        try {
                            String name = (String)o.getClass().getMethod("getName").invoke(o);
                            String mac  = (String)o.getClass().getMethod("getAddress").invoke(o);
                            JSONObject j = new JSONObject();
                            j.put("name", name);
                            j.put("mac", mac);
                            arr.put(j);
                        } catch (Exception ignore) {}
                    }
                    PluginResult pr = new PluginResult(PluginResult.Status.OK, arr);
                    pr.setKeepCallback(true);
                    cb.sendPluginResult(pr);
                }
            });
        } catch (Throwable t) { cb.error("scanBle failed: " + t); }
    }

    private void disconnectR6(CallbackContext cb) {
        try {
            if (r6 != null) { r6.disconnect(); r6 = null; }
            cb.success();
        } catch (Throwable t) { cb.error("disconnect failed: " + t); }
    }

    private void getR6Battery(CallbackContext cb) {
        try {
            if (r6 == null) { cb.error("R6 not connected"); return; }
            int lvl = r6.getBattery(); // method exists in this AAR
            cb.success(String.valueOf(lvl));
        } catch (Throwable t) { cb.error("battery failed: " + t); }
    }

    // ===== Permissions for Android 12+ BLE =====
    private void ensureBlePermsThen(Runnable work, CallbackContext cb) {
        if (Build.VERSION.SDK_INT < 31) { cordova.getThreadPool().execute(work); return; }
        String[] perms = new String[] {
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        };
        boolean need = false;
        for (String p : perms) {
            if (cordova.hasPermission(p) == false) { need = true; break; }
        }
        if (!need) { cordova.getThreadPool().execute(work); return; }
        cordova.requestPermissions(this, 9001, perms);
        // Call work() from onRequestPermissionResult if granted
        this.pendingWork = work; this.pendingCb = cb;
    }

    private Runnable pendingWork; private CallbackContext pendingCb;

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        if (requestCode == 9001) {
            for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) {
                if (pendingCb != null) pendingCb.error("BLE permissions denied");
                pendingWork = null; pendingCb = null; return;
            }
            if (pendingWork != null) cordova.getThreadPool().execute(pendingWork);
            pendingWork = null; pendingCb = null;
        }
        super.onRequestPermissionResult(requestCode, permissions, grantResults);
    }

    @Override public void onPause(boolean multitasking) {
        super.onPause(multitasking);
        try { if (r6 != null) r6.stopInventory(); } catch (Throwable ignore) {}
    }

    @Override public void onDestroy() {
        super.onDestroy();
        try { if (scanner != null) { scanner.stopScan(); scanner.close(); } } catch (Throwable ignore) {}
        try { if (r6 != null) { r6.disconnect(); } } catch (Throwable ignore) {}
        scanner = null; r6 = null;
    }
}
