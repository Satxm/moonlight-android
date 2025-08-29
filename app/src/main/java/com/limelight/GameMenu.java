package com.limelight;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.text.Html;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.limelight.binding.input.GameInputDevice;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.utils.KeyCodeMapper;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.app.Activity;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.widget.EditText;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * 提供游戏流媒体进行中的选项菜单
 * 在游戏活动中按返回键时显示
 */
public class GameMenu {

    // 常量定义
    private static final long TEST_GAME_FOCUS_DELAY = 10L;
    private static final long KEY_UP_DELAY = 25L;
    private static final long SLEEP_DELAY = 200L;
    private static final float DIALOG_ALPHA = 0.7f;
    private static final float DIALOG_DIM_AMOUNT = 0.3f;

    // 用于存储自定义按键的 SharedPreferences 文件名和键名
    private static final String PREF_NAME = "custom_special_keys";
    private static final String KEY_NAME = "data";

    // 图标映射缓存
    private static final Map<String, Integer> ICON_MAP = new HashMap<>();

    static {
        ICON_MAP.put("game_menu_toggle_keyboard", R.drawable.ic_keyboard_cute);
        ICON_MAP.put("game_menu_toggle_performance_overlay", R.drawable.ic_performance_cute);
        ICON_MAP.put("game_menu_toggle_virtual_controller", R.drawable.ic_controller_cute);
        ICON_MAP.put("game_menu_disconnect", R.drawable.ic_disconnect_cute);
        ICON_MAP.put("game_menu_send_keys", R.drawable.ic_send_keys_cute);
        ICON_MAP.put("game_menu_toggle_host_keyboard", R.drawable.ic_host_keyboard);
        ICON_MAP.put("game_menu_disconnect_and_quit", R.drawable.ic_btn_quit);
        ICON_MAP.put("game_menu_cancel", R.drawable.ic_cancel_cute);
        ICON_MAP.put("mouse_mode", R.drawable.ic_mouse_cute);
        ICON_MAP.put("game_menu_mouse_emulation", R.drawable.ic_mouse_emulation_cute);
    }

    /**
     * 菜单选项类
     */
    public static class MenuOption {
        private final String label;
        private final boolean withGameFocus;
        private final Runnable runnable;
        private final String iconKey; // 用于图标映射的键
        private final boolean showIcon; // 是否显示图标
        private final boolean keepDialog; // 点击此项时是否保留对话框并替换左侧菜单（用于二级菜单）

        public MenuOption(String label, boolean withGameFocus, Runnable runnable) {
            this(label, withGameFocus, runnable, null, true);
        }

        public MenuOption(String label, Runnable runnable) {
            this(label, false, runnable, null, true);
        }

        public MenuOption(String label, boolean withGameFocus, Runnable runnable, String iconKey) {
            this(label, withGameFocus, runnable, iconKey, true);
        }

        public MenuOption(String label, boolean withGameFocus, Runnable runnable, String iconKey, boolean showIcon) {
            this(label, withGameFocus, runnable, iconKey, showIcon, false);
        }

        public MenuOption(String label, boolean withGameFocus, Runnable runnable, String iconKey, boolean showIcon, boolean keepDialog) {
            this.label = label;
            this.withGameFocus = withGameFocus;
            this.runnable = runnable;
            this.iconKey = iconKey;
            this.showIcon = showIcon;
            this.keepDialog = keepDialog;
        }

        public String getLabel() { return label; }
        public boolean isWithGameFocus() { return withGameFocus; }
        public Runnable getRunnable() { return runnable; }

        public String getIconKey() {
            return iconKey;
        }

        public boolean isShowIcon() {
            return showIcon;
        }

        public boolean isKeepDialog() {
            return keepDialog;
        }
    }

    // 实例变量
    private final Game game;
    private final NvApp app;
    private final NvConnection conn;
    private final GameInputDevice device;
    private final Handler handler;
    // 当前激活的对话框（如果有）
    private AlertDialog activeDialog;
    // 当前激活对话框所用的自定义视图引用（便于内部替换）
    private View activeCustomView;
    // 标志：上一次运行的选项是否打开了子菜单（由 showSubMenu 设置）
    private boolean lastActionOpenedSubmenu = false;
    // 菜单历史栈，用于二级/多级菜单的回退
    private final java.util.Deque<MenuState> menuStack = new java.util.ArrayDeque<>();

    public GameMenu(Game game, NvApp app, NvConnection conn, GameInputDevice device) {
        this.game = game;
        this.app = app;
        this.conn = conn;
        this.device = device;
        this.handler = new Handler();

        showMenu();
    }

    /**
     * 菜单状态，用于回退
     */
    private static class MenuState {
        final String title;
        final MenuOption[] normalOptions;

        MenuState(String title, MenuOption[] normalOptions) {
            this.title = title;
            this.normalOptions = normalOptions;
        }
    }

    /**
     * 获取字符串资源
     */
    private String getString(int id) {
        return game.getResources().getString(id);
    }

    /**
     * 键盘修饰符枚举
     */
    private enum KeyModifier {
        SHIFT((short) KeyboardTranslator.VK_LSHIFT, KeyboardPacket.MODIFIER_SHIFT),
        CTRL((short) KeyboardTranslator.VK_LCONTROL, KeyboardPacket.MODIFIER_CTRL),
        META((short) KeyboardTranslator.VK_LWIN, KeyboardPacket.MODIFIER_META),
        ALT((short) KeyboardTranslator.VK_MENU, KeyboardPacket.MODIFIER_ALT);

        final short keyCode;
        final byte modifier;

        KeyModifier(short keyCode, byte modifier) {
            this.keyCode = keyCode;
            this.modifier = modifier;
        }

        public static byte getModifier(short key) {
            for (KeyModifier km : values()) {
                if (km.keyCode == key) {
                    return km.modifier;
                }
            }
            return 0;
        }
    }

    /**
     * 获取键盘修饰符
     */
    private static byte getModifier(short key) {
        return KeyModifier.getModifier(key);
    }

    /**
     * 断开连接并退出
     */
    private void disconnectAndQuit() {
        try {
            game.disconnect();
            conn.doStopAndQuit();
        } catch (IOException | XmlPullParserException e) {
            Toast.makeText(game, "断开连接时发生错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 发送键盘按键序列
     */
    private void sendKeys(short[] keys) {
        if (keys == null || keys.length == 0) {
            return;
        }

        final byte[] modifier = { (byte) 0 };

        // 按下所有按键
        for (short key : keys) {
            conn.sendKeyboardInput(key, KeyboardPacket.KEY_DOWN, modifier[0], (byte) 0);
            modifier[0] |= getModifier(key);
        }

        // 延迟后释放按键
        handler.postDelayed(() -> {
            for (int pos = keys.length - 1; pos >= 0; pos--) {
                short key = keys[pos];
                modifier[0] &= ~getModifier(key);
                conn.sendKeyboardInput(key, KeyboardPacket.KEY_UP, modifier[0], (byte) 0);
            }
        }, KEY_UP_DELAY);
    }

    /**
     * 在游戏获得焦点时运行任务
     */
    private void runWithGameFocus(Runnable runnable) {
        if (game.isFinishing()) {
            return;
        }

        if (!game.hasWindowFocus()) {
            handler.postDelayed(() -> runWithGameFocus(runnable), TEST_GAME_FOCUS_DELAY);
            return;
        }

        runnable.run();
    }

    /**
     * 执行菜单选项
     */
    private void run(MenuOption option) {
        if (option == null || option.getRunnable() == null) {
            return;
        }

        if (option.isWithGameFocus()) {
            runWithGameFocus(option.getRunnable());
        } else {
            option.getRunnable().run();
        }
    }

    /**
     * 切换增强触摸模式
     */
    private void toggleEnhancedTouch() {
        game.prefConfig.enableEnhancedTouch = !game.prefConfig.enableEnhancedTouch;
        String message = game.prefConfig.enableEnhancedTouch ? "增强式多点触控已开启" : "经典鼠标模式已开启";
        Toast.makeText(game, message, Toast.LENGTH_SHORT).show();
    }

    /**
     * 显示一个菜单列表，用于在“增强式多点触控”,“经典鼠标模式”,“触控板模式”之间切换。
     */
    private void showTouchModeMenu() {
        boolean isEnhancedTouch = game.prefConfig.enableEnhancedTouch;
        boolean isTouchscreenTrackpad = game.prefConfig.touchscreenTrackpad;

        MenuOption[] touchModeOptions = {
                new MenuOption(
                        getString(R.string.game_menu_touch_mode_enhanced),
                        isEnhancedTouch,
                        () -> {
                            game.prefConfig.enableEnhancedTouch = true;
                            game.setTouchMode(false);
                            Toast.makeText(game, getString(R.string.toast_touch_mode_enhanced_on), Toast.LENGTH_SHORT).show();
                        },
                        null,
                        false
                ),
                new MenuOption(
                        getString(R.string.game_menu_touch_mode_classic),
                        !isEnhancedTouch,
                        () -> {
                            game.prefConfig.enableEnhancedTouch = false;
                            game.setTouchMode(false);
                            Toast.makeText(game, getString(R.string.toast_touch_mode_classic_on), Toast.LENGTH_SHORT).show();
                        },
                        null,
                        false
                ),
                new MenuOption(
                        getString(R.string.game_menu_touch_mode_trackpad),
                        isTouchscreenTrackpad,
                        () -> {
                            game.setTouchMode(true);
                            Toast.makeText(game, getString(R.string.toast_touch_mode_trackpad_on), Toast.LENGTH_SHORT).show();
                        },
                        null,
                        false
                )
        };

        // 3. 显示为子菜单（在活动对话框内替换普通菜单区域）
        showSubMenu(getString(R.string.game_menu_switch_touch_mode), touchModeOptions);
    }

    /**
     * 切换麦克风开关
     */
    private void toggleMicrophone() {
        // 切换GameView中麦克风按钮的显示/隐藏状态
        game.toggleMicrophoneButton();
    }

    /**
     * 切换王冠功能
     */
    private void toggleCrownFeature() {
        game.setCrownFeatureEnabled(!game.isCrownFeatureEnabled());
        Toast.makeText(game, game.isCrownFeatureEnabled() ? getString(R.string.crown_switch_to_crown) : getString(R.string.crown_switch_to_normal), Toast.LENGTH_SHORT).show();
    }



    /**
     * 调整码率
     */
    private void adjustBitrate(int bitrateKbps) {
        try {
            // 显示正在调整的提示
            Toast.makeText(game, "正在调整码率...", Toast.LENGTH_SHORT).show();
            
            // 调用码率调整，使用回调等待API真正返回结果
            conn.setBitrate(bitrateKbps, new NvConnection.BitrateAdjustmentCallback() {
                @Override
                public void onSuccess(int newBitrate) {
                    // API成功返回，在主线程显示成功消息
                    game.runOnUiThread(() -> {
                        try {
                            String successMessage = String.format(getString(R.string.game_menu_bitrate_adjustment_success), newBitrate / 1000);
                            Toast.makeText(game, successMessage, Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            LimeLog.warning("Failed to show success toast: " + e.getMessage());
                        }
                    });
                }

                @Override
                public void onFailure(String errorMessage) {
                    // API失败返回，在主线程显示错误消息
                    game.runOnUiThread(() -> {
                        try {
                            String errorMsg = getString(R.string.game_menu_bitrate_adjustment_failed) + ": " + errorMessage;
                            Toast.makeText(game, errorMsg, Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            LimeLog.warning("Failed to show error toast: " + e.getMessage());
                        }
                    });
                }
            });
            
        } catch (Exception e) {
            // 调用setBitrate时发生异常（如参数错误等）
            game.runOnUiThread(() -> {
                try {
                    Toast.makeText(game, getString(R.string.game_menu_bitrate_adjustment_failed) + ": " + e.getMessage(), Toast.LENGTH_SHORT).show();
                } catch (Exception toastException) {
                    LimeLog.warning("Failed to show error toast: " + toastException.getMessage());
                }
            });
        }
    }



    /**
     * 显示菜单对话框
     */
    private void showMenuDialog(String title, MenuOption[] normalOptions, MenuOption[] superOptions) {
        AlertDialog.Builder builder = new AlertDialog.Builder(game, R.style.GameMenuDialogStyle);

        // 创建自定义视图
        View customView = createCustomView(builder);
        AlertDialog dialog = builder.create();
        // 记录为当前活动对话框
        this.activeDialog = dialog;
        this.activeCustomView = customView;

        // 设置自定义标题栏
        setupCustomTitleBar(customView, title);

        // 动态设置菜单列表区域高度
        setupMenuListHeight(customView);
        
        // 设置App名字显示
        setupAppNameDisplay(customView);

        // 设置快捷按钮
        setupQuickButtons(customView, dialog);

        // 设置普通菜单
        setupNormalMenu(customView, normalOptions, dialog);

        // 设置超级菜单
        setupSuperMenu(customView, superOptions, dialog);

        // 设置码率调整区域
        setupBitrateAdjustmentArea(customView, dialog);

        // 设置对话框属性
        setupDialogProperties(dialog);

        // 在对话框关闭时清理状态
        dialog.setOnDismissListener(d -> {
            // 清理活动引用和菜单栈
            if (this.activeDialog == dialog) this.activeDialog = null;
            if (this.activeCustomView != null) this.activeCustomView = null;
            menuStack.clear();
        });

        dialog.show();
    }

    /**
     * 创建自定义视图
     */
    private View createCustomView(AlertDialog.Builder builder) {
        LayoutInflater inflater = game.getLayoutInflater();
        View customView = inflater.inflate(R.layout.custom_dialog, null);
        builder.setView(customView);
        return customView;
    }

    /**
     * 动态设置菜单列表区域高度
     * 最大高度就是内容实际高度，不做屏幕高度约束
     */
    private void setupMenuListHeight(View customView) {
        customView.post(() -> {
            View menuListContainer = customView.findViewById(R.id.menuListContainer);
            if (menuListContainer == null) return;

            float density = game.getResources().getDisplayMetrics().density;
            int minHeight = (int) (220 * density);

            int contentHeight = 0;
            try {
                contentHeight = calculateContentHeight(menuListContainer);
            } catch (Exception ignored) {}

            int finalHeight = Math.max(minHeight, contentHeight);
            ViewGroup.LayoutParams lp = menuListContainer.getLayoutParams();
            if (lp != null) {
                lp.height = finalHeight > 0 ? finalHeight : minHeight;
                menuListContainer.setLayoutParams(lp);
            }
        });
    }

    /**
     * 计算内容实际高度
     * 使用性能优化的方式计算
     */
    private int calculateContentHeight(View container) {
        try {
            // 获取ListView
            ListView normalListView = container.findViewById(R.id.gameMenuList);
            ListView superListView = container.findViewById(R.id.superMenuList);

            int totalHeight = 0;
            int maxItems= 0;

            // 计算普通菜单高度
            if (normalListView != null && normalListView.getAdapter() != null) {
                int normalItemCount = normalListView.getAdapter().getCount();
                if (normalItemCount > 0) {
                    // 获取单个item的高度
                    View itemView = normalListView.getAdapter().getView(0, null, normalListView);
                    itemView.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                                   View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    int itemHeight = itemView.getMeasuredHeight();
                    maxItems = normalItemCount;
                    totalHeight = Math.max(totalHeight, itemHeight * maxItems);
                }
            }

            // 计算超级菜单高度
            if (superListView != null && superListView.getAdapter() != null) {
                int superItemCount = superListView.getAdapter().getCount();
                if (superItemCount > 0) {
                    // 获取单个item的高度
                    View itemView = superListView.getAdapter().getView(0, null, superListView);
                    itemView.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                                   View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    int itemHeight = itemView.getMeasuredHeight();
                    maxItems = superItemCount;
                    totalHeight = Math.max(totalHeight, itemHeight * maxItems);
                }
            }

            // 添加一些padding和margin
            totalHeight += (int) ((maxItems*2 + 8) * game.getResources().getDisplayMetrics().density);

            return totalHeight;

        } catch (Exception e) {
            // 如果计算失败，返回默认高度
            return (int) (220 * game.getResources().getDisplayMetrics().density);
        }
    }

    /**
     * 设置自定义标题
     */
    private void setupCustomTitleBar(View customView, String title) {
        TextView titleTextView = customView.findViewById(R.id.customTitleTextView);
        if (titleTextView != null) {
            titleTextView.setText(title);
        }
        
        // 设置王冠按钮的下划线样式和动态文本
        TextView crownToggleButton = customView.findViewById(R.id.btnCrownToggle);
        if (crownToggleButton != null) {
            // 根据王冠功能状态设置文本
            String crownText = game.isCrownFeatureEnabled() ? getString(R.string.crown_switch_to_normal) : getString(R.string.crown_switch_to_crown);
            crownToggleButton.setText(Html.fromHtml("<u>" + crownText + "</u>"));
            crownToggleButton.setOnClickListener(v -> {
                // 先切换状态
                boolean wasEnabled = game.isCrownFeatureEnabled();
                toggleCrownFeature();
                // 根据切换后的状态更新文本
                String newCrownText = !wasEnabled ? getString(R.string.crown_switch_to_normal) : getString(R.string.crown_switch_to_crown);
                crownToggleButton.setText(Html.fromHtml("<u>" + newCrownText + "</u>"));
            });
        }
    }

    /**
     * 设置当前串流应用信息 (名字、HDR支持)
     */
    private void setupAppNameDisplay(View customView) {
        try {
            // 获取当前串流应用的名字
            String appName = app.getAppName();
            // 获取当前串流应用的HDR支持状态
            boolean hdrSupported = app.isHdrSupported();
            
            // 找到App名字显示的TextView
            TextView appNameTextView = customView.findViewById(R.id.appNameTextView);
            appNameTextView.setText(appName + " (" + (hdrSupported ? "HDR: Supported" : "HDR: Unknown") + ")");
        } catch (Exception e) {
            // 如果获取失败，使用默认名字
            TextView appNameTextView = customView.findViewById(R.id.appNameTextView);
            if (appNameTextView != null) {
                appNameTextView.setText("Moonlight V+");
            }
        }
    }

    /**
     * 设置快捷按钮
     */
    private void setupQuickButtons(View customView, AlertDialog dialog) {
        // 创建动画
        android.view.animation.Animation scaleDown = android.view.animation.AnimationUtils.loadAnimation(game, R.anim.button_scale_animation);
        android.view.animation.Animation scaleUp = android.view.animation.AnimationUtils.loadAnimation(game, R.anim.button_scale_restore);
        
        // 设置按钮点击动画
        setupButtonWithAnimation(customView.findViewById(R.id.btnEsc), scaleDown, scaleUp, v ->
                sendKeys(new short[]{KeyboardTranslator.VK_ESCAPE}));

        setupButtonWithAnimation(customView.findViewById(R.id.btnWin), scaleDown, scaleUp, v ->
                sendKeys(new short[]{KeyboardTranslator.VK_LWIN}));

        setupButtonWithAnimation(customView.findViewById(R.id.btnHDR), scaleDown, scaleUp, v ->
                sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_MENU, KeyboardTranslator.VK_B}));

        // 设置麦克风按钮，根据设置决定是否启用
        View micButton = customView.findViewById(R.id.btnMic);
        if (game.prefConfig != null && game.prefConfig.enableMic) {
            // 麦克风重定向已开启，启用按钮
            setupButtonWithAnimation(micButton, scaleDown, scaleUp, v -> toggleMicrophone());
        } else {
            // 麦克风重定向未开启，禁用按钮
            micButton.setEnabled(false);
            micButton.setAlpha(0.5f);
            // 设置禁用图标
            if (micButton instanceof android.widget.Button) {
                android.widget.Button button = (android.widget.Button) micButton;
                button.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_mic_gm_disabled, 0, 0, 0);
            }
            micButton.setOnClickListener(v -> {
                // 显示提示信息
                Toast.makeText(game, "请在设置中开启麦克风重定向", Toast.LENGTH_SHORT).show();
            });
        }

        setupButtonWithAnimation(customView.findViewById(R.id.btnSleep), scaleDown, scaleUp, v -> {
            sendKeys(new short[]{KeyboardTranslator.VK_LWIN, 88});
            handler.postDelayed(() -> sendKeys(new short[]{85, 83}), SLEEP_DELAY);
        });

        setupButtonWithAnimation(customView.findViewById(R.id.btnQuit), scaleDown, scaleUp, v -> disconnectAndQuit());
    }

    /**
     * 为按钮设置动画效果
     */
    @SuppressLint("ClickableViewAccessibility")
    private void setupButtonWithAnimation(View button, android.view.animation.Animation scaleDown,
                                          android.view.animation.Animation scaleUp, View.OnClickListener listener) {
        // 设置按钮样式
        if (button instanceof android.widget.Button) {
            android.widget.Button btn = (android.widget.Button) button;
            btn.setTextAppearance(game, R.style.GameMenuButtonStyle);
        }

        // 设置按钮支持焦点
        button.setFocusable(true);
        button.setClickable(true);
        button.setFocusableInTouchMode(true);

        // 设置触摸事件
        button.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.startAnimation(scaleDown);
                    // 添加按下状态的视觉反馈
                    v.setAlpha(0.8f);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.startAnimation(scaleUp);
                    // 恢复透明度
                    v.setAlpha(1.0f);
                    if (event.getAction() == MotionEvent.ACTION_UP) {
                        // 添加点击反馈
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                        listener.onClick(v);
                    }
                    break;
            }
            return true;
        });

        // 设置键盘事件支持（手柄和遥控器）
        setupButtonKeyListener(button, scaleDown, scaleUp, listener);
    }

    /**
     * 通用按钮键盘事件处理方法
     */
    private void setupButtonKeyListener(View button, android.view.animation.Animation scaleDown,
                                        android.view.animation.Animation scaleUp, View.OnClickListener listener) {
        button.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                    // 添加点击反馈
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                    // 播放动画
                    v.startAnimation(scaleDown);
                    v.postDelayed(() -> {
                        v.startAnimation(scaleUp);
                        listener.onClick(v);
                    }, 100);
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * 通用菜单设置方法
     */
    private void setupMenu(ListView listView, ArrayAdapter<MenuOption> adapter, AlertDialog dialog) {
        // 设置ListView支持手柄和遥控导航
        listView.setItemsCanFocus(true);

        listView.setOnItemClickListener((parent, view, pos, id) -> {
            MenuOption option = adapter.getItem(pos);
            // 在执行前清除子菜单打开标志
            lastActionOpenedSubmenu = false;
            if (option != null) {
                run(option);
            }

            // 根据选项或运行结果决定是否关闭 dialog：如果该选项需要保留 dialog（如打开二级菜单），或最近操作已打开子菜单，则不关闭
            boolean shouldKeep = (option != null && option.isKeepDialog()) || lastActionOpenedSubmenu;
            if (!shouldKeep) {
                dialog.dismiss();
            }
            // 重置标志
            lastActionOpenedSubmenu = false;
        });
    }

    /**
     * 设置普通菜单
     */
    private void setupNormalMenu(View customView, MenuOption[] normalOptions, AlertDialog dialog) {
        GameMenuAdapter normalAdapter = new GameMenuAdapter(game, normalOptions);
        ListView normalListView = customView.findViewById(R.id.gameMenuList);
        normalListView.setAdapter(normalAdapter);
        setupMenu(normalListView, normalAdapter, dialog);
    }

    /**
     * 在现有对话框中替换普通菜单区域为新的选项（用于二级菜单），并将当前状态推入栈以便回退
     */
    private void replaceNormalMenuInDialog(AlertDialog dialog, String title, MenuOption[] newNormalOptions, boolean pushToStack) {
        if (dialog == null || dialog.getWindow() == null) return;
        // 首先尝试使用保存的 custom view 引用
        View customView = this.activeCustomView;
        if (customView == null) {
            customView = dialog.findViewById(android.R.id.content);
        }
        // dialog 的自定义视图可能不在 android.R.id.content，尝试通过 getLayoutInflater 找到
        if (customView == null) {
            // 通过反向查找原始创建时的视图引用
            customView = dialog.getWindow().getDecorView().findViewById(android.R.id.content);
        }

        if (customView == null) return;

        // 更新标题
        TextView titleTextView = customView.findViewById(R.id.customTitleTextView);
        if (titleTextView != null && title != null) titleTextView.setText(title);

        // 更新普通菜单列表
        ListView normalListView = customView.findViewById(R.id.gameMenuList);
        if (normalListView != null) {
            GameMenuAdapter adapter = new GameMenuAdapter(game, newNormalOptions);
            normalListView.setAdapter(adapter);
            setupMenu(normalListView, adapter, dialog);
        }

        // 替换后重新计算并应用列表容器高度
        final View finalCustomView = customView;
        if (finalCustomView != null) {
            finalCustomView.post(() -> setupMenuListHeight(finalCustomView));
        }

        // 可选地推入栈
        if (pushToStack) {
            // 因为调用者可能已经将当前状态入栈，只有当需要时再入栈
            menuStack.push(new MenuState(title, newNormalOptions));
        }
    }

    /**
     * 在当前打开的 dialog 中显示一个子菜单（保持超级菜单不变）
     */
    private void showSubMenu(String title, MenuOption[] subOptions) {
        if (activeDialog != null && activeDialog.isShowing()) {
            // 表示接下来要打开子菜单，避免点击后被自动 dismiss
            lastActionOpenedSubmenu = true;
            // 尝试读取当前普通菜单并保存到栈，以便回退
            if (this.activeCustomView != null) {
                ListView normalListView = this.activeCustomView.findViewById(R.id.gameMenuList);
                if (normalListView != null && normalListView.getAdapter() != null) {
                    int count = normalListView.getAdapter().getCount();
                    MenuOption[] currentOptions = new MenuOption[count];
                    for (int i = 0; i < count; i++) {
                        currentOptions[i] = (MenuOption) normalListView.getAdapter().getItem(i);
                    }
                    menuStack.push(new MenuState(null, currentOptions));
                }
            }

            // 替换为子菜单（不再自动将子菜单入栈，因为已经保存了当前状态）
            replaceNormalMenuInDialog(activeDialog, title, subOptions, false);
        } else {
            // 没有活动 dialog，则创建新的
            showMenuDialog(title, subOptions, new MenuOption[0]);
        }
    }

    /**
     * 设置超级菜单
     */
    private void setupSuperMenu(View customView, MenuOption[] superOptions, AlertDialog dialog) {
        ListView superListView = customView.findViewById(R.id.superMenuList);

        if (superOptions.length > 0) {
            SuperMenuAdapter superAdapter = new SuperMenuAdapter(game, superOptions);
            superListView.setAdapter(superAdapter);
            setupMenu(superListView, superAdapter, dialog);
        } else {
            setupEmptySuperMenu(superListView);
        }
    }

    /**
     * 设置码率调整区域
     */
    private void setupBitrateAdjustmentArea(View customView, AlertDialog dialog) {
        View bitrateContainer = customView.findViewById(R.id.bitrateAdjustmentContainer);
        SeekBar bitrateSeekBar = customView.findViewById(R.id.bitrateSeekBar);
        TextView currentBitrateText = customView.findViewById(R.id.currentBitrateText);
        TextView bitrateValueText = customView.findViewById(R.id.bitrateValueText);
        ImageView bitrateTipIcon = customView.findViewById(R.id.bitrateTipIcon);

        if (bitrateContainer == null || bitrateSeekBar == null || 
            currentBitrateText == null || bitrateValueText == null || bitrateTipIcon == null) {
            return;
        }

        int currentBitrate = conn.getCurrentBitrate();
        int currentBitrateMbps = currentBitrate / 1000;

        currentBitrateText.setText(String.format(getString(R.string.game_menu_bitrate_current), currentBitrateMbps));

        // 设置滑动条
        bitrateSeekBar.setMax(1995); // 200000 - 500 = 199500，每100kbps一个单位
        bitrateSeekBar.setProgress((currentBitrate - 500) / 100);

        bitrateValueText.setText(String.format("%d Mbps", currentBitrateMbps));

        bitrateTipIcon.setOnClickListener(v -> {
            // 显示提示对话框
            new AlertDialog.Builder(game, R.style.AppDialogStyle)
                .setMessage(getString(R.string.game_menu_bitrate_tip))
                .setPositiveButton("懂了", null)
                .show();
        });

        // 添加延迟应用码率的机制
        final Handler bitrateHandler = new Handler();
        final Runnable bitrateApplyRunnable = new Runnable() {
            @Override
            public void run() {
                int newBitrate = (bitrateSeekBar.getProgress() * 100) + 500;
                adjustBitrate(newBitrate);
            }
        };

        bitrateSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    int newBitrate = (progress * 100) + 500; // 500 + progress * 100
                    int newBitrateMbps = newBitrate / 1000;
                    bitrateValueText.setText(String.format("%d Mbps", newBitrateMbps));
                    
                    bitrateHandler.removeCallbacks(bitrateApplyRunnable);
                    bitrateHandler.postDelayed(bitrateApplyRunnable, 500);
                }
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // 开始拖动时取消之前的延迟应用
                bitrateHandler.removeCallbacks(bitrateApplyRunnable);
            }
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 停止拖动时立即应用码率
                bitrateHandler.removeCallbacks(bitrateApplyRunnable);
                int newBitrate = (seekBar.getProgress() * 100) + 500;
                adjustBitrate(newBitrate);
            }
        });

        // 添加键盘/控制器事件支持
        bitrateSeekBar.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || 
                    keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                    // 控制器左右键改变滑动条值后，延迟应用码率
                    bitrateHandler.removeCallbacks(bitrateApplyRunnable);
                    bitrateHandler.postDelayed(bitrateApplyRunnable, 300);
                    return false;
                }
            }
            return false;
        });
    }

    /**
     * 设置空的超级菜单
     */
    private void setupEmptySuperMenu(ListView superListView) {
        View emptyView = LayoutInflater.from(game).inflate(R.layout.game_menu_super_empty, superListView, false);
        ViewGroup parent = (ViewGroup) superListView.getParent();
        parent.addView(emptyView);
        superListView.setEmptyView(emptyView);
        SuperMenuAdapter emptyAdapter = new SuperMenuAdapter(game, new MenuOption[0]);
        superListView.setAdapter(emptyAdapter);
    }

    /**
     * 设置对话框属性
     */
    private void setupDialogProperties(AlertDialog dialog) {
        if (dialog.getWindow() != null) {
            WindowManager.LayoutParams layoutParams = dialog.getWindow().getAttributes();
            layoutParams.alpha = DIALOG_ALPHA;
            layoutParams.dimAmount = DIALOG_DIM_AMOUNT;
            layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
            
            dialog.getWindow().setAttributes(layoutParams);
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.game_menu_dialog_bg);
        }
    }

    /**
     * 显示特殊按键菜单（从rew加载默认配置，可自定义和添加选项）
     */
    private void showSpecialKeysMenu() {
        List<MenuOption> options = new ArrayList<>();

        // 从 SharedPreferences 加载所有按键。
        // 如果是首次运行，则从 res/raw/default_special_keys.json 加载默认值。
        boolean hasKeys = loadAndAddAllKeys(options);

        // 添加 "添加自定义按键" 选项
        options.add(new MenuOption(getString(R.string.game_menu_add_custom_key), false, this::showAddCustomKeyDialog, null, false));

        // 如果存在任何按键 (默认或自定义)，则添加 "删除" 选项
        if (hasKeys) {
            options.add(new MenuOption(getString(R.string.game_menu_delete_custom_key), false, this::showDeleteKeysDialog, null, false));
        }

        // 添加 "取消" 选项
        options.add(new MenuOption(getString(R.string.game_menu_cancel), false, null, null, false));

        //  显示为子菜单
        showSubMenu(getString(R.string.game_menu_send_keys), options.toArray(new MenuOption[0]));
    }


    /**
     * 从 SharedPreferences 加载所有按键。
     * 如果 SharedPreferences 为空，则从 res/raw/default_special_keys.json 加载默认按键并保存。
     * @param options 用于填充菜单选项的列表
     * @return 如果成功加载了至少一个按键，则返回 true
     */
    private boolean loadAndAddAllKeys(List<MenuOption> options) {
        SharedPreferences preferences = game.getSharedPreferences(PREF_NAME, Activity.MODE_PRIVATE);
        String value = preferences.getString(KEY_NAME, "");

        // 如果 SharedPreferences 中没有数据（例如首次启动），则从 raw 资源文件加载默认按键
        if (TextUtils.isEmpty(value)) {
            value = readRawResourceAsString(R.raw.default_special_keys);
            if (!TextUtils.isEmpty(value)) {
                // 将从文件读取的默认值保存到 SharedPreferences 中，以便后续可以对其进行修改
                preferences.edit().putString(KEY_NAME, value).apply();
            }
        }

        if (TextUtils.isEmpty(value)) {
            return false; // 如果值仍然为空（例如文件读取失败），则直接返回
        }

        try {
            JSONObject root = new JSONObject(value);
            JSONArray dataArray = root.optJSONArray("data");
            if (dataArray != null && dataArray.length() > 0) {
                for (int i = 0; i < dataArray.length(); i++) {
                    JSONObject keyObject = dataArray.getJSONObject(i);
                    String name = keyObject.optString("name");
                    JSONArray codesArray = keyObject.getJSONArray("data");
                    short[] datas = new short[codesArray.length()];
                    for (int j = 0; j < codesArray.length(); j++) {
                        String code = codesArray.getString(j);
                        // 解析 "0xXX" 格式的十六进制字符串
                        datas[j] = (short) Integer.parseInt(code.substring(2), 16);
                    }
                    MenuOption option = new MenuOption(name, false, () -> sendKeys(datas), null, false);
                    options.add(option);
                }
                return true; // 成功加载，返回 true
            }
        } catch (Exception e) {
            LimeLog.warning("Exception while loading keys from SharedPreferences: " + e.getMessage());
            Toast.makeText(game, getString(R.string.toast_load_custom_keys_corrupted), Toast.LENGTH_SHORT).show();
        }
        return false; // 没有加载到任何按键
    }

    /**
     * 从 res/raw 目录读取资源文件内容并返回字符串。
     * @param resourceId 资源文件的 ID (例如 R.raw.default_special_keys)
     * @return 文件内容的字符串，如果失败则返回空字符串
     */
    private String readRawResourceAsString(int resourceId) {
        try (InputStream inputStream = game.getResources().openRawResource(resourceId);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            return builder.toString();
        } catch (IOException e) {
            LimeLog.warning("Failed to read raw resource file: " + resourceId + ": " + e);
            return "";
        }
    }

    /**
     * 将新的自定义按键保存到 SharedPreferences
     * @param name 按键的显示名称
     * @param keysString 逗号分隔的十六进制按键码字符串
     */
    private void saveCustomKey(String name, String keysString) {
        SharedPreferences preferences = game.getSharedPreferences(PREF_NAME, Activity.MODE_PRIVATE);
        String value = preferences.getString(KEY_NAME, "{\"data\":[]}"); // 如果为空，提供默认JSON结构

        try {
            // 解析按键码
            String[] keyParts = keysString.split(",");
            JSONArray keyCodesArray = new JSONArray();
            for (String part : keyParts) {
                String trimmedPart = part.trim();
                // 简单验证是否是 "0x" 开头的十六进制
                if (!trimmedPart.startsWith("0x")) {
                    Toast.makeText(game, R.string.toast_key_code_format_error, Toast.LENGTH_LONG).show();
                    return;
                }
                keyCodesArray.put(trimmedPart);
            }

            // 读取现有的JSON数据
            JSONObject root = new JSONObject(value);
            JSONArray dataArray = root.getJSONArray("data");

            // 创建新的JSON对象并添加
            JSONObject newKeyEntry = new JSONObject();
            newKeyEntry.put("name", name);
            newKeyEntry.put("data", keyCodesArray);
            dataArray.put(newKeyEntry);

            // 写回SharedPreferences
            SharedPreferences.Editor editor = preferences.edit();
            editor.putString(KEY_NAME, root.toString());
            editor.apply();

            Toast.makeText(game, game.getString(R.string.toast_custom_key_saved, name), Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            LimeLog.warning("Exception while saving custom key" + e.getMessage());
            Toast.makeText(game, R.string.toast_save_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void showAddCustomKeyDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(game, R.style.AppDialogStyle);
        builder.setTitle(R.string.dialog_title_add_custom_key);

        View dialogView = LayoutInflater.from(game).inflate(R.layout.dialog_add_custom_key, null);
        builder.setView(dialogView);

        final EditText nameInput = dialogView.findViewById(R.id.edit_text_key_name);
        final TextView keysDisplay = dialogView.findViewById(R.id.text_view_key_codes);
        final Button clearButton = dialogView.findViewById(R.id.button_clear_keys);

        // 初始化/重置 TextView 的数据存储 (tag) 和显示 (text)
        keysDisplay.setTag("");
        keysDisplay.setText("");
        keysDisplay.setHint(R.string.dialog_hint_key_codes);

        // 清空按钮: 同时清空数据(tag)和显示(text)
        clearButton.setOnClickListener(v -> {
            keysDisplay.setTag("");
            keysDisplay.setText("");
        });

        // 递归设置键盘监听器
        setupCompactKeyboardListeners((ViewGroup) dialogView.findViewById(R.id.keyboard_drawing), keysDisplay);

        // 保存按钮
        builder.setPositiveButton(R.string.dialog_button_save, (dialog, which) -> {
            String name = nameInput.getText().toString().trim();
            String androidKeyCodesStr = keysDisplay.getTag().toString(); // 从 tag 获取原始数据

            if (TextUtils.isEmpty(name) || TextUtils.isEmpty(androidKeyCodesStr)) {
                Toast.makeText(game, R.string.toast_name_and_codes_cannot_be_empty, Toast.LENGTH_SHORT).show();
                return;
            }

            // 将 Android KeyCodes 字符串转换为 Windows KeyCodes 字符串
            String[] androidCodes = androidKeyCodesStr.split(",");
            StringBuilder windowsCodesBuilder = new StringBuilder();
            for (int i = 0; i < androidCodes.length; i++) {
                try {
                    int code = Integer.parseInt(androidCodes[i]);
                    String windowsCode = KeyCodeMapper.getWindowsKeyCode(code);
                    if (windowsCode == null) throw new NullPointerException(); // 如果找不到映射，则抛出异常

                    windowsCodesBuilder.append(windowsCode).append(i < androidCodes.length - 1 ? "," : "");
                } catch (Exception e) {
                    Toast.makeText(game, "错误: 包含无效的按键码", Toast.LENGTH_LONG).show();
                    return;
                }
            }
            saveCustomKey(name, windowsCodesBuilder.toString());
        });

        builder.setNegativeButton(R.string.dialog_button_cancel, null);
        builder.create().show();
    }

    /**
     * 键盘监听器设置方法
     * @param parent 键盘布局的根视图
     * @param keysDisplay 用于存储和显示按键的 TextView
     */
    private void setupCompactKeyboardListeners(ViewGroup parent, final TextView keysDisplay) {
        if (parent == null) return;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof ViewGroup) {
                setupCompactKeyboardListeners((ViewGroup) child, keysDisplay); // 递归
            } else if (child instanceof TextView && child.getTag() != null) {
                child.setOnClickListener(v -> {
                    String androidKeyCode = v.getTag().toString();
                    String currentTag = keysDisplay.getTag().toString();

                    // 1. 更新数据 (Tag)
                    String newTag = currentTag.isEmpty() ? androidKeyCode : currentTag + "," + androidKeyCode;
                    keysDisplay.setTag(newTag);

                    // 2. 更新显示 (Text)
                    String currentText = keysDisplay.getText().toString();
                    String displayName = KeyCodeMapper.getDisplayName(Integer.parseInt(androidKeyCode));
                    String newText = currentText.isEmpty() ? displayName : currentText + " + " + displayName;
                    keysDisplay.setText(newText);
                });
            }
        }
    }

    /**
     * 显示一个对话框，列出所有自定义按键，允许用户选择并删除。
     */
    private void showDeleteKeysDialog() {
        SharedPreferences preferences = game.getSharedPreferences(PREF_NAME, Activity.MODE_PRIVATE);
        String value = preferences.getString(KEY_NAME, "");

        if (TextUtils.isEmpty(value)) {
            Toast.makeText(game, R.string.toast_no_custom_keys_to_delete, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            JSONObject root = new JSONObject(value);
            JSONArray dataArray = root.optJSONArray("data");

            if (dataArray == null || dataArray.length() == 0) {
                Toast.makeText(game, R.string.toast_no_custom_keys_to_delete, Toast.LENGTH_SHORT).show();
                return;
            }

            // 准备列表和选中状态
            final List<String> keyNames = new ArrayList<>();
            for (int i = 0; i < dataArray.length(); i++) {
                keyNames.add(dataArray.getJSONObject(i).optString("name"));
            }
            final boolean[] checkedItems = new boolean[keyNames.size()];

            // 创建并显示对话框
            AlertDialog.Builder builder = new AlertDialog.Builder(game, R.style.AppDialogStyle);
            builder.setTitle(R.string.dialog_title_select_keys_to_delete)
                    .setMultiChoiceItems(keyNames.toArray(new CharSequence[0]), checkedItems, (dialog, which, isChecked) ->
                            checkedItems[which] = isChecked)
                    .setPositiveButton(R.string.dialog_button_delete, (dialog, which) -> {
                        try {
                            // 从后往前删除选中项
                            for (int i = checkedItems.length - 1; i >= 0; i--) {
                                if (checkedItems[i]) {
                                    dataArray.remove(i);
                                }
                            }
                            // 保存更改
                            root.put("data", dataArray);
                            preferences.edit().putString(KEY_NAME, root.toString()).apply();
                            Toast.makeText(game, R.string.toast_selected_keys_deleted, Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            LimeLog.warning("Exception while deleting keys" + e.getMessage());
                            Toast.makeText(game, R.string.toast_delete_failed, Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton(R.string.dialog_button_cancel, null)
                    .create()
                    .show();

        } catch (Exception e) {
            LimeLog.warning("Exception while loading key list" + e.getMessage());
            Toast.makeText(game, R.string.toast_load_key_list_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 显示主菜单
     */
    private void showMenu() {
        List<MenuOption> normalOptions = new ArrayList<>();
        List<MenuOption> superOptions = new ArrayList<>();

        // 构建普通菜单项
        buildNormalMenuOptions(normalOptions);

        // 构建超级菜单项
        buildSuperMenuOptions(superOptions);

        showMenuDialog(getString(R.string.game_menu),
                normalOptions.toArray(new MenuOption[0]),
                superOptions.toArray(new MenuOption[0]));
    }

    /**
     * 构建普通菜单选项
     */
    private void buildNormalMenuOptions(List<MenuOption> normalOptions) {
        normalOptions.add(new MenuOption(getString(R.string.game_menu_toggle_keyboard), true,
                game::toggleKeyboard, "game_menu_toggle_keyboard", true));

        normalOptions.add(new MenuOption(getString(R.string.game_menu_toggle_host_keyboard), true,
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_O}),
                "game_menu_toggle_host_keyboard", true));

        // 显示当前触控模式
        String touchModeText = getString(R.string.game_menu_switch_touch_mode) + ": " +
                (game.prefConfig.touchscreenTrackpad ? getString(R.string.game_menu_touch_mode_trackpad) :
                        game.prefConfig.enableEnhancedTouch ? getString(R.string.game_menu_touch_mode_enhanced) :
                                getString(R.string.game_menu_touch_mode_classic));


        // 此菜单是 UI 操作，不应该依赖游戏窗口焦点
        normalOptions.add(new MenuOption(
                touchModeText,
                false,
                this::showTouchModeMenu,
                "mouse_mode",
                true, true));

        if (device != null) {
            normalOptions.addAll(device.getGameMenuOptions());
        }

        normalOptions.add(new MenuOption(getString(R.string.game_menu_toggle_performance_overlay),
                false, game::togglePerformanceOverlay, "game_menu_toggle_performance_overlay", true));

        // 只有在启用了虚拟手柄时才显示虚拟手柄切换选项
        if (game.prefConfig.onscreenController) {
            normalOptions.add(new MenuOption(getString(R.string.game_menu_toggle_virtual_controller),
                    false, game::toggleVirtualController, "game_menu_toggle_virtual_controller", true));
        }

        normalOptions.add(new MenuOption(getString(R.string.game_menu_send_keys),
                false, this::showSpecialKeysMenu, "game_menu_send_keys", true, true));

        normalOptions.add(new MenuOption(getString(R.string.game_menu_disconnect), true,
                game::disconnect, "game_menu_disconnect", true));

        normalOptions.add(new MenuOption(getString(R.string.game_menu_disconnect_and_quit), true,
                this::disconnectAndQuit, "game_menu_disconnect_and_quit", true));

        // normalOptions.add(new MenuOption(getString(R.string.game_menu_cancel), false, null, null, true));
    }

    /**
     * 构建超级菜单选项
     */
    private void buildSuperMenuOptions(List<MenuOption> superOptions) {
        JsonArray cmdList = app.getCmdList();
        if (cmdList != null) {
            for (int i = 0; i < cmdList.size(); i++) {
                JsonObject cmd = cmdList.get(i).getAsJsonObject();
                superOptions.add(new MenuOption(cmd.get("name").getAsString(), true, () -> {
                    try {
                        conn.sendSuperCmd(cmd.get("id").getAsString());
                    } catch (IOException | XmlPullParserException e) {
                        Toast.makeText(game, "发送超级命令时发生错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                }, null, false)); // 超级指令菜单不显示图标
            }
        }
    }

    /**
     * 获取菜单项图标
     */
    private static int getIconForMenuOption(String iconKey) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return ICON_MAP.getOrDefault(iconKey, R.drawable.ic_menu_item_default);
        }
        return -1;
    }

    /**
     * 自定义适配器用于显示美化的菜单项
     */
    private static class GameMenuAdapter extends ArrayAdapter<MenuOption> {
        private final Context context;

        public GameMenuAdapter(Context context, MenuOption[] options) {
            super(context, 0, options);
            this.context = context;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(R.layout.game_menu_list_item, parent, false);
            }

            MenuOption option = getItem(position);
            if (option != null) {
                TextView textView = convertView.findViewById(R.id.menu_item_text);
                ImageView iconView = convertView.findViewById(R.id.menu_item_icon);

                textView.setText(option.getLabel());
                
                if (option.isShowIcon()) {
                    iconView.setImageResource(getIconForMenuOption(option.getIconKey()));
                    iconView.setVisibility(View.VISIBLE);
                } else {
                    iconView.setVisibility(View.GONE);
                }
            }

            return convertView;
        }
    }

    /**
     * 超级菜单适配器
     */
    private static class SuperMenuAdapter extends ArrayAdapter<MenuOption> {
        private final Context context;

        public SuperMenuAdapter(Context context, MenuOption[] options) {
            super(context, 0, options);
            this.context = context;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(R.layout.game_menu_list_item, parent, false);
            }

            MenuOption option = getItem(position);
            if (option != null) {
                TextView textView = convertView.findViewById(R.id.menu_item_text);
                ImageView iconView = convertView.findViewById(R.id.menu_item_icon);

                textView.setText(option.getLabel());
                
                if (option.isShowIcon()) {
                    iconView.setImageResource(R.drawable.ic_cmd_cute);
                    iconView.setVisibility(View.VISIBLE);
                } else {
                    iconView.setVisibility(View.GONE);
                }
            }

            return convertView;
        }
    }
}
