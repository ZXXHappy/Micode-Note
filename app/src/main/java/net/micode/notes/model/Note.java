package net.micode.notes.model;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.net.Uri;
import android.os.RemoteException;
import android.util.Log;

import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.CallNote;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.data.Notes.TextNote;

import java.util.ArrayList;

/**
 * Note 类：管理单条笔记对象
 * - 封装笔记元数据（标题、类型、修改时间等）
 * - 封装笔记内容（文本笔记 / 通话笔记）
 * - 提供数据库创建、更新和同步方法
 */
public class Note {

    // 存储笔记元数据变更，用于数据库更新
    private ContentValues mNoteDiffValues;

    // 存储笔记内容（文本/通话）
    private NoteData mNoteData;

    private static final String TAG = "Note";

    /**
     * 静态方法：创建一个新的笔记记录
     * @param context 上下文
     * @param folderId 所属文件夹ID
     * @return 新创建的 noteId
     */
    public static synchronized long getNewNoteId(Context context, long folderId) {
        // 创建笔记元数据
        ContentValues values = new ContentValues();
        long createdTime = System.currentTimeMillis();
        values.put(NoteColumns.CREATED_DATE, createdTime);
        values.put(NoteColumns.MODIFIED_DATE, createdTime);
        values.put(NoteColumns.TYPE, Notes.TYPE_NOTE); // 普通文本笔记
        values.put(NoteColumns.LOCAL_MODIFIED, 1);     // 标记为本地修改
        values.put(NoteColumns.PARENT_ID, folderId);   // 所属文件夹

        // 插入数据库，获取 Uri
        Uri uri = context.getContentResolver().insert(Notes.CONTENT_NOTE_URI, values);

        // 从 Uri 获取 noteId
        long noteId = 0;
        try {
            noteId = Long.valueOf(uri.getPathSegments().get(1));
        } catch (NumberFormatException e) {
            Log.e(TAG, "Get note id error :" + e.toString());
            noteId = 0;
        }

        if (noteId == -1) {
            throw new IllegalStateException("Wrong note id:" + noteId);
        }
        return noteId;
    }

    // 构造函数：初始化元数据和内容
    public Note() {
        mNoteDiffValues = new ContentValues();
        mNoteData = new NoteData();
    }

    /**
     * 设置笔记元数据
     * @param key 字段名
     * @param value 字段值
     */
    public void setNoteValue(String key, String value) {
        mNoteDiffValues.put(key, value);
        mNoteDiffValues.put(NoteColumns.LOCAL_MODIFIED, 1);
        mNoteDiffValues.put(NoteColumns.MODIFIED_DATE, System.currentTimeMillis());
    }

    /**
     * 设置文本笔记内容
     */
    public void setTextData(String key, String value) {
        mNoteData.setTextData(key, value);
    }

    public void setTextDataId(long id) {
        mNoteData.setTextDataId(id);
    }

    public long getTextDataId() {
        return mNoteData.mTextDataId;
    }

    /**
     * 设置通话笔记内容
     */
    public void setCallDataId(long id) {
        mNoteData.setCallDataId(id);
    }

    public void setCallData(String key, String value) {
        mNoteData.setCallData(key, value);
    }

    /**
     * 判断笔记是否有本地修改
     * @return true 表示笔记或内容有修改
     */
    public boolean isLocalModified() {
        return mNoteDiffValues.size() > 0 || mNoteData.isLocalModified();
    }

    /**
     * 同步笔记到数据库
     * @param context 上下文
     * @param noteId 笔记ID
     * @return true 表示同步成功
     */
    public boolean syncNote(Context context, long noteId) {
        if (noteId <= 0) {
            throw new IllegalArgumentException("Wrong note id:" + noteId);
        }

        // 如果没有修改，直接返回
        if (!isLocalModified()) {
            return true;
        }

        // 更新笔记元数据到数据库
        if (context.getContentResolver().update(
                ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, noteId), mNoteDiffValues, null,
                null) == 0) {
            Log.e(TAG, "Update note error, should not happen");
        }
        mNoteDiffValues.clear();

        // 更新笔记内容（文本/通话）
        if (mNoteData.isLocalModified()
                && (mNoteData.pushIntoContentResolver(context, noteId) == null)) {
            return false;
        }

        return true;
    }

    /**
     * 内部类 NoteData：管理笔记内容（文本 + 通话）
     */
    private class NoteData {
        private long mTextDataId;                 // 文本笔记ID
        private ContentValues mTextDataValues;   // 文本笔记数据
        private long mCallDataId;                 // 通话笔记ID
        private ContentValues mCallDataValues;   // 通话笔记数据
        private static final String TAG = "NoteData";

        public NoteData() {
            mTextDataValues = new ContentValues();
            mCallDataValues = new ContentValues();
            mTextDataId = 0;
            mCallDataId = 0;
        }

        // 判断内容是否有修改
        boolean isLocalModified() {
            return mTextDataValues.size() > 0 || mCallDataValues.size() > 0;
        }

        void setTextDataId(long id) {
            if(id <= 0) {
                throw new IllegalArgumentException("Text data id should larger than 0");
            }
            mTextDataId = id;
        }

        void setCallDataId(long id) {
            if (id <= 0) {
                throw new IllegalArgumentException("Call data id should larger than 0");
            }
            mCallDataId = id;
        }

        void setCallData(String key, String value) {
            mCallDataValues.put(key, value);
            mNoteDiffValues.put(NoteColumns.LOCAL_MODIFIED, 1);
            mNoteDiffValues.put(NoteColumns.MODIFIED_DATE, System.currentTimeMillis());
        }

        void setTextData(String key, String value) {
            mTextDataValues.put(key, value);
            mNoteDiffValues.put(NoteColumns.LOCAL_MODIFIED, 1);
            mNoteDiffValues.put(NoteColumns.MODIFIED_DATE, System.currentTimeMillis());
        }

        /**
         * 将文本和通话数据写入数据库
         * @param context 上下文
         * @param noteId 笔记ID
         * @return Uri 成功返回数据库 Uri，失败返回 null
         */
        Uri pushIntoContentResolver(Context context, long noteId) {
            if (noteId <= 0) {
                throw new IllegalArgumentException("Wrong note id:" + noteId);
            }

            ArrayList<ContentProviderOperation> operationList = new ArrayList<>();
            ContentProviderOperation.Builder builder = null;

            // 处理文本数据
            if(mTextDataValues.size() > 0) {
                mTextDataValues.put(DataColumns.NOTE_ID, noteId);

                // 新建文本数据
                if (mTextDataId == 0) {
                    mTextDataValues.put(DataColumns.MIME_TYPE, TextNote.CONTENT_ITEM_TYPE);
                    Uri uri = context.getContentResolver().insert(Notes.CONTENT_DATA_URI,
                            mTextDataValues);
                    try {
                        setTextDataId(Long.valueOf(uri.getPathSegments().get(1)));
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "Insert new text data fail with noteId" + noteId);
                        mTextDataValues.clear();
                        return null;
                    }
                } else {
                    // 更新已有文本数据
                    builder = ContentProviderOperation.newUpdate(ContentUris.withAppendedId(
                            Notes.CONTENT_DATA_URI, mTextDataId));
                    builder.withValues(mTextDataValues);
                    operationList.add(builder.build());
                }
                mTextDataValues.clear();
            }

            // 处理通话数据
            if(mCallDataValues.size() > 0) {
                mCallDataValues.put(DataColumns.NOTE_ID, noteId);

                // 新建通话数据
                if (mCallDataId == 0) {
                    mCallDataValues.put(DataColumns.MIME_TYPE, CallNote.CONTENT_ITEM_TYPE);
                    Uri uri = context.getContentResolver().insert(Notes.CONTENT_DATA_URI,
                            mCallDataValues);
                    try {
                        setCallDataId(Long.valueOf(uri.getPathSegments().get(1)));
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "Insert new call data fail with noteId" + noteId);
                        mCallDataValues.clear();
                        return null;
                    }
                } else {
                    // 更新已有通话数据
                    builder = ContentProviderOperation.newUpdate(ContentUris.withAppendedId(
                            Notes.CONTENT_DATA_URI, mCallDataId));
                    builder.withValues(mCallDataValues);
                    operationList.add(builder.build());
                }
                mCallDataValues.clear();
            }

            // 批量执行数据库操作
            if (operationList.size() > 0) {
                try {
                    ContentProviderResult[] results = context.getContentResolver().applyBatch(
                            Notes.AUTHORITY, operationList);
                    return (results == null || results.length == 0 || results[0] == null) ? null
                            : ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, noteId);
                } catch (RemoteException | OperationApplicationException e) {
                    Log.e(TAG, String.format("%s: %s", e.toString(), e.getMessage()));
                    return null;
                }
            }
            return null;
        }
    }
}
