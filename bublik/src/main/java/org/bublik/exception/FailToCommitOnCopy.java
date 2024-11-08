package org.bublik.exception;

public class FailToCommitOnCopy extends RuntimeException {
    public FailToCommitOnCopy(String message) {
        super(message);
    }
}
