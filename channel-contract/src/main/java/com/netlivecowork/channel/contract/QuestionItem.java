package com.netlivecowork.channel.contract;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 单个问题。required、allowOther 等按已确认后端能力映射，不猜默认策略。 */
public record QuestionItem(String questionId, List<ContentPart> content, boolean required, Input input) {
    public QuestionItem {
        Producer.requireText(questionId, "question.questionId");
        content = content == null ? List.of() : List.copyOf(content);
        if (input == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "question.input is required");
        }
    }

    /** 输入形态：自由文本或选择题。 */
    public sealed interface Input permits QuestionItem.Input.TextInput, QuestionItem.Input.ChoiceInput {
        boolean choice();

        record TextInput() implements Input {
            @Override
            public boolean choice() {
                return false;
            }
        }

        record ChoiceInput(boolean multiple, boolean allowOther, List<ChoiceOption> options) implements Input {
            public ChoiceInput {
                if (options == null || options.isEmpty()) {
                    throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                        "choice input requires options");
                }
                options = List.copyOf(options);
                Set<String> ids = new HashSet<>();
                for (ChoiceOption option : options) {
                    if (!ids.add(option.optionId())) {
                        throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                            "duplicate optionId: " + option.optionId());
                    }
                }
            }

            @Override
            public boolean choice() {
                return true;
            }
        }
    }

    /** 选项。recommended 只影响展示（INV-59）。 */
    public record ChoiceOption(String optionId, String label, String description, boolean recommended) {
        public ChoiceOption {
            Producer.requireText(optionId, "option.optionId");
            Producer.requireText(label, "option.label");
            description = description == null || description.isBlank() ? null : description;
        }

        public static ChoiceOption of(String optionId, String label) {
            return new ChoiceOption(optionId, label, null, false);
        }
    }
}
