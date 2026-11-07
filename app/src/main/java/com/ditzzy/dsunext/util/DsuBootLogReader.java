package com.ditzzy.dsunext.util;

import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Reads DSU boot diagnostics and logs from the host device:
 * 1. Kernel pstore/ramoops from previous boot (/sys/fs/pstore/console-ramoops)
 * 2. Guest userdata image (/data/gsi/dsu/userdata_gsi) loopback mounted to read persistent logs
 * 3. User storage (/sdcard/DSU_Logs) if completed
 */
public final class DsuBootLogReader {
    private static final String TAG = "DsuBootLogReader";
    private static final int MAX_LOG_SIZE = 1024 * 1024; // 1MB per section

    private DsuBootLogReader() {
    }

    public static String readLogsJson() {
        JSONObject json = new JSONObject();
        try {
            StringBuilder infoBuilder = new StringBuilder();
            StringBuilder logcatBuilder = new StringBuilder();
            StringBuilder dmesgBuilder = new StringBuilder();
            StringBuilder pstoreBuilder = new StringBuilder();

            boolean hasLogs = false;

            // 1. Kernel pstore / ramoops / last_kmsg
            String[] pstoreCandidates = {
                    "/sys/fs/pstore/console-ramoops",
                    "/sys/fs/pstore/console-ramoops-0",
                    "/sys/fs/pstore/pmsg-ramoops-0",
                    "/proc/last_kmsg"
            };

            for (String pstorePath : pstoreCandidates) {
                File f = new File(pstorePath);
                if (f.exists() && f.length() > 0) {
                    String content = readFileTruncated(f, MAX_LOG_SIZE);
                    if (!content.isEmpty()) {
                        pstoreBuilder.append("--- Source: ").append(pstorePath).append(" ---\n");
                        pstoreBuilder.append(content).append("\n\n");
                        hasLogs = true;
                    }
                }
            }

            // 2. DSU guest userdata image
            String[] userdataCandidates = {
                    "/data/gsi/dsu/userdata_gsi",
                    "/data/gsi/dsu/userdata",
                    "/metadata/gsi/dsu/userdata_gsi"
            };

            String guestUserdataPath = null;
            for (String path : userdataCandidates) {
                File f = new File(path);
                if (f.exists() && f.length() > 0) {
                    guestUserdataPath = path;
                    break;
                }
            }

            if (guestUserdataPath != null) {
                File mountPoint = new File("/data/local/tmp/dsu_guest_mnt");
                mountPoint.mkdirs();
                try {
                    Process mountProc = Runtime.getRuntime().exec(new String[]{
                            "mount", "-o", "ro,loop", guestUserdataPath, mountPoint.getAbsolutePath()
                    });
                    mountProc.waitFor();

                    File guestLogDir = new File(mountPoint, "local/tmp/dsu_boot");
                    if (guestLogDir.exists() && guestLogDir.isDirectory()) {
                        File infoFile = new File(guestLogDir, "boot_info.txt");
                        if (infoFile.exists()) {
                            infoBuilder.append(readFileTruncated(infoFile, MAX_LOG_SIZE));
                            hasLogs = true;
                        }

                        File logcatFile = new File(guestLogDir, "logcat.txt");
                        if (!logcatFile.exists() || logcatFile.length() == 0) {
                            logcatFile = new File(guestLogDir, "early_logcat.txt");
                        }
                        if (logcatFile.exists()) {
                            logcatBuilder.append(readFileTruncated(logcatFile, MAX_LOG_SIZE));
                            hasLogs = true;
                        }

                        File dmesgFile = new File(guestLogDir, "dmesg.txt");
                        if (!dmesgFile.exists() || dmesgFile.length() == 0) {
                            dmesgFile = new File(guestLogDir, "early_dmesg.txt");
                        }
                        if (dmesgFile.exists()) {
                            dmesgBuilder.append(readFileTruncated(dmesgFile, MAX_LOG_SIZE));
                            hasLogs = true;
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error reading guest userdata", e);
                } finally {
                    try {
                        Process umountProc = Runtime.getRuntime().exec(new String[]{
                                "umount", mountPoint.getAbsolutePath()
                        });
                        umountProc.waitFor();
                        mountPoint.delete();
                    } catch (Exception ignored) {
                    }
                }
            }

            // 3. Check /sdcard/DSU_Logs/ if boot succeeded and copied logs to user storage
            File sdLogDir = new File("/sdcard/DSU_Logs");
            if (sdLogDir.exists() && sdLogDir.isDirectory()) {
                File sdLogcat = new File(sdLogDir, "boot_logcat.txt");
                if (sdLogcat.exists() && logcatBuilder.length() == 0) {
                    logcatBuilder.append(readFileTruncated(sdLogcat, MAX_LOG_SIZE));
                    hasLogs = true;
                }
                File sdDmesg = new File(sdLogDir, "boot_dmesg.txt");
                if (sdDmesg.exists() && dmesgBuilder.length() == 0) {
                    dmesgBuilder.append(readFileTruncated(sdDmesg, MAX_LOG_SIZE));
                    hasLogs = true;
                }
                File sdInfo = new File(sdLogDir, "boot_info.txt");
                if (sdInfo.exists() && infoBuilder.length() == 0) {
                    infoBuilder.append(readFileTruncated(sdInfo, MAX_LOG_SIZE));
                    hasLogs = true;
                }
            }

            json.put("has_logs", hasLogs);
            json.put("boot_info", infoBuilder.toString());
            json.put("logcat", logcatBuilder.toString());
            json.put("dmesg", dmesgBuilder.toString());
            json.put("pstore", pstoreBuilder.toString());
            json.put("status_message", hasLogs
                    ? "DSU boot logs retrieved successfully."
                    : "No DSU boot logs found. Inject the boot logger into your GSI before installing.");

        } catch (Exception e) {
            Log.e(TAG, "Error in readLogsJson", e);
            try {
                json.put("has_logs", false);
                json.put("status_message", "Error reading logs: " + e.getMessage());
            } catch (JSONException ignored) {
            }
        }
        return json.toString();
    }

    private static String readFileTruncated(File file, int maxBytes) {
        if (!file.exists() || !file.canRead()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int total = 0;
            int read;
            while ((read = reader.read(buf)) != -1) {
                sb.append(buf, 0, read);
                total += read;
                if (total >= maxBytes) {
                    sb.append("\n\n[... Truncated at ").append(maxBytes / 1024).append(" KB ...]\n");
                    break;
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to read " + file, e);
        }
        return sb.toString();
    }
}
