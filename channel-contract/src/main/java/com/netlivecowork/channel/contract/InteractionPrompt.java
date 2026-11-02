package com.netlivecowork.channel.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;

/**
 * 审批与问题组提示（data-contracts.md §7）。展示标签不作身份，推荐项不等于用户选择（INV-59）。
 */
public sealed interface InteractionPrompt permits InteractionPrompt.Approval, InteractionPrompt.Questions {

    List<ContentPart> content();

    Set<AllowedResponse> allowedResponses();

    /** 审批提示。operation 是脱敏后的展示参数，不是完整执行参数。 */
    record Approval(List<ContentPart> content, Operation operation, Set<AllowedResponse> allowedResponses)
        implements InteractionPrompt {
        public Approval {
            content = content == null ? List.of() : List.copyOf(content);
            allowedResponses = allowedResponses == null || allowedResponses.isEmpty()
                ? Set.of(AllowedResponse.APPROVE, AllowedResponse.REJECT)
                : Set.copyOf(allowedResponses);
            if (allowedResponses.contains(AllowedResponse.ANSWER)) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "approval prompt cannot accept answers");
            }
        }

        public record Operation(String name, JsonNode arguments, String editSchemaRef) {
            public Operation {
                Producer.requireText(name, "operation.name");
                arguments = arguments == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : arguments;
                editSchemaRef = editSchemaRef == null || editSchemaRef.isBlank() ? null : editSchemaRef;
            }

            public boolean editable() {
                return editSchemaRef != null;
            }
        }
    }

    /** 问题组提示。questions 非空；questionId 事项内唯一，optionId 题目内唯一。 */
    record Questions(List<ContentPart> content, List<QuestionItem> questions,
                     Set<AllowedResponse> allowedResponses) implements InteractionPrompt {
        public Questions {
            content = content == null ? List.of() : List.copyOf(content);
            if (questions == null || questions.isEmpty()) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "questions must not be empty");
            }
            questions = List.copyOf(questions);
            Set<String> ids = new java.util.HashSet<>();
            for (QuestionItem item : questions) {
                if (!ids.add(item.questionId())) {
                    throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                        "duplicate questionId: " + item.questionId());
                }
            }
            allowedResponses = allowedResponses == null || allowedResponses.isEmpty()
                ? Set.of(AllowedResponse.ANSWER)
                : Set.copyOf(allowedResponses);
        }
    }

    enum AllowedResponse {
        APPROVE("approve"), REJECT("reject"), ANSWER("answer");

        private final String wire;

        AllowedResponse(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }
}
