package com.eddyizm.tempus.service;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.util.UnstableApi;

import java.util.function.BooleanSupplier;

/**
 * Keeps the process alive (foreground service + partial WakeLock + WifiLock) while
 * {@link com.eddyizm.tempus.util.ExternalAudioWriter} has queued work. The queue itself lives in
 * memory, so work queued before the process is killed anyway is not resumed.
 */
@UnstableApi
public class ExternalDownloadService extends Service {

    private static final String TAG = "ExternalDownloadSvc";
    private static final String LOCK_TAG = "Tempus:ExternalDownload";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // Main thread only.
    private static ExternalDownloadService running;
    private static BooleanSupplier hasPendingWork = () -> false;

    private int lastStartId;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    /** Called when the queue goes from idle to busy. */
    public static void start(Context context, BooleanSupplier pendingWork) {
        Context appContext = context.getApplicationContext();
        MAIN.post(() -> {
            hasPendingWork = pendingWork;
            if (!pendingWork.getAsBoolean()) return;
            try {
                ContextCompat.startForegroundService(appContext, new Intent(appContext, ExternalDownloadService.class));
            } catch (RuntimeException e) {
                // e.g. background start restrictions; downloads still run, just unprotected
                Log.w(TAG, "Could not start foreground service", e);
            }
        });
    }

    /** Called when the queue has drained; the service stops unless new work arrived meanwhile. */
    public static void stopIfIdle() {
        MAIN.post(() -> {
            if (running != null) running.stopWhenIdle();
        });
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        // Always go foreground first: startForegroundService() requires it even if we stop at once.
        try {
            ServiceCompat.startForeground(this, DownloadProgressState.EXTERNAL_PROGRESS_NOTIFICATION_ID,
                    DownloadProgressState.getInstance().buildProgressNotification(this),
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0);
        } catch (RuntimeException e) {
            Log.w(TAG, "startForeground failed", e);
            stopSelf();
            return START_NOT_STICKY;
        }
        running = this;
        acquireLocks();
        stopWhenIdle();
        return START_NOT_STICKY;
    }

    private void stopWhenIdle() {
        if (hasPendingWork.getAsBoolean()) return;
        Log.d(TAG, "Queue drained, stopping");
        // Only stops if no newer start request is waiting to be delivered.
        stopSelfResult(lastStartId);
    }

    @SuppressLint("WakelockTimeout")
    private void acquireLocks() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG);
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire();

            if (wifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    wifiLock = createWifiLock(wm);
                    wifiLock.setReferenceCounted(false);
                }
            }
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not acquire locks", e);
        }
    }

    @SuppressWarnings("deprecation")
    private static WifiManager.WifiLock createWifiLock(WifiManager wm) {
        // WIFI_MODE_FULL_HIGH_PERF is deprecated on API 34+ (ignored there), still honoured on the SP2000T (API 28).
        return wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, LOCK_TAG);
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not release locks", e);
        }
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        // Android 15+ dataSync time limit; must leave the foreground state or the app is killed.
        Log.w(TAG, "Foreground service timed out");
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (running == this) running = null;
        releaseLocks();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
