package com.thingworx.things.agent.playbook;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

/** Computes nodes to skip after a {@code condition} branch decision. */
final class PlaybookBranchPlanner {

    private PlaybookBranchPlanner() {}

    /** Nodes reachable only from {@code branchRootToSkip}, excluding the kept branch and the condition node. */
    static Set<String> exclusiveToSkippedBranch(
            PlaybookDocument doc, String conditionNodeId, String branchRootToSkip) {
        JSONObject condition = doc.nodesById().get(conditionNodeId);
        if (condition == null || branchRootToSkip == null || branchRootToSkip.isEmpty()) {
            return Set.of();
        }
        String then = condition.optString("then", "");
        String els = condition.optString("else", "");
        String keepRoot = branchRootToSkip.equals(then) ? els : then;
        Set<String> skip = reachableFrom(doc, branchRootToSkip);
        if (!keepRoot.isEmpty()) {
            skip.removeAll(reachableFrom(doc, keepRoot));
        }
        skip.remove(conditionNodeId);
        return skip;
    }

    private static Set<String> reachableFrom(PlaybookDocument doc, String rootId) {
        if (rootId == null || rootId.isEmpty()) {
            return Set.of();
        }
        Map<String, List<String>> forward = forwardEdges(doc);
        Set<String> seen = new LinkedHashSet<>();
        Queue<String> q = new ArrayDeque<>();
        q.add(rootId);
        while (!q.isEmpty()) {
            String id = q.poll();
            if (!seen.add(id)) {
                continue;
            }
            for (String next : forward.getOrDefault(id, List.of())) {
                q.add(next);
            }
        }
        return seen;
    }

    private static Map<String, List<String>> forwardEdges(PlaybookDocument doc) {
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (String id : doc.nodeIdsInOrder()) {
            edges.putIfAbsent(id, new ArrayList<>());
        }
        for (String id : doc.nodeIdsInOrder()) {
            JSONObject node = doc.nodesById().get(id);
            if (node == null) {
                continue;
            }
            JSONArray deps = node.optJSONArray("dependsOn");
            if (deps == null) {
                continue;
            }
            for (int i = 0; i < deps.length(); i++) {
                String dep = deps.optString(i, "");
                if (!dep.isEmpty()) {
                    edges.computeIfAbsent(dep, k -> new ArrayList<>()).add(id);
                }
            }
            if ("condition".equals(node.optString("kind", ""))) {
                String then = node.optString("then", "");
                String els = node.optString("else", "");
                if (!then.isEmpty()) {
                    edges.computeIfAbsent(id, k -> new ArrayList<>()).add(then);
                }
                if (!els.isEmpty()) {
                    edges.computeIfAbsent(id, k -> new ArrayList<>()).add(els);
                }
            }
        }
        return edges;
    }

    static Set<String> allNodes(PlaybookDocument doc) {
        return new HashSet<>(doc.nodeIdsInOrder());
    }
}
