package com.ferg.awfulapp.search

import android.os.Parcel
import android.os.Parcelable
import android.support.v7.app.AlertDialog
import android.view.View
import android.widget.EditText
import com.ferg.awfulapp.R
import com.ferg.awfulapp.preferences.AwfulPreferences

/**
 * Created by baka kaba on 13/06/2018.
 *
 * Represents a filter for use in forums searches.
 *
 * This holds a type (reflecting the different search keywords available on the site) and the actual
 * data being searched for / filtered on - text, IDs etc.
 */
class SearchFilter(val type: FilterType, val param: String) : Parcelable {

    /**
     * Describes the various types of filters available in the forums search.
     *
     * [filterTemplate] is used to produce the output required by the query string.
     * [label] is a short name for the filter, and [description] is an optional longer version used
     * in the hint text for input dialogs.
     */
    enum class FilterType(
            val filterTemplate: String,
            val label: String,
            val description: String? = null,
            val fixedValue: (() -> String)? = null
    ) {

        PostText("%s", "Text in posts"),
        UserId("userid:%s", "User ID"),
        Username("username:\"%s\"", "Username"),
        MyUsername("username:\"%s\"", "My username", fixedValue = { AwfulPreferences.getInstance().username }),
        Quoting("quoting:\"%s\"", "User being quoted"),
        Before("before:\"%s\"", "Earlier than"),
        After("since:\"%s\"", "Later than"),
        ThreadId("threadid:%s", "Thread ID"),
        InTitle("intitle:\"%s\"", "Thread title", "Text in thread title");

        val editable = fixedValue == null

        /**
         * Show a popup dialog allowing the user to add data to filter on.
         * Sets the result on the provided #SearchFragment.
         */
        fun showDialog(searchFragment: SearchFragment) {
            if (editable) {
                SearchFilter.showDialog(searchFragment, this, callback = searchFragment::addFilter)
            } else {
                searchFragment.addFilter(SearchFilter(this, fixedValue!!()))
            }
        }
    }

    override fun toString(): String = type.filterTemplate.format(type.fixedValue?.invoke() ?: param)

    fun edit(searchFragment: SearchFragment, callback: (SearchFilter) -> Unit) {
        if (type.editable) showDialog(searchFragment, type, param, editing = true, callback = callback)
    }

    //
    // Parcelable boilerplate - maybe use @Parcelize from Kotlin Extensions?
    //

    constructor(parcel: Parcel) : this(
            FilterType.values()[parcel.readInt()],
            parcel.readString()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) = with(parcel) {
        writeInt(type.ordinal)
        writeString(param)
    }

    override fun describeContents(): Int = 0

    companion object {

        /**
         * Show a popup dialog allowing the user to add data to filter on.
         * Sets the result on the provided #SearchFragment.
         */
        fun showDialog(
                searchFragment: SearchFragment,
                filterType: FilterType,
                filterData: String = "",
                editing: Boolean = false,
                callback: (SearchFilter) -> Unit
        ) {
            searchFragment.activity?.run {
                val layout = layoutInflater.inflate(R.layout.insert_text_dialog, null)
                val textField = layout.findViewById<View>(R.id.text_field) as EditText

                textField.hint = filterType.description ?: filterType.label
                textField.setText(filterData)
                AlertDialog.Builder(this)
                        .setTitle(if (editing) "Edit search filter" else "Add search filter")
                        .setView(layout)
                        .setPositiveButton(if (editing) "Confirm" else "Add filter", { _, _ ->
                            SearchFilter(filterType, textField.text.toString()).run(callback)
                        })
                        .show()
            }
        }

        @JvmField
        val CREATOR = object : Parcelable.Creator<SearchFilter> {
            override fun createFromParcel(parcel: Parcel): SearchFilter = SearchFilter(parcel)
            override fun newArray(size: Int): Array<SearchFilter?> = arrayOfNulls(size)
        }
    }

}