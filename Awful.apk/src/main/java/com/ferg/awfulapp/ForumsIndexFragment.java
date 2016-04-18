/********************************************************************************
 * Copyright (c) 2011, Scott Ferguson
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of the software nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY SCOTT FERGUSON ''AS IS'' AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL SCOTT FERGUSON BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *******************************************************************************/

package com.ferg.awfulapp;


import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.ferg.awfulapp.constants.Constants;
import com.ferg.awfulapp.forums.Forum;
import com.ferg.awfulapp.forums.ForumListAdapter;
import com.ferg.awfulapp.forums.ForumRepository;
import com.ferg.awfulapp.preferences.AwfulPreferences;
import com.ferg.awfulapp.provider.ColorProvider;
import com.orangegangsters.github.swipyrefreshlayout.library.SwipyRefreshLayout;
import com.orangegangsters.github.swipyrefreshlayout.library.SwipyRefreshLayoutDirection;

import java.util.Date;
import java.util.List;

import static android.view.View.VISIBLE;
import static com.ferg.awfulapp.forums.ForumStructure.TWO_LEVEL;

public class ForumsIndexFragment extends AwfulFragment
		implements SwipyRefreshLayout.OnRefreshListener, ForumRepository.ForumsUpdateListener, ForumListAdapter.EventListener {


	private static final String TAG = "ForumsIndexFragment";

	private View mProbationBar;
	private TextView mProbationMessage;
	private ImageButton mProbationButton;

	private RecyclerView forumRecyclerView;
	private ForumListAdapter forumListAdapter;
	private ForumRepository forumRepo;


    @Override
    public void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState); if(DEBUG) Log.d(TAG, "onCreate" + (savedInstanceState != null ? " + saveState" : ""));
        setHasOptionsMenu(true);
        setRetainInstance(false);
    }

    @Override
    public View onCreateView(LayoutInflater aInflater, ViewGroup aContainer, Bundle aSavedState) {
        if(DEBUG) Log.d(TAG, "onCreateView");
        View result = inflateView(R.layout.forum_index, aContainer, aInflater);

		forumRecyclerView = (RecyclerView) result.findViewById(R.id.index_view);

		mProbationBar = result.findViewById(R.id.probationbar);
		mProbationMessage = (TextView) result.findViewById(R.id.probation_message);
		mProbationButton  = (ImageButton) result.findViewById(R.id.go_to_LC);
		updateProbationBar();

        return result;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mSRL = (SwipyRefreshLayout) view.findViewById(R.id.index_swipe);
        mSRL.setOnRefreshListener(this);
		updateViewColours();
    }

    @Override
    public void onActivityCreated(Bundle aSavedState) {
		super.onActivityCreated(aSavedState);
		Context context = getActivity();

		forumRepo = ForumRepository.getInstance(getContext());
		List<Forum> forumList = forumRepo.getForumStructure().getAsList().build();
		forumListAdapter = ForumListAdapter.getInstance(context, forumList, this);
		forumRecyclerView.setAdapter(forumListAdapter);
		forumRecyclerView.setLayoutManager(new LinearLayoutManager(context));
	}

    @Override
    public void onResume() {
        super.onResume();
		forumRepo.registerListener(this);
		refreshForumList();
		updateProbationBar();
    }

	@Override
	public void onPause() {
		forumRepo.unregisterListener(this);
		super.onPause();
	}

	// TODO: do these need to be here?

	@Override
	public void onPageVisible() {
		if(DEBUG) Log.d(TAG, "onPageVisible");
	}

	@Override
	public void onPageHidden() {
		if(DEBUG) Log.d(TAG, "onPageHidden");
	}


    @Override
    public String getInternalId() {
        return TAG;
    }


	@Override
	public void onPreferenceChange(AwfulPreferences mPrefs, String key) {
		super.onPreferenceChange(mPrefs, key);
		updateViewColours();
	}


	/**
	 * Set any colours that need to change according to the current theme
	 */
	private void updateViewColours() {
		if (mSRL != null) {
			mSRL.setColorSchemeResources(ColorProvider.getSRLProgressColor());
			mSRL.setProgressBackgroundColor(ColorProvider.getSRLBackgroundColor());
		}
		if (forumRecyclerView != null) {
			forumRecyclerView.setBackgroundColor(ColorProvider.getBackgroundColor());
		}
	}


	@Override
	public void onForumsUpdateStarted() {
		getActivity().runOnUiThread(new Runnable() {
			@Override
			public void run() {
				Toast.makeText(getActivity(), "Forums update in progress", Toast.LENGTH_SHORT).show();
				// TODO: make the spinny thing actually work because this doesn't
				requestStarted(null);
			}
		});
	}


	@Override
	public void onForumsUpdateCompleted() {
		getActivity().runOnUiThread(new Runnable() {
			@Override
			public void run() {
				Toast.makeText(getActivity(), "Forum update complete!", Toast.LENGTH_SHORT).show();
				// stop spinny thing
				requestEnded(null, null);
				refreshForumList();
			}
		});
	}


	/**
	 * Query the database for the current Forum data, and update the list
	 */
	private void refreshForumList() {
		if (forumRepo == null || forumListAdapter == null) {
			return;
		}
		// get a new data set (possibly empty if there's no data yet) and give it to the adapter
		List<Forum> forumList = forumRepo.getForumStructure()
				.getAsList()
				.includeSections(true)
				.formatAs(TWO_LEVEL)
				.build();
		forumListAdapter.updateForumList(forumList);
	}


	@Override
	public void onRefresh(SwipyRefreshLayoutDirection swipyRefreshLayoutDirection) {
		refreshForumList();
	}


	@Override
	public void onForumClicked(@NonNull Forum forum) {
		displayForum(forum.id, 1);
	}

	@Override
	public String getTitle() {
		if(getActivity() != null){
			return getResources().getString(R.string.forums_title);
		}else{
			return "Forums";
		}
	}

	@Override
	public boolean volumeScroll(KeyEvent event) {
		int action = event.getAction();
	    int keyCode = event.getKeyCode();    
	        switch (keyCode) {
	        case KeyEvent.KEYCODE_VOLUME_UP:
	            if (action == KeyEvent.ACTION_DOWN) {
	            	forumRecyclerView.smoothScrollBy(-forumRecyclerView.getHeight()/2, 0);
	            }
	            return true;
	        case KeyEvent.KEYCODE_VOLUME_DOWN:
	            if (action == KeyEvent.ACTION_DOWN) {
	            	forumRecyclerView.smoothScrollBy(forumRecyclerView.getHeight()/2, 0);
	            }
	            return true;
	        default:
	            return false;
	        }
	}
	
	public void updateProbationBar(){
		if(!mPrefs.isOnProbation()){
			mProbationBar.setVisibility(View.GONE);
			return;
		}
		mProbationBar.setVisibility(VISIBLE);
		mProbationMessage.setText(String.format(this.getResources().getText(R.string.probation_message).toString(),new Date(mPrefs.probationTime).toLocaleString()));
		mProbationButton.setOnClickListener(new OnClickListener() {
			
			@Override
			public void onClick(View v) {
				Intent openThread = new Intent(Intent.ACTION_VIEW, Uri.parse(Constants.FUNCTION_BANLIST+'?'+Constants.PARAM_USER_ID+"="+mPrefs.userId));
				startActivity(openThread);
			}
		});
	}

}
