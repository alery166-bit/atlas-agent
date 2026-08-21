package com.atlas.enterprise.task;

public final class InvalidTaskTransitionException extends RuntimeException {
    private final TaskStatus from;
    private final TaskStatus to;

    public InvalidTaskTransitionException(TaskStatus from, TaskStatus to) {
        super("Task cannot transition from %s to %s".formatted(from, to));
        this.from = from;
        this.to = to;
    }

    public TaskStatus from() {
        return from;
    }

    public TaskStatus to() {
        return to;
    }
}
