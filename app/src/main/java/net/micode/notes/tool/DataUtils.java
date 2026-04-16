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

package net.micode.notes.tool;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.os.RemoteException;
import android.util.Log;

import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.CallNote;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.ui.NotesListAdapter.AppWidgetAttribute;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * 数据工具类，提供对笔记数据库的常用操作方法，
 * 包括批量删除、移动笔记、查询文件夹及便签信息等功能。
 */
public class DataUtils {
    // 日志标签，用于 logcat 输出标识
    public static final String TAG = "DataUtils";

    /**
     * 批量删除笔记。
     *
     * @param resolver Android ContentResolver，用于执行数据库操作
     * @param ids      要删除的笔记 ID 集合（HashSet<Long>）
     * @return 删除成功返回 true，否则返回 false；ids 为 null 或为空时也返回 true
     */
    public static boolean batchDeleteNotes(ContentResolver resolver, HashSet<Long> ids) {
        // ids 为 null 时，认为无需删除，直接返回成功
        if (ids == null) {
            Log.d(TAG, "the ids is null");
            return true;
        }
        // ids 为空集合时，同样无需操作，直接返回成功
        if (ids.size() == 0) {
            Log.d(TAG, "no id is in the hashset");
            return true;
        }

        // 构建批量删除操作列表
        ArrayList<ContentProviderOperation> operationList = new ArrayList<ContentProviderOperation>();
        for (long id : ids) {
            // 跳过根文件夹，不允许删除系统根目录
            if(id == Notes.ID_ROOT_FOLDER) {
                Log.e(TAG, "Don't delete system folder root");
                continue;
            }
            // 为每个 id 构建一个删除操作
            ContentProviderOperation.Builder builder = ContentProviderOperation
                    .newDelete(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, id));
            operationList.add(builder.build());
        }
        try {
            // 批量执行删除操作
            ContentProviderResult[] results = resolver.applyBatch(Notes.AUTHORITY, operationList);
            // 检查结果是否有效
            if (results == null || results.length == 0 || results[0] == null) {
                Log.d(TAG, "delete notes failed, ids:" + ids.toString());
                return false;
            }
            return true;
        } catch (RemoteException e) {
            // 远程调用异常
            Log.e(TAG, String.format("%s: %s", e.toString(), e.getMessage()));
        } catch (OperationApplicationException e) {
            // 操作执行异常
            Log.e(TAG, String.format("%s: %s", e.toString(), e.getMessage()));
        }
        return false;
    }

    /**
     * 将单条笔记从源文件夹移动到目标文件夹。
     *
     * @param resolver      Android ContentResolver
     * @param id            要移动的笔记 ID
     * @param srcFolderId   源文件夹 ID
     * @param desFolderId   目标文件夹 ID
     */
    public static void moveNoteToFoler(ContentResolver resolver, long id, long srcFolderId, long desFolderId) {
        ContentValues values = new ContentValues();
        // 设置新的父文件夹 ID（目标文件夹）
        values.put(NoteColumns.PARENT_ID, desFolderId);
        // 记录原始父文件夹 ID（便于撤销或追踪来源）
        values.put(NoteColumns.ORIGIN_PARENT_ID, srcFolderId);
        // 标记本地已修改，触发同步
        values.put(NoteColumns.LOCAL_MODIFIED, 1);
        resolver.update(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, id), values, null, null);
    }

    /**
     * 批量将笔记移动到指定文件夹。
     *
     * @param resolver  Android ContentResolver
     * @param ids       要移动的笔记 ID 集合
     * @param folderId  目标文件夹 ID
     * @return 移动成功返回 true，否则返回 false；ids 为 null 时也返回 true
     */
    public static boolean batchMoveToFolder(ContentResolver resolver, HashSet<Long> ids,
            long folderId) {
        // ids 为 null 时，认为无需操作，直接返回成功
        if (ids == null) {
            Log.d(TAG, "the ids is null");
            return true;
        }

        // 构建批量更新操作列表
        ArrayList<ContentProviderOperation> operationList = new ArrayList<ContentProviderOperation>();
        for (long id : ids) {
            // 为每个 id 构建更新操作，设置新的父文件夹并标记本地已修改
            ContentProviderOperation.Builder builder = ContentProviderOperation
                    .newUpdate(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, id));
            builder.withValue(NoteColumns.PARENT_ID, folderId);
            builder.withValue(NoteColumns.LOCAL_MODIFIED, 1);
            operationList.add(builder.build());
        }

        try {
            // 批量执行更新操作
            ContentProviderResult[] results = resolver.applyBatch(Notes.AUTHORITY, operationList);
            // 检查结果是否有效
            if (results == null || results.length == 0 || results[0] == null) {
                Log.d(TAG, "delete notes failed, ids:" + ids.toString());
                return false;
            }
            return true;
        } catch (RemoteException e) {
            // 远程调用异常
            Log.e(TAG, String.format("%s: %s", e.toString(), e.getMessage()));
        } catch (OperationApplicationException e) {
            // 操作执行异常
            Log.e(TAG, String.format("%s: %s", e.toString(), e.getMessage()));
        }
        return false;
    }

    /**
     * 获取用户自定义文件夹的数量，不包含系统文件夹（如回收站）。
     * Get the all folder count except system folders {@link Notes#TYPE_SYSTEM}}
     *
     * @param resolver Android ContentResolver
     * @return 用户文件夹的数量
     */
    public static int getUserFolderCount(ContentResolver resolver) {
        // 查询类型为文件夹且不在回收站中的记录数量
        Cursor cursor =resolver.query(Notes.CONTENT_NOTE_URI,
                new String[] { "COUNT(*)" },
                NoteColumns.TYPE + "=? AND " + NoteColumns.PARENT_ID + "<>?",
                new String[] { String.valueOf(Notes.TYPE_FOLDER), String.valueOf(Notes.ID_TRASH_FOLER)},
                null);

        int count = 0;
        if(cursor != null) {
            if(cursor.moveToFirst()) {
                try {
                    // 读取查询结果中的计数值
                    count = cursor.getInt(0);
                } catch (IndexOutOfBoundsException e) {
                    Log.e(TAG, "get folder count failed:" + e.toString());
                } finally {
                    cursor.close();
                }
            }
        }
        return count;
    }

    /**
     * 检查指定 ID 的笔记是否在数据库中可见（不在回收站中，且类型匹配）。
     *
     * @param resolver Android ContentResolver
     * @param noteId   笔记 ID
     * @param type     笔记类型（如普通笔记或文件夹）
     * @return 可见则返回 true，否则返回 false
     */
    public static boolean visibleInNoteDatabase(ContentResolver resolver, long noteId, int type) {
        // 查询指定 ID、指定类型且不在回收站中的笔记
        Cursor cursor = resolver.query(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, noteId),
                null,
                NoteColumns.TYPE + "=? AND " + NoteColumns.PARENT_ID + "<>" + Notes.ID_TRASH_FOLER,
                new String [] {String.valueOf(type)},
                null);

        boolean exist = false;
        if (cursor != null) {
            // 若查询结果数量大于0，说明该笔记可见
            if (cursor.getCount() > 0) {
                exist = true;
            }
            cursor.close();
        }
        return exist;
    }

    /**
     * 检查指定 ID 的笔记是否存在于笔记数据库中（不区分是否在回收站）。
     *
     * @param resolver Android ContentResolver
     * @param noteId   笔记 ID
     * @return 存在返回 true，否则返回 false
     */
    public static boolean existInNoteDatabase(ContentResolver resolver, long noteId) {
        // 根据 ID 查询笔记，不附加任何条件
        Cursor cursor = resolver.query(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, noteId),
                null, null, null, null);

        boolean exist = false;
        if (cursor != null) {
            // 若查询结果数量大于0，说明该笔记存在
            if (cursor.getCount() > 0) {
                exist = true;
            }
            cursor.close();
        }
        return exist;
    }

    /**
     * 检查指定 ID 的数据条目是否存在于数据数据库中。
     *
     * @param resolver Android ContentResolver
     * @param dataId   数据条目 ID
     * @return 存在返回 true，否则返回 false
     */
    public static boolean existInDataDatabase(ContentResolver resolver, long dataId) {
        // 根据数据 ID 查询数据表，不附加任何条件
        Cursor cursor = resolver.query(ContentUris.withAppendedId(Notes.CONTENT_DATA_URI, dataId),
                null, null, null, null);

        boolean exist = false;
        if (cursor != null) {
            // 若查询结果数量大于0，说明该数据条目存在
            if (cursor.getCount() > 0) {
                exist = true;
            }
            cursor.close();
        }
        return exist;
    }

    /**
     * 检查指定名称的文件夹在可见区域（非回收站）中是否已存在。
     *
     * @param resolver Android ContentResolver
     * @param name     文件夹名称
     * @return 存在同名文件夹返回 true，否则返回 false
     */
    public static boolean checkVisibleFolderName(ContentResolver resolver, String name) {
        // 查询类型为文件夹、不在回收站、且名称匹配的记录
        Cursor cursor = resolver.query(Notes.CONTENT_NOTE_URI, null,
                NoteColumns.TYPE + "=" + Notes.TYPE_FOLDER +
                " AND " + NoteColumns.PARENT_ID + "<>" + Notes.ID_TRASH_FOLER +
                " AND " + NoteColumns.SNIPPET + "=?",
                new String[] { name }, null);
        boolean exist = false;
        if(cursor != null) {
            if(cursor.getCount() > 0) {
                exist = true;
            }
            cursor.close();
        }
        return exist;
    }

    /**
     * 获取指定文件夹下所有笔记关联的桌面小部件（Widget）属性集合。
     *
     * @param resolver Android ContentResolver
     * @param folderId 文件夹 ID
     * @return 包含 AppWidgetAttribute 的 HashSet，若无则返回 null
     */
    public static HashSet<AppWidgetAttribute> getFolderNoteWidget(ContentResolver resolver, long folderId) {
        // 查询指定文件夹下所有笔记的 Widget ID 和 Widget 类型
        Cursor c = resolver.query(Notes.CONTENT_NOTE_URI,
                new String[] { NoteColumns.WIDGET_ID, NoteColumns.WIDGET_TYPE },
                NoteColumns.PARENT_ID + "=?",
                new String[] { String.valueOf(folderId) },
                null);

        HashSet<AppWidgetAttribute> set = null;
        if (c != null) {
            if (c.moveToFirst()) {
                set = new HashSet<AppWidgetAttribute>();
                do {
                    try {
                        // 逐行读取 Widget 属性并加入集合
                        AppWidgetAttribute widget = new AppWidgetAttribute();
                        widget.widgetId = c.getInt(0);
                        widget.widgetType = c.getInt(1);
                        set.add(widget);
                    } catch (IndexOutOfBoundsException e) {
                        Log.e(TAG, e.toString());
                    }
                } while (c.moveToNext()); // 遍历所有结果行
            }
            c.close();
        }
        return set;
    }

    /**
     * 根据笔记 ID 获取关联的通话电话号码。
     *
     * @param resolver Android ContentResolver
     * @param noteId   笔记 ID
     * @return 对应的电话号码字符串，若不存在则返回空字符串 ""
     */
    public static String getCallNumberByNoteId(ContentResolver resolver, long noteId) {
        // 在数据表中查询与该笔记关联的通话记录，获取电话号码
        Cursor cursor = resolver.query(Notes.CONTENT_DATA_URI,
                new String [] { CallNote.PHONE_NUMBER },
                CallNote.NOTE_ID + "=? AND " + CallNote.MIME_TYPE + "=?",
                new String [] { String.valueOf(noteId), CallNote.CONTENT_ITEM_TYPE },
                null);

        if (cursor != null && cursor.moveToFirst()) {
            try {
                // 读取并返回电话号码
                return cursor.getString(0);
            } catch (IndexOutOfBoundsException e) {
                Log.e(TAG, "Get call number fails " + e.toString());
            } finally {
                cursor.close();
            }
        }
        return "";
    }

    /**
     * 根据电话号码和通话日期查找对应笔记的 ID。
     *
     * @param resolver    Android ContentResolver
     * @param phoneNumber 电话号码
     * @param callDate    通话日期（时间戳，毫秒）
     * @return 对应的笔记 ID，若未找到则返回 0
     */
    public static long getNoteIdByPhoneNumberAndCallDate(ContentResolver resolver, String phoneNumber, long callDate) {
        // 查询通话日期和电话号码均匹配的数据记录，使用 PHONE_NUMBERS_EQUAL 函数做号码匹配
        Cursor cursor = resolver.query(Notes.CONTENT_DATA_URI,
                new String [] { CallNote.NOTE_ID },
                CallNote.CALL_DATE + "=? AND " + CallNote.MIME_TYPE + "=? AND PHONE_NUMBERS_EQUAL("
                + CallNote.PHONE_NUMBER + ",?)",
                new String [] { String.valueOf(callDate), CallNote.CONTENT_ITEM_TYPE, phoneNumber },
                null);

        if (cursor != null) {
            if (cursor.moveToFirst()) {
                try {
                    // 返回找到的笔记 ID
                    return cursor.getLong(0);
                } catch (IndexOutOfBoundsException e) {
                    Log.e(TAG, "Get call note id fails " + e.toString());
                }
            }
            cursor.close();
        }
        return 0;
    }

    /**
     * 根据笔记 ID 获取该笔记的摘要（Snippet）内容。
     *
     * @param resolver Android ContentResolver
     * @param noteId   笔记 ID
     * @return 笔记摘要字符串
     * @throws IllegalArgumentException 若指定 ID 的笔记不存在，则抛出异常
     */
    public static String getSnippetById(ContentResolver resolver, long noteId) {
        // 查询指定 ID 笔记的 SNIPPET 字段
        Cursor cursor = resolver.query(Notes.CONTENT_NOTE_URI,
                new String [] { NoteColumns.SNIPPET },
                NoteColumns.ID + "=?",
                new String [] { String.valueOf(noteId)},
                null);

        if (cursor != null) {
            String snippet = "";
            if (cursor.moveToFirst()) {
                // 读取摘要内容
                snippet = cursor.getString(0);
            }
            cursor.close();
            return snippet;
        }
        // 若未找到对应笔记，抛出非法参数异常
        throw new IllegalArgumentException("Note is not found with id: " + noteId);
    }

    /**
     * 格式化笔记摘要：去除首尾空白，并截取第一行内容（遇到换行符则截断）。
     *
     * @param snippet 原始摘要字符串
     * @return 格式化后的摘要字符串
     */
    public static String getFormattedSnippet(String snippet) {
        if (snippet != null) {
            // 去除首尾空白字符
            snippet = snippet.trim();
            // 查找第一个换行符的位置
            int index = snippet.indexOf('\n');
            if (index != -1) {
                // 只保留第一行内容
                snippet = snippet.substring(0, index);
            }
        }
        return snippet;
    }
}
