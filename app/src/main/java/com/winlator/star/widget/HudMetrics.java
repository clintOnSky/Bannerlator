package com.winlator.star.widget;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Shared live-metric collector for the performance HUDs. Encapsulates the sysfs/Android
 * readers (GPU load, temperature, RAM, power/charging) ported from {@link FrameRating},
 * plus the two metrics GameHub's HUD adds: overall CPU usage % (from {@code /proc/stat})
 * and a dual-battery power fix that sums the per-cell current channels.
 *
 * Not thread-safe; call from a single (UI) thread on the HUD's refresh tick.
 */
public class HudMetrics {
    private final Context context;

    public HudMetrics(Context context) { this.context = context; }

    // ---- CPU usage % (emulator process-tree delta) ------------------------
    // The global /proc/stat aggregate is unreadable to apps on modern Android
    // (SELinux proc_stat + hidepid=invisible), so measure the emulator's own CPU
    // instead: sum utime+stime across our visible (same-UID) process tree — the
    // app plus its wine/box64/proot children — and delta against wall-clock. This
    // is the meaningful number for a game HUD and works without root.
    private long prevCpuTicks = 0, prevWallNs = 0;
    private boolean cpuBaselineSet = false;
    private static final long CLK_TCK =
        Math.max(1, android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK));

    /** Emulator CPU usage 0..100 (share of total capacity), delta since the last call. */
    public float getCPUUsage() {
        try {
            long ticks = readProcessTreeCpuTicks();
            long nowNs = SystemClock.elapsedRealtimeNanos();
            long dTicks = ticks - prevCpuTicks;
            long dWallNs = nowNs - prevWallNs;
            boolean hadBaseline = cpuBaselineSet;
            prevCpuTicks = ticks;
            prevWallNs = nowNs;
            cpuBaselineSet = true;
            if (!hadBaseline || dWallNs <= 0 || dTicks < 0) return 0; // first call: only seed baseline
            int nCores = Math.max(1, Runtime.getRuntime().availableProcessors());
            double cpuNs = dTicks * (1_000_000_000.0 / CLK_TCK);
            float usage = (float) (cpuNs * 100.0 / ((double) dWallNs * nCores));
            return Math.max(0, Math.min(100, usage));
        } catch (Exception e) {
            return 0;
        }
    }

    /** Sum of utime+stime (clock ticks) across all PID dirs visible under /proc. */
    private long readProcessTreeCpuTicks() {
        long sum = 0;
        File proc = new File("/proc");
        File[] entries = proc.listFiles();
        if (entries == null) return prevCpuTicks; // keep last -> 0 delta rather than a spike
        for (File d : entries) {
            String name = d.getName();
            boolean numeric = !name.isEmpty();
            for (int i = 0; numeric && i < name.length(); i++)
                if (!Character.isDigit(name.charAt(i))) numeric = false;
            if (!numeric) continue;
            try (BufferedReader r = new BufferedReader(new FileReader(new File(d, "stat")))) {
                String line = r.readLine();
                if (line == null) continue;
                // Fields 14 (utime) + 15 (stime) are the 12th/13th tokens AFTER the
                // ')' that closes comm — parsing from there avoids comm's spaces/parens.
                int close = line.lastIndexOf(')');
                if (close < 0 || close + 2 >= line.length()) continue;
                String[] f = line.substring(close + 2).trim().split("\\s+");
                // post-comm index 0 = state (field 3); utime = field 14 -> index 11,
                // stime = field 15 -> index 12.
                if (f.length > 12) sum += Long.parseLong(f[11]) + Long.parseLong(f[12]);
            } catch (Exception ignored) {
                // process vanished mid-scan or stat unreadable — skip it.
            }
        }
        return sum;
    }

    // ---- GPU load % (ported from FrameRating) -----------------------------
    public int getGPULoad() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/sys/class/kgsl/kgsl-3d0/gpubusy"));
            String line = reader.readLine();
            reader.close();
            if (line != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) {
                    long busy = Long.parseLong(parts[0]);
                    long total = Long.parseLong(parts[1]);
                    if (total != 0) return (int) ((busy * 100) / total);
                }
            }
        } catch (Exception e) {
            try {
                BufferedReader reader = new BufferedReader(new FileReader("/sys/class/misc/mali0/device/utilisation"));
                String line = reader.readLine();
                reader.close();
                if (line != null) return Integer.parseInt(line.trim());
            } catch (Exception e2) {}
        }
        return 0;
    }

    // ---- RAM % ------------------------------------------------------------
    public float getRAMPercent() {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        if (mi.totalMem <= 0) return 0;
        return (mi.totalMem - mi.availMem) * 100f / mi.totalMem;
    }

    // ---- Temperature (CPU thermal zones, ported from FrameRating) ---------
    private static final String[] THERMAL_PATHS = {
        "/sys/class/thermal/thermal_zone0/temp", "/sys/class/thermal/thermal_zone1/temp",
        "/sys/class/thermal/thermal_zone7/temp", "/sys/class/thermal/thermal_zone10/temp",
        "/sys/devices/virtual/thermal/thermal_zone0/temp", "/sys/class/hwmon/hwmon0/temp1_input",
        "/sys/devices/system/cpu/cpu0/cpufreq/cpu_temp"
    };
    private String[] cpuThermalPaths = null;

    public float getTemperature() {
        float max = 0;
        for (String path : discoverCpuThermalPaths()) {
            float t = readTemp(path);
            if (t > max) max = t;
        }
        if (max > 0) return max;
        for (String path : THERMAL_PATHS) {
            float t = readTemp(path);
            if (t > 0) return t;
        }
        return 0;
    }

    private String[] discoverCpuThermalPaths() {
        if (cpuThermalPaths != null) return cpuThermalPaths;
        ArrayList<String> found = new ArrayList<>();
        try {
            File thermalDir = new File("/sys/class/thermal");
            File[] zones = thermalDir.listFiles((dir, name) -> name.startsWith("thermal_zone"));
            if (zones != null) {
                for (File zone : zones) {
                    try (BufferedReader r = new BufferedReader(new FileReader(new File(zone, "type")))) {
                        String type = r.readLine();
                        if (type == null) continue;
                        type = type.trim().toLowerCase(Locale.ENGLISH);
                        if (type.contains("cpu") && !type.contains("gpu")) {
                            File tempFile = new File(zone, "temp");
                            if (tempFile.canRead()) found.add(tempFile.getAbsolutePath());
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        cpuThermalPaths = found.toArray(new String[0]);
        return cpuThermalPaths;
    }

    private float readTemp(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line = reader.readLine();
            if (line != null) {
                float temp = Float.parseFloat(line.trim());
                if (temp > 1000) temp /= 1000.0f;
                if (temp > 0 && temp < 150) return temp;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    // ---- Power (W) + charging --------------------------------------------
    public static final class Battery {
        public final float watts; public final boolean charging;
        Battery(float watts, boolean charging) { this.watts = watts; this.charging = charging; }
    }

    /** power_supply current_now channels (µA) for the dual-battery sum. */
    private static final String[] CURRENT_CHANNELS = {
        "/sys/class/power_supply/battery/current_now",
        "/sys/class/power_supply/bms/current_now",
        "/sys/class/power_supply/main/current_now",
    };

    /**
     * @param dualBattery when true, sum the per-cell current channels (battery + bms/main)
     *                    to correct devices that report only one cell's current and read low.
     */
    public Battery getBattery(boolean dualBattery) {
        Intent status = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        boolean charging = false;
        int voltageMv = 0;
        if (status != null) {
            charging = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            voltageMv = status.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        }
        long microAmps;
        if (dualBattery) {
            long sum = 0; int n = 0;
            for (String path : CURRENT_CHANNELS) {
                Long v = readLong(path);
                if (v != null) { sum += Math.abs(v); n++; }
            }
            if (n > 0) {
                microAmps = -sum; // treat as discharge magnitude
            } else {
                microAmps = readCurrentNowFallback();
            }
        } else {
            microAmps = readCurrentNowFallback();
        }
        // Show draw magnitude regardless of the device's current-sign convention:
        // some report discharge as negative, others (this Xiaomi) as positive, so
        // keying on microAmps < 0 left watts stuck at 0 on the latter. The `charging`
        // flag (from EXTRA_PLUGGED) still drives the CHG/PWR label in the HUD.
        // BATTERY_PROPERTY_CURRENT_NOW returns Long.MIN_VALUE when unsupported.
        float watts = 0f;
        if (microAmps != 0 && microAmps != Long.MIN_VALUE) {
            long absUa = Math.abs(microAmps);
            // Spec unit is µA, but some Xiaomi/MTK units report mA here, which would
            // round to ~0.0W. Under a running game the draw is always >10mA, so an
            // implausibly small magnitude means mA — scale it up to µA.
            if (absUa < 10_000) absUa *= 1000;
            watts = (absUa * (float) voltageMv) / 1_000_000_000.0f;
        }
        return new Battery(watts, charging);
    }

    private long readCurrentNowFallback() {
        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        return bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
    }

    private Long readLong(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line = r.readLine();
            if (line != null) return Long.parseLong(line.trim());
        } catch (Exception ignored) {}
        return null;
    }
}
