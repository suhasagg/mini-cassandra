package com.example.minicassandra.cluster;

import com.example.minicassandra.util.HashUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

public final class ConsistentHashRing {
    private final List<Token> tokens;

    public ConsistentHashRing(List<String> nodeIds, int virtualNodes) {
        if (nodeIds.isEmpty()) throw new IllegalArgumentException("nodeIds cannot be empty");
        this.tokens = new ArrayList<>();
        for (String nodeId : nodeIds) {
            for (int i = 0; i < virtualNodes; i++) {
                tokens.add(new Token(HashUtil.hash64(nodeId + ":" + i), nodeId));
            }
        }
        tokens.sort(Comparator.comparingLong(Token::token));
    }

    public List<String> replicasFor(String partitionKey, int replicationFactor) {
        if (replicationFactor <= 0) throw new IllegalArgumentException("replicationFactor must be positive");
        long token = HashUtil.hash64(partitionKey);
        int start = lowerBound(token);
        LinkedHashSet<String> replicas = new LinkedHashSet<>();
        int i = start;
        while (replicas.size() < replicationFactor) {
            Token current = tokens.get(i % tokens.size());
            replicas.add(current.nodeId());
            i++;
            if (i - start > tokens.size() * 2) {
                throw new IllegalStateException("Unable to find enough unique replicas");
            }
        }
        return new ArrayList<>(replicas);
    }

    public List<String> describe(int limit) {
        return tokens.stream()
                .limit(limit)
                .map(token -> token.token() + ":" + token.nodeId())
                .toList();
    }

    private int lowerBound(long token) {
        int left = 0;
        int right = tokens.size();
        while (left < right) {
            int mid = (left + right) >>> 1;
            if (tokens.get(mid).token() < token) left = mid + 1;
            else right = mid;
        }
        return left == tokens.size() ? 0 : left;
    }

    private record Token(long token, String nodeId) {
    }
}
