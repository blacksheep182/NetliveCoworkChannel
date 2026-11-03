package com.netlivecowork.channel.l2;

import static org.junit.jupiter.api.Assertions.*;

import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.json.ContentJson;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.json.PacketJson;
import com.netlivecowork.channel.l2.store.RequestRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InteractionCommandTest {
    private TestModule test;
    private IngressResolution scope;

    @BeforeEach void setup() {
        test = new TestModule();
        test.createEntry();
        scope = resolve("console");
        assertInstanceOf(ChannelResponse.Completed.class, command("/list"));
        assertInstanceOf(ChannelResponse.Completed.class, command("/use 1"));
    }

    @AfterEach void close() {
        test.module.close();
        test.db.close();
    }

    private IngressResolution resolve(String view) {
        return test.module.ingress(TestModule.ENTRY).resolve(new VerifiedIngress("resolve-" + view, "acct-1",
            new VerifiedIngress.Subject(VerifiedIngress.SubjectKind.USER, "alice"),
            new VerifiedIngress.ExternalScope("web-view", view),
            new VerifiedIngress.ExternalReplyTarget(VerifiedIngress.Audience.PERSONAL, "alice",
                Json.obj().put("conversation", "alice")), "event", null)).toCompletableFuture().join();
    }

    private ChannelResponse command(String text) {
        return command(scope, UUID.randomUUID().toString(), text);
    }

    private ChannelResponse command(IngressResolution resolution, String requestId, String text) {
        return test.module.ingress(TestModule.ENTRY).submit(resolution.admissionRef(),
            new IngressRequest("1", requestId, resolution.identity(), resolution.routing(),
                new ChannelOperation.InputSubmit(null, ChannelOperation.Interpretation.COMMAND_AWARE,
                    List.of(new ContentPart.Text("p", text)), null))).toCompletableFuture().join();
    }

    private InteractionRespondPayload accepted(String text) {
        var response = command(text);
        return payload(assertInstanceOf(ChannelResponse.Accepted.class, response, response.toString()));
    }

    private InteractionRespondPayload payload(ChannelResponse.Accepted accepted) {
        var packet = PacketJson.decCommand(test.module.requests().inboxByOperation(accepted.operationRef()).commandJson());
        assertEquals("target-1", packet.context().targetRef());
        return assertInstanceOf(CommandPayload.InteractionRespond.class, packet.payload()).payload();
    }

    private RequestRepository.InteractionRow pending(String label, InteractionPrompt prompt) {
        return pending(label, "target-1", prompt, null);
    }

    private RequestRepository.InteractionRow pending(String label, String target, InteractionPrompt prompt, Instant expiry) {
        return test.module.requests().ensureInteraction(TestModule.TENANT, TestModule.WORKSPACE, target, null,
            "flow-" + label, "backend-" + label, 1, Json.write(ContentJson.encode(prompt)), expiry,
            scope.routing().scopeRef(), scope.routing().replyTargetRef(), null);
    }

    private InteractionPrompt approval(String label) {
        return new InteractionPrompt.Approval(List.of(new ContentPart.Text("p", label)),
            new InteractionPrompt.Approval.Operation("deploy", Json.obj(), null), null);
    }

    private InteractionPrompt question(String label) {
        return new InteractionPrompt.Questions(List.of(), List.of(new QuestionItem("q",
            List.of(new ContentPart.Text("p", label)), true, new QuestionItem.Input.TextInput())), null);
    }

    @Test void rejectNeedsNeitherReferenceNorReason() {
        var item = pending("reject", approval("是否部署？"));
        var response = accepted("/reject");
        assertEquals(item.interactionRef(), response.interactionRef());
        assertNull(assertInstanceOf(InteractionResponse.Reject.class, response.response()).reason());
    }

    @Test void approveAlsoUsesTheOnlyPendingApproval() {
        var item = pending("approve", approval("是否继续？"));
        assertEquals(item.interactionRef(), accepted("/approve").interactionRef());
    }

    @Test void earlierExplicitReferenceCommandsRemainCompatible() {
        var approval = pending("legacy-reject", approval("是否继续？"));
        var rejected = accepted("/reject " + approval.interactionRef() + " 旧客户端的原因");
        assertEquals("旧客户端的原因", ((InteractionResponse.Reject) rejected.response()).reason());
        var question = pending("legacy-answer", question("选择城市"));
        assertEquals(question.interactionRef(), accepted("/answer " + question.interactionRef() + " 北京").interactionRef());
    }

    @Test void answerPreservesTheCompleteTextWithoutAReference() {
        var item = pending("text", question("要分析什么？"));
        var response = accepted("/answer 请分析 frontend 和 backend 两个目录");
        assertEquals(item.interactionRef(), response.interactionRef());
        var answer = assertInstanceOf(InteractionResponse.Answer.class, response.response());
        var text = assertInstanceOf(QuestionAnswer.TextAnswer.class, answer.answers().get(0));
        assertEquals("请分析 frontend 和 backend 两个目录", ((ContentPart.Text) text.content().get(0)).text());
    }

    @Test void answerNumberIsAnOptionRatherThanAnInteractionSelector() {
        var choice = new QuestionItem.Input.ChoiceInput(false, false, List.of(
            QuestionItem.ChoiceOption.of("beijing", "北京"), QuestionItem.ChoiceOption.of("shanghai", "上海")));
        pending("choice", new InteractionPrompt.Questions(List.of(), List.of(new QuestionItem("q",
            List.of(new ContentPart.Text("p", "选择城市")), true, choice)), null));
        var answer = assertInstanceOf(InteractionResponse.Answer.class, accepted("/answer 2").response());
        assertEquals(List.of("shanghai"), ((QuestionAnswer.ChoiceAnswer) answer.answers().get(0)).optionIds());
    }

    @Test void commandKindsResolveIndependently() {
        var approval = pending("approval", approval("是否继续？"));
        var question = pending("question", question("选择什么？"));
        assertEquals(approval.interactionRef(), accepted("/reject").interactionRef());
        assertEquals(question.interactionRef(), accepted("/answer 北京").interactionRef());
    }

    @Test void multipleItemsDisplayTheirContentAndRequireAnExplicitChoice() {
        pending("first", question("第一条问题"));
        pending("second", question("第二条问题"));
        var rejected = assertInstanceOf(ChannelResponse.Rejected.class, command("/answer 北京"));
        assertTrue(rejected.error().message().contains("第一条问题"), rejected.toString());
        assertTrue(rejected.error().message().contains("第二条问题"));
        assertTrue(rejected.error().message().contains("/answer #1 答案"));
        var displayed = test.db.query("SELECT * FROM l2_interactions ORDER BY created_at,interaction_ref",
            rs -> rs.getString("interaction_ref"));
        assertEquals(displayed.get(1), accepted("/answer #2 北京").interactionRef());
        assertEquals("PENDING", test.module.requests().interaction(displayed.get(0)).status());
    }

    @Test void numberedRejectUsesNoReasonAndCannotGuessWithoutAList() {
        pending("first", approval("第一条审批"));
        pending("second", approval("第二条审批"));
        assertInstanceOf(ChannelResponse.Rejected.class, command("/reject #1"));
        assertInstanceOf(ChannelResponse.Rejected.class, command("/reject"));
        assertInstanceOf(InteractionResponse.Reject.class, accepted("/reject #1").response());
    }

    @Test void aChangedPromptCannotReuseAnOldDisplayedNumber() {
        var first = pending("first", question("第一条问题"));
        var second = pending("second", question("第二条问题"));
        assertInstanceOf(ChannelResponse.Rejected.class, command("/answer 北京"));
        for (var row : List.of(first, second)) test.module.requests().ensureInteraction(TestModule.TENANT,
            TestModule.WORKSPACE, row.targetRef(), null, row.flowId(), row.backendInteractionId(), 2,
            row.promptJson(), null, row.scopeRef(), row.destinationRef(), null);
        var rejected = assertInstanceOf(ChannelResponse.Rejected.class, command("/answer #1 北京"));
        assertEquals("INTERACTION_STALE", rejected.error().code());
        assertEquals("PENDING", test.module.requests().interaction(first.interactionRef()).status());
        assertEquals("PENDING", test.module.requests().interaction(second.interactionRef()).status());
    }

    @Test void anotherScopeCannotReuseDisplayedNumbers() {
        pending("first", approval("第一条审批"));
        pending("second", approval("第二条审批"));
        assertInstanceOf(ChannelResponse.Rejected.class, command("/reject"));
        var other = resolve("other");
        command(other, "list-other", "/list");
        command(other, "select-other", "/use 1");
        assertInstanceOf(ChannelResponse.Rejected.class, command(other, "reject-other", "/reject #1"));
    }

    @Test void aResolvedNumberNeverShiftsToAnotherPendingItem() {
        pending("first", question("第一条问题"));
        pending("second", question("第二条问题"));
        assertInstanceOf(ChannelResponse.Rejected.class, command("/answer 北京"));
        var displayed = test.db.query("SELECT interaction_ref FROM l2_interactions ORDER BY created_at,interaction_ref",
            rs -> rs.getString(1));
        test.module.requests().finishInteraction(displayed.get(0), "RESOLVED", 1);
        assertEquals("INTERACTION_STALE", assertInstanceOf(ChannelResponse.Rejected.class,
            command("/answer #1 北京")).error().code());
        assertEquals("PENDING", test.module.requests().interaction(displayed.get(1)).status());
    }

    @Test void expiredCompletedAndOtherSessionItemsAreNeverDefaults() {
        pending("expired", "target-1", approval("过期审批"), test.clock.instant());
        var completed = pending("completed", approval("已处理审批"));
        test.module.requests().finishInteraction(completed.interactionRef(), "RESOLVED", completed.revision());
        pending("other", "target-other", approval("另一个会话的审批"), null);
        var rejected = assertInstanceOf(ChannelResponse.Rejected.class, command("/reject"));
        assertEquals("INTERACTION_STALE", rejected.error().code());
        assertTrue(rejected.error().message().contains("没有等待处理"));
    }

    @Test void revokedAccessCannotRevealOrRespondToPendingItems() {
        pending("private", approval("私密审批内容"));
        test.module.directory().setTargetOwner("target-1", "another-owner");
        var rejected = assertInstanceOf(ChannelResponse.Rejected.class, command("/reject"));
        assertEquals("PERMISSION_DENIED", rejected.error().code());
        assertFalse(rejected.error().message().contains("私密审批内容"));
    }

    @Test void replayKeepsTheOriginalInteractionInsteadOfResolvingANewDefault() {
        var first = pending("first", approval("第一条审批"));
        var original = assertInstanceOf(ChannelResponse.Accepted.class, command(scope, "same-request", "/reject"));
        pending("second", approval("第二条审批"));
        var replay = assertInstanceOf(ChannelResponse.Accepted.class,
            command(scope, "same-request", "/reject"));
        assertEquals(original.operationRef(), replay.operationRef());
        assertEquals(first.interactionRef(), payload(original).interactionRef());
    }

    @Test void emptyAnswerAndUnselectedScopeHaveActionableMessages() {
        assertTrue(assertInstanceOf(ChannelResponse.Rejected.class, command("/answer")).error().message()
            .contains("/answer 答案"));
        var unselected = resolve("unselected");
        assertTrue(assertInstanceOf(ChannelResponse.Rejected.class, command(unselected, "no-selection", "/reject"))
            .error().message().contains("/list 和 /use"));
    }
}
