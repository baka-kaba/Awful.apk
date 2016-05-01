package com.ferg.awfulapp.forums;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.android.volley.VolleyError;
import com.ferg.awfulapp.constants.Constants;
import com.ferg.awfulapp.network.NetworkUtils;
import com.ferg.awfulapp.provider.AwfulProvider;
import com.ferg.awfulapp.task.AwfulRequest;
import com.ferg.awfulapp.task.IndexIconRequest;
import com.ferg.awfulapp.thread.AwfulForum;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.ferg.awfulapp.forums.Forum.BOOKMARKS;
import static com.ferg.awfulapp.forums.Forum.SECTION;
import static com.ferg.awfulapp.forums.ForumStructure.FLAT;

/**
 * Created by baka kaba on 04/04/2016.
 *
 * Provides access to current forum state, forcing updates etc.
 *
 */
public class ForumRepository implements UpdateTask.ResultListener {

    private static final String TAG = "ForumRepo";
    /** The ID of the 'root' of the forums hierarchy - anything with this parent ID will be top-level */
    static final int TOP_LEVEL_PARENT_ID = 0;

    private static ForumRepository mThis = null;

    private final Set<ForumsUpdateListener> listeners = new HashSet<>();
    private final Context context;

    // global flag to prevent simultaneous refresh tasks
    private static volatile boolean refreshActive = false;


    /**
     * Get an instance of ForumsRepository.
     * The first call <b>must</b> provide a Context to initialise the singleton!
     * Subsequent calls can pass null.
     * @param context   A context used to initialise the repo
     * @return          A reference to the application-wide ForumRepository
     */
    public static ForumRepository getInstance(@Nullable Context context) {
        if (mThis == null && context == null) {
            throw new IllegalStateException("ForumRepository has not been initialised - requires a context, but got null");
        }
        if (mThis == null) {
            mThis = new ForumRepository(context);
        }
        return mThis;
    }

    private ForumRepository(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }


    public void registerListener(@NonNull ForumsUpdateListener listener) {
        listeners.add(listener);
        // let the new listener know if there's an update in progress
        if (isUpdating()) {
            listener.onForumsUpdateStarted();
        }
    }


    public void unregisterListener(@NonNull ForumsUpdateListener listener) {
        listeners.remove(listener);
    }


    ///////////////////////////////////////////////////////////////////////////
    // Update task lifecycle
    ///////////////////////////////////////////////////////////////////////////

    // TODO: add task cancellation (forum update and icons) - this is from the old ForumsIndexFragment code:
//    NetworkUtils.cancelRequests(IndexIconRequest.REQUEST_TAG);


    /**
     * Update the current forum list in the background.
     * Does nothing if an update is already in progress
     */
    public synchronized void updateForums() {
        // synchronize to make sure only one update can start
        if (!refreshActive) {
            refreshActive = true;
            UpdateTask refreshTask = new CrawlerTask(context);
//            UpdateTask refreshTask = new DropdownParserTask(context);
            refreshTask.execute(this);
            for (ForumsUpdateListener listener : listeners) {
                listener.onForumsUpdateStarted();
            }

        } else {
            Log.w(TAG, "Tried to refresh forums while the task was already running!");
        }
    }


    @Override
    public void onRefreshCompleted(boolean success, @Nullable ForumStructure parsedStructure) {
        if (success && parsedStructure != null) {
            storeForumData(parsedStructure);
            refreshTags();
        } else {
            onUpdateComplete();
        }
    }


    /**
     * Refresh the forum tags.
     * This needs to be done after the forum hierarchy has been rebuilt, since it updates the new records
     */
    private void refreshTags() {
        NetworkUtils.queueRequest(new IndexIconRequest(context).build(null, new AwfulRequest.AwfulResultCallback<Void>() {
            public void success(Void result) {
                onUpdateComplete();
            }

            public void failure(VolleyError error) {
                onUpdateComplete();
            }
        }));
    }


    /**
     * Called when the task is complete (whether successful or not)
     */
    private void onUpdateComplete() {
        // no need to synchronise here, it just enables #updateForums to allow a new task to start
        refreshActive = false;
        for (ForumsUpdateListener listener : listeners) {
            listener.onForumsUpdateCompleted();
        }
    }


    /**
     * Check if a forums data update is in progress.
     */
    public boolean isUpdating() {
        return refreshActive;
    }


    ///////////////////////////////////////////////////////////////////////////
    // Get forum data
    ///////////////////////////////////////////////////////////////////////////


    @NonNull
    public ForumStructure getForumStructure() {
        return ForumStructure.buildFromOrderedList(loadForumData(), TOP_LEVEL_PARENT_ID);
    }


    ///////////////////////////////////////////////////////////////////////////
    // Database operations
    ///////////////////////////////////////////////////////////////////////////


    /**
     * Get all forums stored in the DB, as a list of Forums ordered by index.
     * See {@link #storeForumData(ForumStructure)} for details on index ordering.
     * @return  The list of Forums
     */
    @NonNull
    private List<Forum> loadForumData() {
        List<Forum> forumList = new ArrayList<>();
        ContentResolver contentResolver = context.getContentResolver();
        // get all forums, ordered by index (the order they were added to the DB)
        Cursor cursor = contentResolver.query(AwfulForum.CONTENT_URI,
                AwfulProvider.ForumProjection,
                null,
                null,
                AwfulForum.INDEX);
        if (cursor == null) {
            return forumList;
        }

        //
        Forum forum;
        while (cursor.moveToNext()) {
            forum = new Forum(
                    cursor.getInt(cursor.getColumnIndex(AwfulForum.ID)),
                    cursor.getInt(cursor.getColumnIndex(AwfulForum.PARENT_ID)),
                    cursor.getString(cursor.getColumnIndex(AwfulForum.TITLE)),
                    cursor.getString(cursor.getColumnIndex(AwfulForum.SUBTEXT))
            );
            // the forum might have an image tag too
            String tagUrl = cursor.getString(cursor.getColumnIndex(AwfulForum.TAG_URL));
            if (tagUrl != null && !tagUrl.equals("")) {
                forum.setTagUrl(tagUrl);
            }

            // set the type e.g. for the index list to handle formatting
            if (forum.id == Constants.USERCP_ID) {
                forum.setType(BOOKMARKS);
            } else if (forum.parentId == TOP_LEVEL_PARENT_ID) {
                forum.setType(SECTION);
            }
            forumList.add(forum);
        }
        cursor.close();
        return forumList;
    }


    /**
     * Store a list of Forums in the DB.
     * The forums will be assigned an index in the order they're passed in. This index is used
     * to determine the order a group of forums should be displayed in, e.g. a flat list of all
     * forums, or within a list of subforums.
     * @param parsedStructure  The forum hierarchy
     */
    private void storeForumData(@NonNull ForumStructure parsedStructure) {
        ContentResolver contentResolver = context.getContentResolver();
        String updateTime = new Timestamp(System.currentTimeMillis()).toString();
        List<ContentValues> recordsToAdd = new ArrayList<>();

        // we're replacing all the forums, so wipe them
        clearForumData();

        // add any special forums not on the main hierarchy
        addForumRecord(recordsToAdd, Constants.USERCP_ID, TOP_LEVEL_PARENT_ID, "Bookmarks", "", updateTime);

        // get all the parsed forums in an ordered list, so we can store them in this order using the INDEX field
        List<Forum> flattenedForums = parsedStructure
                .getAsList()
                .includeSections(true)
                .formatAs(FLAT)
                .build();
        for (Forum forum : flattenedForums) {
            addForumRecord(recordsToAdd, forum.id, forum.parentId, forum.title, forum.subtitle, updateTime);
        }

        contentResolver.bulkInsert(AwfulForum.CONTENT_URI, recordsToAdd.toArray(new ContentValues[recordsToAdd.size()]));
    }


    /**
     * Remove all cached forum data from the DB.
     */
    public void clearForumData() {
        ContentResolver contentResolver = context.getContentResolver();
        contentResolver.delete(AwfulForum.CONTENT_URI, null, null);
    }


    /**
     *  Create a ContentValues object for a set of forum details, and add it to the supplied list.
     *  This will automatically set the INDEX field according to the object's position in the list.
     */
    private void addForumRecord(@NonNull List<ContentValues> recordList,
                                int forumId,
                                int parentId,
                                @NonNull String title,
                                @NonNull String subtitle,
                                @NonNull String updateTime) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(AwfulForum.INDEX, recordList.size());
        contentValues.put(AwfulForum.ID, forumId);
        contentValues.put(AwfulForum.PARENT_ID, parentId);
        contentValues.put(AwfulForum.TITLE, title);
        contentValues.put(AwfulForum.SUBTEXT, subtitle);
        contentValues.put(AwfulProvider.UPDATED_TIMESTAMP, updateTime);

        recordList.add(contentValues);
    }


    public interface ForumsUpdateListener {
        /** Called when an update has started */
        void onForumsUpdateStarted();

        /** Called when an update has finished - the forums data may or may not have changed */
        void onForumsUpdateCompleted();
    }

}
