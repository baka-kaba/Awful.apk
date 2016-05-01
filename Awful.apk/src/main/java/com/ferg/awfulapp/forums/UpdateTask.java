package com.ferg.awfulapp.forums;

import android.content.Context;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;
import android.util.Log;

import com.ferg.awfulapp.network.NetworkUtils;
import com.ferg.awfulapp.task.AwfulRequest;
import com.ferg.awfulapp.util.AwfulError;

import org.jsoup.nodes.Document;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.ferg.awfulapp.constants.Constants.DEBUG;

/**
 * Created by baka kaba on 18/04/2016.
 *
 * <p>Base class for async update tasks.</p>
 *
 * <p>Subclasses need to provide an initial {@link ForumParseTask} to run. This should add
 * further tasks as necessary using {@link #startTask(ForumParseTask)}. Once all started tasks
 * have ended (or the task times out), the supplied {@link ResultListener} is called with
 * the result status and the collected forums (if successful)</p> *
 */
abstract class UpdateTask {

    protected String TAG = "UpdateTask";

    // give up if the task hasn't completed after this length of time:
    private static final int TIMEOUT = 5;
    private static final TimeUnit TIMEOUT_UNITS = TimeUnit.MINUTES;

    private final Executor taskExecutor = Executors.newSingleThreadExecutor();
    private final CountDownLatch finishedSignal = new CountDownLatch(1);
    private final AtomicInteger openTasks = new AtomicInteger();
    private volatile boolean executed = false;
    private volatile boolean failed = false;

    protected final Context context;
    protected ForumParseTask initialTask;


    interface ResultListener {
        void onRefreshCompleted(boolean success, @Nullable ForumStructure forumStructure);
    }

    UpdateTask(@NonNull Context context) {
        this.context = context;
    }

    /**
     * Start the refresh task.
     * The task and its callback will be executed on a worker thread.
     * @param callback  A listener to deliver the result to
     */
    public void execute(@NonNull final ResultListener callback) {
        if (executed) {
            throw new IllegalStateException("Task already executed - you need to create a new one!");
        }
        executed = true;

        taskExecutor.execute(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "Running forum update task");
                startTask(initialTask);
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
                // only build the structure if we succeeded (otherwise it'll be incomplete)
                ForumStructure forumStructure = null;
                if (success) {
                    forumStructure = buildForumStructure();
                }
                callback.onRefreshCompleted(success, forumStructure);

                // print some debug infos about what was produced
                if (DEBUG && success && forumStructure != null) {
                    List<Forum> allForums = forumStructure.getAsList().formatAs(ForumStructure.FLAT).build();
                    Log.w(TAG, String.format("Forums parsed! %d sections found:\n\n", allForums.size()));
                    for (String line : printForums(forumStructure).split("\\n")) {
                        Log.w(TAG, line);
                    }
                }
            }
        });
    }


    /**
     * Called on success - this is where the task should create the final forum structure.
     * @return  The complete parsed forums hierarchy
     */
    @NonNull
    protected abstract ForumStructure buildForumStructure();


    /**
     * Mark the task as failed.
     * Call this to end the task and return a failed result.
     */
    protected void fail() {
        failed = true;
    }


    /**
     * Add a new parse task to the queue, incrementing the number of pending tasks.
     * This call will be ignored if the main task has already been flagged as failed,
     * so it can wind down without generating new (pointless) work.
     * @param requestTask   The task to start
     */
    protected void startTask(@NonNull ForumParseTask requestTask) {
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


    ///////////////////////////////////////////////////////////////////////////
    // Requests
    ///////////////////////////////////////////////////////////////////////////


    /**
     * Abstract superclass ensuring {@link #finishTask(boolean)} is always called appropriately
     */
    @WorkerThread
    protected abstract class ForumParseTask extends AwfulRequest<Void> {

        /** The url of the page to retrieve, which is returned in the handle* methods */
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


    ///////////////////////////////////////////////////////////////////////////
    // Output stuff for logging
    ///////////////////////////////////////////////////////////////////////////


    private String printForums(ForumStructure forums) {
        StringBuilder sb = new StringBuilder();
        List<Forum> forumList = forums.getAsList().formatAs(ForumStructure.FULL_TREE).build();
        for (Forum section : forumList) {
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

}
