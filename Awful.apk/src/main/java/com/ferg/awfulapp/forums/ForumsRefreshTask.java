package com.ferg.awfulapp.forums;

import android.content.Context;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;
import android.util.Log;

import com.ferg.awfulapp.constants.Constants;
import com.ferg.awfulapp.network.NetworkUtils;
import com.ferg.awfulapp.task.AwfulRequest;
import com.ferg.awfulapp.util.AwfulError;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.ferg.awfulapp.constants.Constants.DEBUG;

/**
 * Created by baka kaba on 01/04/2016.
 *
 * A task that parses and updates the forum structure by spidering subforum links
 */
class ForumsRefreshTask {

    private static final String TAG = "ForumsRefreshTask";
    // give up if the task hasn't completed after this length of time:
    private static final int TIMEOUT = 120;
    private static final TimeUnit TIMEOUT_UNITS = TimeUnit.SECONDS;

    private final Executor taskExecutor = Executors.newSingleThreadExecutor();
    private final CountDownLatch finishedSignal = new CountDownLatch(1);

    private final Context context;
    private final List<Forum> forumSections = Collections.synchronizedList(new ArrayList<Forum>());
    private final AtomicInteger openTasks = new AtomicInteger();
    private volatile boolean executed = false;
    private volatile boolean failed = false;


    ForumsRefreshTask(Context context) {
        this.context = context;
    }


    /**
     * Start the refresh task.
     * The task and its callback will be executed on a worker thread.
     * @param callback  A listener to deliver the result to
     */
    void execute(@NonNull final ForumsRefreshedListener callback) {
        if (executed) {
            throw new IllegalStateException("Task already executed - you need to create a new one!");
        }
        executed = true;

        taskExecutor.execute(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "Running forum update task");
                startTask(new MainForumRequest());
                boolean success;
                try {
                    boolean completed = finishedSignal.await(TIMEOUT, TIMEOUT_UNITS);
                    if (!completed) {
                        Log.w(TAG, "Task timeout!");
                    }
                    success = completed && !failed;
                } catch (InterruptedException e) {
                    success = false;
                    Log.w(TAG, String.format("Task interrupted with %d jobs in queue!", openTasks.intValue()));
                }

                Log.d(TAG, String.format("Thread unlatched - success: %s\nFailure: %s", success, failed));
                callback.onRefreshCompleted(success, (success) ? forumSections : null);

                if (DEBUG && success) {
                    Log.w(TAG, String.format("Forums parsed! %d sections found:\n\n", forumSections.size()));
                    for (String line : printForums().split("\\n")) {
                        Log.w(TAG, line);
                    }
                }
            }
        });
    }


    /**
     * Add a new parse task to the queue, incrementing the number of pending tasks.
     * This call will be ignored if the main task has already been flagged as failed,
     * so it can wind down without generating new (pointless) work.
     * @param requestTask   The task to start
     */
    private void startTask(@NonNull ForumParseTask requestTask) {
        if (!failed) {
            openTasks.incrementAndGet();
            NetworkUtils.queueRequest(requestTask.build());
        }
    }


    /**
     * Remove a finished task from the queue, and handle its result.
     * Every task initiated with {@link #startTask(ForumParseTask)}  must call this!
     * If called with success = false, the main task will be flagged as failed and terminate.
     * @param success   true if the task completed, false if it failed somehow
     */
    private void finishTask(boolean success) {
        int remaining = openTasks.decrementAndGet();
        if (!success) {
            failed = true;
            finishedSignal.countDown();
            return;
        }

        if (remaining <= 0) {
            finishedSignal.countDown();
        }
    }


    /**
     * Parse the category links on the main forum page (Main, Discussion etc).
     * This is to ensure all the 'hidden' subforums are picked up, which don't show up
     * on the main single-page listing.
     * @param doc   A JSoup document built from the main forum page
     */
    @WorkerThread
    private void parseMainSections(Document doc) {
        // look for section links on the main page - fail immediately if we can't find them!
        Elements sections = doc.getElementsByClass("category");
        if (sections.size() == 0) {
            Log.w(TAG,  "Unable to parse main forum page - 0 links found!");
            failed = true;
            return;
        }

        // parse each section to get its data, and add a 'forum' to the top level list
        for (Element section : sections) {
            Element link = section.select("a").first();
            String title = link.text();
            String url = link.attr("abs:href");

            addForumToList(forumSections, ForumRepository.TOP_LEVEL_PARENT_ID, url, title, "");
        }
    }


    /**
     * Parse a forum page, and attempt to scrape any subforum links it contains.
     * This can be used on category pages (e.g. the 'Main' link) as well as actual
     * forum pages (e.g. GBS)
     * @param forum The Forum object representing the forum being parsed
     * @param doc   A JSoup document built from the forum's url
     */
    @WorkerThread
    private void parseSubforums(Forum forum, Document doc) {

        // look for subforums
        Elements subforumElements = doc.getElementsByTag("tr").select(".subforum");
        if (DEBUG) {
            String message = "Parsed forum (%s)\nFound %d subforums";
            Log.d(TAG, String.format(message, forum.title, subforumElements.size()));
        }

        // parse details and create subforum objects, and add them to this forum's subforum list
        for (Element subforumElement : subforumElements) {
            Element link = subforumElement.select("a").first();
            String title = link.text();
            String subtitle = subforumElement.select("dd").text();
            String url = link.attr("abs:href");

            // strip leading junk on subtitles
            final String garbage = "- ";
            if (subtitle.startsWith(garbage)) {
                subtitle = subtitle.substring(garbage.length());
            }

            addForumToList(forum.subforums, forum.id, url, title, subtitle);
        }
    }


    /**
     * Parse a forum's url to retrieve its ID
     * @param url   The forum's full url
     * @return      Its ID
     * @throws InvalidParameterException if the ID could not be found
     */
    private int getForumId(@NonNull String url) throws InvalidParameterException {
        String FORUM_ID_KEY = "forumid";
        try {
            Uri uri = Uri.parse(url);
            String forumId = uri.getQueryParameter(FORUM_ID_KEY);
            return Integer.valueOf(forumId);
        } catch (NumberFormatException e) {
            String message = "Unable to find forum ID key (%s) in url (%s)\nException: %s";
            throw new InvalidParameterException(String.format(message, FORUM_ID_KEY, url, e.getMessage()));
        }
    }


    /**
     * Create and add a Forum object to a list.
     * @param forumList The list to add to
     * @param parentId  The ID of the parent forum
     * @param url       The url of this forum
     * @param title     The title of this forum
     * @param subtitle  The subtitle of this forum
     */
    private void addForumToList(@NonNull List<Forum> forumList,
                                int parentId,
                                @NonNull String url,
                                @NonNull String title,
                                @NonNull String subtitle) {
        try {
            // the subforum list needs to be synchronized since multiple async requests can add to it
            List<Forum> subforums = Collections.synchronizedList(new ArrayList<Forum>());
            Forum forum = new Forum(getForumId(url), parentId, title, subtitle, subforums);
            forumList.add(forum);

            if (!"".equals(url)) {
                startTask(new ParseSubforumsRequest(forum, url));
            }
        } catch (InvalidParameterException e) {
            Log.w(TAG, e.getMessage());
        }
    }


    /*
        Requests
     */

    /**
     * Abstract superclass ensuring {@link #finishTask(boolean)} is always called appropriately
     */
    private abstract class ForumParseTask extends AwfulRequest<Void> {

        String url;

        public ForumParseTask() {
            super(context, null);
        }


        @Override
        protected String generateUrl(Uri.Builder urlBuilder) {
            return url;
        }

        // TODO: request errors aren't being handled properly, e.g. failed page loads when you're not logged in don't call here, and the task times out

        @Override
        protected Void handleResponse(Document doc) throws AwfulError {
            onRequestSucceeded(doc);
            finishTask(true);
            return null;
        }


        @Override
        protected boolean handleError(AwfulError error, Document doc) {
            onRequestFailed(error);
            finishTask(false);
            return false;
        }


        abstract protected void onRequestSucceeded(Document doc);

        abstract protected void onRequestFailed(AwfulError error);
    }


    /**
     * A request that fetches the main forums page and parses it for sections (Main etc)
     */
    private class MainForumRequest extends ForumParseTask {

        { url = Constants.BASE_URL; }

        @Override
        protected void onRequestSucceeded(Document doc) {
            Log.i(TAG, "Parsing main page");
            parseMainSections(doc);
        }


        @Override
        protected void onRequestFailed(AwfulError error) {
            Log.w(TAG, "Failed to get index page!\n" + error.getMessage());
        }
    }


    /**
     * A request that fetches a forum page and parses it for subforum data
     */
    private class ParseSubforumsRequest extends ForumParseTask {

        @NonNull
        private final Forum forum;

        /**
         * Parse a Forum to add any subforums
         * @param forum A Forum to load and parse, and add any subforums to
         */
        private ParseSubforumsRequest(@NonNull Forum forum, @NonNull String url) {
            this.forum = forum;
            this.url = url;
        }

        @Override
        protected void onRequestSucceeded(Document doc) {
            parseSubforums(forum, doc);
        }


        @Override
        protected void onRequestFailed(AwfulError error) {
            Log.w(TAG, String.format("Failed to load forum: %s\n%s", forum.title, error.getMessage()));
        }
    }



    /*
        Output stuff for logging
     */


    private String printForums() {
        StringBuilder sb = new StringBuilder();
        for (Forum section : forumSections) {
            printForum(section, 0, sb);
        }
        return sb.toString();
    }

    private void printForum(Forum forum, int depth, StringBuilder sb) {
        appendPadded(sb, forum.title, depth).append(":\n");
        if (!"".equals(forum.subtitle)) {
            appendPadded(sb, forum.subtitle, depth).append("\n");
        }
        for (Forum subforum : forum.subforums) {
            printForum(subforum, depth+1, sb);
        }
    }

    private StringBuilder appendPadded(StringBuilder sb, String message, int pad) {
        for (int i = 0; i < pad; i++) {
            sb.append("-");
        }
        sb.append(message);
        return sb;
    }


    interface ForumsRefreshedListener {
        void onRefreshCompleted(boolean success, @Nullable List<Forum> parsedForums);
    }

}
