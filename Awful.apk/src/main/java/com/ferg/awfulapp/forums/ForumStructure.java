package com.ferg.awfulapp.forums;

import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by baka kaba on 09/04/2016.
 */
public class ForumStructure {

    private static final String TAG = "ForumStructure";

    private final List<Forum> forumTree;


    private ForumStructure(List<Forum> forumTree) {
        this.forumTree = forumTree;
    }


    /**
     * Build a ForumStructure from an ordered list of Forum elements.
     * This will build a hierarchical tree based on the IDs and parent IDs of the Forums,
     * and the elements at each node will be in the order they appeared in the list.
     *
     * Use this to turn a collection of Forum items (in indexed order) into an ordered tree.
     * Only the Forums in the supplied list will be added - their subforum fields will be ignored.
     *
     * The topLevelId represents the parentID of the top level forums - any Forums with this as
     * their {@link Forum#parentId} will appear at the top of the hierarchy. If a Forum has a
     * parentId which doesn't match another Forum's id, and it isn't the topLevelId, that Forum is
     * effectively orphaned and will be excluded (along with its subforums) from the resulting ForumStructure.
     * This can be used to select a small branch of the tree (only the subforums of a given forum)
     *
     * @param orderedForums A list of Forums in the order they should appear,
     *                      with {@link Forum#id} and {@link Forum#parentId} set
     * @param topLevelId    The ID that represents the root of the hierarchy
     * @return              A ForumStructure representing the supplied forums
     */
    public static ForumStructure buildFromOrderedList(List<Forum> orderedForums, int topLevelId) {
        List<Forum> forumTree = new ArrayList<>();

        Map<Integer, Forum> forumsById = new LinkedHashMap<>();
        for (Forum forum : orderedForums) {
            forumsById.put(forum.id, forum);
        }

        Forum parentForum;
        for (Forum forum : forumsById.values()) {
            // check if this forum is a top-level category 'forum' like Main or Community
            if (forum.parentId == topLevelId) {
                forumTree.add(forum);
            }
            // otherwise add the forum to its parent's subforum list
            else {
                parentForum = forumsById.get(forum.parentId);
                if (parentForum != null) {
                    parentForum.subforums.add(forum);
                } else {
                    Log.w(TAG, "Unable to find parent forum with ID: " + forum.parentId);
                }
            }
        }

        return new ForumStructure(forumTree);
    }


    /**
     * Build a ForumStructure from a hierarchical tree of Forum objects.
     *
     * This will treat the Forum/subforum structure as authoritative, and the Forums'
     * {@link Forum#parentId}s will be set to reflect the {@link Forum#id} of its containing Forum.
     * The order of each node list will be preserved.
     *
     * @param forumTree     A list of Forums, which in turn may contain Forums in their subforum lists
     * @param topLevelId    The ID that represents the root of the hierarchy
     * @return              A ForumStructure with the same hierarchy
     */
    public static ForumStructure buildFromForumTree(List<Forum> forumTree, int topLevelId) {
        List<Forum> newForumTree = new ArrayList<>();
        copyTreeWithParentId(forumTree, newForumTree, topLevelId);
        return new ForumStructure(newForumTree);
    }


    /**
     * Recursively add the contents of a tree node into another tree node, specifying a new parent ID.
     * @param sourceTree        The tree to copy
     * @param destinationTree   The tree to copy into
     * @param parentId          The parent ID for the new tree
     */
    private static void copyTreeWithParentId(List<Forum> sourceTree, List<Forum> destinationTree, int parentId) {
        for (Forum sourceForum : sourceTree) {
            Forum forumCopy = new Forum(sourceForum.id, parentId, sourceForum.title, sourceForum.subtitle);
            destinationTree.add(forumCopy);
            // copy this Forum's subforums, but ensure the parent IDs refer to this Forum's ID
            copyTreeWithParentId(sourceForum.subforums, forumCopy.subforums, forumCopy.id);
        }
    }

    /*
        Getters
     */

    public List<Forum> getForumTree() {
        // TODO: since the subforum lists are mutable, should this be a copy?
        return forumTree;
    }


    public List<Forum> getTwoLevelListWithCategories() {
        List<Forum> flattenedForums = new ArrayList<>();
        for (Forum category : forumTree) {
            // add the category but don't add its subforums
            flattenedForums.add(new Forum(category));
            // for each of its subforums, add the top level one into the main list
            for (Forum topForum : category.subforums) {
                Forum forumCopy = new Forum(topForum);
                flattenedForums.add(forumCopy);
                // collect each top level forum's complete subforum hierarchy as a flat list
                collectSubforums(topForum.subforums, forumCopy.subforums);
            }
        }

        return flattenedForums;
    }


    // TODO: refactor these to get rid of the shared code
    public List<Forum> getTwoLevelList() {
        List<Forum> flattenedForums = new ArrayList<>();
        // copy all the top level forums into a list (new Forum objects with empty subforum lists)
        for (Forum topForum : forumTree) {
            Forum forumCopy = new Forum(topForum);
            flattenedForums.add(forumCopy);
            // collect each top level forum's complete subforum hierarchy as a flat list
            collectSubforums(topForum.subforums, forumCopy.subforums);
        }

        return flattenedForums;
    }


    public List<Forum> getFlatList() {
        List<Forum> flattenedForums = new ArrayList<>();
        for (Forum topForum : forumTree) {
            Forum forumCopy = new Forum(topForum);
            flattenedForums.add(forumCopy);
            collectSubforums(topForum.subforums, flattenedForums);
        }

        return flattenedForums;
    }


    /**
     * Recursively copy all subforums in a tree into a flat list.
     * @param source        The source tree, whose hierarchy will be traversed
     * @param collection    A list to collect all the subforum objects in
     */
    private static void collectSubforums(List<Forum> source, List<Forum> collection) {
        for (Forum forum : source) {
            collection.add(new Forum(forum));
            collectSubforums(forum.subforums, collection);
        }
    }


}
