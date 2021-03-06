package com.ferg.awfulapp.task

import android.content.ContentValues
import android.content.Context
import com.ferg.awfulapp.constants.Constants.*
import com.ferg.awfulapp.provider.DatabaseHelper
import com.ferg.awfulapp.reply.Reply
import com.ferg.awfulapp.util.AwfulError
import org.jsoup.nodes.Document
import java.sql.Timestamp

/**
 * Request the data you get when starting a new reply on the site.
 *
 * This provides you with any initial reply contents, the form key
 * and cookie (for authentication?) as well as any selected
 * options (see [Reply.processReply]) and a current timestamp.
 */
class ReplyRequest(context: Context, private val threadId: Int)
    : AwfulRequest<ContentValues>(context, FUNCTION_POST_REPLY) {

    init {
        with(parameters) {
            add(PARAM_ACTION, "newreply")
            add(PARAM_THREAD_ID, threadId.toString())
        }
    }

    @Throws(AwfulError::class)
    override fun handleResponse(doc: Document): ContentValues {
        return Reply.processReply(doc, threadId).apply {
            put(DatabaseHelper.UPDATED_TIMESTAMP, Timestamp(System.currentTimeMillis()).toString())
        }
    }

}
