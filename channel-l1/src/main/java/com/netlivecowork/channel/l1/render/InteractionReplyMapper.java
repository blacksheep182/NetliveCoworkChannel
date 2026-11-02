package com.netlivecowork.channel.l1.render;

import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.json.ContentJson;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** L1 presentation conversion only. L2 remains the authority for scope, version and permission. */
public final class InteractionReplyMapper {
    private InteractionReplyMapper() {}

    public static ChannelOperation.InteractionRespond form(String ref,long revision,JsonNode response) {
        return operation(ref,revision,ContentJson.decodeResponse(response));
    }
    private static ChannelOperation.InteractionRespond operation(String ref,long revision,InteractionResponse response) {
        return new ChannelOperation.InteractionRespond(new InteractionRespondPayload(ref,revision,response));
    }

    /** Text is interpreted against a specific delivered prompt, never the current session. */
    public static ChannelOperation.InteractionRespond reply(InteractionRequiredPayload pending,String reply) {
        String value=reply.strip();
        InteractionResponse response;
        if(pending.prompt() instanceof InteractionPrompt.Approval) {
            response=switch(value.toLowerCase(Locale.ROOT)) {
                case "同意","批准","approve" -> InteractionResponse.Approve.plain();
                case "拒绝","reject" -> new InteractionResponse.Reject(null);
                default -> throw invalid("请回复同意或拒绝。");
            };
        } else {
            var questions=((InteractionPrompt.Questions)pending.prompt()).questions();
            var supplied=new LinkedHashMap<Integer,String>();
            if(questions.size()==1&&!value.matches("(?s)^\\d+[:：].*"))supplied.put(1,value);
            else for(String part:value.split("[;；\\n]")) {
                String[] pair=part.strip().split("[:：]",2);
                try {
                    if(pair.length!=2||supplied.putIfAbsent(Integer.parseInt(pair[0].strip()),pair[1].strip())!=null)
                        throw invalid("请按 1:答案;2:答案 整组回复。");
                } catch(NumberFormatException e){throw invalid("题号无效。");}
            }
            if(supplied.keySet().stream().anyMatch(n->n<1||n>questions.size()))throw invalid("题号无效。");
            var answers=new ArrayList<QuestionAnswer>();
            for(int n=1;n<=questions.size();n++) {
                var question=questions.get(n-1);String answer=supplied.get(n);
                if(answer==null||answer.isBlank()) {
                    if(question.required())throw invalid("请回答全部必答题。");
                    continue;
                }
                if(question.input() instanceof QuestionItem.Input.ChoiceInput choice) {
                    var ids=new ArrayList<String>();String other=null;
                    for(String token:answer.split("[,，]")) {
                        String t=token.strip();
                        if(t.startsWith("其他=")) {
                            if(!choice.allowOther()||other!=null)throw invalid("其他答案无效。");
                            other=t.substring(3).strip();continue;
                        }
                        int option;
                        try{option=Integer.parseInt(t);}catch(NumberFormatException e){throw invalid("请选择选项序号；其他答案使用 其他=内容。");}
                        if(option<1||option>choice.options().size())throw invalid("选项序号无效。");
                        ids.add(choice.options().get(option-1).optionId());
                    }
                    if(!choice.multiple()&&ids.size()+(other==null?0:1)!=1)throw invalid("单选题只能选择一项。");
                    answers.add(new QuestionAnswer.ChoiceAnswer(question.questionId(),ids,other));
                } else answers.add(new QuestionAnswer.TextAnswer(question.questionId(),List.of(new ContentPart.Text("answer",answer))));
            }
            response=new InteractionResponse.Answer(answers);
        }
        return operation(pending.interactionRef(),pending.revision(),response);
    }
    private static ChannelContractException invalid(String message) {
        return new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,message);
    }
}
