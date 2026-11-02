package com.netlivecowork.channel.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * 用户对一次人工事项的响应。kind 必须在 allowedResponses 内；整组提交（INV-60）。
 */
public sealed interface InteractionResponse permits InteractionResponse.Approve, InteractionResponse.Reject,
    InteractionResponse.Answer {

    String kind();

    /** 批准。modifiedArguments 仅在 editSchemaRef 存在且有修改权时允许。 */
    record Approve(JsonNode modifiedArguments) implements InteractionResponse {
        public Approve {
            modifiedArguments = modifiedArguments == null || modifiedArguments.isNull()
                ? null : modifiedArguments;
        }

        @Override
        public String kind() {
            return "approve";
        }

        public static Approve plain() {
            return new Approve(null);
        }
    }

    /** 拒绝。reason 映射后端 message 字段。 */
    record Reject(String reason) implements InteractionResponse {
        public Reject {
            reason = reason == null || reason.isBlank() ? null : reason;
        }

        @Override
        public String kind() {
            return "reject";
        }
    }

    /** 问题组答案。answers 覆盖全部必答题，可省略选答题。 */
    record Answer(List<QuestionAnswer> answers) implements InteractionResponse {
        public Answer {
            if (answers == null || answers.isEmpty()) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "answer.answers must not be empty");
            }
            answers = List.copyOf(answers);
            var ids = new java.util.HashSet<String>();
            for (QuestionAnswer answer : answers) {
                if (!ids.add(answer.questionId())) {
                    throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                        "duplicate questionId in answers: " + answer.questionId());
                }
            }
        }

        @Override
        public String kind() {
            return "answer";
        }
    }
}
