package com.netlivecowork.channel.contract;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 单题答案。选择题以 questionId + optionIds 关联，不从展示文字反推（data-contracts.md §4）。 */
public sealed interface QuestionAnswer permits QuestionAnswer.TextAnswer, QuestionAnswer.ChoiceAnswer {

    String questionId();

    record TextAnswer(String questionId, List<ContentPart> content) implements QuestionAnswer {
        public TextAnswer {
            Producer.requireText(questionId, "answer.questionId");
            if (content == null || content.isEmpty()) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "text answer requires content");
            }
            content = List.copyOf(content);
        }
    }

    record ChoiceAnswer(String questionId, List<String> optionIds, String otherText) implements QuestionAnswer {
        public ChoiceAnswer {
            Producer.requireText(questionId, "answer.questionId");
            optionIds = optionIds == null ? List.of() : List.copyOf(optionIds);
            Set<String> ids = new HashSet<>();
            for (String id : optionIds) {
                Producer.requireText(id, "answer.optionId");
                if (!ids.add(id)) {
                    throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                        "duplicate optionId in answer: " + id);
                }
            }
            otherText = otherText == null || otherText.isBlank() ? null : otherText;
            if (optionIds.isEmpty() && otherText == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "choice answer requires optionIds or otherText");
            }
        }
    }
}
