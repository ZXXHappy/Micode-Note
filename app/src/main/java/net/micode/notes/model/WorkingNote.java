/*
 * Copyright (c) 2010-2011, The MiCode Open Source Community (www.micode.net)
 * Apache License 2.0
 */

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
 * 【设计定位】WorkingNote 是 MVC 架构中的 Model 层核心类。
 *
 * 它封装了一条便签在内存中的完整状态（内容、颜色、提醒时间、分类等），
 * 并通过 Note 对象（mNote）追踪哪些字段发生了变化，实现"脏数据标记"机制，
 * 避免每次保存都全量写库，只将真正修改过的字段同步到数据库。
 *
 * 对外暴露的接口分两类：
 *   1. setter：修改状态 + 通知 View 层刷新（通过 NoteSettingChangedListener 回调）
 *   2. 工厂方法：createEmptyNote（新建）/ load（加载已有便签）
 */
public class WorkingNote {

    // -------------------------------------------------------------------------
    // 【成员变量说明】
    // mNote：底层数据操作对象，负责跟踪字段变更、执行数据库同步
    // mNoteId：数据库主键，0 表示尚未持久化（新建未保存状态）
    // mContent：便签正文内容，对应 data 表的 content 字段
    // mMode：0=普通文本模式，1=清单（CheckList）模式
    // mAlertDate：闹钟提醒时间戳，0 表示未设置提醒
    // mCategoryId：便签分类（新增功能）：0未分类/1工作/2生活/3学习
    // -------------------------------------------------------------------------
    private Note mNote;
    private long mNoteId;
    private String mContent;
    private int mMode;
    private long mAlertDate;
    private long mModifiedDate;
    private int mBgColorId;
    private int mWidgetId;
    private int mWidgetType;
    private long mFolderId;
    private Context mContext;
    private int mCategoryId;

    private static final String TAG = "WorkingNote";

    // 标记是否已被删除，saveNote() 时若为 true 则跳过保存
    private boolean mIsDeleted;

    /**
     * 【观察者模式】View 层（NoteEditActivity）实现此接口，
     * 当 Model 状态改变时，WorkingNote 主动回调通知 View 刷新 UI，
     * 实现了 Model → View 的单向数据驱动，解耦两层之间的直接依赖。
     */
    private NoteSettingChangedListener mNoteSettingStatusListener;

    // 【投影列定义】
    // 查询数据库时只取需要的列（而非 SELECT *），减少 IO 与内存开销。
    // 列索引常量（DATA_ID_COLUMN 等）与此数组下标严格对应，
    // 后续通过 cursor.getXxx(常量) 取值，避免硬编码数字导致的维护风险。
    public static final String[] DATA_PROJECTION = new String[]{
            DataColumns.ID,        // 0: data 行 id
            DataColumns.CONTENT,   // 1: 正文内容
            DataColumns.MIME_TYPE, // 2: 类型（text_note / call_note）
            DataColumns.DATA1,     // 3: 文本模式标志 / 通话日期
            DataColumns.DATA2,     // 4: 通用扩展字段
            DataColumns.DATA3,     // 5: 电话号码
            DataColumns.DATA4,     // 6: 通用扩展字段
    };

    public static final String[] NOTE_PROJECTION = new String[]{
            NoteColumns.PARENT_ID,          // 0: 所属文件夹 id
            NoteColumns.ALERTED_DATE,       // 1: 闹钟时间戳
            NoteColumns.BG_COLOR_ID,        // 2: 背景色 id
            NoteColumns.WIDGET_ID,          // 3: 桌面小组件 id
            NoteColumns.WIDGET_TYPE,        // 4: 小组件类型（2x / 4x）
            NoteColumns.MODIFIED_DATE,      // 5: 最后修改时间
            NoteColumns.COLUMN_CATEGORY_ID  // 6: 分类 id（新增字段）
    };

    // 列索引常量，与 DATA_PROJECTION 数组下标一一对应
    private static final int DATA_ID_COLUMN       = 0;
    private static final int DATA_CONTENT_COLUMN  = 1;
    private static final int DATA_MIME_TYPE_COLUMN = 2;
    private static final int DATA_MODE_COLUMN     = 3;

    // 列索引常量，与 NOTE_PROJECTION 数组下标一一对应
    private static final int NOTE_PARENT_ID_COLUMN      = 0;
    private static final int NOTE_ALERTED_DATE_COLUMN   = 1;
    private static final int NOTE_BG_COLOR_ID_COLUMN    = 2;
    private static final int NOTE_WIDGET_ID_COLUMN      = 3;
    private static final int NOTE_WIDGET_TYPE_COLUMN    = 4;
    private static final int NOTE_MODIFIED_DATE_COLUMN  = 5;

    // -------------------------------------------------------------------------
    // 【构造函数私有化】
    // 外部不能直接 new WorkingNote()，必须通过工厂方法 createEmptyNote() 或
    // load() 获取实例。这样可以在工厂方法中统一做参数校验和状态初始化，
    // 避免对象以不完整的状态对外暴露。
    // -------------------------------------------------------------------------

    /**
     * 新建便签专用构造：mNoteId=0 表示尚未写入数据库，
     * 只有调用 saveNote() 后才会生成真实 id。
     */
    private WorkingNote(Context context, long folderId) {
        mContext = context;
        mAlertDate = 0;
        mModifiedDate = System.currentTimeMillis();
        mFolderId = folderId;
        mNote = new Note();
        mNoteId = 0;           // 0 = 新建，尚未持久化
        mIsDeleted = false;
        mMode = 0;
        mWidgetType = Notes.TYPE_WIDGET_INVALIDE;
    }

    /**
     * 加载已有便签专用构造：传入 noteId，构造时立即从数据库读取数据。
     */
    private WorkingNote(Context context, long noteId, long folderId) {
        mContext = context;
        mNoteId = noteId;
        mFolderId = folderId;
        mIsDeleted = false;
        mNote = new Note();
        loadNote(); // 立即触发数据库加载
    }

    /**
     * 【数据加载 — 第一步】从 note 表加载便签的"元数据"：
     * 文件夹归属、背景色、Widget 绑定、闹钟时间、分类 id 等。
     *
     * 【防御性编程】使用 getColumnIndex() 而非硬编码列索引获取分类字段，
     * 即使旧版数据库中该列不存在（columnIndex = -1），
     * 也不会抛出异常，保证向下兼容。
     */
    private void loadNote() {
        Cursor cursor = mContext.getContentResolver().query(
                ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, mNoteId),
                NOTE_PROJECTION, null, null, null);

        if (cursor != null) {
            if (cursor.moveToFirst()) {
                mFolderId    = cursor.getLong(NOTE_PARENT_ID_COLUMN);
                mBgColorId   = cursor.getInt(NOTE_BG_COLOR_ID_COLUMN);
                mWidgetId    = cursor.getInt(NOTE_WIDGET_ID_COLUMN);
                mWidgetType  = cursor.getInt(NOTE_WIDGET_TYPE_COLUMN);
                mAlertDate   = cursor.getLong(NOTE_ALERTED_DATE_COLUMN);
                mModifiedDate = cursor.getLong(NOTE_MODIFIED_DATE_COLUMN);

                // 【新增字段兼容读取】getColumnIndex 返回 -1 时表示旧库无此列，
                // 跳过赋值，mCategoryId 保持默认值 0（未分类），不崩溃。
                int categoryIndex = cursor.getColumnIndex(NoteColumns.COLUMN_CATEGORY_ID);
                if (categoryIndex >= 0) {
                    mCategoryId = cursor.getInt(categoryIndex);
                }
            }
            cursor.close(); // 及时关闭，防止 Cursor 泄漏
        } else {
            Log.e(TAG, "No note with id:" + mNoteId);
            throw new IllegalArgumentException("Unable to find note with id " + mNoteId);
        }
        loadNoteData(); // 元数据加载完毕后，继续加载正文内容
    }

    /**
     * 【数据加载 — 第二步】从 data 表加载便签正文内容。
     *
     * 【设计说明】note 表和 data 表是 1:N 关系：
     * 一条普通便签对应一条 text_note 类型的 data 行；
     * 通话记录便签额外对应一条 call_note 类型的 data 行（存储电话号码和通话时间）。
     * 通过 MIME_TYPE 字段区分行类型，分别处理，互不干扰。
     */
    private void loadNoteData() {
        Cursor cursor = mContext.getContentResolver().query(
                Notes.CONTENT_DATA_URI, DATA_PROJECTION,
                DataColumns.NOTE_ID + "=?",
                new String[]{String.valueOf(mNoteId)}, null);

        if (cursor != null) {
            if (cursor.moveToFirst()) {
                do {
                    String type = cursor.getString(DATA_MIME_TYPE_COLUMN);
                    if (DataConstants.NOTE.equals(type)) {
                        // 普通文本便签：读取正文和编辑模式（普通/清单）
                        mContent = cursor.getString(DATA_CONTENT_COLUMN);
                        mMode    = cursor.getInt(DATA_MODE_COLUMN);
                        mNote.setTextDataId(cursor.getLong(DATA_ID_COLUMN));
                    } else if (DataConstants.CALL_NOTE.equals(type)) {
                        // 通话记录便签：只记录 data 行 id，内容由 CallNote 单独管理
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

    // -------------------------------------------------------------------------
    // 【工厂方法】对外提供两种创建方式，隐藏构造细节
    // -------------------------------------------------------------------------

    /**
     * 创建一条空白新便签，设置默认背景色和 Widget 绑定信息。
     * 此时 mNoteId=0，尚未写库，调用 saveNote() 后才正式持久化。
     */
    public static WorkingNote createEmptyNote(Context context, long folderId,
            int widgetId, int widgetType, int defaultBgColorId) {
        WorkingNote note = new WorkingNote(context, folderId);
        note.setBgColorId(defaultBgColorId);
        note.setWidgetId(widgetId);
        note.setWidgetType(widgetType);
        return note;
    }

    /**
     * 从数据库加载一条已存在的便签。
     */
    public static WorkingNote load(Context context, long id) {
        return new WorkingNote(context, id, 0);
    }

    /**
     * 【核心保存逻辑】synchronized 保证多线程安全（Widget 更新可能并发触发）。
     *
     * 保存前先调用 isWorthSaving() 做"值不值得保存"的判断：
     *   - 已删除 → 不保存
     *   - 新建但内容为空 → 不保存（避免产生空便签垃圾数据）
     *   - 已存在但内容未修改 → 不保存（避免无意义的数据库写入）
     *
     * 新建便签首次保存时，通过 Note.getNewNoteId() 在数据库中
     * 先 insert 一条空记录占位，拿到真实 id 后再同步完整数据。
     */
    public synchronized boolean saveNote() {
        if (isWorthSaving()) {
            if (!existInDatabase()) {
                // 新便签：先插入占位行，获取数据库分配的真实 id
                if ((mNoteId = Note.getNewNoteId(mContext, mFolderId)) == 0) {
                    Log.e(TAG, "Create new note fail with id:" + mNoteId);
                    return false;
                }
            }
            // 将内存中所有"脏字段"同步写入数据库
            mNote.syncNote(mContext, mNoteId);

            // 若便签绑定了桌面 Widget，通知 Widget 刷新显示内容
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

    /** mNoteId > 0 说明该便签在数据库中已有对应行 */
    public boolean existInDatabase() {
        return mNoteId > 0;
    }

    /**
     * 【脏数据过滤】综合三个条件判断是否值得写库，
     * 避免频繁无效的数据库 IO，是性能优化的重要手段。
     */
    private boolean isWorthSaving() {
        if (mIsDeleted
                || (!existInDatabase() && TextUtils.isEmpty(mContent))
                || (existInDatabase() && !mNote.isLocalModified())) {
            return false; // 以上任一条件成立，跳过保存
        } else {
            return true;
        }
    }

    /** 注册 View 层的监听器，建立 Model → View 的回调通道 */
    public void setOnSettingStatusChangedListener(NoteSettingChangedListener l) {
        mNoteSettingStatusListener = l;
    }

    // -------------------------------------------------------------------------
    // 【Setter 设计规范】
    // 每个 setter 遵循统一模式：
    //   1. 判断新旧值是否真的发生变化（避免重复触发回调）
    //   2. 更新内存状态
    //   3. 通过 mNote.setNoteValue() 标记该字段为"脏"，待 syncNote() 时写库
    //   4. 通过 mNoteSettingStatusListener 回调通知 View 层刷新对应 UI
    // -------------------------------------------------------------------------

    /**
     * 设置闹钟提醒时间。
     * set=true 表示新增提醒，set=false 表示取消提醒（date 传 0）。
     * 回调 onClockAlertChanged 通知 NoteEditActivity 刷新提醒图标。
     */
    public void setAlertDate(long date, boolean set) {
        if (date != mAlertDate) {
            mAlertDate = date;
            mNote.setNoteValue(NoteColumns.ALERTED_DATE, String.valueOf(mAlertDate));
        }
        if (mNoteSettingStatusListener != null) {
            mNoteSettingStatusListener.onClockAlertChanged(date, set);
        }
    }

    /**
     * 标记便签为已删除状态。
     * 注意：这里只是设置内存标志，并未立即操作数据库。
     * 真正的删除由 DataUtils.batchDeleteNotes() 在列表页执行。
     * 同时通知 Widget 刷新，避免已删除便签仍显示在桌面组件上。
     */
    public void markDeleted(boolean mark) {
        mIsDeleted = mark;
        if (mWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
                && mWidgetType != Notes.TYPE_WIDGET_INVALIDE
                && mNoteSettingStatusListener != null) {
            mNoteSettingStatusListener.onWidgetChanged();
        }
    }

    /**
     * 设置背景色。
     * 回调 onBackgroundColorChanged 让 NoteEditActivity 立即刷新背景，
     * 用户能实时预览颜色变化，无需等待保存完成。
     */
    public void setBgColorId(int id) {
        if (id != mBgColorId) {
            mBgColorId = id;
            if (mNoteSettingStatusListener != null) {
                mNoteSettingStatusListener.onBackgroundColorChanged();
            }
            mNote.setNoteValue(NoteColumns.BG_COLOR_ID, String.valueOf(id));
        }
    }

    /**
     * 切换普通文本模式 ↔ 清单（CheckList）模式。
     * 回调 onCheckListModeChanged，NoteEditActivity 据此重建编辑器 UI。
     */
    public void setCheckListMode(int mode) {
        if (mMode != mode) {
            if (mNoteSettingStatusListener != null) {
                mNoteSettingStatusListener.onCheckListModeChanged(mMode, mode);
            }
            mMode = mode;
            mNote.setTextData(TextNote.MODE, String.valueOf(mMode));
        }
    }

    public void setWidgetType(int type) {
        if (type != mWidgetType) {
            mWidgetType = type;
            mNote.setNoteValue(NoteColumns.WIDGET_TYPE, String.valueOf(mWidgetType));
        }
    }

    public void setWidgetId(int id) {
        if (id != mWidgetId) {
            mWidgetId = id;
            mNote.setNoteValue(NoteColumns.WIDGET_ID, String.valueOf(mWidgetId));
        }
    }

    /**
     * 更新正文内容。使用 TextUtils.equals() 而非 == 进行字符串比较，
     * 避免引用相等判断带来的逻辑错误。
     */
    public void setWorkingText(String text) {
        if (!TextUtils.equals(mContent, text)) {
            mContent = text;
            mNote.setTextData(DataColumns.CONTENT, mContent);
        }
    }

    /**
     * 将普通便签转换为通话记录便签。
     * 写入电话号码和通话时间，并将父文件夹强制设为通话记录专用文件夹。
     */
    public void convertToCallNote(String phoneNumber, long callDate) {
        mNote.setCallData(CallNote.CALL_DATE, String.valueOf(callDate));
        mNote.setCallData(CallNote.PHONE_NUMBER, phoneNumber);
        mNote.setNoteValue(NoteColumns.PARENT_ID,
                String.valueOf(Notes.ID_CALL_RECORD_FOLDER));
    }

    /** mAlertDate > 0 即表示已设置了闹钟提醒 */
    public boolean hasClockAlert() {
        return (mAlertDate > 0);
    }

    // -------------------------------------------------------------------------
    // Getter 方法：供 View 层读取当前状态，不触发任何副作用
    // -------------------------------------------------------------------------
    public String getContent()      { return mContent; }
    public long getAlertDate()      { return mAlertDate; }
    public long getModifiedDate()   { return mModifiedDate; }
    public int getBgColorId()       { return mBgColorId; }
    public int getCheckListMode()   { return mMode; }
    public long getNoteId()         { return mNoteId; }
    public long getFolderId()       { return mFolderId; }
    public int getWidgetId()        { return mWidgetId; }
    public int getWidgetType()      { return mWidgetType; }

    /** 根据背景色 id 获取对应的 drawable 资源 id，解耦颜色逻辑与 UI 渲染 */
    public int getBgColorResId()  { return NoteBgResources.getNoteBgResource(mBgColorId); }
    public int getTitleBgResId()  { return NoteBgResources.getNoteTitleBgResource(mBgColorId); }

    // -------------------------------------------------------------------------
    // 【观察者接口】NoteSettingChangedListener
    // 定义 Model → View 的四种通知类型，Activity 实现此接口后，
    // WorkingNote 无需持有 Activity 引用即可驱动 UI 更新，
    // 实现了依赖倒置，降低 Model 层与 View 层的耦合度。
    // -------------------------------------------------------------------------
    public interface NoteSettingChangedListener {
        /** 背景色改变时触发，View 层刷新背景 drawable */
        void onBackgroundColorChanged();

        /** 闹钟设置/取消时触发，View 层刷新提醒图标和时间显示 */
        void onClockAlertChanged(long date, boolean set);

        /** 绑定的桌面 Widget 需要刷新内容时触发 */
        void onWidgetChanged();

        /** 普通模式与清单模式互切时触发，View 层重建编辑器 */
        void onCheckListModeChanged(int oldMode, int newMode);
    }

    // -------------------------------------------------------------------------
    // 【新增功能】便签分类（category）的 getter / setter
    // 遵循与其他字段完全一致的脏标记模式：
    // 只有值真正改变时才标记 mNote 脏，避免无效写库。
    // -------------------------------------------------------------------------

    public int getCategoryId() {
        return mCategoryId;
    }

    /**
     * 设置便签分类（0未分类 / 1工作 / 2生活 / 3学习）。
     * 调用 mNote.setNoteValue() 将变更加入待同步队列，
     * 下次 saveNote() 时随其他脏字段一起批量写入数据库。
     */
    public void setCategoryId(int id) {
        if (id != mCategoryId) {
            mCategoryId = id;
            mNote.setNoteValue(NoteColumns.COLUMN_CATEGORY_ID, String.valueOf(mCategoryId));
        }
    }
}
