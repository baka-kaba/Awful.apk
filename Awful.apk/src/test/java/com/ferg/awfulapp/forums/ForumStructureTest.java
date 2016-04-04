package com.ferg.awfulapp.forums;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.Is.is;

/**
 * Created by baka kaba on 09/04/2016.
 */
public class ForumStructureTest {

    private static final int TOP_LEVEL_ID = 0;

    private Forum forum1;
    private Forum forum2;
    private Forum forum3;
    private Forum forum4;
    private Forum forum5;

    private List<Forum> expectedTree;

    @Before
    public void setUp() {
        // reset the handy test objects
        forum1 = new Forum(1, TOP_LEVEL_ID, "1", "");
        forum2 = new Forum(2, TOP_LEVEL_ID, "2", "");
        forum3 = new Forum(3, 2, "2-1", "");
        forum4 = new Forum(4, 2, "2-2", "");
        forum5 = new Forum(5, 3, "2-1-1", "");

        /* build the expected tree from the forum* hierarchy, making this:
                 root
              f1     f2
                   f3  f4
                   f5

           using copies of the original forum objects, so this is an independent structure
        */
        expectedTree = new ArrayList<>();
        Forum newForum1 = new Forum(forum1);
        Forum newForum2 = new Forum(forum2);
        Forum newForum3 = new Forum(forum3);
        Forum newForum4 = new Forum(forum4);
        Forum newForum5 = new Forum(forum5);
        Collections.addAll(expectedTree, newForum1, newForum2);
        Collections.addAll(newForum2.subforums, newForum3, newForum4);
        newForum3.subforums.add(newForum5);
    }


    /*
        Build from a flat list
     */


    @Test
    public void testBuildFromOrderedList() throws Exception {
        // create an ordered, flat list of forums
        List<Forum> sourceList = new ArrayList<>();
        Collections.addAll(sourceList, forum1, forum2, forum3, forum4, forum5);

        // build a ForumStructure from the flat list, and get the tree it creates
        ForumStructure forumStructure = ForumStructure.buildFromOrderedList(sourceList, TOP_LEVEL_ID);
        List<Forum> builtTree = forumStructure.getForumTree();

        // the tree should match the expected one we built
        assertThat(builtTree, is(aForumTreeMatching(expectedTree)));
    }


    @Test
    public void testBuildFromOrderedList_withOrphanedNodes() {
        // To be part of the final tree, a forum's parent ID needs to match another forum in
        // the tree, or the the top-level ID that represents the root of the tree

        // create a list of source objects, with some that won't be below the root (and will be discarded)
        List<Forum> sourceList = new ArrayList<>();
        Collections.addAll(sourceList, forum1, forum2, forum3, forum5, forum4);
        // set a new ID for the root - only forums with parent ID = 2 (or their descendants) should be added
        int newTopLevelId = 2;

        // build the expected result - basically forum2's subforum hierarchy
        expectedTree = new ArrayList<>();
        Forum newForum3 = new Forum(forum3);
        Forum newForum4 = new Forum(forum4);
        Forum newForum5 = new Forum(forum5);
        Collections.addAll(expectedTree, newForum3, newForum4);
        newForum3.subforums.add(newForum5);


        // build a ForumStructure from the flat list, using the new root ID
        ForumStructure forumStructure = ForumStructure.buildFromOrderedList(sourceList, newTopLevelId);
        List<Forum> builtTree = forumStructure.getForumTree();


        // the tree should only contain the section under the new root ID
        assertThat(builtTree, is(aForumTreeMatching(expectedTree)));
    }


    @Test
    public void testBuildFromOrderedList_withEmptyList() {
        // create a ForumStructure from an empty list
        ForumStructure forumStructure = ForumStructure.buildFromOrderedList(Collections.<Forum>emptyList(), TOP_LEVEL_ID);
        List<Forum> builtTree = forumStructure.getForumTree();

        // the resulting tree should be empty
        assertThat(builtTree.isEmpty(), is(true));
    }


    /*
        Build from a tree
     */


    @Test
    public void testBuildFromForumTree() throws Exception {
        // build a hierarchy that matches the example tree, but with inconsistent ID/ParentID relationships
        // We've got different parents, missing parents, the whole thing
        List<Forum> sourceTree = new ArrayList<>();
        Forum newForum1 = new Forum(1, TOP_LEVEL_ID, "1", "");
        Forum newForum2 = new Forum(2, 33, "2", "");
        Forum newForum3 = new Forum(3, 1, "2-1", "");
        Forum newForum4 = new Forum(4, 3, "2-2", "");
        Forum newForum5 = new Forum(5, 6, "2-1-1", "");
        Collections.addAll(sourceTree, newForum1, newForum2);
        Collections.addAll(newForum2.subforums, newForum3, newForum4);
        newForum3.subforums.add(newForum5);

        // build a structure from this and get its tree
        ForumStructure forumStructure = ForumStructure.buildFromForumTree(sourceTree, TOP_LEVEL_ID);
        List<Forum> builtTree = forumStructure.getForumTree();

        // the original should not match the expected tree (with all the ID mismatches)
        assertThat(sourceTree, is(not(aForumTreeMatching(expectedTree))));
        // the built tree should have resolved all this and match
        assertThat(builtTree, is(aForumTreeMatching(expectedTree)));
    }


    @Test
    public void testBuildFromForumTree_withEmptySource() {
        // create a ForumStructure from an empty tree
        ForumStructure forumStructure = ForumStructure.buildFromForumTree(Collections.<Forum>emptyList(), TOP_LEVEL_ID);
        List<Forum> builtTree = forumStructure.getForumTree();

        // the resulting tree should be empty
        assertThat(builtTree.isEmpty(), is(true));
    }


    /*
        list getters
     */

    @Test
    public void getFlatList() {
        // flattening the structure in expectedTree should give this ordered list:
        List<Forum> expectedList = new ArrayList<>();
        Collections.addAll(expectedList, forum1, forum2, forum3, forum5, forum4);

        // build the structure with the tree and flatten it
        ForumStructure forumStructure = ForumStructure.buildFromForumTree(expectedTree, TOP_LEVEL_ID);
        List<Forum> flatList = forumStructure.getFlatList();

        // check the list contains the same Forum elements, in order
        assertThat(flatList, is(expectedList));
    }


    @Test
    public void getTwoLevelList() {
        // flattening the structure in expectedTree should give this tree:
        List<Forum> expectedList = new ArrayList<>();
        Collections.addAll(expectedList, forum1, forum2);
        Collections.addAll(forum2.subforums, forum3, forum5, forum4);

        // build the structure with the tree and flatten it
        ForumStructure forumStructure = ForumStructure.buildFromForumTree(expectedTree, TOP_LEVEL_ID);
        List<Forum> twoLevelList = forumStructure.getTwoLevelList();

        // check that the result was split into levels and ordered correctly
        assertThat(twoLevelList, is(aForumTreeMatching(expectedList)));
    }



    private Matcher<List<Forum>> aForumTreeMatching(final List<Forum> forumTree) {
        return new TypeSafeMatcher<List<Forum>>() {

            private String errorMsg;

            @Override
            protected boolean matchesSafely(List<Forum> otherTree) {
                return nodesMatch(forumTree, otherTree);
            }


            private boolean nodesMatch(List<Forum> firstNode, List<Forum> secondNode) {
                if (firstNode.size() != secondNode.size()) {
                    errorMsg = String.format("got the wrong number of forums (%d vs %d)", firstNode.size(), secondNode.size());
                    return false;
                }
                Forum firstForum, secondForum;
                // check the forums in each position match, and do the same for their subforums
                for (int i = 0; i < firstNode.size(); i++) {
                    firstForum = firstNode.get(i);
                    secondForum = secondNode.get(i);
                    if (!firstForum.equals(secondForum)) {
                        errorMsg = String.format("Forums in position %d don't match", i);
                        return false;
                    } else if (!nodesMatch(firstForum.subforums, secondForum.subforums)) {
                        return false;
                    }
                }

                return true;
            }


            @Override
            public void describeTo(Description description) {
                description.appendText("a tree with the same hierarchy of Forum objects, in the same order");
            }


            @Override
            protected void describeMismatchSafely(List<Forum> item, Description mismatchDescription) {
                mismatchDescription.appendText(errorMsg);
            }
        };
    }

}