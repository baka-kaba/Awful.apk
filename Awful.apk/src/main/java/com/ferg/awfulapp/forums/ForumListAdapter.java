package com.ferg.awfulapp.forums;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.view.animation.FastOutSlowInInterpolator;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.volley.VolleyError;
import com.android.volley.toolbox.ImageLoader;
import com.android.volley.toolbox.NetworkImageView;
import com.bignerdranch.expandablerecyclerview.Adapter.ExpandableRecyclerAdapter;
import com.bignerdranch.expandablerecyclerview.Model.ParentListItem;
import com.bignerdranch.expandablerecyclerview.ViewHolder.ChildViewHolder;
import com.bignerdranch.expandablerecyclerview.ViewHolder.ParentViewHolder;
import com.ferg.awfulapp.R;
import com.ferg.awfulapp.network.NetworkUtils;
import com.ferg.awfulapp.preferences.AwfulPreferences;
import com.ferg.awfulapp.provider.ColorProvider;
import com.ferg.awfulapp.util.AwfulUtils;

import java.util.ArrayList;
import java.util.List;

import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static com.ferg.awfulapp.forums.Forum.BOOKMARKS;
import static com.ferg.awfulapp.forums.Forum.FORUM;
import static com.ferg.awfulapp.forums.Forum.SECTION;

/**
 * Created by baka kaba on 13/04/2016.
 *
 * A RecyclerView adapter for displaying expandable two-level lists of forums.
 *
 * <strong>Important!</strong> As of writing this, the data set passed into the constructor
 * cannot be changed (in the library)
 *
 */
public class ForumListAdapter extends ExpandableRecyclerAdapter<ForumListAdapter.TopLevelForumHolder, ForumListAdapter.SubforumHolder> {

    private final AwfulPreferences awfulPrefs;
    @NonNull
    private final EventListener eventListener;
    @NonNull
    private final LayoutInflater inflater;
    /** interpolator for any animations a view holder wants to do */
    @NonNull
    private final Interpolator interpolator;


    public interface EventListener {
        void onForumClicked(@NonNull Forum forum);
    }


    /**
     * Get an adapter!
     * Takes a list of Forums which will form the main list.
     * Any of those which has items in {@link Forum#subforums} will be expandable,
     * and the subforums will be shown as an inner list. Any subforums of those items
     * will be ignored. Use {@link com.ferg.awfulapp.forums.ForumStructure.ListBuilder} etc.
     * to flatten the forums hierarchy into two levels.
     *
     * @param context   Used for layout inflation
     * @param forums    A list of Forums to display
     * @param listener  Gets callbacks for clicks etc
     * @param awfulPreferences used to check for user options
     * @return  an adapter containing the provided forums
     */
	public static ForumListAdapter getInstance(@NonNull Context context,
                                               @NonNull List<Forum> forums,
                                               @NonNull EventListener listener,
                                               @Nullable AwfulPreferences awfulPreferences) {
        List<TopLevelForum> topLevelForums = new ArrayList<>();
        ForumListAdapter adapter = new ForumListAdapter(context, topLevelForums, listener, awfulPreferences);
        // this is a stupid hack so we can supply the constructor with a list of objects we
        // can't even create without an instance... it's better than pulling TopLevelForum out
        // into a separate file at least
        adapter.addToTopLevelForums(forums, topLevelForums);
        adapter.notifyParentItemRangeInserted(0, topLevelForums.size());
        return adapter;
	}


	private ForumListAdapter(@NonNull Context context,
                             @NonNull List<TopLevelForum> topLevelForums,
                             @NonNull EventListener listener,
                             @Nullable AwfulPreferences awfulPreferences) {
        super(topLevelForums);
        eventListener = listener;
        awfulPrefs = awfulPreferences;
        inflater = LayoutInflater.from(context);
        interpolator = new FastOutSlowInInterpolator();
    }


    /**
     * Create TopLevelForums from a list of Forums, adding them to a supplied list.
     * @param forums            The forums to add
     * @param topLevelForums    The list to add to
     */
    private void addToTopLevelForums(@NonNull List<Forum> forums,
                                     @NonNull List<TopLevelForum> topLevelForums) {
        for (Forum forum : forums) {
            topLevelForums.add(new TopLevelForum(forum));
        }
    }


    /**
     * Update the contents of the data set with a new list of forums.
     * @param forums    The new list to display
     *                  (see {@link #getInstance(Context, List, EventListener, AwfulPreferences)} for the list format)
     */
    public void updateForumList(@NonNull List<Forum> forums) {
        @SuppressWarnings("unchecked")
        List<TopLevelForum> itemList = (List<TopLevelForum>) getParentItemList();

        // we can't just reassign the dataset variable, we have to mess with the contents instead
        int oldSize = itemList.size();
        if (oldSize > 0) {
            notifyParentItemRangeRemoved(0, oldSize);
        }
        itemList.clear();
        addToTopLevelForums(forums, itemList);
        int newSize = forums.size();
        if (newSize > 0) {
            notifyParentItemRangeInserted(0, newSize);
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Internal adapter wiring
    ///////////////////////////////////////////////////////////////////////////


	@Override
    public TopLevelForumHolder onCreateParentViewHolder(ViewGroup parentViewGroup) {
        View view = inflater.inflate(R.layout.forum_item_new, parentViewGroup, false);
        return new TopLevelForumHolder(view, interpolator);
    }


    @Override
    public SubforumHolder onCreateChildViewHolder(ViewGroup childViewGroup) {
        View view = inflater.inflate(R.layout.forum_item_subforum_new, childViewGroup, false);
        return new SubforumHolder(view);
    }


    @Override
    public void onBindParentViewHolder(TopLevelForumHolder parentViewHolder, int position, ParentListItem parentListItem) {
        parentViewHolder.bind((TopLevelForum) parentListItem);
    }


    @Override
    public void onBindChildViewHolder(SubforumHolder childViewHolder, int position, Object childListItem) {
        childViewHolder.bind((Forum) childListItem);
    }


    private class TopLevelForum implements ParentListItem {

        final Forum forum;

        TopLevelForum(Forum forum) {
            this.forum = forum;
        }

        @Override
        public List<?> getChildItemList() {
            return forum.subforums;
        }

        @Override
        public boolean isInitiallyExpanded() {
            return false;
        }
    }


    ///////////////////////////////////////////////////////////////////////////
    // List items!
    ///////////////////////////////////////////////////////////////////////////


    class TopLevelForumHolder extends ParentViewHolder {

		private final Interpolator interpolator;

		private Forum forum;

        // list item sections - overall view, left column (tags etc), right column (details)
        private final View itemView;
        private final View tagArea;
        private final View detailsArea;


        // right column (used for forums)
        private final TextView title;
        private final TextView subtitle;

        // left column (used for forums)
		private final ImageView dropdownButton;
        private final ImageView forumTag;

        // section title (used for section headers)
        private final TextView sectionTitle;

        // the divider line
        private final View listDivider;


		public TopLevelForumHolder(View itemView, Interpolator interpolator) {
			super(itemView);
			this.interpolator = interpolator;

            this.itemView = itemView;
            tagArea = itemView.findViewById(R.id.tag_and_expander);
            detailsArea = itemView.findViewById(R.id.forum_details);

            title = (TextView) itemView.findViewById(R.id.forum_title);
            subtitle = (TextView) itemView.findViewById(R.id.forum_subtitle);

            sectionTitle = (TextView) itemView.findViewById(R.id.section_title);
            forumTag = (ImageView) itemView.findViewById(R.id.forum_tag);
            dropdownButton = (ImageView) itemView.findViewById(R.id.explist_indicator);

            listDivider = itemView.findViewById(R.id.list_divider);
        }


		public void bind(final TopLevelForum forumItem) {
			forum = forumItem.forum;

            // TODO: pull out a general dimension for forum/thread list heights
            // section items hide everything but the section title,
            // other forum types hide the section title and show the other components.
            // Think of of them as two alternative layouts in the same Layout file
            tagArea.setVisibility(forum.isType(SECTION) ? GONE : VISIBLE);
            detailsArea.setVisibility(forum.isType(SECTION) ? GONE : VISIBLE);
            sectionTitle.setVisibility(forum.isType(SECTION) ? VISIBLE : GONE);

            // hide the list divider for section titles and expanded parent forums
            boolean hideDivider = forum.isType(SECTION) || forumItem.isInitiallyExpanded();
            listDivider.setVisibility(hideDivider ? INVISIBLE : VISIBLE);

            // sectionTitle is basically a differently formatted version of the title
            title.setText(forum.title);
            subtitle.setText(forum.subtitle);
            sectionTitle.setText(forum.title);

            // we remove the subtitle if it's not there (or it's disabled) so that the title gets vertically centred
            boolean subtitlesEnabled = false;
            if (awfulPrefs != null) {
                subtitlesEnabled = awfulPrefs.forumIndexShowSubtitles;
            }
            subtitle.setVisibility(!forum.subtitle.isEmpty() && subtitlesEnabled ? VISIBLE : GONE);

            setColours(itemView, title, subtitle);

            // the left section (potentially) has a tag and a dropdown button, anything missing
            // is set to GONE so whatever's there gets vertically centred, and the space remains

            // if there's a forum tag then display it, otherwise remove it
            boolean hasForumTag = forum.getTagUrl() != null;
            if (hasForumTag) {
                NetworkUtils.getImageLoader().get(forum.getTagUrl(), new ImageLoader.ImageListener() {
                    @Override
                    public void onResponse(ImageLoader.ImageContainer response, boolean isImmediate) {
                        Bitmap threadTag = response.getBitmap();
                        if (threadTag != null) {
                            Drawable counter;
                            if (AwfulUtils.isLollipop()) {
                                counter = forumTag.getContext().getResources().getDrawable(R.drawable.forumtagtest, forumTag.getContext().getTheme());
                            } else {
                                counter = forumTag.getContext().getResources().getDrawable(R.drawable.forumtagtest);
                            }
                            if (counter != null) {
                                counter.mutate();
                                forumTag.setImageDrawable(counter);
                                // get square color
                                forumTag.setColorFilter(threadTag.getPixel(4,10), PorterDuff.Mode.MULTIPLY);
                            }
                            // get background color
                            forumTag.setBackgroundColor(threadTag.getPixel(33,13));
                        }
                    }

                    @Override
                    public void onErrorResponse(VolleyError error) {
                        //uh-oh
                    }
                });
                forumTag.setVisibility(View.VISIBLE);
            }  else {
                forumTag.setVisibility(View.GONE);
            }

            // if this item has subforums, show the dropdown and make it work, otherwise remove it
			boolean hasSubforums = !forumItem.getChildItemList().isEmpty();
            if (hasSubforums) {
                dropdownButton.setVisibility(VISIBLE);
                rotateDropdown(dropdownButton, !isExpanded(), true);
                dropdownButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (isExpanded()) {
                            collapseView();
                        } else {
                            expandView();
                        }
                    }
                });
            } else {
                dropdownButton.setVisibility(GONE);
                dropdownButton.setOnClickListener(null);
            }

            // make forums and bookmarks clickable (not headers!)
            if (forum.isType(BOOKMARKS) || forum.isType(FORUM)) {
                detailsArea.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        eventListener.onForumClicked(forum);
                    }
                });
            }
		}


        @Override
		public boolean shouldItemViewClickToggleExpansion() {
			return false;
		}


		@Override
		public void onExpansionToggled(boolean closing) {
			super.onExpansionToggled(closing);
            // TODO: make a proper button with toggleable state + anims, so it can just be set to the correct state in bind()
            // right now a rotated button is sticking when its view is recycled
            rotateDropdown(dropdownButton, closing, false);
            listDivider.setVisibility(closing ? VISIBLE : INVISIBLE);
		}
	}


    /**
     * Rotate the dropdown button to the up or down position.
     * @param dropdown  The view to rotate
     * @param down      True to rotate to the down state, false for up
     * @param immediate True will immediately set the visual state, false will animate
     */
    private void rotateDropdown(@NonNull ImageView dropdown, boolean down, boolean immediate) {
        final int DOWN_ROTATION = 0;
        final int UP_ROTATION = -540;
        if (immediate) {
            dropdown.animate()
                    .setDuration(0)
                    .rotation(down ? DOWN_ROTATION : UP_ROTATION)
                    .setInterpolator(interpolator);
        } else {
            dropdown.animate()
                    .setDuration(400)
                    .rotation(down ? DOWN_ROTATION : UP_ROTATION)
                    .setInterpolator(interpolator);
        }
    }


	class SubforumHolder extends ChildViewHolder {

		private final TextView title;
		private final TextView subtitle;
		private final View forumDetails;
		private final View itemLayout;

		public SubforumHolder(View itemView) {
			super(itemView);
			title = (TextView) itemView.findViewById(R.id.forum_title);
			subtitle = (TextView) itemView.findViewById(R.id.forum_subtitle);
			forumDetails = itemView.findViewById(R.id.forum_details);
			itemLayout = itemView.findViewById(R.id.item_container);
		}

		public void bind(final Forum forumItem) {
			title.setText(forumItem.title);
			subtitle.setText(forumItem.subtitle);

            // we remove the subtitle if it's not there (or it's disabled) so that the title gets vertically centred
            boolean subtitlesEnabled = false;
            if (awfulPrefs != null) {
                subtitlesEnabled = awfulPrefs.forumIndexShowSubtitles;
            }
            subtitle.setVisibility(!forumItem.subtitle.isEmpty() && subtitlesEnabled ? VISIBLE : GONE);

			setColours(itemLayout, title, subtitle);

			forumDetails.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
                    eventListener.onForumClicked(forumItem);
				}
			});
		}
	}


    /**
     * Apply colour theming
     * @param mainView      The main item layout, has its background set
     */
    private void setColours(View mainView, TextView title, TextView subtitle) {
        mainView.setBackgroundColor(ColorProvider.getBackgroundColor());
        title.setTextColor(ColorProvider.getTextColor());
        subtitle.setTextColor(ColorProvider.getAltTextColor());
    }

}
