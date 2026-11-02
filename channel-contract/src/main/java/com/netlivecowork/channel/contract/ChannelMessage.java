package com.netlivecowork.channel.contract;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一条可展示消息。messageRef 由 L2 签发；replyToMessageRef 表达回复关系，与 message_reference 独立。
 */
public record ChannelMessage(String messageRef, List<ContentPart> content, String replyToMessageRef) {

    public ChannelMessage {
        Producer.requireText(messageRef, "message.messageRef");
        if (content == null || content.isEmpty()) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "message.content must not be empty");
        }
        content = List.copyOf(content);
        Set<String> partIds = new HashSet<>();
        for (ContentPart part : content) {
            if (!partIds.add(part.partId())) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "duplicate partId in message: " + part.partId());
            }
        }
        replyToMessageRef = replyToMessageRef == null || replyToMessageRef.isBlank() ? null : replyToMessageRef;
    }

    public static ChannelMessage of(String messageRef, ContentPart... parts) {
        return new ChannelMessage(messageRef, List.of(parts), null);
    }

    /** 供 L1 渲染的纯文本投影；平台只能表达文本时使用，不做内容裁剪替换（INV-38）。 */
    public String plainText() {
        StringBuilder text = new StringBuilder();
        for (ContentPart part : content) {
            if (part instanceof ContentPart.Text t) {
                text.append(t.text());
            }
        }
        return text.toString();
    }

    public boolean hasOnlyText() {
        for (ContentPart part : content) {
            if (!(part instanceof ContentPart.Text)) {
                return false;
            }
        }
        return true;
    }

    /** 保持原有顺序复制，供需要可变列表的适配代码使用。 */
    public List<ContentPart> mutableContent() {
        return new ArrayList<>(content);
    }
}
