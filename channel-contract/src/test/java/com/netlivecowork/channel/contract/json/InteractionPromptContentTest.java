package com.netlivecowork.channel.contract.json;

import static org.junit.jupiter.api.Assertions.*;

import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.InteractionPrompt;
import org.junit.jupiter.api.Test;

class InteractionPromptContentTest {
    @Test void questionsMayContainTheTextWithoutAnIntroductoryParagraph() {
        var prompt = ContentJson.decodePrompt(Json.read("""
            {"kind":"questions","content":[],"questions":[
              {"questionId":"q","content":[{"partId":"p","type":"text","text":"选择城市"}],
               "required":true,"input":{"type":"text"}}],"allowedResponses":["answer"]}
            """));
        assertTrue(prompt.content().isEmpty());
        assertEquals(1, assertInstanceOf(InteractionPrompt.Questions.class, prompt).questions().size());
    }

    @Test void approvalMayDescribeItsOperationWithoutAnIntroductoryParagraph() {
        var prompt = ContentJson.decodePrompt(Json.read("""
            {"kind":"approval","content":[],"operation":{"name":"deploy","arguments":{}},
             "allowedResponses":["approve","reject"]}
            """));
        assertTrue(prompt.content().isEmpty());
        assertEquals("deploy", assertInstanceOf(InteractionPrompt.Approval.class, prompt).operation().name());
    }

    @Test void malformedPromptContentAndEmptyMessagesStillFail() {
        assertThrows(ChannelContractException.class, () -> ContentJson.decodePrompt(Json.read(
            "{\"kind\":\"approval\",\"content\":\"invalid\"}")));
        assertThrows(ChannelContractException.class, () -> ContentJson.decodeContent(Json.arr()));
    }
}
