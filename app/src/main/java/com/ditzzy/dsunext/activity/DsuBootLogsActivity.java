package com.ditzzy.dsunext.activity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.ditzzy.dsunext.IWorkspaceService;
import com.ditzzy.dsunext.R;
import com.ditzzy.dsunext.databinding.ActivityBootLogsBinding;
import com.ditzzy.dsunext.service.WorkspaceRootService;
import com.ditzzy.dsunext.ui.InsetsUtils;
import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.ipc.RootService;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DsuBootLogsActivity extends AppCompatActivity {
    private static final String TAG = "DsuBootLogsActivity";

    private enum Tab { LOGCAT, DMESG, PSTORE, INFO }

    private ActivityBootLogsBinding binding;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private IWorkspaceService service;
    private boolean bound = false;

    private Tab currentTab = Tab.LOGCAT;
    private String rawLogcat = "";
    private String rawDmesg = "";
    private String rawPstore = "";
    private String rawInfo = "";
    private String statusMessage = "";
    private boolean hasLogs = false;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IWorkspaceService.Stub.asInterface(binder);
            fetchLogs();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        binding = ActivityBootLogsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.toolbar.setNavigationOnClickListener(v -> finish());
        InsetsUtils.padBottomAndSides(binding.scrollView);

        setupTabs();
        setupSearch();
        setupButtons();

        connectRootService();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bound) {
            try {
                RootService.unbind(connection);
            } catch (Exception ignored) {
            }
            bound = false;
        }
        io.shutdown();
        binding = null;
    }

    private void setupTabs() {
        binding.tabLogcat.setOnClickListener(v -> selectTab(Tab.LOGCAT));
        binding.tabDmesg.setOnClickListener(v -> selectTab(Tab.DMESG));
        binding.tabPstore.setOnClickListener(v -> selectTab(Tab.PSTORE));
        binding.tabInfo.setOnClickListener(v -> selectTab(Tab.INFO));
    }

    private void selectTab(Tab tab) {
        currentTab = tab;
        updateTabStyles();
        renderCurrentTab();
    }

    private void updateTabStyles() {
        int tonal = com.google.android.material.R.attr.materialButtonTonalStyle;
        int outlined = com.google.android.material.R.attr.materialButtonOutlinedStyle;

        setButtonStyle(binding.tabLogcat, currentTab == Tab.LOGCAT);
        setButtonStyle(binding.tabDmesg, currentTab == Tab.DMESG);
        setButtonStyle(binding.tabPstore, currentTab == Tab.PSTORE);
        setButtonStyle(binding.tabInfo, currentTab == Tab.INFO);

        switch (currentTab) {
            case LOGCAT:
                binding.logStreamLabel.setText(R.string.dsu_boot_logs_tab_logcat);
                break;
            case DMESG:
                binding.logStreamLabel.setText(R.string.dsu_boot_logs_tab_dmesg);
                break;
            case PSTORE:
                binding.logStreamLabel.setText(R.string.dsu_boot_logs_tab_pstore);
                break;
            case INFO:
                binding.logStreamLabel.setText(R.string.dsu_boot_logs_tab_info);
                break;
        }
    }

    private void setButtonStyle(com.google.android.material.button.MaterialButton button, boolean selected) {
        button.setAlpha(selected ? 1.0f : 0.65f);
        button.setStrokeWidth(selected ? 4 : 1);
    }

    private void setupSearch() {
        binding.searchEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderCurrentTab();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void setupButtons() {
        binding.btnRefresh.setOnClickListener(v -> fetchLogs());

        binding.btnCopyTab.setOnClickListener(v -> {
            String content = binding.logTextContent.getText().toString();
            if (content.isEmpty()) {
                Toast.makeText(this, R.string.dsu_boot_logs_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("DSU Log", content));
            Toast.makeText(this, R.string.dsu_boot_logs_copied, Toast.LENGTH_SHORT).show();
        });

        binding.btnExportAll.setOnClickListener(v -> exportAllLogs());
    }

    private void connectRootService() {
        binding.loadingProgress.setVisibility(View.VISIBLE);
        binding.statusTitle.setText(R.string.dsu_boot_logs_scanning);

        Shell.getShell(shell -> {
            if (!shell.isRoot()) {
                runOnUiThread(() -> {
                    binding.loadingProgress.setVisibility(View.GONE);
                    binding.statusTitle.setText(R.string.requires_root);
                    binding.statusDesc.setText(R.string.dsu_boot_logs_root_required);
                });
                return;
            }
            RootService.bind(new Intent(this, WorkspaceRootService.class), connection);
            bound = true;
        });
    }

    private void fetchLogs() {
        binding.loadingProgress.setVisibility(View.VISIBLE);
        binding.statusTitle.setText(R.string.dsu_boot_logs_scanning);

        io.execute(() -> {
            IWorkspaceService s = service;
            if (s == null) {
                runOnUiThread(() -> {
                    binding.loadingProgress.setVisibility(View.GONE);
                    binding.statusTitle.setText(R.string.gsi_service_unavailable);
                });
                return;
            }

            try {
                String resultJson = s.readDsuBootLogs();
                JSONObject json = new JSONObject(resultJson);

                hasLogs = json.optBoolean("has_logs", false);
                rawLogcat = json.optString("logcat", "");
                rawDmesg = json.optString("dmesg", "");
                rawPstore = json.optString("pstore", "");
                rawInfo = json.optString("boot_info", "");
                statusMessage = json.optString("status_message", "");

                runOnUiThread(() -> {
                    binding.loadingProgress.setVisibility(View.GONE);
                    if (hasLogs) {
                        binding.statusIcon.setImageResource(R.drawable.ic_check);
                        binding.statusTitle.setText(R.string.dsu_boot_logs_found);
                    } else {
                        binding.statusIcon.setImageResource(R.drawable.ic_info);
                        binding.statusTitle.setText(R.string.dsu_boot_logs_not_found);
                    }
                    binding.statusDesc.setText(statusMessage);
                    updateTabStyles();
                    renderCurrentTab();
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed reading logs", e);
                runOnUiThread(() -> {
                    binding.loadingProgress.setVisibility(View.GONE);
                    binding.statusTitle.setText(R.string.gsi_service_unavailable);
                    binding.statusDesc.setText(e.getMessage());
                });
            }
        });
    }

    private void renderCurrentTab() {
        String source;
        switch (currentTab) {
            case LOGCAT:
                source = rawLogcat;
                break;
            case DMESG:
                source = rawDmesg;
                break;
            case PSTORE:
                source = rawPstore;
                break;
            case INFO:
                source = rawInfo;
                break;
            default:
                source = "";
                break;
        }

        String filter = binding.searchEdit.getText() != null
                ? binding.searchEdit.getText().toString().trim().toLowerCase(Locale.ROOT)
                : "";

        if (source.isEmpty()) {
            binding.logTextContent.setText(getString(R.string.dsu_boot_logs_empty));
            return;
        }

        if (filter.isEmpty()) {
            binding.logTextContent.setText(source);
            return;
        }

        StringBuilder filtered = new StringBuilder();
        String[] lines = source.split("\n");
        int count = 0;
        for (String line : lines) {
            if (line.toLowerCase(Locale.ROOT).contains(filter)) {
                filtered.append(line).append("\n");
                count++;
            }
        }

        if (count == 0) {
            binding.logTextContent.setText(getString(R.string.dsu_boot_logs_no_matches, filter));
        } else {
            binding.logTextContent.setText(filtered.toString());
        }
    }

    private void exportAllLogs() {
        StringBuilder bundle = new StringBuilder();
        bundle.append("========================================\n");
        bundle.append("     DSU NEXT COMPLETE BOOT LOG REPORT  \n");
        bundle.append("========================================\n\n");

        bundle.append("--- [1] SYSTEM & BOOT INFO ---\n");
        bundle.append(rawInfo.isEmpty() ? "No boot info recorded.\n" : rawInfo).append("\n\n");

        bundle.append("--- [2] KERNEL PANIC / PSTORE RAMOOPS ---\n");
        bundle.append(rawPstore.isEmpty() ? "No kernel pstore recorded.\n" : rawPstore).append("\n\n");

        bundle.append("--- [3] KERNEL DMESG ---\n");
        bundle.append(rawDmesg.isEmpty() ? "No dmesg recorded.\n" : rawDmesg).append("\n\n");

        bundle.append("--- [4] ANDROID LOGCAT ---\n");
        bundle.append(rawLogcat.isEmpty() ? "No logcat recorded.\n" : rawLogcat).append("\n");

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "DSU Next Boot Diagnostics");
        shareIntent.putExtra(Intent.EXTRA_TEXT, bundle.toString());
        startActivity(Intent.createChooser(shareIntent, getString(R.string.dsu_boot_logs_export)));
    }
}
