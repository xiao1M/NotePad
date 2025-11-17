/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.notepad;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import java.util.Arrays;

/**
 * This Activity handles "editing" a note, where editing is responding to
 * {@link Intent#ACTION_VIEW} (request to view data), edit a note
 * {@link Intent#ACTION_EDIT}, create a note {@link Intent#ACTION_INSERT}, or
 * create a new note from the current contents of the clipboard {@link Intent#ACTION_PASTE}.
 *
 * NOTE: Notice that the provider operations in this Activity are taking place on the UI thread.
 * This is not a good practice. It is only done here to make the code more readable. A real
 * application should use the {@link android.content.AsyncQueryHandler}
 * or {@link android.os.AsyncTask} object to perform operations asynchronously on a separate thread.
 */
public class NoteEditor extends Activity {
    // For logging and debugging purposes
    private static final String TAG = "NoteEditor";
    // 在类变量中添加
    private String mCurrentCategory = "其他";
    private EditText mTitle;

    /*
     * Creates a projection that returns the note ID and the note contents.
     */
    private static final String[] PROJECTION =
        new String[] {
            NotePad.Notes._ID,
            NotePad.Notes.COLUMN_NAME_TITLE,
            NotePad.Notes.COLUMN_NAME_NOTE
    };

    // A label for the saved state of the activity
    private static final String ORIGINAL_CONTENT = "origContent";

    // This Activity can be started by more than one action. Each action is represented
    // as a "state" constant
    private static final int STATE_EDIT = 0;
    private static final int STATE_INSERT = 1;

    // Global mutable variables
    private int mState;
    private Uri mUri;
    private Cursor mCursor;
    private EditText mText;
    private String mOriginalContent;

    /**
     * Defines a custom EditText View that draws lines between each line of text that is displayed.
     */
    public static class LinedEditText extends androidx.appcompat.widget.AppCompatEditText {
        private Rect mRect;
        private Paint mPaint;

        // This constructor is used by LayoutInflater
        public LinedEditText(Context context, AttributeSet attrs) {
            super(context, attrs);

            // Creates a Rect and a Paint object, and sets the style and color of the Paint object.
            mRect = new Rect();
            mPaint = new Paint();
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setColor(0x800000FF);
        }

        /**
         * This is called to draw the LinedEditText object
         * @param canvas The canvas on which the background is drawn.
         */
        @Override
        protected void onDraw(Canvas canvas) {

            // Gets the number of lines of text in the View.
            int count = getLineCount();

            // Gets the global Rect and Paint objects
            Rect r = mRect;
            Paint paint = mPaint;

            /*
             * Draws one line in the rectangle for every line of text in the EditText
             */
            for (int i = 0; i < count; i++) {

                // Gets the baseline coordinates for the current line of text
                int baseline = getLineBounds(i, r);

                /*
                 * Draws a line in the background from the left of the rectangle to the right,
                 * at a vertical position one dip below the baseline, using the "paint" object
                 * for details.
                 */
                canvas.drawLine(r.left, baseline + 1, r.right, baseline + 1, paint);
            }

            // Finishes up by calling the parent method
            super.onDraw(canvas);
        }
    }

    /**
     * This method is called by Android when the Activity is first started. From the incoming
     * Intent, it determines what kind of editing is desired, and then does it.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 在setContentView之前进行一些初始化
        getWindow().setBackgroundDrawable(null); // 减少过度绘制

        final Intent intent = getIntent();
        final String action = intent.getAction();

        if (Intent.ACTION_EDIT.equals(action)) {
            mState = STATE_EDIT;
            mUri = intent.getData();

            // 验证编辑模式的URI
            if (isInvalidUri(mUri)) {
                Log.e(TAG, "Invalid edit URI: " + mUri);
                Toast.makeText(this, "无效的笔记数据", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }

        } else if (Intent.ACTION_INSERT.equals(action) || Intent.ACTION_PASTE.equals(action)) {
            mState = STATE_INSERT;

            // 对于插入操作，先不创建URI，等用户输入内容后再创建
            // 移除立即插入空记录的代码，改为在用户输入后保存

        } else {
            Log.e(TAG, "Unknown action: " + action);
            finish();
            return;
        }

        setContentView(R.layout.note_editor);
        mTitle = (EditText) findViewById(R.id.title);
        mText = (EditText) findViewById(R.id.note);

        if (savedInstanceState != null) {
            mOriginalContent = savedInstanceState.getString(ORIGINAL_CONTENT);
        }

        // 只有编辑模式才立即查询数据
        if (mState == STATE_EDIT && mUri != null) {
            try {
                mCursor = managedQuery(mUri, PROJECTION, null, null, null);
                if (mCursor == null) {
                    Log.e(TAG, "Failed to query note data for URI: " + mUri);
                    Toast.makeText(this, "无法加载笔记数据", Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error querying note data: " + e.getMessage(), e);
                Toast.makeText(this, "加载笔记数据出错", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
        }
        setContentView(R.layout.note_editor);

        // 延迟加载视图，避免阻塞UI线程
        new android.os.Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                initializeViews();
            }
        }, 50);
    }
    private void initializeViews() {
        mTitle = (EditText) findViewById(R.id.title);
        mText = (EditText) findViewById(R.id.note);

        // 延迟加载数据
        new android.os.Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                loadNoteData();
            }
        }, 100);
    }
    private void loadNoteData() {
        if (mState == STATE_EDIT && mUri != null && !isInvalidUri(mUri)) {
            try {
                mCursor = managedQuery(mUri, PROJECTION, null, null, null);
                if (mCursor != null && mCursor.moveToFirst()) {
                    // 加载数据到视图
                    int colTitleIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_TITLE);
                    int colNoteIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_NOTE);
                    int colCategoryIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_CATEGORY);

                    if (colTitleIndex >= 0) {
                        String title = mCursor.getString(colTitleIndex);
                        if (title != null && mTitle != null) {
                            mTitle.setText(title);
                        }
                    }

                    if (colNoteIndex >= 0) {
                        String note = mCursor.getString(colNoteIndex);
                        if (note != null && mText != null) {
                            mText.setText(note);
                            mOriginalContent = note;
                        }
                    }

                    if (colCategoryIndex >= 0) {
                        String category = mCursor.getString(colCategoryIndex);
                        if (category != null) {
                            mCurrentCategory = category;
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading note data: " + e.getMessage());
            }
        }
    }
    /**
     * This method is called when the Activity is about to come to the foreground. This happens
     * when the Activity comes to the top of the task stack, OR when it is first starting.
     *
     * Moves to the first note in the list, sets an appropriate title for the action chosen by
     * the user, puts the note contents into the TextView, and saves the original text as a
     * backup.
     */
    @Override
    protected void onResume() {
        super.onResume();

        if (mCursor != null) {
            try {
                mCursor.requery();
                mCursor.moveToFirst();

                int colCategoryIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_CATEGORY);
                if (colCategoryIndex >= 0) {
                    String category = mCursor.getString(colCategoryIndex);
                    if (category != null) {
                        mCurrentCategory = category;
                    }
                }

                // Modifies the window title for the Activity according to the current Activity state.
                if (mState == STATE_EDIT) {
                    int colTitleIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_TITLE);
                    String title = mCursor.getString(colTitleIndex);
                    Resources res = getResources();
                    String text = String.format(res.getString(R.string.title_edit), title);
                    setTitle(text);
                } else if (mState == STATE_INSERT) {
                    setTitle(getText(R.string.title_create));
                }

                // 加载标题和内容到编辑框
                int colTitleIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_TITLE);
                int colNoteIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_NOTE);

                if (colTitleIndex >= 0 && colNoteIndex >= 0) {
                    String title = mCursor.getString(colTitleIndex);
                    String note = mCursor.getString(colNoteIndex);

                    // 设置标题和内容
                    if (mTitle != null && title != null) {
                        mTitle.setTextKeepState(title);
                    }
                    if (mText != null && note != null) {
                        mText.setTextKeepState(note);
                    }

                    // Stores the original note text, to allow the user to revert changes.
                    if (mOriginalContent == null) {
                        mOriginalContent = note;
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Error in onResume: " + e.getMessage());
                setTitle(getText(R.string.error_title));
                if (mText != null) {
                    mText.setText(getText(R.string.error_message));
                }
            }
        } else {
            Log.w(TAG, "Cursor is null in onResume");
            setTitle(getText(R.string.error_title));
            if (mText != null) {
                mText.setText(getText(R.string.error_message));
            }
        }
    }

    /**
     * This method is called when an Activity loses focus during its normal operation, and is then
     * later on killed. The Activity has a chance to save its state so that the system can restore
     * it.
     *
     * Notice that this method isn't a normal part of the Activity lifecycle. It won't be called
     * if the user simply navigates away from the Activity.
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        // Save away the original text, so we still have it if the activity
        // needs to be killed while paused.
        outState.putString(ORIGINAL_CONTENT, mOriginalContent);
    }

    /**
     * This method is called when the Activity loses focus.
     *
     * For Activity objects that edit information, onPause() may be the one place where changes are
     * saved. The Android application model is predicated on the idea that "save" and "exit" aren't
     * required actions. When users navigate away from an Activity, they shouldn't have to go back
     * to it to complete their work. The act of going away should save everything and leave the
     * Activity in a state where Android can destroy it if necessary.
     *
     * If the user hasn't done anything, then this deletes or clears out the note, otherwise it
     * writes the user's work to the provider.
     */
    @Override
    protected void onPause() {
        super.onPause();

        if (mCursor != null || mState == STATE_INSERT) {
            String text = mText.getText().toString();
            String title = mTitle.getText().toString();
            int textLength = text.length();
            int titleLength = title.length();

            if (isFinishing() && (textLength == 0 && titleLength == 0)) {
                setResult(RESULT_CANCELED);
                // 对于新笔记，如果内容为空，不需要删除
            } else if (mState == STATE_EDIT) {
                updateNote(text, null);
            } else if (mState == STATE_INSERT) {
                if (textLength > 0 || titleLength > 0) {
                    saveNoteAndSetCategory(); // 使用新的保存方法
                    mState = STATE_EDIT;
                }
            }
        }
    }
    // 新增方法：保存新笔记
    private void saveNewNote(String text) {
        ContentValues values = new ContentValues();
        Long now = Long.valueOf(System.currentTimeMillis());

        // 使用用户输入的标题
        String userTitle = mTitle.getText().toString().trim();
        if (userTitle.isEmpty()) {
            userTitle = generateTitle(text);
        }

        values.put(NotePad.Notes.COLUMN_NAME_TITLE, userTitle);
        values.put(NotePad.Notes.COLUMN_NAME_NOTE, text);
        values.put(NotePad.Notes.COLUMN_NAME_CREATE_DATE, now);
        values.put(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, now);
        values.put(NotePad.Notes.COLUMN_NAME_CATEGORY, mCurrentCategory);

        mUri = getContentResolver().insert(getIntent().getData(), values);
        if (mUri != null) {
            mState = STATE_EDIT;
            setResult(RESULT_OK, (new Intent()).setAction(mUri.toString()));
        }
    }

    // 生成标题的辅助方法
    private String generateTitle(String text) {
        if (text == null || text.isEmpty()) {
            return "新笔记";
        }

        int length = text.length();
        String title = text.substring(0, Math.min(30, length));

        if (length > 30) {
            int lastSpace = title.lastIndexOf(' ');
            if (lastSpace > 0) {
                title = title.substring(0, lastSpace);
            }
        }
        return title;
    }

    /**
     * This method is called when the user clicks the device's Menu button the first time for
     * this Activity. Android passes in a Menu object that is populated with items.
     *
     * Builds the menus for editing and inserting, and adds in alternative actions that
     * registered themselves to handle the MIME types for this application.
     *
     * @param menu A Menu object to which items should be added.
     * @return True to display the menu.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate menu from XML resource
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.editor_options_menu, menu);


        // Only add extra menu items for a saved note 
        if (mState == STATE_EDIT) {
            // Append to the
            // menu items for any other activities that can do stuff with it
            // as well.  This does a query on the system for any activities that
            // implement the ALTERNATIVE_ACTION for our data, adding a menu item
            // for each one that is found.
            Intent intent = new Intent(null, mUri);
            intent.addCategory(Intent.CATEGORY_ALTERNATIVE);
            menu.addIntentOptions(Menu.CATEGORY_ALTERNATIVE, 0, 0,
                    new ComponentName(this, NoteEditor.class), null, intent, 0, null);
        }

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // 首先检查 cursor 是否为 null
        if (mCursor == null) {
            Log.w(TAG, "Cursor is null in onPrepareOptionsMenu");
            // 如果 cursor 为 null，隐藏 revert 菜单项
            MenuItem revertItem = menu.findItem(R.id.menu_revert);
            if (revertItem != null) {
                revertItem.setVisible(false);
            }
            return super.onPrepareOptionsMenu(menu);
        }

        try {
            // Check if note has changed and enable/disable the revert option
            int colNoteIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_NOTE);
            if (colNoteIndex == -1) {
                Log.w(TAG, "Note column not found in cursor");
                menu.findItem(R.id.menu_revert).setVisible(false);
                return super.onPrepareOptionsMenu(menu);
            }

            String savedNote = mCursor.getString(colNoteIndex);
            String currentNote = mText.getText().toString();

            if (savedNote == null) {
                savedNote = "";
            }

            if (savedNote.equals(currentNote)) {
                menu.findItem(R.id.menu_revert).setVisible(false);
            } else {
                menu.findItem(R.id.menu_revert).setVisible(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onPrepareOptionsMenu: " + e.getMessage());
            // 出错时隐藏 revert 菜单项
            MenuItem revertItem = menu.findItem(R.id.menu_revert);
            if (revertItem != null) {
                revertItem.setVisible(false);
            }
        }
        // 控制分类菜单项的可见性
        MenuItem categoryItem = menu.findItem(R.id.menu_category);
        if (categoryItem != null) {
            // 只有在有效状态下才显示分类菜单
            categoryItem.setVisible(mUri != null && !isInvalidUri(mUri));
        }

        return super.onPrepareOptionsMenu(menu);
    }

    /**
     * This method is called when a menu item is selected. Android passes in the selected item.
     * The switch statement in this method calls the appropriate method to perform the action the
     * user chose.
     *
     * @param item The selected MenuItem
     * @return True to indicate that the item was processed, and no further work is necessary. False
     * to proceed to further processing as indicated in the MenuItem object.
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.menu_category) {
            // 延迟处理菜单点击，避免立即响应导致的UI阻塞
            new android.os.Handler().post(new Runnable() {
                @Override
                public void run() {
                    showCategoryDialog();
                }
            });
            return true;
        } else if (id == R.id.menu_save) {
            String text = mText.getText().toString();
            updateNote(text, null);
            finish();
            return true;
        } else if (id == R.id.menu_delete) {
            deleteNote();
            finish();
            return true;
        } else if (id == R.id.menu_revert) {
            cancelNote();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
//BEGIN_INCLUDE(paste)
    /**
     * A helper method that replaces the note's data with the contents of the clipboard.
     */
    private final void performPaste() {

        // Gets a handle to the Clipboard Manager
        ClipboardManager clipboard = (ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);

        // Gets a content resolver instance
        ContentResolver cr = getContentResolver();

        // Gets the clipboard data from the clipboard
        ClipData clip = clipboard.getPrimaryClip();
        if (clip != null) {

            String text=null;
            String title=null;

            // Gets the first item from the clipboard data
            ClipData.Item item = clip.getItemAt(0);

            // Tries to get the item's contents as a URI pointing to a note
            Uri uri = item.getUri();

            // Tests to see that the item actually is an URI, and that the URI
            // is a content URI pointing to a provider whose MIME type is the same
            // as the MIME type supported by the Note pad provider.
            if (uri != null && NotePad.Notes.CONTENT_ITEM_TYPE.equals(cr.getType(uri))) {

                // The clipboard holds a reference to data with a note MIME type. This copies it.
                Cursor orig = cr.query(
                        uri,            // URI for the content provider
                        PROJECTION,     // Get the columns referred to in the projection
                        null,           // No selection variables
                        null,           // No selection variables, so no criteria are needed
                        null            // Use the default sort order
                );

                // If the Cursor is not null, and it contains at least one record
                // (moveToFirst() returns true), then this gets the note data from it.
                if (orig != null) {
                    if (orig.moveToFirst()) {
                        int colNoteIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_NOTE);
                        int colTitleIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_TITLE);
                        text = orig.getString(colNoteIndex);
                        title = orig.getString(colTitleIndex);
                    }

                    // Closes the cursor.
                    orig.close();
                }
            }

            // If the contents of the clipboard wasn't a reference to a note, then
            // this converts whatever it is to text.
            if (text == null) {
                text = item.coerceToText(this).toString();
            }

            // Updates the current note with the retrieved title and text.
            updateNote(text, title);
        }
    }
//END_INCLUDE(paste)

    /**
     * Replaces the current note contents with the text and title provided as arguments.
     * @param text The new note contents to use.
     * @param title The new note title to use
     */
    private final void updateNote(String text, String title) {
        if (mUri == null) {
            // 如果是新笔记还没有URI，先保存
            saveNewNote(text);
            return;
        }

        ContentValues values = new ContentValues();
        values.put(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, System.currentTimeMillis());
        values.put(NotePad.Notes.COLUMN_NAME_CATEGORY, mCurrentCategory);

        // 使用用户输入的标题，而不是自动生成
        String userTitle = mTitle.getText().toString().trim();
        if (!userTitle.isEmpty()) {
            values.put(NotePad.Notes.COLUMN_NAME_TITLE, userTitle);
        } else if (mState == STATE_INSERT) {
            // 如果用户没有输入标题，为新笔记生成一个
            if (title == null) {
                title = generateTitle(text);
            }
            values.put(NotePad.Notes.COLUMN_NAME_TITLE, title);
        } else if (title != null) {
            values.put(NotePad.Notes.COLUMN_NAME_TITLE, title);
        }

        // This puts the desired notes text into the map.
        values.put(NotePad.Notes.COLUMN_NAME_NOTE, text);

        getContentResolver().update(
                mUri,
                values,
                null,
                null
        );
    }

    /**
     * This helper method cancels the work done on a note.  It deletes the note if it was
     * newly created, or reverts to the original text of the note i
     */
    private final void cancelNote() {
        if (mCursor != null) {
            if (mState == STATE_EDIT) {
                // 恢复原始标题和内容
                int colTitleIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_TITLE);
                int colNoteIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_NOTE);

                String originalTitle = mCursor.getString(colTitleIndex);
                String originalNote = mCursor.getString(colNoteIndex);

                mTitle.setText(originalTitle);
                mText.setText(originalNote);

            } else if (mState == STATE_INSERT) {
                // 清空输入
                mTitle.setText("");
                mText.setText("");
            }
        }
    }

    /**
     * Take care of deleting a note.  Simply deletes the entry.
     */
    private final void deleteNote() {
        if (mCursor != null) {
            mCursor.close();
            mCursor = null;
            getContentResolver().delete(mUri, null, null);
            mText.setText("");
        }
    }
    // 显示分类选择对话框
    // 在 NoteEditor.java 中修复 showCategoryDialog 方法
    private void showCategoryDialog() {
        final String[] categories = {"个人", "工作", "学习", "生活", "其他"};

        // 使用最简单的对话框创建方式
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("设置分类");

            builder.setItems(categories, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    mCurrentCategory = categories[which];
                    updateNoteCategory();
                }
            });

            builder.setNegativeButton("取消", null);

            // 直接显示，不添加复杂逻辑
            builder.show();

        } catch (Exception e) {
            Log.e(TAG, "Error showing category dialog: " + e.getMessage());
            // 如果对话框失败，使用Toast提示
            Toast.makeText(this, "对话框显示失败，请在笔记列表中长按笔记设置分类", Toast.LENGTH_LONG).show();
        }
    }

    // 改进的URI验证方法
    private boolean isInvalidUri(Uri uri) {
        if (uri == null) return true;

        String uriString = uri.toString();
        // 检查常见的无效URI模式
        return uriString.contains("-9223372036854775808") ||
                uriString.contains("content://com.google.provider.NotePad") ||
                !uriString.startsWith("content://" + NotePad.AUTHORITY);
    }

    // 检查并修复URI
    private boolean ensureValidUri() {
        if (mUri == null || isInvalidUri(mUri)) {
            if (mState == STATE_INSERT) {
                // 对于新笔记，需要先保存
                String text = mText.getText().toString();
                String title = mTitle.getText().toString().trim();

                if (text.isEmpty() && title.isEmpty()) {
                    return false;
                }

                // 保存笔记
                ContentValues values = new ContentValues();
                Long now = System.currentTimeMillis();

                if (title.isEmpty()) {
                    title = generateTitle(text);
                }

                values.put(NotePad.Notes.COLUMN_NAME_TITLE, title);
                values.put(NotePad.Notes.COLUMN_NAME_NOTE, text);
                values.put(NotePad.Notes.COLUMN_NAME_CREATE_DATE, now);
                values.put(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, now);
                values.put(NotePad.Notes.COLUMN_NAME_CATEGORY, mCurrentCategory);

                try {
                    Uri newUri = getContentResolver().insert(getIntent().getData(), values);
                    if (newUri != null) {
                        mUri = newUri;
                        mState = STATE_EDIT;
                        return true;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to save note: " + e.getMessage());
                }
            }
            return false;
        }
        return true;
    }
    // 更新笔记分类 - 简化版本
    private void updateNoteCategory() {
        if (mUri == null) {
            Toast.makeText(this, "请先保存笔记", Toast.LENGTH_SHORT).show();
            return;
        }

        ContentValues values = new ContentValues();
        values.put(NotePad.Notes.COLUMN_NAME_CATEGORY, mCurrentCategory);
        values.put(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, System.currentTimeMillis());

        try {
            getContentResolver().update(mUri, values, null, null);
            Toast.makeText(this, "分类已设置为: " + mCurrentCategory, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Error updating category: " + e.getMessage());
            Toast.makeText(this, "分类设置失败", Toast.LENGTH_SHORT).show();
        }
    }
    // 保存笔记并设置分类（用于新笔记）
    private void saveNoteAndSetCategory() {
        String text = mText.getText().toString();
        String title = mTitle.getText().toString().trim();

        if (title.isEmpty()) {
            title = generateTitle(text);
        }

        ContentValues values = new ContentValues();
        Long now = Long.valueOf(System.currentTimeMillis());

        values.put(NotePad.Notes.COLUMN_NAME_TITLE, title);
        values.put(NotePad.Notes.COLUMN_NAME_NOTE, text);
        values.put(NotePad.Notes.COLUMN_NAME_CREATE_DATE, now);
        values.put(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, now);
        values.put(NotePad.Notes.COLUMN_NAME_CATEGORY, mCurrentCategory);

        try {
            Uri newUri = getContentResolver().insert(getIntent().getData(), values);
            if (newUri != null) {
                mUri = newUri;
                mState = STATE_EDIT;
                setResult(RESULT_OK, (new Intent()).setAction(mUri.toString()));

                // 重新查询数据
                if (mCursor != null) {
                    mCursor.close();
                }
                mCursor = managedQuery(mUri, PROJECTION, null, null, null);

                Toast.makeText(this, "笔记已保存，分类设置为: " + mCurrentCategory, Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Note saved with category, new URI: " + mUri);
            } else {
                Toast.makeText(this, "保存失败，无法设置分类", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving note with category: " + e.getMessage(), e);
            Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }


}
