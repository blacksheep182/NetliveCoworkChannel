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
import com.netlivecowork.channel.infra.Digests;
import com.netlivecowork.channel.l2.store.DirectoryRepository;
import com.netlivecowork.channel.l2.store.RequestRepository;
import java.util.ArrayList;
import java.util.List;

/**
 * 普通文字输入的确定性命令解释（l1-adapter.md §5.2）：只识别完整、确定性的命令语法；
 * 未知或有歧义返回明确错误，不交给模型猜测管理动作。
 */
public final class CommandInterpreter {

    private static final String HELP = "supported commands: /new <first message>, /workspaces, /workspace <number|sessionWorkspaceRef>, /list, /use <number|targetRef>, /status, /resume, "
        + "/cancel <taskRef>, /approve, /reject, /answer <text>";

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
            case "/new" -> {
                var binding=directory.binding(tenantRef,workspaceRef,scopeRef);
                if(binding==null || binding.sessionWorkspaceRef()==null || text.length()<=5)
                    throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,"Select a workspace with /workspaces and /workspace, then use /new <first message>");
                yield new Interpreted(new ChannelOperation.SessionCreate(binding.sessionWorkspaceRef(),text.substring(5).strip(),binding.revision()),text);
            }
            case "/workspaces" -> new Interpreted(new ChannelOperation.WorkspaceList(),text);
            case "/workspace" -> new Interpreted(workspace(tenantRef,workspaceRef,scopeRef,first),text);
            case "/list" -> new Interpreted(
                new ChannelOperation.SessionList(null, null), text);
            case "/use" -> new Interpreted(select(tenantRef, workspaceRef, scopeRef, first, request.identity().callerRef()), text);
            case "/status" -> new Interpreted(status(tenantRef, workspaceRef, scopeRef), text);
            case "/resume" -> {
                if (!first.isBlank()) throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "Use /resume after selecting a session with /list and /use");
                yield new Interpreted(new ChannelOperation.SessionResume(
                    status(tenantRef, workspaceRef, scopeRef).targetRef()), text);
            }
            case "/cancel" -> new Interpreted(cancel(first, rest), text);
            case "/approve", "/reject" -> {
                if (!rest.isBlank() && (!command.equals("/reject") || first.startsWith("#")
                    || requests.interaction(first) == null)) throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "请使用 " + command + "，有多条待处理项时使用 " + command + " #序号");
                InteractionResponse response = command.equals("/approve")
                    ? new InteractionResponse.Approve(null) : new InteractionResponse.Reject(rest);
                var item = selectInteraction(tenantRef, workspaceRef, scopeRef, request.identity().callerRef(),
                    first, response.kind());
                yield new Interpreted(new ChannelOperation.InteractionRespond(
                    new InteractionRespondPayload(item.interactionRef(), item.revision(), response)), text);
            }
            case "/answer" -> {
                String selector = "";
                String answerText = text.substring(command.length()).strip();
                if (first.matches("#\\d+") || requests.interaction(first) != null) {
                    selector = first;
                    answerText = rest;
                }
                if (answerText.isBlank()) throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "请使用 /answer 答案，例如 /answer 北京 或 /answer 2");
                var item = selectInteraction(tenantRef, workspaceRef, scopeRef, request.identity().callerRef(),
                    selector, "answer");
                InteractionRespondPayload payload = new InteractionRespondPayload(item.interactionRef(),
                    item.revision(), textAnswer(ContentJson.decodePrompt(Json.read(item.promptJson())), answerText));
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

    private ChannelOperation.SessionGet status(String tenantRef, String workspaceRef, String scopeRef) {
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

    private RequestRepository.InteractionRow selectInteraction(String tenant, String workspace, String scope,
                                                               String caller, String selector, String kind) {
        // Keep explicit references compatible with earlier clients; normal commands need no internal IDs.
        if (!selector.isBlank() && !selector.startsWith("#")) return interaction(tenant, workspace, selector);
        var binding = scope == null ? null : directory.binding(tenant, workspace, scope);
        if (binding == null || binding.targetRef() == null) throw new ChannelContractException(
            ChannelErrorCode.INVALID_ARGUMENT, "请先用 /list 和 /use 选择要处理的会话");
        String target = binding.targetRef();
        var scopeInfo = directory.scope(tenant, workspace, scope);
        if (scopeInfo == null || !grants.canAct(tenant, workspace, scopeInfo.entryRef(), caller, target, "target.read"))
            throw new ChannelContractException(
            ChannelErrorCode.PERMISSION_DENIED, "当前会话的访问权限已失效，请重新选择会话");
        var pending = requests.pendingInteractions(tenant, workspace, target).stream()
            .filter(item -> ContentJson.decodePrompt(Json.read(item.promptJson())).allowedResponses().stream()
                .anyMatch(allowed -> allowed.wire().equals(kind))).toList();
        String choiceKind = "interaction:" + Digests.sha256(String.join("\u0000", caller, target, kind));
        if (!selector.isBlank()) {
            var choices = directory.navigationList(scope, choiceKind);
            int number;
            try { number = Integer.parseInt(selector.substring(1)); }
            catch (NumberFormatException e) { number = 0; }
            if (number < 1 || number > choices.size()) throw new ChannelContractException(
                ChannelErrorCode.INVALID_ARGUMENT, "请先发送 /" + kind + (kind.equals("answer") ? " 答案" : "")
                    + "，再从显示的待处理列表中选择编号");
            String selected = choices.get(number - 1);
            return pending.stream().filter(item -> interactionChoice(item).equals(selected)).findFirst()
                .orElseThrow(() -> new ChannelContractException(ChannelErrorCode.INTERACTION_STALE,
                    "这条待处理项已变化或已被处理，请重新发送命令获取当前列表"));
        }
        if (pending.isEmpty()) throw new ChannelContractException(ChannelErrorCode.INTERACTION_STALE,
            kind.equals("answer") ? "当前会话没有等待回答的问题" : "当前会话没有等待处理的审批");
        if (pending.size() == 1) return pending.get(0);
        directory.rememberNavigationList(scope, choiceKind, pending.stream()
            .map(this::interactionChoice).toList());
        StringBuilder message = new StringBuilder("当前会话有多条待处理项，请选择：");
        for (int i = 0; i < pending.size(); i++) {
            InteractionPrompt prompt = ContentJson.decodePrompt(Json.read(pending.get(i).promptJson()));
            var content = new ArrayList<>(prompt.content());
            if (prompt instanceof InteractionPrompt.Questions questions)
                questions.questions().forEach(question -> content.addAll(question.content()));
            String label = content.stream().filter(part -> part instanceof ContentPart.Text)
                .map(part -> ((ContentPart.Text) part).text()).reduce("", (a, b) -> a + " " + b)
                .strip().replaceAll("\\s+", " ");
            if (label.isBlank() && prompt instanceof InteractionPrompt.Approval approval && approval.operation() != null)
                label = approval.operation().name();
            if (label.isBlank()) label = kind.equals("answer") ? "等待回答的问题" : "等待处理的审批";
            message.append("\n").append(i + 1).append(". ").append(label.length() > 160 ? label.substring(0, 160) + "…" : label);
        }
        message.append("\n使用 /").append(kind).append(" #1").append(kind.equals("answer") ? " 答案" : "");
        throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, message.toString());
    }

    private String interactionChoice(RequestRepository.InteractionRow item) {
        return item.interactionRef() + ":" + item.revision();
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
