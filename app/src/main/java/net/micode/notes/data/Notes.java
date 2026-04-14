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

package net.micode.notes.data;

import android.net.Uri;

/**
 * Notes 契约类：定义了应用数据层的核心标准。
 * 包含：数据库列名、内容提供者(ContentProvider)的URI、便签分类常量等。
 */
public class Notes {
    /** 数据库授权标识，必须与 AndroidManifest.xml 中的定义一致 */
    public static final String AUTHORITY = "micode_notes";
    public static final String TAG = "Notes";

    /** * 数据条目的类型定义 [cite: 25, 48, 53]
     * TYPE_NOTE: 普通文本便签
     * TYPE_FOLDER: 用户手动创建的文件夹
     * TYPE_SYSTEM: 系统预设的特殊文件夹（如回收站）
     */
    public static final int TYPE_NOTE     = 0;
    public static final int TYPE_FOLDER   = 1;
    public static final int TYPE_SYSTEM   = 2;

    /**
     * 系统级文件夹的唯一标识符 ID [cite: 63]
     * ID_ROOT_FOLDER: 顶级根目录文件夹 (默认值 0)
     * ID_TEMPARAY_FOLDER: 临时/未分类文件夹 (ID 为 -1)
     * ID_CALL_RECORD_FOLDER: 通话录音自动生成的便签文件夹 (ID 为 -2)
     * ID_TRASH_FOLER: 回收站文件夹 (ID 为 -3)
     */
    public static final int ID_ROOT_FOLDER = 0;
    public static final int ID_TEMPARAY_FOLDER = -1;
    public static final int ID_CALL_RECORD_FOLDER = -2;
    public static final int ID_TRASH_FOLER = -3;

    /** Intent 传递参数时使用的 Key，用于在 Activity 跳转时定位具体数据 [cite: 48, 67] */
    public static final String INTENT_EXTRA_ALERT_DATE = "net.micode.notes.alert_date";
    public static final String INTENT_EXTRA_BACKGROUND_ID = "net.micode.notes.background_color_id";
    public static final String INTENT_EXTRA_WIDGET_ID = "net.micode.notes.widget_id";
    public static final String INTENT_EXTRA_WIDGET_TYPE = "net.micode.notes.widget_type";
    public static final String INTENT_EXTRA_FOLDER_ID = "net.micode.notes.folder_id";
    public static final String INTENT_EXTRA_CALL_DATE = "net.micode.notes.call_date";

    /** 桌面小部件 (Widget) 的尺寸枚举 */
    public static final int TYPE_WIDGET_INVALIDE      = -1;
    public static final int TYPE_WIDGET_2X            = 0;
    public static final int TYPE_WIDGET_4X            = 1;

    /** 数据类型的 MIME 类型映射 */
    public static class DataConstants {
        public static final String NOTE = TextNote.CONTENT_ITEM_TYPE;
        public static final String CALL_NOTE = CallNote.CONTENT_ITEM_TYPE;
    }

    /** * 查询所有便签和文件夹的根 URI 
     * 对应数据库中的 'note' 表 [cite: 63]
     */
    public static final Uri CONTENT_NOTE_URI = Uri.parse("content://" + AUTHORITY + "/note");

    /** * 查询具体数据内容（如正文、电话号码等）的根 URI 
     * 对应数据库中的 'data' 表
     */
    public static final Uri CONTENT_DATA_URI = Uri.parse("content://" + AUTHORITY + "/data");

    /**
     * NoteColumns 接口定义了便签表的主结构
     */
    public interface NoteColumns {
        /** 唯一主键 ID */
        public static final String ID = "_id";

        /** 父级文件夹 ID (用于实现文件夹嵌套逻辑) [cite: 63] */
        public static final String PARENT_ID = "parent_id";

        /** 创建时间戳 */
        public static final String CREATED_DATE = "created_date";

        /** 最后一次修改时间戳 */
        public static final String MODIFIED_DATE = "modified_date";

        /** 闹钟提醒时间戳 */
        public static final String ALERTED_DATE = "alert_date";

        /** 便签内容的简要预览或文件夹名称 */
        public static final String SNIPPET = "snippet";

        /** 关联的桌面小部件 ID */
        public static final String WIDGET_ID = "widget_id";

        /** 桌面小部件的类型（2x2 或 4x4） */
        public static final String WIDGET_TYPE = "widget_type";

        /** 背景颜色索引 ID */
        public static final String BG_COLOR_ID = "bg_color_id";

        /** 标记是否包含附件（如多媒体信息） */
        public static final String HAS_ATTACHMENT = "has_attachment";

        /** 该文件夹内包含的便签总数 */
        public static final String NOTES_COUNT = "notes_count";

        /** 区分条目类型：便签(0) 或 文件夹(1) */
        public static final String TYPE = "type";

        /** 云端同步记录的 ID */
        public static final String SYNC_ID = "sync_id";

        /** 本地数据是否发生变更的标记 (用于同步判断) */
        public static final String LOCAL_MODIFIED = "local_modified";

        /** 移入临时/垃圾文件夹之前的原始父目录 ID */
        public static final String ORIGIN_PARENT_ID = "origin_parent_id";

        /** Google Task 任务 ID */
        public static final String GTASK_ID = "gtask_id";

        /** 数据库条目的版本号 */
        public static final String VERSION = "version";
    }

    /**
     * DataColumns 接口定义了数据内容的详细字段
     * 这里的 DATA1~DATA5 是通用字段，其含义取决于 MIME_TYPE
     */
    public interface DataColumns {
        /** 唯一 ID */
        public static final String ID = "_id";

        /** 数据的 MIME 类型，用于区分是文字便签还是通话便签 */
        public static final String MIME_TYPE = "mime_type";

        /** 指向 NoteColumns 表中对应便签的 ID */
        public static final String NOTE_ID = "note_id";

        /** 创建时间 */
        public static final String CREATED_DATE = "created_date";

        /** 修改时间 */
        public static final String MODIFIED_DATE = "modified_date";

        /** 核心文本内容 */
        public static final String CONTENT = "content";

        /** 通用整数列 1: 在 TextNote 中代表清单模式 [cite: 58] */
        public static final String DATA1 = "data1";

        /** 通用整数列 2 */
        public static final String DATA2 = "data2";

        /** 通用文本列 3: 在 CallNote 中代表电话号码 [cite: 58] */
        public static final String DATA3 = "data3";

        /** 通用文本列 4 */
        public static final String DATA4 = "data4";

        /** 通用文本列 5 */
        public static final String DATA5 = "data5";
    }

    /**
     * 文字便签特有的数据常量
     */
    public static final class TextNote implements DataColumns {
        /** DATA1 的别名：标记是否处于清单(Checklist)模式 */
        public static final String MODE = DATA1;

        public static final int MODE_CHECK_LIST = 1;

        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/text_note";

        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/text_note";

        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/text_note");
    }

    /**
     * 通话记录便签特有的数据常量
     */
    public static final class CallNote implements DataColumns {
        /** DATA1 的别名：通话时间戳 [cite: 58] */
        public static final String CALL_DATE = DATA1;

        /** DATA3 的别名：电话号码 [cite: 58] */
        public static final String PHONE_NUMBER = DATA3;

        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/call_note";

        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/call_note";

        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/call_note");
    }
}
