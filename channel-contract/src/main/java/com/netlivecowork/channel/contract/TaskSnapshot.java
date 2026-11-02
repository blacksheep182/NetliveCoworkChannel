package com.netlivecowork.channel.contract;

import java.util.List;

/**
 * 任务状态快照。summary 只是获准展示的状态说明，不承载完整最终答案（data-contracts.md §5）。
 */
public record TaskSnapshot(String taskRef, String targetRef, TaskStatus status, List<ContentPart> summary) {
    public TaskSnapshot {
        Producer.requireText(taskRef, "task.taskRef");
        Producer.requireText(targetRef, "task.targetRef");
        status = status == null ? TaskStatus.UNKNOWN : status;
        summary = summary == null ? List.of() : List.copyOf(summary);
    }

    public static TaskSnapshot of(String taskRef, String targetRef, TaskStatus status) {
        return new TaskSnapshot(taskRef, targetRef, status, List.of());
    }
}
