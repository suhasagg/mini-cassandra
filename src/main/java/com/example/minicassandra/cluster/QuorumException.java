package com.example.minicassandra.cluster;

public final class QuorumException extends RuntimeException {
    public QuorumException(String message) {
        super(message);
    }
}
