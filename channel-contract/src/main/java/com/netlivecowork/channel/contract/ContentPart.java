package com.netlivecowork.channel.contract;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 有序消息内容：文本、图片、文件、消息引用四类（data-contracts.md §4）。
 * 内容数组保序，不分类重排；partId 在一条消息正文及引用预览内唯一，首次规范化后固定。
 */
public sealed interface ContentPart permits BasicContentPart, ContentPart.MessageReference {

    String partId();

    PartType type();

    enum PartType {
        TEXT("text"), IMAGE("image"), FILE("file"), MESSAGE_REFERENCE("message_reference");

        private final String wire;

        PartType(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /** 文本 part。format 表达原文语义，渲染转义归 L1。 */
    record Text(String partId, String text, Format format) implements BasicContentPart {
        public Text {
            Producer.requireText(partId, "text.partId");
            if (text == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "text.text is required");
            }
            format = format == null ? Format.PLAIN : format;
        }

        public Text(String partId, String text) {
            this(partId, text, Format.PLAIN);
        }

        @Override
        public PartType type() {
            return PartType.TEXT;
        }

        public enum Format {
            PLAIN("plain"), MARKDOWN("markdown");

            private final String wire;

            Format(String wire) {
                this.wire = wire;
            }

            public String wire() {
                return wire;
            }
        }
    }

    /** 图片 part：展示/理解语义，不是文件传输凭证（INV-41）。 */
    record Image(String partId, String fileRef, String alt) implements BasicContentPart {
        public Image {
            Producer.requireText(partId, "image.partId");
            Producer.requireText(fileRef, "image.fileRef");
            alt = alt == null || alt.isBlank() ? null : alt;
        }

        @Override
        public PartType type() {
            return PartType.IMAGE;
        }
    }

    /** 附件 part。与 Image 可引用同一固定文件版本。 */
    record FilePart(String partId, String fileRef) implements BasicContentPart {
        public FilePart {
            Producer.requireText(partId, "file.partId");
            Producer.requireText(fileRef, "file.fileRef");
        }

        @Override
        public PartType type() {
            return PartType.FILE;
        }
    }

    /** 正文引用已有消息；不切换逻辑目标、不继承原发送者权限。 */
    record MessageReference(String partId, String messageRef, Preview preview) implements ContentPart {
        public MessageReference {
            Producer.requireText(partId, "reference.partId");
            Producer.requireText(messageRef, "reference.messageRef");
        }

        @Override
        public PartType type() {
            return PartType.MESSAGE_REFERENCE;
        }

        /** 引用时的内容快照，不递归引用；无法取得全文时 truncated=true。 */
        public record Preview(List<BasicContentPart> content, boolean truncated) {
            public Preview {
                content = content == null ? List.of() : List.copyOf(content);
            }
        }
    }
}
