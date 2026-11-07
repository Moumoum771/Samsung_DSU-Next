package com.ditzzy.dsunext.workspace;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Injects an early-boot diagnostics service and scripts into an unpacked GSI system partition.
 * Starts logging at post-fs and class core so that early HAL failures, Zygote crashes,
 * and kernel events are captured to disk before a bootloop can trigger a reset.
 */
public final class BootLoggerInjector {
    private static final String TAG = "BootLoggerInjector";

    private BootLoggerInjector() {
    }

    public static final String RC_CONTENT =
            "# DSU Next - Early Boot Logger & Diagnostics Service\n" +
            "# Starts logging before zygote and framework to capture early crashes and HAL failures.\n\n" +
            "# Phase 1: At post-fs, /system is mounted and /dev is available (RAM tmpfs)\n" +
            "on post-fs\n" +
            "    mkdir /dev/dsu_boot 0777 root root\n" +
            "    start dsu_early_logger\n\n" +
            "# Phase 2: At post-fs-data, guest /data is mounted and decrypted\n" +
            "on post-fs-data\n" +
            "    mkdir /data/local/tmp/dsu_boot 0777 root root\n" +
            "    start dsu_boot_logger\n\n" +
            "# Phase 3: When Android finishes booting\n" +
            "on property:sys.boot_completed=1\n" +
            "    start dsu_boot_completed\n\n" +
            "service dsu_early_logger /system/bin/sh /system/bin/dsu_early_logger.sh\n" +
            "    user root\n" +
            "    group root system\n" +
            "    disabled\n" +
            "    oneshot\n\n" +
            "service dsu_boot_logger /system/bin/sh /system/bin/dsu_boot_logger.sh\n" +
            "    user root\n" +
            "    group root log system\n" +
            "    disabled\n" +
            "    oneshot\n\n" +
            "service dsu_boot_completed /system/bin/sh /system/bin/dsu_boot_completed.sh\n" +
            "    user root\n" +
            "    group root log system\n" +
            "    disabled\n" +
            "    oneshot\n";

    public static final String EARLY_LOGGER_SH =
            "#!/system/bin/sh\n" +
            "# DSU Next Phase 1: Early Boot Logger (executed at post-fs)\n\n" +
            "echo \"DSU_NEXT: [Phase 1] Early boot logger initialized at post-fs\" > /dev/kmsg 2>/dev/null\n\n" +
            "mkdir -p /dev/dsu_boot 2>/dev/null\n" +
            "chmod 777 /dev/dsu_boot 2>/dev/null\n\n" +
            "# Continuous kernel dmesg stream to RAM buffer\n" +
            "dmesg -w > /dev/dsu_boot/early_dmesg.txt 2>/dev/null &\n\n" +
            "# Background loop waiting for logd socket to capture early logcat\n" +
            "(\n" +
            "    for i in $(seq 1 200); do\n" +
            "        if [ -e /dev/socket/logd ]; then\n" +
            "            echo \"DSU_NEXT: [Phase 1] logd socket active, capturing early logcat\" > /dev/kmsg 2>/dev/null\n" +
            "            logcat -b all -v threadtime -f /dev/dsu_boot/early_logcat.txt &\n" +
            "            break\n" +
            "        fi\n" +
            "        sleep 0.05\n" +
            "    done\n" +
            ") &\n";

    public static final String BOOT_LOGGER_SH =
            "#!/system/bin/sh\n" +
            "# DSU Next Phase 2: Persistent Boot Logger (executed at post-fs-data)\n\n" +
            "echo \"DSU_NEXT: [Phase 2] post-fs-data reached, initializing persistent logging\" > /dev/kmsg 2>/dev/null\n\n" +
            "LOG_DIR=\"/data/local/tmp/dsu_boot\"\n" +
            "mkdir -p \"$LOG_DIR\" 2>/dev/null\n" +
            "chmod 777 \"$LOG_DIR\" 2>/dev/null\n\n" +
            "# Flush early logs from /dev/dsu_boot into persistent /data/local/tmp/dsu_boot/\n" +
            "if [ -d /dev/dsu_boot ]; then\n" +
            "    cp -f /dev/dsu_boot/* \"$LOG_DIR/\" 2>/dev/null\n" +
            "fi\n\n" +
            "# Record system diagnostics header\n" +
            "{\n" +
            "    echo \"==========================================\"\n" +
            "    echo \"       DSU NEXT BOOT DIAGNOSTICS          \"\n" +
            "    echo \"==========================================\"\n" +
            "    echo \"Date/Time       : $(date 2>/dev/null || uptime)\"\n" +
            "    echo \"Uptime          : $(uptime 2>/dev/null)\"\n" +
            "    echo \"Android Release : $(getprop ro.build.version.release)\"\n" +
            "    echo \"SDK Version     : $(getprop ro.build.version.sdk)\"\n" +
            "    echo \"Build ID        : $(getprop ro.build.id)\"\n" +
            "    echo \"Fingerprint     : $(getprop ro.build.fingerprint)\"\n" +
            "    echo \"Device / Model  : $(getprop ro.product.model) ($(getprop ro.product.device))\"\n" +
            "    echo \"Manufacturer    : $(getprop ro.product.manufacturer)\"\n" +
            "    echo \"SELinux Status  : $(getenforce 2>/dev/null || echo 'Unknown')\"\n" +
            "    echo \"Kernel Version  : $(uname -a 2>/dev/null || cat /proc/version)\"\n" +
            "    echo \"==========================================\"\n" +
            "    echo \"\"\n" +
            "} > \"$LOG_DIR/boot_info.txt\"\n\n" +
            "# Stream full continuous logcat to persistent disk\n" +
            "logcat -b all -v threadtime -f \"$LOG_DIR/logcat.txt\" &\n\n" +
            "# Stream full continuous kernel dmesg to persistent disk\n" +
            "dmesg -w > \"$LOG_DIR/dmesg.txt\" 2>&1 &\n";

    public static final String BOOT_COMPLETED_SH =
            "#!/system/bin/sh\n" +
            "# DSU Next Phase 3: Finalizing boot logs on sys.boot_completed=1\n\n" +
            "echo \"DSU_NEXT: [Phase 3] sys.boot_completed=1 reached! Finalizing boot logs\" > /dev/kmsg 2>/dev/null\n\n" +
            "LOG_DIR=\"/data/local/tmp/dsu_boot\"\n" +
            "STORAGE_DIR=\"/sdcard/DSU_Logs\"\n\n" +
            "mkdir -p \"$STORAGE_DIR\" 2>/dev/null\n" +
            "chmod 777 \"$STORAGE_DIR\" 2>/dev/null\n\n" +
            "if [ -d \"$LOG_DIR\" ]; then\n" +
            "    cp -r \"$LOG_DIR\"/* \"$STORAGE_DIR/\" 2>/dev/null\n" +
            "fi\n\n" +
            "logcat -b all -d -v threadtime > \"$STORAGE_DIR/boot_logcat.txt\" 2>/dev/null\n" +
            "dmesg > \"$STORAGE_DIR/boot_dmesg.txt\" 2>/dev/null\n" +
            "[ -f \"$LOG_DIR/boot_info.txt\" ] && cp -f \"$LOG_DIR/boot_info.txt\" \"$STORAGE_DIR/\" 2>/dev/null\n\n" +
            "echo \"DSU_NEXT: Boot logs flushed to $STORAGE_DIR\" > /dev/kmsg 2>/dev/null\n";

    public static boolean inject(String workspaceName) throws IOException {
        File workspace = WorkspaceStore.existing(workspaceName);
        Properties props = WorkspaceStore.readProps(workspace);
        String partition = props.getProperty(WorkspaceStore.K_PARTITION, WorkspaceStore.PARTITION);

        File partitionDir = new File(workspace, partition);
        if (!partitionDir.isDirectory()) {
            throw new IOException("Partition directory not found: " + partitionDir);
        }

        // 1. Write /etc/init/dsu_boot_logger.rc
        File etcInit = new File(partitionDir, "etc/init");
        if (!etcInit.isDirectory() && !etcInit.mkdirs()) {
            throw new IOException("Cannot create directory " + etcInit);
        }
        File rcFile = new File(etcInit, "dsu_boot_logger.rc");
        writeFile(rcFile, RC_CONTENT);

        // 2. Write /bin/ scripts
        File binDir = new File(partitionDir, "bin");
        if (!binDir.isDirectory() && !binDir.mkdirs()) {
            throw new IOException("Cannot create directory " + binDir);
        }
        File earlySh = new File(binDir, "dsu_early_logger.sh");
        File bootSh = new File(binDir, "dsu_boot_logger.sh");
        File completedSh = new File(binDir, "dsu_boot_completed.sh");

        writeFile(earlySh, EARLY_LOGGER_SH);
        writeFile(bootSh, BOOT_LOGGER_SH);
        writeFile(completedSh, BOOT_COMPLETED_SH);

        // Ensure executable permissions
        earlySh.setExecutable(true, false);
        bootSh.setExecutable(true, false);
        completedSh.setExecutable(true, false);

        // 3. Update config/<partition>_fs_config
        File configDir = new File(workspace, WorkspaceStore.CONFIG_DIR);
        if (configDir.isDirectory()) {
            File fsConfig = new File(configDir, partition + "_fs_config");
            appendConfigLines(fsConfig, new String[]{
                    partition + "/etc/init/dsu_boot_logger.rc 0 0 644",
                    partition + "/bin/dsu_early_logger.sh 0 0 755",
                    partition + "/bin/dsu_boot_logger.sh 0 0 755",
                    partition + "/bin/dsu_boot_completed.sh 0 0 755"
            });

            File fileContexts = new File(configDir, partition + "_file_contexts");
            appendConfigLines(fileContexts, new String[]{
                    "/" + partition + "/etc/init/dsu_boot_logger\\.rc u:object_r:system_file:s0",
                    "/" + partition + "/bin/dsu_early_logger\\.sh u:object_r:system_file:s0",
                    "/" + partition + "/bin/dsu_boot_logger\\.sh u:object_r:system_file:s0",
                    "/" + partition + "/bin/dsu_boot_completed\\.sh u:object_r:system_file:s0"
            });
        }

        props.setProperty("boot_logger_injected", "true");
        WorkspaceStore.writeProps(workspace, props);
        Log.i(TAG, "Early Boot Logger injected into workspace " + workspaceName);
        return true;
    }

    private static void writeFile(File file, String content) throws IOException {
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(content);
        }
    }

    private static void appendConfigLines(File file, String[] lines) throws IOException {
        if (!file.exists()) {
            return;
        }
        List<String> existing = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                existing.add(line);
            }
        }
        boolean modified = false;
        for (String line : lines) {
            String prefix = line.split(" ")[0];
            boolean found = false;
            for (String exist : existing) {
                if (exist.startsWith(prefix)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                existing.add(line);
                modified = true;
            }
        }
        if (modified) {
            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                for (String l : existing) {
                    writer.write(l);
                    writer.write("\n");
                }
            }
        }
    }
}
