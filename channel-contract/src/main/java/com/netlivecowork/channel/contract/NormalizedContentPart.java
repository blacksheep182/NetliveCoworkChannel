package com.netlivecowork.channel.contract;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * L3 规范化后的可展示内容：使用后端对象标识，不含内部 messageRef/fileRef。L2 负责签发内部引用。
 */
public sealed interface NormalizedContentPart permits NormalizedContentPart.Text, NormalizedContentPart.Image,
    NormalizedContentPart.FilePart, NormalizedContentPart.MessageReference {

    String partId();

    ContentPart.PartType type();

    record Text(String partId, String text, ContentPart.Text.Format format) implements NormalizedContentPart {
        public Text {
            Producer.requireText(partId, "text.partId");
            if (text == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "text.text is required");
            }
            format = format == null ? ContentPart.Text.Format.PLAIN : format;
        }

        @Override
        public ContentPart.PartType type() {
            return ContentPart.PartType.TEXT;
        }
    }

    record Image(String partId, BackendFileGrant file, String alt) implements NormalizedContentPart {
        public Image {
            Producer.requireText(partId, "image.partId");
            if (file == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "image.file is required");
            }
            alt = alt == null || alt.isBlank() ? null : alt;
        }

        @Override
        public ContentPart.PartType type() {
            return ContentPart.PartType.IMAGE;
        }
    }

    record FilePart(String partId, BackendFileGrant file) implements NormalizedContentPart {
        public FilePart {
            Producer.requireText(partId, "file.partId");
            if (file == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "file.file is required");
            }
        }

        @Override
        public ContentPart.PartType type() {
            return ContentPart.PartType.FILE;
        }
    }

    record MessageReference(String partId, String backendMessageId, List<Text> preview, boolean truncated)
        implements NormalizedContentPart {
        public MessageReference {
            Producer.requireText(partId, "reference.partId");
            Producer.requireText(backendMessageId, "reference.backendMessageId");
            preview = preview == null ? List.of() : List.copyOf(preview);
            Set<String> ids = new HashSet<>();
            for (Text part : preview) {
                if (!ids.add(part.partId())) {
                    throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                        "duplicate preview partId: " + part.partId());
                }
            }
        }

        @Override
        public ContentPart.PartType type() {
            return ContentPart.PartType.MESSAGE_REFERENCE;
        }
    }
}
