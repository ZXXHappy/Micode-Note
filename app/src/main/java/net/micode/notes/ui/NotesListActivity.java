/*
 * Copyright (c) 2010-2011, The MiCode Open Source Community (www.micode.net)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.micode.notes.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.appwidget.AppWidgetManager;
import android.content.AsyncQueryHandler;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.ActionMode;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Display;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnCreateContextMenuListener;
import android.view.View.OnTouchListener;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.gtask.remote.GTaskSyncService;
import net.micode.notes.model.WorkingNote;
import net.micode.notes.tool.BackupUtils;
import net.micode.notes.tool.DataUtils;
import net.micode.notes.tool.ResourceParser;
import net.micode.notes.ui.NotesListAdapter.AppWidgetAttribute;
import net.micode.notes.widget.NoteWidgetProvider_2x;
import net.micode.notes.widget.NoteWidgetProvider_4x;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;

/**
 * NotesListActivity: 应用的主界面类。
 * 负责便签列表的展示、文件夹导航、批量操作（删除/移动）以及搜索入口。
 */
public class NotesListActivity extends Activity implements OnClickListener, OnItemLongClickListener {
    /** 查询 Token：用于标识不同的异步查询操作 */
    private static final int FOLDER_NOTE_LIST_QUERY_TOKEN = 0;
    private static final int FOLDER_LIST_QUERY_TOKEN      = 1;

    /** 文件夹项的长按菜单 ID */
    private static final int MENU_FOLDER_DELETE = 0;
    private static final int MENU_FOLDER_VIEW = 1;
    private static final int MENU_FOLDER_CHANGE_NAME = 2;

    private static final String PREFERENCE_ADD_INTRODUCTION = "net.micode.notes.introduction";

    /** 列表编辑状态枚举 */
    private enum ListEditState {
        NOTE_LIST, SUB_FOLDER, CALL_RECORD_FOLDER
    };

    private ListEditState mState;
    private BackgroundQueryHandler mBackgroundQueryHandler;
    private NotesListAdapter mNotesListAdapter;
    private ListView mNotesListView;
    private Button mAddNewNote;
    private boolean mDispatch;
    private int mOriginY;
    private int mDispatchY;
    private TextView mTitleBar;
    private long mCurrentFolderId;
    private ContentResolver mContentResolver;
    private ModeCallback mModeCallBack;
    private static final String TAG = "NotesListActivity";

    public static final int NOTES_LISTVIEW_SCROLL_RATE = 30;
    private NoteItemData mFocusNoteDataItem;

    /** SQL 查询条件定义 */
    private static final String NORMAL_SELECTION = NoteColumns.PARENT_ID + "=?";
    private static final String ROOT_FOLDER_SELECTION = "(" + NoteColumns.TYPE + "<>"
            + Notes.TYPE_SYSTEM + " AND " + NoteColumns.PARENT_ID + "=?)" + " OR ("
            + NoteColumns.ID + "=" + Notes.ID_CALL_RECORD_FOLDER + " AND "
            + NoteColumns.NOTES_COUNT + ">0)";

    private final static int REQUEST_CODE_OPEN_NODE = 102;
    private final static int REQUEST_CODE_NEW_NODE  = 103;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.note_list);
        initResources();
        /** 首次使用应用时插入介绍便签  */
        setAppInfoFromRawRes();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK
                && (requestCode == REQUEST_CODE_OPEN_NODE || requestCode == REQUEST_CODE_NEW_NODE)) {
            mNotesListAdapter.changeCursor(null);
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    /** 从 Raw 资源加载初始介绍内容  */
    private void setAppInfoFromRawRes() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        if (!sp.getBoolean(PREFERENCE_ADD_INTRODUCTION, false)) {
            StringBuilder sb = new StringBuilder();
            InputStream in = null;
            try {
                 in = getResources().openRawResource(R.raw.introduction);
                if (in != null) {
                    InputStreamReader isr = new InputStreamReader(in);
                    BufferedReader br = new BufferedReader(isr);
                    char [] buf = new char[1024];
                    int len = 0;
                    while ((len = br.read(buf)) > 0) {
                        sb.append(buf, 0, len);
                    }
                } else {
                    Log.e(TAG, "Read introduction file error");
                    return;
                }
            } catch (IOException e) {
                e.printStackTrace();
                return;
            } finally {
                if(in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

            WorkingNote note = WorkingNote.createEmptyNote(this, Notes.ID_ROOT_FOLDER,
                    AppWidgetManager.INVALID_APPWIDGET_ID, Notes.TYPE_WIDGET_INVALIDE,
                    ResourceParser.RED);
            note.setWorkingText(sb.toString());
            if (note.saveNote()) {
                sp.edit().putBoolean(PREFERENCE_ADD_INTRODUCTION, true).commit();
            } else {
                Log.e(TAG, "Save introduction note error");
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        startAsyncNotesListQuery(); // 开始异步加载列表 [cite: 67]
    }

    /** 初始化控件和资源 */
    private void initResources() {
        mContentResolver = this.getContentResolver();
        mBackgroundQueryHandler = new BackgroundQueryHandler(this.getContentResolver());
        mCurrentFolderId = Notes.ID_ROOT_FOLDER;
        mNotesListView = (ListView) findViewById(R.id.notes_list);
        mNotesListView.addFooterView(LayoutInflater.from(this).inflate(R.layout.note_list_footer, null),
                null, false);
        mNotesListView.setOnItemClickListener(new OnListItemClickListener());
        mNotesListView.setOnItemLongClickListener(this);
        mNotesListAdapter = new NotesListAdapter(this);
        mNotesListView.setAdapter(mNotesListAdapter);
        mAddNewNote = (Button) findViewById(R.id.btn_new_note);
        mAddNewNote.setOnClickListener(this);
        mAddNewNote.setOnTouchListener(new NewNoteOnTouchListener());
        mDispatch = false;
        mDispatchY = 0;
        mOriginY = 0;
        mTitleBar = (TextView) findViewById(R.id.tv_title_bar);
        mState = ListEditState.NOTE_LIST;
        mModeCallBack = new ModeCallback();
    }

    /** 实现列表的多选编辑模式 [cite: 67] */
    private class ModeCallback implements ListView.MultiChoiceModeListener, OnMenuItemClickListener {
        private DropdownMenu mDropDownMenu;
        private ActionMode mActionMode;
        private MenuItem mMoveMenu;

        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            getMenuInflater().inflate(R.menu.note_list_options, menu);
            menu.findItem(R.id.delete).setOnMenuItemClickListener(this);
            mMoveMenu = menu.findItem(R.id.move);
            if (mFocusNoteDataItem.getParentId() == Notes.ID_CALL_RECORD_FOLDER
                    || DataUtils.getUserFolderCount(mContentResolver) == 0) {
                mMoveMenu.setVisible(false);
            } else {
                mMoveMenu.setVisible(true);
                mMoveMenu.setOnMenuItemClickListener(this);
            }
            mActionMode = mode;
            mNotesListAdapter.setChoiceMode(true);
            mNotesListView.setLongClickable(false);
            mAddNewNote.setVisibility(View.GONE);

            View customView = LayoutInflater.from(NotesListActivity.this).inflate(
                    R.layout.note_list_dropdown_menu, null);
            mode.setCustomView(customView);
            mDropDownMenu = new DropdownMenu(NotesListActivity.this,
                    (Button) customView.findViewById(R.id.selection_menu),
                    R.menu.note_list_dropdown);
            mDropDownMenu.setOnDropdownMenuItemClickListener(new PopupMenu.OnMenuItemClickListener(){
                public boolean onMenuItemClick(MenuItem item) {
                    mNotesListAdapter.selectAll(!mNotesListAdapter.isAllSelected());
                    updateMenu();
                    return true;
                }
            });
            return true;
        }

        private void updateMenu() {
            int selectedCount = mNotesListAdapter.getSelectedCount();
            String format = getResources().getString(R.string.menu_select_title, selectedCount);
            mDropDownMenu.setTitle(format);
            MenuItem item = mDropDownMenu.findItem(R.id.action_select_all);
            if (item != null) {
                if (mNotesListAdapter.isAllSelected()) {
                    item.setChecked(true);
                    item.setTitle(R.string.menu_deselect_all);
                } else {
                    item.setChecked(false);
                    item.setTitle(R.string.menu_select_all);
                }
            }
        }

        public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) { return false; }

        public void onDestroyActionMode(ActionMode mode) {
            mNotesListAdapter.setChoiceMode(false);
            mNotesListView.setLongClickable(true);
            mAddNewNote.setVisibility(View.VISIBLE);
        }

        public void finishActionMode() { mActionMode.finish(); }

        public void onItemCheckedStateChanged(ActionMode mode, int position, long id, boolean checked) {
            mNotesListAdapter.setCheckedItem(position, checked);
            updateMenu();
        }

        public boolean onMenuItemClick(MenuItem item) {
            if (mNotesListAdapter.getSelectedCount() == 0) {
                Toast.makeText(NotesListActivity.this, getString(R.string.menu_select_none),
                        Toast.LENGTH_SHORT).show();
                return true;
            }

            int itemId = item.getItemId();
            if (itemId == R.id.delete) {
                AlertDialog.Builder builder = new AlertDialog.Builder(NotesListActivity.this);
                builder.setTitle(getString(R.string.alert_title_delete));
                builder.setIcon(android.R.drawable.ic_dialog_alert);
                builder.setMessage(getString(R.string.alert_message_delete_notes,
                                         mNotesListAdapter.getSelectedCount()));
                builder.setPositiveButton(android.R.string.ok, (dialog, which) -> batchDelete());
                builder.setNegativeButton(android.R.string.cancel, null);
                builder.show();
                return true;
            } else if (itemId == R.id.move) {
                startQueryDestinationFolders();
                return true;
            }
            return false;
        }
    }

    /** 按钮点击触摸分发 [cite: 67] */
    private class NewNoteOnTouchListener implements OnTouchListener {
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN: {
                    Display display = getWindowManager().getDefaultDisplay();
                    int screenHeight = display.getHeight();
                    int newNoteViewHeight = mAddNewNote.getHeight();
                    int start = screenHeight - newNoteViewHeight;
                    int eventY = start + (int) event.getY();
                    if (mState == ListEditState.SUB_FOLDER) {
                        eventY -= mTitleBar.getHeight();
                        start -= mTitleBar.getHeight();
                    }
                    if (event.getY() < (event.getX() * (-0.12) + 94)) {
                        View view = mNotesListView.getChildAt(mNotesListView.getChildCount() - 1
                                - mNotesListView.getFooterViewsCount());
                        if (view != null && view.getBottom() > start
                                && (view.getTop() < (start + 94))) {
                            mOriginY = (int) event.getY();
                            mDispatchY = eventY;
                            event.setLocation(event.getX(), mDispatchY);
                            mDispatch = true;
                            return mNotesListView.dispatchTouchEvent(event);
                        }
                    }
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (mDispatch) {
                        mDispatchY += (int) event.getY() - mOriginY;
                        event.setLocation(event.getX(), mDispatchY);
                        return mNotesListView.dispatchTouchEvent(event);
                    }
                    break;
                }
                default: {
                    if (mDispatch) {
                        event.setLocation(event.getX(), mDispatchY);
                        mDispatch = false;
                        return mNotesListView.dispatchTouchEvent(event);
                    }
                    break;
                }
            }
            return false;
        }
    };

    /** 开始查询便签列表数据 */
    private void startAsyncNotesListQuery() {
        String selection = (mCurrentFolderId == Notes.ID_ROOT_FOLDER) ? ROOT_FOLDER_SELECTION : NORMAL_SELECTION;
        mBackgroundQueryHandler.startQuery(FOLDER_NOTE_LIST_QUERY_TOKEN, null,
                Notes.CONTENT_NOTE_URI, NoteItemData.PROJECTION, selection, new String[] {
                    String.valueOf(mCurrentFolderId)
                }, NoteColumns.TYPE + " DESC," + NoteColumns.MODIFIED_DATE + " DESC");
    }

    /** 异步查询处理器  */
    private final class BackgroundQueryHandler extends AsyncQueryHandler {
        public BackgroundQueryHandler(ContentResolver contentResolver) { super(contentResolver); }
        @Override
        protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
            switch (token) {
                case FOLDER_NOTE_LIST_QUERY_TOKEN:
                    mNotesListAdapter.changeCursor(cursor);
                    break;
                case FOLDER_LIST_QUERY_TOKEN:
                    if (cursor != null && cursor.getCount() > 0) {
                        showFolderListMenu(cursor);
                    } else {
                        Log.e(TAG, "Query folder failed");
                    }
                    break;
            }
        }
    }

    /** 显示移动目标文件夹列表 */
    private void showFolderListMenu(Cursor cursor) {
        AlertDialog.Builder builder = new AlertDialog.Builder(NotesListActivity.this);
        builder.setTitle(R.string.menu_title_select_folder);
        final FoldersListAdapter adapter = new FoldersListAdapter(this, cursor);
        builder.setAdapter(adapter, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                DataUtils.batchMoveToFolder(mContentResolver,
                        mNotesListAdapter.getSelectedItemIds(), adapter.getItemId(which));
                mModeCallBack.finishActionMode();
            }
        });
        builder.show();
    }

    /** 创建新便签 */
    private void createNewNote() {
        Intent intent = new Intent(this, NoteEditActivity.class);
        intent.setAction(Intent.ACTION_INSERT_OR_EDIT);
        intent.putExtra(Notes.INTENT_EXTRA_FOLDER_ID, mCurrentFolderId);
        this.startActivityForResult(intent, REQUEST_CODE_NEW_NODE);
    }

    /** 批量删除操作 */
    private void batchDelete() {
        new AsyncTask<Void, Void, HashSet<AppWidgetAttribute>>() {
            protected HashSet<AppWidgetAttribute> doInBackground(Void... unused) {
                HashSet<AppWidgetAttribute> widgets = mNotesListAdapter.getSelectedWidget();
                if (!isSyncMode()) {
                    DataUtils.batchDeleteNotes(mContentResolver, mNotesListAdapter.getSelectedItemIds());
                } else {
                    DataUtils.batchMoveToFolder(mContentResolver, mNotesListAdapter.getSelectedItemIds(), Notes.ID_TRASH_FOLER);
                }
                return widgets;
            }
            @Override
            protected void onPostExecute(HashSet<AppWidgetAttribute> widgets) {
                if (widgets != null) {
                    for (AppWidgetAttribute widget : widgets) {
                        updateWidget(widget.widgetId, widget.widgetType);
                    }
                }
                mModeCallBack.finishActionMode();
            }
        }.execute();
    }

    private void deleteFolder(long folderId) {
        if (folderId == Notes.ID_ROOT_FOLDER) return;
        HashSet<Long> ids = new HashSet<>();
        ids.add(folderId);
        if (!isSyncMode()) {
            DataUtils.batchDeleteNotes(mContentResolver, ids);
        } else {
            DataUtils.batchMoveToFolder(mContentResolver, ids, Notes.ID_TRASH_FOLER);
        }
    }

    private void openNode(NoteItemData data) {
        Intent intent = new Intent(this, NoteEditActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra(Intent.EXTRA_UID, data.getId());
        this.startActivityForResult(intent, REQUEST_CODE_OPEN_NODE);
    }

    private void openFolder(NoteItemData data) {
        mCurrentFolderId = data.getId();
        startAsyncNotesListQuery();
        if (data.getId() == Notes.ID_CALL_RECORD_FOLDER) {
            mState = ListEditState.CALL_RECORD_FOLDER;
            mAddNewNote.setVisibility(View.GONE);
            mTitleBar.setText(R.string.call_record_folder_name);
        } else {
            mState = ListEditState.SUB_FOLDER;
            mTitleBar.setText(data.getSnippet());
        }
        mTitleBar.setVisibility(View.VISIBLE);
    }

    public void onClick(View v) {
        if (v.getId() == R.id.btn_new_note) createNewNote();
    }

    private void showSoftInput() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
    }

    private void hideSoftInput(View view) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    private void showCreateOrModifyFolderDialog(final boolean create) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_text, null);
        final EditText etName = (EditText) view.findViewById(R.id.et_foler_name);
        showSoftInput();
        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
            hideSoftInput(etName);
            String name = etName.getText().toString();
            if (!create && mFocusNoteDataItem != null) {
                ContentValues values = new ContentValues();
                values.put(NoteColumns.SNIPPET, name);
                mContentResolver.update(Notes.CONTENT_NOTE_URI, values, NoteColumns.ID + "=?", new String[]{String.valueOf(mFocusNoteDataItem.getId())});
            } else if (!TextUtils.isEmpty(name)) {
                ContentValues values = new ContentValues();
                values.put(NoteColumns.SNIPPET, name);
                values.put(NoteColumns.TYPE, Notes.TYPE_FOLDER);
                mContentResolver.insert(Notes.CONTENT_NOTE_URI, values);
            }
        });
        builder.setNegativeButton(android.R.string.cancel, (dialog, which) -> hideSoftInput(etName));
        builder.setView(view).show();
    }

    @Override
    public void onBackPressed() {
        if (mState == ListEditState.SUB_FOLDER || mState == ListEditState.CALL_RECORD_FOLDER) {
            mCurrentFolderId = Notes.ID_ROOT_FOLDER;
            mState = ListEditState.NOTE_LIST;
            startAsyncNotesListQuery();
            mTitleBar.setVisibility(View.GONE);
            mAddNewNote.setVisibility(View.VISIBLE);
        } else {
            super.onBackPressed();
        }
    }

    private void updateWidget(int appWidgetId, int appWidgetType) {
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        intent.setClass(this, appWidgetType == Notes.TYPE_WIDGET_2X ? NoteWidgetProvider_2x.class : NoteWidgetProvider_4x.class);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[] { appWidgetId });
        sendBroadcast(intent);
    }

    private final OnCreateContextMenuListener mFolderOnCreateContextMenuListener = (menu, v, menuInfo) -> {
        if (mFocusNoteDataItem != null) {
            menu.setHeaderTitle(mFocusNoteDataItem.getSnippet());
            menu.add(0, MENU_FOLDER_VIEW, 0, R.string.menu_folder_view);
            menu.add(0, MENU_FOLDER_DELETE, 0, R.string.menu_folder_delete);
            menu.add(0, MENU_FOLDER_CHANGE_NAME, 0, R.string.menu_folder_change_name);
        }
    };

    @Override
    public void onContextMenuClosed(Menu menu) {
        if (mNotesListView != null) mNotesListView.setOnCreateContextMenuListener(null);
        super.onContextMenuClosed(menu);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        if (mFocusNoteDataItem == null) return false;
        switch (item.getItemId()) {
            case MENU_FOLDER_VIEW: openFolder(mFocusNoteDataItem); break;
            case MENU_FOLDER_DELETE: deleteFolder(mFocusNoteDataItem.getId()); break;
            case MENU_FOLDER_CHANGE_NAME: showCreateOrModifyFolderDialog(false); break;
        }
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.clear();
        int menuRes = mState == ListEditState.NOTE_LIST ? R.menu.note_list : (mState == ListEditState.SUB_FOLDER ? R.menu.sub_folder : R.menu.call_record_folder);
        getMenuInflater().inflate(menuRes, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.menu_new_folder) showCreateOrModifyFolderDialog(true);
        else if (itemId == R.id.menu_new_note) createNewNote();
        else if (itemId == R.id.menu_setting) startPreferenceActivity();
        return true;
    }

    @Override
    public boolean onSearchRequested() {
        startSearch(null, false, null, false);
        return true;
    }

    private void exportNoteToText() {
        final BackupUtils backup = BackupUtils.getInstance(this);
        new AsyncTask<Void, Void, Integer>() {
            @Override
            protected Integer doInBackground(Void... unused) { return backup.exportToText(); }
        }.execute();
    }

    private boolean isSyncMode() {
        return NotesPreferenceActivity.getSyncAccountName(this).trim().length() > 0;
    }

    private void startPreferenceActivity() {
        Intent intent = new Intent(this, NotesPreferenceActivity.class);
        startActivity(intent);
    }

    private class OnListItemClickListener implements OnItemClickListener {
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            if (view instanceof NotesListItem) {
                NoteItemData item = ((NotesListItem) view).getItemData();
                if (mNotesListAdapter.isInChoiceMode()) {
                    if (item.getType() == Notes.TYPE_NOTE) {
                        position -= mNotesListView.getHeaderViewsCount();
                        mModeCallBack.onItemCheckedStateChanged(null, position, id, !mNotesListAdapter.isSelectedItem(position));
                    }
                } else if (item.getType() == Notes.TYPE_NOTE) {
                    openNode(item);
                } else {
                    openFolder(item);
                }
            }
        }
    }

    private void startQueryDestinationFolders() {
        String selection = NoteColumns.TYPE + "=? AND " + NoteColumns.PARENT_ID + "<>? AND " + NoteColumns.ID + "<>?";
        mBackgroundQueryHandler.startQuery(FOLDER_LIST_QUERY_TOKEN, null, Notes.CONTENT_NOTE_URI, FoldersListAdapter.PROJECTION, selection, new String[] { String.valueOf(Notes.TYPE_FOLDER), String.valueOf(Notes.ID_TRASH_FOLER), String.valueOf(mCurrentFolderId) }, NoteColumns.MODIFIED_DATE + " DESC");
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        if (view instanceof NotesListItem) {
            mFocusNoteDataItem = ((NotesListItem) view).getItemData();
            if (mFocusNoteDataItem.getType() == Notes.TYPE_NOTE && !mNotesListAdapter.isInChoiceMode()) {
                mNotesListView.startActionMode(mModeCallBack);
                mModeCallBack.onItemCheckedStateChanged(null, position, id, true);
            } else if (mFocusNoteDataItem.getType() == Notes.TYPE_FOLDER) {
                mNotesListView.setOnCreateContextMenuListener(mFolderOnCreateContextMenuListener);
            }
        }
        return false;
    }
}
