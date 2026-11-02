package com.netlivecowork.channel.contract.adapter;

import com.netlivecowork.channel.contract.ContentPart;
import com.netlivecowork.channel.contract.EventType;
import java.util.Set;

/**
 * 能力声明必须与实际端口一致。actions=true 不等于支持所有人工交互；files 分入站/出站声明。
 */
public record AdapterCapabilities(LifecycleMode lifecycle, boolean inbound, boolean textCommandInput,
                                  boolean interactiveAuth, boolean connectionTest, Files files, Delivery delivery,
                                  HistoryAccess historyAccess) {

    public enum HistoryAccess { NONE, SUMMARY, FULL }

    /** Existing/chat adapters retain summary-only access until they explicitly opt in. */
    public AdapterCapabilities(LifecycleMode lifecycle, boolean inbound, boolean textCommandInput,
                               boolean interactiveAuth, boolean connectionTest, Files files, Delivery delivery) {
        this(lifecycle, inbound, textCommandInput, interactiveAuth, connectionTest, files, delivery,
            HistoryAccess.SUMMARY);
    }

    public AdapterCapabilities {
        historyAccess = historyAccess == null ? HistoryAccess.SUMMARY : historyAccess;
        if (lifecycle == null) {
            throw new IllegalArgumentException("lifecycle is required");
        }
    }

    public record Files(boolean inbound, boolean outbound) {
        public static Files none() {
            return new Files(false, false);
        }
    }

    /** delivery=null 表示该入口不支持主动推送。 */
    public record Delivery(Set<EventType> eventTypes, Set<ContentPart.PartType> contentTypes, boolean actions,
                           Idempotency idempotency, boolean receiptQuery, Long maxTextLength, Long maxFileBytes,
                           Handover handover, CompletionNotice completionNotice) {
        public Delivery(Set<EventType> eventTypes, Set<ContentPart.PartType> contentTypes, boolean actions,
                        Idempotency idempotency, boolean receiptQuery, Long maxTextLength, Long maxFileBytes) {
            this(eventTypes, contentTypes, actions, idempotency, receiptQuery, maxTextLength, maxFileBytes,
                Handover.PROVIDER, CompletionNotice.NONE);
        }
        public Delivery {
            eventTypes = eventTypes == null ? Set.of() : Set.copyOf(eventTypes);
            contentTypes = contentTypes == null ? Set.of() : Set.copyOf(contentTypes);
            idempotency = idempotency == null ? Idempotency.LOCAL_ONLY : idempotency;
            handover = handover == null ? Handover.PROVIDER : handover;
            completionNotice = completionNotice == null ? CompletionNotice.NONE : completionNotice;
            if (handover == Handover.DURABLE_INBOX && !receiptQuery) throw new IllegalArgumentException("Inbox requires receipt queries");
        }

        public boolean supports(EventType type) {
            return eventTypes.contains(type);
        }

        public boolean supports(ContentPart.PartType type) {
            return contentTypes.contains(type);
        }
    }

    public enum Idempotency {
        PROVIDER, LOCAL_ONLY, NONE
    }
    public enum Handover { PROVIDER, DURABLE_INBOX }
    public enum CompletionNotice { STRUCTURED, TEXT, NONE }

    public boolean canDeliver() {
        return delivery != null;
    }
}
