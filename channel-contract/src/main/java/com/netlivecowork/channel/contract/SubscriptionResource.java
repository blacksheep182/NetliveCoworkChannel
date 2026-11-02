package com.netlivecowork.channel.contract;

/** 订阅资源：内部任务或逻辑目标会话。 */
public sealed interface SubscriptionResource permits SubscriptionResource.Task, SubscriptionResource.Session {

    String type();

    record Task(String taskRef) implements SubscriptionResource {
        public Task {
            Producer.requireText(taskRef, "subscription.taskRef");
        }

        @Override
        public String type() {
            return "task";
        }
    }

    record Session(String targetRef) implements SubscriptionResource {
        public Session {
            Producer.requireText(targetRef, "subscription.targetRef");
        }

        @Override
        public String type() {
            return "session";
        }
    }
}
