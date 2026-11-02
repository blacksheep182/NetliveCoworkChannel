package com.netlivecowork.channel.l3;

import com.netlivecowork.channel.contract.IntakeAck;
import com.netlivecowork.channel.contract.VerifiedBackendEvent;
import com.netlivecowork.channel.contract.json.PacketJson;
import com.netlivecowork.channel.infra.Ids;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * L3 → L2 的可恢复转交泵（INV-31、64）。任务执行事实与送达状态独立：转交失败只改送达状态，
 * 事件保留并按退避重试，达到上限暂停而不丢弃。
 */
final class ForwardPump {

    private final EventStore events;
    private final com.netlivecowork.channel.contract.port.ApplicationIntakePort intake;
    private final L3Settings settings;
    private final String owner = "l3-forward-" + Ids.random(8);

    private volatile Thread worker;
    private volatile boolean running;

    ForwardPump(EventStore events, com.netlivecowork.channel.contract.port.ApplicationIntakePort intake,
                L3Settings settings) {
        this.events = events;
        this.intake = intake;
        this.settings = settings;
    }

    /** 一次 tick：领取待转交事件、在事务外调用 L2、回写确认。返回本次确认送达的事件数。 */
    int forwardOnce(int limit) {
        if (limit < 1 || intake == null) {
            return 0;
        }
        List<EventStore.Claimed> claimed = events.claim(limit, owner);
        int acked = 0;
        for (EventStore.Claimed row : claimed) {
            VerifiedBackendEvent event;
            try {
                event = PacketJson.decVerifiedEvent(row.verifiedJson());
            } catch (RuntimeException e) {
                // 已持久记录无法解码时保留原记录并暴露原因，不静默删除。
                events.fail(row, owner, "stored backend event cannot be decoded");
                continue;
            }
            try {
                IntakeAck ack = intake.receiveEvent(event).toCompletableFuture()
                    .get(Math.max(1_000L, settings.httpTimeoutMs()), TimeUnit.MILLISECONDS);
                if (ack != null && ack.accepted()) {
                    events.ack(row.backendEventRef(), owner);
                    acked++;
                } else {
                    events.fail(row, owner, ack == null ? "application intake returned no acknowledgement"
                        : messageOf(ack.error()));
                }
            } catch (TimeoutException e) {
                events.fail(row, owner, "application intake acknowledgement timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                events.fail(row, owner, "event forwarding was interrupted");
                return acked;
            } catch (ExecutionException e) {
                events.fail(row, owner, message(e.getCause()));
            } catch (RuntimeException e) {
                events.fail(row, owner, "application intake call failed");
            }
        }
        return acked;
    }

    /** 虚拟线程后台泵：1s 周期，单次最多 50 条。 */
    void start() {
        if (worker != null) {
            return;
        }
        running = true;
        worker = Thread.ofVirtual().name("l3-event-forward").start(() -> {
            while (running) {
                try {
                    forwardOnce(50);
                } catch (RuntimeException e) {
                    // 事件仍持久保留；下一轮继续，不以异常终止泵。
                }
                try {
                    Thread.sleep(1_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    void close() {
        running = false;
        Thread current = worker;
        worker = null;
        if (current != null) {
            current.interrupt();
            try {
                current.join(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String messageOf(com.netlivecowork.channel.contract.ChannelError error) {
        return error == null || error.message() == null ? "application intake rejected the event" : error.message();
    }

    private static String message(Throwable error) {
        if (error == null) {
            return "application intake rejected the event";
        }
        String text = error.getMessage();
        return text == null || text.isBlank() ? error.getClass().getSimpleName() : text;
    }
}
