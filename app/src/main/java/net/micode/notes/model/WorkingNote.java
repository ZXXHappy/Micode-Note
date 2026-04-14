package net.micode.notes.model;

import android.appwidget.AppWidgetManager;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.text.TextUtils;
import android.util.Log;

import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.CallNote;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.DataConstants;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.data.Notes.TextNote;
import net.micode.notes.tool.ResourceParser.NoteBgResources;

/**
 * WorkingNote 类：封装“正在编辑的笔记”对象
 * - 对 Note 类做进一步封装，增加 Widget / 背景色 / 提醒等功能
 * - 负责读取数据库数据，管理笔记属性
 * - 提供保存、更新、删除、提醒等操作
 */
public class WorkingNote {

    // -------------------------
    // 属性定义
    // -------------------------

    private Note mNote;            // 笔记对象，管理内容和元数据
    private long mNoteId;          // 笔记ID
    private String mContent;       // 笔记文本内容
    private int mMode;             // 笔记模式（普通/检查列表）
    private long mAlertDate;       // 闹钟提醒时间
    private long mModifiedDate;    // 最近修改时间
    private int mBgColorId;        // 笔记背景色ID
    private int mWidgetId;         // 关联Widget ID
    private int mWidgetType;       // Widget 类型
    private long mFolderId;        // 所属文件夹ID
    private Context mContext;      // 上下文
    private boolean mIsDeleted;    // 是否被删除
    private NoteSettingChangedListener mNoteSettingStatusListener; // 笔记属性变更回调

    private static final String TAG = "WorkingNote";

    // 数据库查询投影字段
    public static final String[] DATA_PROJECTION = new String[] {
            DataColumns.ID,
            DataColumns.CONTENT,
            DataColumns.MIME_TYPE,
            DataColumns.DATA1,
            DataColumns.DATA2,
            DataColumns.DATA3,
            DataColumns.DATA4,
    };

    public static final String[] NOTE_PROJECTION = new String[] {
            NoteColumns.PARENT_ID,
            NoteColumns.ALERTED_DATE,
            NoteColumns.BG_COLOR_ID,
            NoteColumns.WIDGET_ID,
            NoteColumns.WIDGET_TYPE,
            NoteColumns.MODIFIED_DATE
    };

    // 数据列索引
    private static final int DATA_ID_COLUMN = 0;
    private static final int DATA_CONTENT_COLUMN = 1;
    private static final int DATA_MIME_TYPE_COLUMN = 2;
    private static final int DATA_MODE_COLUMN = 3;
    private static final int NOTE_PARENT_ID_COLUMN = 0;
    private static final int NOTE_ALERTED_DATE_COLUMN = 1;
    private static final int NOTE_BG_COLOR_ID_COLUMN = 2;
    private static final int NOTE_WIDGET_ID_COLUMN = 3;
    private static final int NOTE_WIDGET_TYPE_COLUMN = 4;
    private static final int NOTE_MODIFIED_DATE_COLUMN = 5;

    // -------------------------
    // 构造方法
    // -------------------------

    /** 创建新的空笔记对象 */
    private WorkingNote(Context context, long folderId) {
        mContext = context;
        mAlertDate = 0;
        mModifiedDate = System.currentTimeMillis();
        mFolderId = folderId;
        mNote = new Note();
        mNoteId = 0;
        mIsDeleted = false;
        mMode = 0;
        mWidgetType = Notes.TYPE_WIDGET_INVALIDE;
    }

    /** 加载已有笔记 */
    private WorkingNote(Context context, long noteId, long folderId) {
        mContext = context;
        mNoteId = noteId;
        mFolderId = folderId;
        mIsDeleted = false;
        mNote = new Note();
        loadNote(); // 从数据库读取笔记内容
    }

    // -------------------------
    // 数据加载方法
    // -------------------------

    /** 加载笔记元数据 */
    private void loadNote() {
        Cursor cursor = mContext.getContentResolver().query(
                ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, mNoteId), NOTE_PROJECTION, null,
                null, null);

        if (cursor != null) {
            if (cursor.moveToFirst()) {
                mFolderId = cursor.getLong(NOTE_PARENT_ID_COLUMN);
                mBgColorId = cursor.getInt(NOTE_BG_COLOR_ID_COLUMN);
                mWidgetId = cursor.getInt(NOTE_WIDGET_ID_COLUMN);
                mWidgetType = cursor.getInt(NOTE_WIDGET_TYPE_COLUMN);
                mAlertDate = cursor.getLong(NOTE_ALERTED_DATE_COLUMN);
                mModifiedDate = cursor.getLong(NOTE_MODIFIED_DATE_COLUMN);
            }
            cursor.close();
        } else {
            Log.e(TAG, "No note with id:" + mNoteId);
            throw new IllegalArgumentException("Unable to find note with id " + mNoteId);
        }
        loadNoteData(); // 加载笔记内容
    }

    /** 加载笔记具体内容（文本和通话记录） */
    private void loadNoteData() {
        Cursor cursor = mContext.getContentResolver().query(Notes.CONTENT_DATA_URI, DATA_PROJECTION,
                DataColumns.NOTE_ID + "=?", new String[] {
                        String.valueOf(mNoteId)
                }, null);

        if (cursor != null) {
            if (cursor.moveToFirst()) {
                do {
                    String type = cursor.getString(DATA_MIME_TYPE_COLUMN);
                    if (DataConstants.NOTE.equals(type)) {
                        mContent = cursor.getString(DATA_CONTENT_COLUMN);
                        mMode = cursor.getInt(DATA_MODE_COLUMN);
                        mNote.setTextDataId(cursor.getLong(DATA_ID_COLUMN));
                    } else if (DataConstants.CALL_NOTE.equals(type)) {
                        mNote.setCallDataId(cursor.getLong(DATA_ID_COLUMN));
                    } else {
                        Log.d(TAG, "Wrong note type with type:" + type);
                    }
                } while (cursor.moveToNext());
            }
            cursor.close();
        } else {
            Log.e(TAG, "No data with id:" + mNoteId);
            throw new IllegalArgumentException("Unable to find note's data with id " + mNoteId);
        }
    }

    // -------------------------
    // 静态创建方法
    // -------------------------

    /** 创建一个新的空笔记，并设置 Widget 和背景色 */
    public static WorkingNote createEmptyNote(Context context, long folderId, int widgetId,
                                              int widgetType, int defaultBgColorId) {
        WorkingNote note = new WorkingNote(context, folderId);
        note.setBgColorId(defaultBgColorId);
        note.setWidgetId(widgetId);
        note.setWidgetType(widgetType);
        return note;
    }

    /** 从数据库加载已有笔记 */
    public static WorkingNote load(Context context, long id) {
        return new WorkingNote(context, id, 0);
    }

    // -------------------------
    // 笔记保存与检查
    // -------------------------

    /** 保存笔记 */
    public synchronized boolean saveNote() {
        if (isWorthSaving()) {
            // 新笔记，先在数据库创建
            if (!existInDatabase()) {
                if ((mNoteId = Note.getNewNoteId(mContext, mFolderId)) == 0) {
                    Log.e(TAG, "Create new note fail with id:" + mNoteId);
                    return false;
                }
            }

            // 同步 Note 内容到数据库
            mNote.syncNote(mContext, mNoteId);

            // 更新 Widget，如果有
            if (mWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
                    && mWidgetType != Notes.TYPE_WIDGET_INVALIDE
                    && mNoteSettingStatusListener != null) {
                mNoteSettingStatusListener.onWidgetChanged();
            }
            return true;
        } else {
            return false;
        }
    }

    /** 检查笔记是否存在数据库 */
    public boolean existInDatabase() {
        return mNoteId > 0;
    }

    /** 判断笔记是否值得保存 */
    private boolean isWorthSaving() {
        if (mIsDeleted || (!existInDatabase() && TextUtils.isEmpty(mContent))
                || (existInDatabase() && !mNote.isLocalModified())) {
            return false;
        } else {
            return true;
        }
    }

    // -------------------------
    // 设置回调
    // -------------------------

    public void setOnSettingStatusChangedListener(NoteSettingChangedListener l) {
        mNoteSettingStatusListener = l;
    }

    // -------------------------
    // 属性修改方法
    // -------------------------

    /** 设置提醒时间 */
    public void setAlertDate(long date, boolean set) {
        if (date != mAlertDate) {
            mAlertDate = date;
            mNote.setNoteValue(NoteColumns.ALERTED_DATE, String.valueOf(mAlertDate));
        }
        if (mNoteSettingStatusListener != null) {
            mNoteSettingStatusListener.onClockAlertChanged(date, set);
        }
    }

    /** 标记删除 */
    public void markDeleted(boolean mark) {
        mIsDeleted = mark;
        if (mWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
                && mWidgetType != Notes.TYPE_WIDGET_INVALIDE && mNoteSettingStatusListener != null) {
            mNoteSettingStatusListener.onWidgetChanged();
        }
    }

    /** 设置背景色 */
    public void setBgColorId(int id) {
        if (id != mBgColorId) {
            mBgColorId = id;
            if (mNoteSettingStatusListener != null) {
                mNoteSettingStatusListener.onBackgroundColorChanged();
            }
            mNote.setNoteValue(NoteColumns.BG_COLOR_ID, String.valueOf(id));
        }
    }

    /** 设置检查列表模式 */
    public void setCheckListMode(int mode) {
        if (mMode != mode) {
            if (mNoteSettingStatusListener != null) {
                mNoteSettingStatusListener.onCheckListModeChanged(mMode, mode);
            }
            mMode = mode;
            mNote.setTextData(TextNote.MODE, String.valueOf(mMode));
        }
    }

    /** 设置 Widget 类型 */
    public void setWidgetType(int type) {
        if (type != mWidgetType) {
            mWidgetType = type;
            mNote.setNoteValue(NoteColumns.WIDGET_TYPE, String.valueOf(mWidgetType));
        }
    }

    /** 设置 Widget ID */
    public void setWidgetId(int id) {
        if (id != mWidgetId) {
            mWidgetId = id;
            mNote.setNoteValue(NoteColumns.WIDGET_ID, String.valueOf(mWidgetId));
        }
    }

    /** 设置笔记文本内容 */
    public void setWorkingText(String text) {
        if (!TextUtils.equals(mContent, text)) {
            mContent = text;
            mNote.setTextData(DataColumns.CONTENT, mContent);
        }
    }

    /** 将笔记转为通话笔记 */
    public void convertToCallNote(String phoneNumber, long callDate) {
        mNote.setCallData(CallNote.CALL_DATE, String.valueOf(callDate));
        mNote.setCallData(CallNote.PHONE_NUMBER, phoneNumber);
        mNote.setNoteValue(NoteColumns.PARENT_ID, String.valueOf(Notes.ID_CALL_RECORD_FOLDER));
    }

    // -------------------------
    // 属性获取方法
    // -------------------------

    public boolean hasClockAlert() {
        return (mAlertDate > 0);
    }

    public String getContent() { return mContent; }

    public long getAlertDate() { return mAlertDate; }

    public long getModifiedDate() { return mModifiedDate; }

    public int getBgColorResId() { return NoteBgResources.getNoteBgResource(mBgColorId); }

    public int getBgColorId() { return mBgColorId; }

    public int getTitleBgResId() { return NoteBgResources.getNoteTitleBgResource(mBgColorId); }

    public int getCheckListMode() { return mMode; }

    public long getNoteId() { return mNoteId; }

    public long getFolderId() { return mFolderId; }

    public int getWidgetId() { return mWidgetId; }

    public int getWidgetType() { return mWidgetType; }

    // -------------------------
    // 回调接口
    // -------------------------

    public interface NoteSettingChangedListener {
        /** 当背景色修改 */
        void onBackgroundColorChanged();

        /** 当闹钟修改 */
        void onClockAlertChanged(long date, boolean set);

        /** 当 Widget 内容修改 */
        void onWidgetChanged();

        /** 当切换检查列表模式 */
        void onCheckListModeChanged(int oldMode, int newMode);
    }
}
