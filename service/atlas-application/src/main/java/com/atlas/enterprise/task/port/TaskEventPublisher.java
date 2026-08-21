package com.atlas.enterprise.task.port;

import com.atlas.enterprise.task.application.TaskEventRecord;

public interface TaskEventPublisher {
    void publish(TaskEventRecord event);
}
