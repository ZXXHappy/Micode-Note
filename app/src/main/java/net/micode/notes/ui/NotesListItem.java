/*
 * Copyright (c) 2010-2011, The MiCode Open Source Community (www.micode.net)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.micode.notes.ui;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.ActionBar;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.gtask.remote.GTaskSyncService;

// 设置页面，用于配置笔记同步账号、同步操作等偏好设置
public class NotesPreferenceActivity extends PreferenceActivity {
    // 配置文件名称
    public static final String PREFERENCE_NAME = "notes_preferences";

    // 存储同步账号名的key
    public static final String PREFERENCE_SYNC_ACCOUNT_NAME = "pref_key_account_name";

    // 存储最后同步时间的key
    public static final String PREFERENCE_LAST_SYNC_TIME = "pref_last_sync_time";

    // 设置背景颜色的key
    public static final String PREFERENCE_SET_BG_COLOR_KEY = "pref_key_bg_random_appear";

    // 账号分类的preference key
    private static final String PREFERENCE_SYNC_ACCOUNT_KEY = "pref_sync_account_key";

    // 系统添加账号时的权限过滤key
    private static final String AUTHORITIES_FILTER_KEY = "authorities";

    // 账号设置分组
    private PreferenceCategory mAccountCategory;

    // 接收同步服务广播
    private GTaskReceiver mReceiver;

    // 原始账号列表
    private Account[] mOriAccounts;

    // 标记是否新增了账号
    private boolean mHasAddedAccount;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        /* using the app icon for navigation */
        // 显示返回箭头
        getActionBar().setDisplayHomeAsUpEnabled(true);

        // 加载设置布局
        addPreferencesFromResource(R.xml.preferences);
        mAccountCategory = (PreferenceCategory) findPreference(PREFERENCE_SYNC_ACCOUNT_KEY);
        mReceiver = new GTaskReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(GTaskSyncService.GTASK_SERVICE_BROADCAST_NAME);
        // 注册广播接收器
        registerReceiver(mReceiver, filter);

        mOriAccounts = null;
        // 加载设置页头部布局
        View header = LayoutInflater.from(this).inflate(R.layout.settings_header, null);
        getListView().addHeaderView(header, null, true);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // need to set sync account automatically if user has added a new
        // account
        // 如果用户新增了账号，自动检测并设置为同步账号
        if (mHasAddedAccount) {
            Account[] accounts = getGoogleAccounts();
            if (mOriAccounts != null && accounts.length > mOriAccounts.length) {
                for (Account accountNew : accounts) {
                    boolean found = false;
                    for (Account accountOld : mOriAccounts) {
                        if (TextUtils.equals(accountOld.name, accountNew.name)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        // 设置新账号为同步账号
                        setSyncAccount(accountNew.name);
                        break;
                    }
                }
            }
        }

        // 刷新界面
        refreshUI();
    }

    @Override
    protected void onDestroy() {
        if (mReceiver != null) {
            // 注销广播，避免内存泄漏
            unregisterReceiver(mReceiver);
        }
        super.onDestroy();
    }

    // 加载账号设置项
    private void loadAccountPreference() {
        mAccountCategory.removeAll();

        Preference accountPref = new Preference(this);
        final String defaultAccount = getSyncAccountName(this);
        accountPref.setTitle(getString(R.string.preferences_account_title));
        accountPref.setSummary(getString(R.string.preferences_account_summary));
        accountPref.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            public boolean onPreferenceClick(Preference preference) {
                // 同步中不允许修改账号
                if (!GTaskSyncService.isSyncing()) {
                    if (TextUtils.isEmpty(defaultAccount)) {
                        // the first time to set account
                        // 首次设置账号，显示选择账号对话框
                        showSelectAccountAlertDialog();
                    } else {
                        // if the account has already been set, we need to promp
                        // user about the risk
                        // 已设置账号，提示风险并提供修改/删除选项
                        showChangeAccountConfirmAlertDialog();
                    }
                } else {
                    // 提示正在同步，无法修改账号
                    Toast.makeText(NotesPreferenceActivity.this,
                            R.string.preferences_toast_cannot_change_account, Toast.LENGTH_SHORT)
                            .show();
                }
                return true;
            }
        });

        mAccountCategory.addPreference(accountPref);
    }

    // 加载同步按钮及同步状态显示
    private void loadSyncButton() {
        Button syncButton = (Button) findViewById(R.id.preference_sync_button);
        TextView lastSyncTimeView = (TextView) findViewById(R.id.prefenerece_sync_status_textview);

        // set button state
        // 根据是否正在同步设置按钮文字和点击事件
        if (GTaskSyncService.isSyncing()) {
            syncButton.setText(getString(R.string.preferences_button_sync_cancel));
            syncButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    // 取消同步
                    GTaskSyncService.cancelSync(NotesPreferenceActivity.this);
                }
            });
        } else {
            syncButton.setText(getString(R.string.preferences_button_sync_immediately));
            syncButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    // 立即同步
                    GTaskSyncService.startSync(NotesPreferenceActivity.this);
                }
            });
        }
        // 只有设置了账号才允许点击同步按钮
        syncButton.setEnabled(!TextUtils.isEmpty(getSyncAccountName(this)));

        // set last sync time
        // 显示同步进度或最后同步时间
        if (GTaskSyncService.isSyncing()) {
            lastSyncTimeView.setText(GTaskSyncService.getProgressString());
            lastSyncTimeView.setVisibility(View.VISIBLE);
        } else {
            long lastSyncTime = getLastSyncTime(this);
            if (lastSyncTime != 0) {
                lastSyncTimeView.setText(getString(R.string.preferences_last_sync_time,
                        DateFormat.format(getString(R.string.preferences_last_sync_time_format),
                                lastSyncTime)));
                lastSyncTimeView.setVisibility(View.VISIBLE);
            } else {
                lastSyncTimeView.setVisibility(View.GONE);
            }
        }
    }

    // 刷新整个设置界面
    private void refreshUI() {
        loadAccountPreference();
        loadSyncButton();
    }

    // 显示选择Google同步账号的对话框
    private void showSelectAccountAlertDialog() {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(this);

        View titleView = LayoutInflater.from(this).inflate(R.layout.account_dialog_title, null);
        TextView titleTextView = (TextView) titleView.findViewById(R.id.account_dialog_title);
        titleTextView.setText(getString(R.string.preferences_dialog_select_account_title));
        TextView subtitleTextView = (TextView) titleView.findViewById(R.id.account_dialog_subtitle);
        subtitleTextView.setText(getString(R.string.preferences_dialog_select_account_tips));

        dialogBuilder.setCustomTitle(titleView);
        dialogBuilder.setPositiveButton(null, null);

        // 获取设备上所有Google账号
        Account[] accounts = getGoogleAccounts();
        String defAccount = getSyncAccountName(this);

        mOriAccounts = accounts;
        mHasAddedAccount = false;

        if (accounts.length > 0) {
            CharSequence[] items = new CharSequence[accounts.length];
            final CharSequence[] itemMapping = items;
            int checkedItem = -1;
            int index = 0;
            for (Account account : accounts) {
                if (TextUtils.equals(account.name, defAccount)) {
                    checkedItem = index;
                }
                items[index++] = account.name;
            }
            // 单选账号列表
            dialogBuilder.setSingleChoiceItems(items, checkedItem,
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            // 设置选中的账号为同步账号
                            setSyncAccount(itemMapping[which].toString());
                            dialog.dismiss();
                            refreshUI();
                        }
                    });
        }

        // 添加新账号入口
        View addAccountView = LayoutInflater.from(this).inflate(R.layout.add_account_text, null);
        dialogBuilder.setView(addAccountView);

        final AlertDialog dialog = dialogBuilder.show();
        addAccountView.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mHasAddedAccount = true;
                // 跳转到系统添加账号界面
                Intent intent = new Intent("android.settings.ADD_ACCOUNT_SETTINGS");
                intent.putExtra(AUTHORITIES_FILTER_KEY, new String[] {
                    "gmail-ls"
                });
                startActivityForResult(intent, -1);
                dialog.dismiss();
            }
        });
    }

    // 显示修改/删除同步账号的确认对话框
    private void showChangeAccountConfirmAlertDialog() {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(this);

        View titleView = LayoutInflater.from(this).inflate(R.layout.account_dialog_title, null);
        TextView titleTextView = (TextView) titleView.findViewById(R.id.account_dialog_title);
        titleTextView.setText(getString(R.string.preferences_dialog_change_account_title,
                getSyncAccountName(this)));
        TextView subtitleTextView = (TextView) titleView.findViewById(R.id.account_dialog_subtitle);
        subtitleTextView.setText(getString(R.string.preferences_dialog_change_account_warn_msg));
        dialogBuilder.setCustomTitle(titleView);

        // 对话框选项：修改账号、删除账号、取消
        CharSequence[] menuItemArray = new CharSequence[] {
                getString(R.string.preferences_menu_change_account),
                getString(R.string.preferences_menu_remove_account),
                getString(R.string.preferences_menu_cancel)
        };
        dialogBuilder.setItems(menuItemArray, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                if (which == 0) {
                    // 切换账号
                    showSelectAccountAlertDialog();
                } else if (which == 1) {
                    // 移除同步账号
                    removeSyncAccount();
                    refreshUI();
                }
            }
        });
        dialogBuilder.show();
    }

    // 获取设备中所有Google类型账号
    private Account[] getGoogleAccounts() {
        AccountManager accountManager = AccountManager.get(this);
        return accountManager.getAccountsByType("com.google");
    }

    // 设置同步使用的Google账号
    private void setSyncAccount(String account) {
        if (!getSyncAccountName(this).equals(account)) {
            SharedPreferences settings = getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = settings.edit();
            if (account != null) {
                editor.putString(PREFERENCE_SYNC_ACCOUNT_NAME, account);
            } else {
                editor.putString(PREFERENCE_SYNC_ACCOUNT_NAME, "");
            }
            editor.commit();

            // clean up last sync time
            // 清空最后同步时间
            setLastSyncTime(this, 0);

            // clean up local gtask related info
            // 子线程清空笔记相关的同步ID信息
            new Thread(new Runnable() {
                public void run() {
                    ContentValues values = new ContentValues();
                    values.put(NoteColumns.GTASK_ID, "");
                    values.put(NoteColumns.SYNC_ID, 0);
                    getContentResolver().update(Notes.CONTENT_NOTE_URI, values, null, null);
                }
            }).start();

            // 提示设置账号成功
            Toast.makeText(NotesPreferenceActivity.this,
                    getString(R.string.preferences_toast_success_set_accout, account),
                    Toast.LENGTH_SHORT).show();
        }
    }

    // 移除同步账号并清除相关配置
    private void removeSyncAccount() {
        SharedPreferences settings = getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = settings.edit();
        if (settings.contains(PREFERENCE_SYNC_ACCOUNT_NAME)) {
            editor.remove(PREFERENCE_SYNC_ACCOUNT_NAME);
        }
        if (settings.contains(PREFERENCE_LAST_SYNC_TIME)) {
            editor.remove(PREFERENCE_LAST_SYNC_TIME);
        }
        editor.commit();

        // clean up local gtask related info
        // 清空笔记的同步相关字段
        new Thread(new Runnable() {
            public void run() {
                ContentValues values = new ContentValues();
                values.put(NoteColumns.GTASK_ID, "");
                values.put(NoteColumns.SYNC_ID, 0);
                getContentResolver().update(Notes.CONTENT_NOTE_URI, values, null, null);
            }
        }).start();
    }

    // 获取当前设置的同步账号名
    public static String getSyncAccountName(Context context) {
        SharedPreferences settings = context.getSharedPreferences(PREFERENCE_NAME,
                Context.MODE_PRIVATE);
        return settings.getString(PREFERENCE_SYNC_ACCOUNT_NAME, "");
    }

    // 保存最后同步时间
    public static void setLastSyncTime(Context context, long time) {
        SharedPreferences settings = context.getSharedPreferences(PREFERENCE_NAME,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = settings.edit();
        editor.putLong(PREFERENCE_LAST_SYNC_TIME, time);
        editor.commit();
    }

    // 获取最后同步时间
    public static long getLastSyncTime(Context context) {
        SharedPreferences settings = context.getSharedPreferences(PREFERENCE_NAME,
                Context.MODE_PRIVATE);
        return settings.getLong(PREFERENCE_LAST_SYNC_TIME, 0);
    }

    // 接收同步服务广播，更新同步状态
    private class GTaskReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            refreshUI();
            // 如果正在同步，显示同步进度信息
            if (intent.getBooleanExtra(GTaskSyncService.GTASK_SERVICE_BROADCAST_IS_SYNCING, false)) {
                TextView syncStatus = (TextView) findViewById(R.id.prefenerece_sync_status_textview);
                syncStatus.setText(intent
                        .getStringExtra(GTaskSyncService.GTASK_SERVICE_BROADCAST_PROGRESS_MSG));
            }

        }
    }

    // 处理顶部导航栏菜单点击，主要是返回按钮
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                // 返回笔记列表页面
                Intent intent = new Intent(this, NotesListActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
                return true;
            default:
                return false;
        }
    }
}
