package com.atlas.enterprise.company.application;

import com.atlas.enterprise.company.DataSnapshot;
import com.atlas.enterprise.company.port.DataSnapshotRepository;
import com.atlas.enterprise.task.application.TaskNotFoundException;
import com.atlas.enterprise.task.port.TaskRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataSnapshotService {
    private final DataSnapshotRepository snapshots;
    private final TaskRepository tasks;

    public DataSnapshotService(DataSnapshotRepository snapshots, TaskRepository tasks) {
        this.snapshots = snapshots;
        this.tasks = tasks;
    }

    @Transactional(readOnly = true)
    public DataSnapshot latest(UUID taskId) {
        if (tasks.findById(taskId).isEmpty()) {
            throw new TaskNotFoundException(taskId);
        }
        return snapshots.findLatestByTaskId(taskId)
            .orElseThrow(() -> new SnapshotNotFoundException(taskId));
    }
}
