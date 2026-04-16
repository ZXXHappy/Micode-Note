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

import android.content.Context;
import android.graphics.Rect;
import android.text.Layout;
import android.text.Selection;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.URLSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.view.MotionEvent;
import android.widget.EditText;

import net.micode.notes.R;

import java.util.HashMap;
import java.util.Map;

/**
 * 小米便签自定义编辑框
 * 功能：处理光标定位、回车换行、删除空行、链接点击、焦点变化等逻辑
 */
public class NoteEditText extends EditText {
    private static final String TAG = "NoteEditText";
    private int mIndex; // 当前编辑框在列表中的位置索引
    private int mSelectionStartBeforeDelete; // 删除按键按下前的光标位置

    // 链接类型常量定义
    private static final String SCHEME_TEL = "tel:" ;     // 电话链接
    private static final String SCHEME_HTTP = "http:" ;   // 网页链接
    private static final String SCHEME_EMAIL = "mailto:" ;// 邮件链接

    // 链接类型与对应显示文字的映射
    private static final Map<String, Integer> sSchemaActionResMap = new HashMap<String, Integer>();
    static {
        // 初始化链接类型对应字符串资源
        sSchemaActionResMap.put(SCHEME_TEL, R.string.note_link_tel);
        sSchemaActionResMap.put(SCHEME_HTTP, R.string.note_link_web);
        sSchemaActionResMap.put(SCHEME_EMAIL, R.string.note_link_email);
    }

    /**
     * 文本变化回调接口
     * 由NoteEditActivity实现，用于处理删除、回车、文本变化事件
     */
    public interface OnTextViewChangeListener {
        /**
         * 当按下删除键且文本为空时，删除当前编辑框
         */
        void onEditTextDelete(int index, String text);

        /**
         * 当按下回车键时，在当前编辑框后新增一行
         */
        void onEditTextEnter(int index, String text);

        /**
         * 文本内容变化时，显示/隐藏选项
         */
        void onTextChange(int index, boolean hasText);
    }

    private OnTextViewChangeListener mOnTextViewChangeListener; // 文本变化监听器

    public NoteEditText(Context context) {
        super(context, null);
        mIndex = 0; // 初始化索引为0
    }

    // 设置当前编辑框的索引
    public void setIndex(int index) {
        mIndex = index;
    }

    // 设置文本变化监听器
    public void setOnTextViewChangeListener(OnTextViewChangeListener listener) {
        mOnTextViewChangeListener = listener;
    }

    public NoteEditText(Context context, AttributeSet attrs) {
        super(context, attrs, android.R.attr.editTextStyle);
    }

    public NoteEditText(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        // TODO Auto-generated constructor stub
    }

    /**
     * 触摸事件：精准定位光标位置
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // 获取触摸坐标并转换为文本区域内的坐标
                int x = (int) event.getX();
                int y = (int) event.getY();
                x -= getTotalPaddingLeft();
                y -= getTotalPaddingTop();
                x += getScrollX();
                y += getScrollY();

                Layout layout = getLayout();
                int line = layout.getLineForVertical(y); // 获取触摸的行
                int off = layout.getOffsetForHorizontal(line, x); // 获取触摸的字符位置
                Selection.setSelection(getText(), off); // 设置光标位置
                break;
        }

        return super.onTouchEvent(event);
    }

    /**
     * 按键按下事件处理
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
                // 回车键交给上层处理，这里不拦截
                if (mOnTextViewChangeListener != null) {
                    return false;
                }
                break;
            case KeyEvent.KEYCODE_DEL:
                // 记录删除前的光标起始位置
                mSelectionStartBeforeDelete = getSelectionStart();
                break;
            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * 按键抬起事件处理（核心逻辑：删除、回车）
     */
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch(keyCode) {
            case KeyEvent.KEYCODE_DEL:
                if (mOnTextViewChangeListener != null) {
                    // 如果光标在最前面且不是第一个输入框，触发删除当前行
                    if (0 == mSelectionStartBeforeDelete && mIndex != 0) {
                        mOnTextViewChangeListener.onEditTextDelete(mIndex, getText().toString());
                        return true;
                    }
                } else {
                    Log.d(TAG, "OnTextViewChangeListener was not seted");
                }
                break;
            case KeyEvent.KEYCODE_ENTER:
                if (mOnTextViewChangeListener != null) {
                    // 回车：分割文本，新增一行
                    int selectionStart = getSelectionStart();
                    String text = getText().subSequence(selectionStart, length()).toString();
                    setText(getText().subSequence(0, selectionStart));
                    // 通知上层在下一个位置新增编辑框
                    mOnTextViewChangeListener.onEditTextEnter(mIndex + 1, text);
                } else {
                    Log.d(TAG, "OnTextViewChangeListener was not seted");
                }
                break;
            default:
                break;
        }
        return super.onKeyUp(keyCode, event);
    }

    /**
     * 焦点变化事件
     * 失去焦点且内容为空时，通知上层隐藏当前行
     */
    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        if (mOnTextViewChangeListener != null) {
            if (!focused && TextUtils.isEmpty(getText())) {
                // 无焦点且无文本：标记为空
                mOnTextViewChangeListener.onTextChange(mIndex, false);
            } else {
                // 有文本：标记为有内容
                mOnTextViewChangeListener.onTextChange(mIndex, true);
            }
        }
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
    }

    /**
     * 创建长按上下文菜单：识别链接（电话、网址、邮件）并弹出操作菜单
     */
    @Override
    protected void onCreateContextMenu(ContextMenu menu) {
        if (getText() instanceof Spanned) {
            int selStart = getSelectionStart();
            int selEnd = getSelectionEnd();

            int min = Math.min(selStart, selEnd);
            int max = Math.max(selStart, selEnd);

            // 获取选中区域的URL链接
            final URLSpan[] urls = ((Spanned) getText()).getSpans(min, max, URLSpan.class);
            if (urls.length == 1) {
                int defaultResId = 0;
                // 判断链接类型，设置对应菜单文字
                for(String schema: sSchemaActionResMap.keySet()) {
                    if(urls[0].getURL().indexOf(schema) >= 0) {
                        defaultResId = sSchemaActionResMap.get(schema);
                        break;
                    }
                }

                // 未匹配到类型，显示其他链接
                if (defaultResId == 0) {
                    defaultResId = R.string.note_link_other;
                }

                // 添加菜单，点击后执行链接跳转
                menu.add(0, 0, 0, defaultResId).setOnMenuItemClickListener(
                        new OnMenuItemClickListener() {
                            public boolean onMenuItemClick(MenuItem item) {
                                // 执行链接意图（拨号、浏览器、邮件）
                                urls[0].onClick(NoteEditText.this);
                                return true;
                            }
                        });
            }
        }
        super.onCreateContextMenu(menu);
    }
}
