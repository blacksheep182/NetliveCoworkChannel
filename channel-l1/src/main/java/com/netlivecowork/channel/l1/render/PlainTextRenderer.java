package com.netlivecowork.channel.l1.render;

import com.netlivecowork.channel.contract.ChannelDelivery;
import com.netlivecowork.channel.contract.ContentPart;
import com.netlivecowork.channel.contract.EventPacket;
import com.netlivecowork.channel.contract.EventPayload;
import com.netlivecowork.channel.contract.InteractionPrompt;
import com.netlivecowork.channel.contract.InteractionRequiredPayload;
import com.netlivecowork.channel.contract.QuestionItem;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * EventPacket → 纯文本。文本平台无法表达非文本内容或交互动作时必须显式失败（INV-38），
 * 不得丢弃附件、图片或按钮后报告投递成功。渲染只使用包内已存在的展示内容，
 * 不暴露内部引用、目的地或凭证（INV-40）。
 */
public final class PlainTextRenderer {

    /** 平台不能表达必需内容；调用方转换为 UNSUPPORTED_CONTENT，不得静默裁剪。 */
    public static final class UnsupportedContent extends RuntimeException {
        public UnsupportedContent(String message) {
            super(message);
        }
    }

    private PlainTextRenderer() {
    }

    public static String body(ChannelDelivery delivery) {
        var receipt = delivery.completionReceipt();
        if (receipt == null) return body(delivery.packet());

        var lines = new StringJoiner("\n");
        lines.add(switch (receipt.origin()) {
            case "session_subscription" -> "来自会话订阅";
            case "task_subscription" -> "来自任务订阅";
            default -> "来自会话回复";
        });
        addField(lines, "工作空间", receipt.workspaceLabel());
        addField(lines, "会话", receipt.sessionLabel());
        addField(lines, "用户提问", receipt.query());
        lines.add("任务状态：" + switch (receipt.status()) {
            case "failed" -> "失败";
            case "cancelled" -> "已取消";
            default -> "已完成";
        });
        if (receipt.result() != null && !receipt.result().isBlank()) {
            lines.add("执行结果：\n" + receipt.result());
        } else {
            lines.add("可在原会话查看执行详情。");
        }
        return lines.toString();
    }

    private static void addField(StringJoiner lines, String label, String value) {
        if (value != null && !value.isBlank()) lines.add(label + "：" + value);
    }

    public static String deliveryText(ChannelDelivery delivery) {
        String body = body(delivery);
        return delivery.presentation().notification() == null
            ? withSourceLabel(delivery.presentation().sourceLabel(), body) : body;
    }

    /** 渲染正文（不含来源标注）。 */
    public static String body(EventPacket packet) {
        return switch (packet.payload()) {
            case EventPayload.MessageReady p -> texts(p.message().content());
            case EventPayload.TaskStarted p -> "任务已开始。";
            case EventPayload.TaskProgress p -> progress(p);
            case EventPayload.TaskWaiting p -> "任务等待中" + suffix(p.reason()) + "。";
            case EventPayload.TaskCompleted p -> "任务已完成。";
            case EventPayload.TaskFailed p -> "任务失败" + suffix(p.error().message()) + "。";
            case EventPayload.TaskCancelled p -> "任务已取消" + suffix(p.reason()) + "。";
            case EventPayload.TaskArtifact p -> "任务产物已就绪，共 " + p.fileRefs().size() + " 个文件。";
            case EventPayload.InteractionRequired p -> required(p.payload());
            case EventPayload.InteractionResolved p -> "人工事项已处理（" + outcome(p.outcome()) + "）。";
            case EventPayload.InteractionExpired p -> "人工事项已过期" + suffix(p.reason()) + "。";
            case EventPayload.InteractionCancelled p -> "人工事项已取消" + suffix(p.reason()) + "。";
        };
    }

    /** INV-40：投递必须保留可信来源展示。 */
    public static String withSourceLabel(String sourceLabel, String body) {
        String prefix = "【" + sourceLabel + "】";
        return body == null || body.isBlank() ? prefix : prefix + "\n" + body;
    }

    /** 正文只允许文本 part；任一部分平台无法表达即整体失败（INV-38）。 */
    private static String texts(List<ContentPart> content) {
        StringBuilder out = new StringBuilder();
        for (ContentPart part : content) {
            if (!(part instanceof ContentPart.Text text)) {
                throw new UnsupportedContent("text-only platform cannot deliver " + part.type().wire() + " content");
            }
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(text.text());
        }
        return out.toString();
    }

    private static String progress(EventPayload.TaskProgress progress) {
        StringBuilder out = new StringBuilder("任务进行中");
        if (progress.summary() != null) {
            out.append("：").append(progress.summary());
        }
        if (progress.progress() != null) {
            out.append("（").append(Math.round(progress.progress() * 100)).append("%）");
        }
        return out.append('。').toString();
    }

    private static String required(InteractionRequiredPayload payload) {
        StringBuilder out = new StringBuilder();
        String intro = texts(payload.prompt().content());
        if (!intro.isBlank()) {
            out.append(intro).append('\n');
        }
        if (payload.prompt() instanceof InteractionPrompt.Approval approval) {
            out.append("需要确认的操作：").append(approval.operation().name());
            if(!approval.operation().arguments().isMissingNode())out.append("\n参数：").append(approval.operation().arguments().toPrettyString());
            if (approval.operation().editable()) {
                out.append("（参数可编辑）");
            }
            out.append('\n').append("允许的回应：").append(responses(approval.allowedResponses()));
        } else if (payload.prompt() instanceof InteractionPrompt.Questions questions) {
            int number=0;
            for (QuestionItem question : questions.questions()) {
                out.append(++number).append(". 问题：").append(texts(question.content()));
                appendInput(out, question.input());
                out.append('\n');
            }
            out.append("允许的回应：").append(responses(questions.allowedResponses()));
        }
        // 文本平台没有按钮：只说明该回应绑定本次事项，不替用户提交（INV-05、INV-59）。
        out.append('\n').append("请在原会话中回应，回应绑定到本次事项。");
        return out.toString();
    }

    private static void appendInput(StringBuilder out, QuestionItem.Input input) {
        if (input instanceof QuestionItem.Input.ChoiceInput choice) {
            out.append(choice.multiple() ? "（多选" : "（单选");
            if (choice.allowOther()) {
                out.append("，可填写其他内容");
            }
            out.append("）");
            int number=0;
            for (QuestionItem.ChoiceOption option : choice.options()) {
                out.append("\n  ").append(++number).append(". ").append(option.label());
                if (option.description() != null) {
                    out.append("：").append(option.description());
                }
                if (option.recommended()) {
                    out.append("（推荐）");
                }
            }
        } else {
            out.append("（自由文本）");
        }
    }

    private static String responses(Set<InteractionPrompt.AllowedResponse> allowed) {
        StringBuilder out = new StringBuilder();
        for (InteractionPrompt.AllowedResponse response : allowed) {
            if (!out.isEmpty()) {
                out.append('、');
            }
            out.append(switch (response) {
                case APPROVE -> "批准";
                case REJECT -> "拒绝";
                case ANSWER -> "回答";
            });
        }
        return out.isEmpty() ? "（未声明）" : out.toString();
    }

    private static String outcome(EventPayload.Outcome outcome) {
        return switch (outcome) {
            case APPROVED -> "已批准";
            case REJECTED -> "已拒绝";
            case ANSWERED -> "已回答";
        };
    }

    private static String suffix(String value) {
        return value == null || value.isBlank() ? "" : "：" + value;
    }
}
