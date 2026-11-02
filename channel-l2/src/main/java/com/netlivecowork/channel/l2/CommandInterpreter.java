package com.netlivecowork.channel.l2;

import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.ChannelOperation;
import com.netlivecowork.channel.contract.ContentPart;
import com.netlivecowork.channel.contract.InteractionPrompt;
import com.netlivecowork.channel.contract.InteractionRespondPayload;
import com.netlivecowork.channel.contract.InteractionResponse;
import com.netlivecowork.channel.contract.IngressRequest;
import com.netlivecowork.channel.contract.QuestionAnswer;
import com.netlivecowork.channel.contract.QuestionItem;
import com.netlivecowork.channel.contract.json.ContentJson;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.l2.store.DirectoryRepository;
import com.netlivecowork.channel.l2.store.RequestRepository;
import java.util.ArrayList;
import java.util.List;

/**
 * 普通文字输入的确定性命令解释（l1-adapter.md §5.2）：只识别完整、确定性的命令语法；
 * 未知或有歧义返回明确错误，不交给模型猜测管理动作。
 */
public final class CommandInterpreter {

    private static final String HELP = "supported commands: /workspaces, /workspace <number|sessionWorkspaceRef>, /list, /use <number|targetRef>, /status, "
        + "/cancel <taskRef>, /approve <interactionRef>, /reject <interactionRef> [reason], "
        + "/answer <interactionRef> <text>";

    private final DirectoryRepository directory;
    private final RequestRepository requests;
    private final GrantService grants;

    public CommandInterpreter(DirectoryRepository directory, RequestRepository requests, GrantService grants) {
        this.directory = directory;
        this.requests = requests;
        this.grants = grants;
    }

    public record Interpreted(ChannelOperation operation, String rawText) {
    }

    /** 非 commandAware 输入或非命令文本原样作为 input.submit 返回。 */
    public Interpreted interpret(String tenantRef, String workspaceRef, String scopeRef, IngressRequest request) {
        ChannelOperation operation = request.operation();
        if (!(operation instanceof ChannelOperation.InputSubmit submit)
            || submit.interpretation() != ChannelOperation.Interpretation.COMMAND_AWARE) {
            return new Interpreted(operation, null);
        }
        String text = submit.content().stream()
            .filter(part -> part instanceof ContentPart.Text)
            .map(part -> ((ContentPart.Text) part).text())
            .reduce("", String::concat)
            .trim();
        if (!text.startsWith("/")) {
            return new Interpreted(operation, null);
        }
        String[] parts = text.split("\\s+", 3);
        String command = parts[0];
        String first = parts.length > 1 ? parts[1] : "";
        String rest = parts.length > 2 ? parts[2] : "";
        return switch (command) {
            case "/workspaces" -> new Interpreted(new ChannelOperation.WorkspaceList(),text);
            case "/workspace" -> new Interpreted(workspace(tenantRef,workspaceRef,scopeRef,first),text);
            case "/list" -> new Interpreted(
                new ChannelOperation.SessionList(null, null), text);
            case "/use" -> new Interpreted(select(tenantRef, workspaceRef, scopeRef, first, request.identity().callerRef()), text);
            case "/status" -> new Interpreted(status(tenantRef, workspaceRef, scopeRef), text);
            case "/cancel" -> new Interpreted(cancel(first, rest), text);
            case "/approve" -> new Interpreted(respond(tenantRef, workspaceRef, first,
                new InteractionResponse.Approve(null)), text);
            case "/reject" -> new Interpreted(respond(tenantRef, workspaceRef, first,
                new InteractionResponse.Reject(rest)), text);
            case "/answer" -> {
                InteractionRespondPayload payload = answer(tenantRef, workspaceRef, first, rest);
                yield new Interpreted(new ChannelOperation.InteractionRespond(payload), text);
            }
            default -> throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "unknown command: " + command + "; " + HELP);
        };
    }

    private ChannelOperation select(String tenantRef, String workspaceRef, String scopeRef, String argument, String callerRef) {
        if (scopeRef == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "/use requires a bound scope; " + HELP);
        }
        if (argument.isBlank()) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "/use requires a target number or targetRef");
        }
        String targetRef = argument;
        try {
            int index = Integer.parseInt(argument);
            var targets = directory.navigationList(scopeRef,"session");
            if (index < 1 || index > targets.size()) throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "Run /list first, then choose a number from that displayed page");
            targetRef = targets.get(index - 1);
        } catch (NumberFormatException ignored) {
            // 参数已是 targetRef
        }
        DirectoryRepository.BindingRow binding = directory.binding(tenantRef, workspaceRef, scopeRef);
        long revision = binding == null ? 0L : binding.revision();
        return new ChannelOperation.SessionSelect(targetRef, revision);
    }

    private ChannelOperation workspace(String tenant,String deployment,String scope,String argument) {
        if(scope==null || argument.isBlank())throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,"Use /workspaces then /workspace <number|sessionWorkspaceRef>");
        String ref=argument;
        try {
            int number=Integer.parseInt(argument);var items=directory.navigationList(scope,"workspace");
            if(number<1||number>items.size())throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,"Choose a workspace from the last /workspaces result");
            ref=items.get(number-1);
        } catch(NumberFormatException ignored) {}
        var binding=directory.binding(tenant,deployment,scope);
        return new ChannelOperation.WorkspaceSelect(ref,binding==null?0:binding.revision());
    }

    private ChannelOperation status(String tenantRef, String workspaceRef, String scopeRef) {
        if (scopeRef == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "/status requires a bound scope");
        }
        DirectoryRepository.BindingRow binding = directory.binding(tenantRef, workspaceRef, scopeRef);
        if (binding == null || binding.targetRef()==null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "no target is bound to this scope; use /use first");
        }
        return new ChannelOperation.SessionGet(binding.targetRef());
    }

    private ChannelOperation cancel(String taskRef, String reason) {
        if (taskRef.isBlank()) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "/cancel requires an explicit taskRef; " + HELP);
        }
        return new ChannelOperation.TaskCancel(taskRef, reason.isBlank() ? null : reason);
    }

    private ChannelOperation respond(String tenantRef, String workspaceRef, String interactionRef,
                                     InteractionResponse response) {
        if (interactionRef.isBlank()) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "command requires an interactionRef; " + HELP);
        }
        RequestRepository.InteractionRow interaction = interaction(tenantRef, workspaceRef, interactionRef);
        return new ChannelOperation.InteractionRespond(
            new InteractionRespondPayload(interactionRef, interaction.revision(), response));
    }

    private InteractionRespondPayload answer(String tenantRef, String workspaceRef, String interactionRef,
                                             String text) {
        if (interactionRef.isBlank() || text.isBlank()) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "/answer requires <interactionRef> <text>");
        }
        RequestRepository.InteractionRow interaction = interaction(tenantRef, workspaceRef, interactionRef);
        InteractionPrompt prompt = ContentJson.decodePrompt(Json.read(interaction.promptJson()));
        InteractionResponse.Answer answer = textAnswer(prompt, text);
        return new InteractionRespondPayload(interactionRef, interaction.revision(), answer);
    }

    private RequestRepository.InteractionRow interaction(String tenantRef, String workspaceRef,
                                                         String interactionRef) {
        RequestRepository.InteractionRow interaction = requests.interaction(interactionRef);
        if (interaction == null || !interaction.tenantRef().equals(tenantRef)
            || !interaction.workspaceRef().equals(workspaceRef)) {
            throw new ChannelContractException(ChannelErrorCode.INTERACTION_STALE,
                "unknown interaction: " + interactionRef);
        }
        if (!"PENDING".equals(interaction.status())) {
            throw new ChannelContractException(ChannelErrorCode.INTERACTION_STALE,
                "interaction is no longer pending");
        }
        return interaction;
    }

    /**
     * 单题文本兼容映射：选择题按 optionId / 序号 / 标签匹配；自由文本直接作为答案。
     * 多题必须使用结构化提交，不做猜测性拆题（INV-59、INV-60）。
     */
    private InteractionResponse.Answer textAnswer(InteractionPrompt prompt, String text) {
        if (!(prompt instanceof InteractionPrompt.Questions questions)) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "this interaction is an approval; use /approve or /reject");
        }
        if (questions.questions().size() != 1) {
            throw new ChannelContractException(ChannelErrorCode.UNSUPPORTED_CAPABILITY,
                "multi-question interactions require structured answers");
        }
        QuestionItem item = questions.questions().get(0);
        List<QuestionAnswer> answers = new ArrayList<>();
        if (item.input() instanceof QuestionItem.Input.ChoiceInput choice) {
            List<String> optionIds = new ArrayList<>();
            for (int i = 0; i < choice.options().size(); i++) {
                QuestionItem.ChoiceOption option = choice.options().get(i);
                if (option.optionId().equals(text) || option.label().equals(text)
                    || String.valueOf(i + 1).equals(text)) {
                    optionIds.add(option.optionId());
                }
            }
            if (optionIds.isEmpty()) {
                if (!choice.allowOther()) {
                    throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                        "answer does not match any option id, index or label");
                }
                answers.add(new QuestionAnswer.ChoiceAnswer(item.questionId(), List.of(), text));
            } else {
                answers.add(new QuestionAnswer.ChoiceAnswer(item.questionId(), optionIds, null));
            }
        } else {
            answers.add(new QuestionAnswer.TextAnswer(item.questionId(),
                List.of(new ContentPart.Text("a1", text))));
        }
        return new InteractionResponse.Answer(answers);
    }
}
