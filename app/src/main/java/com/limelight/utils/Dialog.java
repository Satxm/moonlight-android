package com.limelight.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.view.WindowManager;
import android.widget.Button;

import com.limelight.R;

import java.util.ArrayList;

public class Dialog implements Runnable {
    private final String title;
    private final String message;
    private final Activity activity;
    private final Runnable runOnDismiss;

    private AlertDialog alert;

    private static final ArrayList<Dialog> rundownDialogs = new ArrayList<>();

    private boolean isDetailsDialog;

    private Dialog(Activity activity, String title, String message, Runnable runOnDismiss)
    {
        this.activity = activity;
        this.title = title;
        this.message = message;
        this.runOnDismiss = runOnDismiss;
        this.isDetailsDialog = false;
    }

    private Dialog(Activity activity, String title, String message, Runnable runOnDismiss, boolean isDetailsDialog)
    {
        this.activity = activity;
        this.title = title;
        this.message = message;
        this.runOnDismiss = runOnDismiss;
        this.isDetailsDialog = isDetailsDialog;
    }

    public static void closeDialogs()
    {
        synchronized (rundownDialogs) {
            for (Dialog d : rundownDialogs) {
                if (d.alert.isShowing()) {
                    d.alert.dismiss();
                }
            }

            rundownDialogs.clear();
        }
    }

    public static void displayDialog(final Activity activity, String title, String message, final boolean endAfterDismiss)
    {
        activity.runOnUiThread(new Dialog(activity, title, message, new Runnable() {
            @Override
            public void run() {
                if (endAfterDismiss) {
                    activity.finish();
                }
            }
        }));
    }

    public static void displayDetailsDialog(final Activity activity, String title, String message, final boolean endAfterDismiss)
    {
        activity.runOnUiThread(new Dialog(activity, title, message, new Runnable() {
            @Override
            public void run() {
                if (endAfterDismiss) {
                    activity.finish();
                }
            }
        }, true));
    }

    public static void displayDialog(Activity activity, String title, String message, Runnable runOnDismiss)
    {
        activity.runOnUiThread(new Dialog(activity, title, message, runOnDismiss));
    }

    @Override
    public void run() {
        // If we're dying, don't bother creating a dialog
        if (activity.isFinishing())
            return;

        if (isDetailsDialog) {
            createDetailsDialog();
        } else {
            createStandardDialog();
        }
    }

    private void createStandardDialog() {
        alert = new AlertDialog.Builder(activity, R.style.AppDialogStyle).create();

        alert.setTitle(title);
        alert.setMessage(message);
        alert.setCancelable(false);
        alert.setCanceledOnTouchOutside(false);
 
        alert.setButton(AlertDialog.BUTTON_POSITIVE, activity.getResources().getText(android.R.string.ok), new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface dialog, int which) {
                  synchronized (rundownDialogs) {
                      rundownDialogs.remove(Dialog.this);
                      alert.dismiss();
                  }

                  runOnDismiss.run();
              }
        });
        alert.setButton(AlertDialog.BUTTON_NEUTRAL, activity.getResources().getText(R.string.help), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                synchronized (rundownDialogs) {
                    rundownDialogs.remove(Dialog.this);
                    alert.dismiss();
                }

                runOnDismiss.run();

                HelpLauncher.launchTroubleshooting(activity);
            }
        });
        alert.setOnShowListener(new DialogInterface.OnShowListener(){

            @Override
            public void onShow(DialogInterface dialog) {
                // Set focus to the OK button by default
                Button button = alert.getButton(AlertDialog.BUTTON_POSITIVE);
                button.setFocusable(true);
                button.setFocusableInTouchMode(true);
                button.requestFocus();
            }
        });

        synchronized (rundownDialogs) {
            rundownDialogs.add(this);
            alert.show();
        }
        
        // 设置对话框透明度
        if (alert.getWindow() != null) {
            WindowManager.LayoutParams layoutParams = alert.getWindow().getAttributes();
            layoutParams.alpha = 0.8f;
            // layoutParams.dimAmount = 0.3f;
            alert.getWindow().setAttributes(layoutParams);
        }
    }

    private void createDetailsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, R.style.AppDialogStyle);
        
        // 使用自定义布局
        android.view.LayoutInflater inflater = activity.getLayoutInflater();
        android.view.View dialogView = inflater.inflate(R.layout.details_dialog, null);
        
        // 设置标题和内容
        android.widget.TextView titleView = dialogView.findViewById(R.id.detailsTitle);
        android.widget.TextView contentView = dialogView.findViewById(R.id.detailsContent);
        android.widget.ImageButton copyButton = dialogView.findViewById(R.id.copyButton);
        
        titleView.setText(title);
        contentView.setText(formatDetailsMessage(message));
        
        // 设置复制按钮点击事件
        copyButton.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                // 复制内容到剪贴板
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) 
                    activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                android.content.ClipData clip = android.content.ClipData.newPlainText(
                    activity.getString(R.string.copy_details), 
                    contentView.getText().toString()
                );
                clipboard.setPrimaryClip(clip);
                
                // 显示复制成功提示
                android.widget.Toast.makeText(activity, activity.getString(R.string.copy_success), android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        
        // 设置焦点管理和键盘导航
        copyButton.setFocusable(true);
        copyButton.setFocusableInTouchMode(true);
        contentView.setFocusable(true);
        contentView.setFocusableInTouchMode(true);
        
        // 设置键盘导航监听器
        copyButton.setOnKeyListener(new android.view.View.OnKeyListener() {
            @Override
            public boolean onKey(android.view.View v, int keyCode, android.view.KeyEvent event) {
                if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    switch (keyCode) {
                        case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                        case android.view.KeyEvent.KEYCODE_ENTER:
                            copyButton.performClick();
                            return true;
                        case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                            // 向下导航到内容区域
                            contentView.requestFocus();
                            return true;
                        case android.view.KeyEvent.KEYCODE_DPAD_UP:
                            // 向上导航到标题区域
                            titleView.requestFocus();
                            return true;
                        case android.view.KeyEvent.KEYCODE_BACK:
                        case android.view.KeyEvent.KEYCODE_ESCAPE:
                            // 关闭对话框
                            alert.dismiss();
                            return true;
                    }
                }
                return false;
            }
        });
        
        contentView.setOnKeyListener(new android.view.View.OnKeyListener() {
            @Override
            public boolean onKey(android.view.View v, int keyCode, android.view.KeyEvent event) {
                if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    switch (keyCode) {
                        case android.view.KeyEvent.KEYCODE_DPAD_UP:
                            // 向上导航到复制按钮
                            copyButton.requestFocus();
                            return true;
                        case android.view.KeyEvent.KEYCODE_BACK:
                        case android.view.KeyEvent.KEYCODE_ESCAPE:
                            // 关闭对话框
                            alert.dismiss();
                            return true;
                    }
                }
                return false;
            }
        });
        
        // 为标题区域也添加键盘导航支持
        titleView.setOnKeyListener(new android.view.View.OnKeyListener() {
            @Override
            public boolean onKey(android.view.View v, int keyCode, android.view.KeyEvent event) {
                if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    switch (keyCode) {
                        case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                            // 向下导航到复制按钮
                            copyButton.requestFocus();
                            return true;
                        case android.view.KeyEvent.KEYCODE_BACK:
                        case android.view.KeyEvent.KEYCODE_ESCAPE:
                            // 关闭对话框
                            alert.dismiss();
                            return true;
                    }
                }
                return false;
            }
        });
        
        builder.setView(dialogView);
        builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                synchronized (rundownDialogs) {
                    rundownDialogs.remove(Dialog.this);
                    alert.dismiss();
                }
                runOnDismiss.run();
            }
        });
        
        alert = builder.create();
        alert.setCancelable(false);
        alert.setCanceledOnTouchOutside(false);

        synchronized (rundownDialogs) {
            rundownDialogs.add(this);
            alert.show();
        }
        
        // 设置对话框透明度
        if (alert.getWindow() != null) {
            WindowManager.LayoutParams layoutParams = alert.getWindow().getAttributes();
            layoutParams.alpha = 0.8f;
            // layoutParams.dimAmount = 0.3f;
            alert.getWindow().setAttributes(layoutParams);
        }
        
        // 设置初始焦点到复制按钮
        alert.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                copyButton.requestFocus();
            }
        });
        
        // 为对话框设置键盘事件监听器
        alert.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface dialog, int keyCode, android.view.KeyEvent event) {
                if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    switch (keyCode) {
                        case android.view.KeyEvent.KEYCODE_BACK:
                        case android.view.KeyEvent.KEYCODE_ESCAPE:
                            // 关闭对话框
                            alert.dismiss();
                            return true;
                    }
                }
                return false;
            }
        });
    }

    private String formatDetailsMessage(String message) {
        String[] lines = message.split("\n");
        StringBuilder formatted = new StringBuilder();
        
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                formatted.append("\n");
                continue;
            }
            
            if (line.contains(": ")) {
                String[] parts = line.split(": ", 2);
                if (parts.length == 2) {
                    String label = parts[0].trim();
                    String value = parts[1].trim();
                    
                    // 根据不同的标签使用不同的图标
                    String icon = getIconForLabel(label);
                    String tlabel = getTextForLabel(label);
                    String tvalue = getTextForLabel(value);
                    formatted.append(icon).append(" ").append(tlabel).append(" ").append(tvalue).append("\n");
                } else {
                    formatted.append(line).append("\n");
                }
            } else {
                formatted.append(line).append("\n");
            }
        }
        
        return formatted.toString();
    }
    
    private String getIconForLabel(String label) {
        switch (label.toLowerCase()) {
            case "name":
                return "📱";
            case "state":
                return "🔄";
            case "uuid":
                return "🔑";
            case "id":
                return "🆔";
            case "address":
            case "local address":
            case "remote address":
            case "ipv6 address":
            case "manual address":
            case "active address":
                return "🌐";
            case "mac address":
                return "📡";
            case "pair state":
                return "🔗";
            case "running game id":
                return "🎮";
            case "https port":
                return "🔒";
            case "hdr supported":
                return "🎨";
            case "super cmds":
                return "⚡";
            default:
                return "🔹";
        }
    }
        private String getTextForLabel(String label) {
            Resources res = activity.getResources();
            switch (label.toLowerCase()) {
                case "name":
                    return res.getString(R.string.details_name);
                case "state":
                    return res.getString(R.string.details_state);
                case "uuid":
                    return res.getString(R.string.details_uuid);
                case "local address":
                    return res.getString(R.string.details_localaddress);
                case "remote address":
                    return res.getString(R.string.details_remoteaddress);
                case "ipv6 address":
                    return res.getString(R.string.details_ipv6address);
                case "manual address":
                    return res.getString(R.string.details_manualaddress);
                case "active address":
                    return res.getString(R.string.details_activeaddress);
                case "mac address":
                    return res.getString(R.string.details_macaddress);
                case "pair state":
                    return res.getString(R.string.details_pairstate);
                case "running game id":
                    return res.getString(R.string.details_runninggameid);
                case "https port":
                    return res.getString(R.string.details_httpsport);
                case "online":
                    return res.getString(R.string.details_online);
                case "offline":
                    return res.getString(R.string.details_offline);
                case "unknown":
                    return res.getString(R.string.details_unknown);
                case "paired":
                    return res.getString(R.string.details_paired);
                case "not_paired":
                    return res.getString(R.string.details_not_paired);
                default:
                    return label;
            }
    }

}
