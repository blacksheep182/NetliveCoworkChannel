package com.netlivecowork.channel.contract.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netlivecowork.channel.contract.BackendFileGrant;
import com.netlivecowork.channel.contract.ChannelMessage;
import com.netlivecowork.channel.contract.ContentPart;
import com.netlivecowork.channel.contract.FileMetadata;
import com.netlivecowork.channel.contract.InteractionPrompt;
import com.netlivecowork.channel.contract.InteractionResponse;
import com.netlivecowork.channel.contract.NormalizedContentPart;
import com.netlivecowork.channel.contract.QuestionAnswer;
import com.netlivecowork.channel.contract.QuestionItem;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** 内容、提示、答案与后端文件授权的 JSON 编解码。 */
public final class ContentJson {

    private ContentJson() {
    }

    // ---- content ---------------------------------------------------------------------

    public static ObjectNode encode(ContentPart part) {
        ObjectNode node = Json.obj().put("partId", part.partId()).put("type", part.type().wire());
        switch (part) {
            case ContentPart.Text text -> {
                node.put("text", text.text());
                node.put("format", text.format().wire());
            }
            case ContentPart.Image image -> {
                node.put("fileRef", image.fileRef());
                if (image.alt() != null) {
                    node.put("alt", image.alt());
                }
            }
            case ContentPart.FilePart file -> node.put("fileRef", file.fileRef());
            case ContentPart.MessageReference reference -> {
                node.put("messageRef", reference.messageRef());
                if (reference.preview() != null) {
                    ObjectNode preview = node.putObject("preview");
                    ArrayNode content = preview.putArray("content");
                    reference.preview().content().forEach(part1 -> content.add(encode(part1)));
                    preview.put("truncated", reference.preview().truncated());
                }
            }
        }
        return node;
    }

    public static ContentPart decodePart(JsonNode node) {
        Json.onlyFields(node, "partId", "type", "text", "format", "fileRef", "alt", "messageRef", "preview");
        ContentPart.PartType type = partType(node);
        return switch (type) {
            case TEXT -> new ContentPart.Text(Json.text(node, "partId"), Json.text(node, "text"),
                format(Json.optText(node, "format")));
            case IMAGE -> new ContentPart.Image(Json.text(node, "partId"), Json.text(node, "fileRef"),
                Json.optText(node, "alt"));
            case FILE -> new ContentPart.FilePart(Json.text(node, "partId"), Json.text(node, "fileRef"));
            case MESSAGE_REFERENCE -> new ContentPart.MessageReference(Json.text(node, "partId"),
                Json.text(node, "messageRef"), preview(node));
        };
    }

    private static ContentPart.MessageReference.Preview preview(JsonNode node) {
        JsonNode value = Json.optObj(node, "preview");
        if (value == null) {
            return null;
        }
        Json.onlyFields(value, "content", "truncated");
        List<com.netlivecowork.channel.contract.BasicContentPart> parts = new ArrayList<>();
        for (JsonNode part : Json.optArray(value, "content")) {
            ContentPart decoded = decodePart(part);
            if (decoded instanceof com.netlivecowork.channel.contract.BasicContentPart basic) {
                parts.add(basic);
            } else {
                throw Json.invalid("preview.content cannot contain message references");
            }
        }
        return new ContentPart.MessageReference.Preview(parts, Json.optBool(value, "truncated", false));
    }

    private static ContentPart.PartType partType(JsonNode node) {
        String wire = Json.text(node, "type");
        for (ContentPart.PartType type : ContentPart.PartType.values()) {
            if (type.wire().equals(wire)) {
                return type;
            }
        }
        throw Json.invalid("unknown content part type: " + wire);
    }

    private static ContentPart.Text.Format format(String raw) {
        if (raw == null) {
            return ContentPart.Text.Format.PLAIN;
        }
        for (ContentPart.Text.Format format : ContentPart.Text.Format.values()) {
            if (format.wire().equals(raw)) {
                return format;
            }
        }
        throw Json.invalid("unknown text format: " + raw);
    }
    public static List<ContentPart> decodeContent(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw Json.invalid("content array is required");
        }
        List<ContentPart> parts = new ArrayList<>();
        for (JsonNode part : node) {
            parts.add(decodePart(part));
        }
        return parts;
    }

    public static ObjectNode encode(ChannelMessage message) {
        ObjectNode node = Json.obj().put("messageRef", message.messageRef());
        if (message.replyToMessageRef() != null) {
            node.put("replyToMessageRef", message.replyToMessageRef());
        }
        ArrayNode content = node.putArray("content");
        message.content().forEach(part -> content.add(encode(part)));
        return node;
    }

    public static ChannelMessage decodeMessage(JsonNode node) {
        Json.onlyFields(node, "messageRef", "content", "replyToMessageRef");
        return new ChannelMessage(Json.text(node, "messageRef"), decodeContent(node.get("content")),
            Json.optText(node, "replyToMessageRef"));
    }

    // ---- normalized (L3) content -----------------------------------------------------

    public static ObjectNode encode(NormalizedContentPart part) {
        ObjectNode node = Json.obj().put("partId", part.partId()).put("type", part.type().wire());
        switch (part) {
            case NormalizedContentPart.Text text -> {
                node.put("text", text.text());
                node.put("format", text.format().wire());
            }
            case NormalizedContentPart.Image image -> {
                node.set("file", encode(image.file()));
                if (image.alt() != null) {
                    node.put("alt", image.alt());
                }
            }
            case NormalizedContentPart.FilePart file -> node.set("file", encode(file.file()));
            case NormalizedContentPart.MessageReference reference -> {
                node.put("backendMessageId", reference.backendMessageId());
                ArrayNode preview = node.putArray("preview");
                reference.preview().forEach(part1 -> preview.add(encode(part1)));
                node.put("truncated", reference.truncated());
            }
        }
        return node;
    }

    public static NormalizedContentPart decodeNormalizedPart(JsonNode node) {
        Json.onlyFields(node, "partId", "type", "text", "format", "file", "alt", "backendMessageId", "preview",
            "truncated");
        ContentPart.PartType type = partType(node);
        return switch (type) {
            case TEXT -> new NormalizedContentPart.Text(Json.text(node, "partId"), Json.text(node, "text"),
                format(Json.optText(node, "format")));
            case IMAGE -> new NormalizedContentPart.Image(Json.text(node, "partId"),
                decodeBackendFile(Json.obj(node, "file")), Json.optText(node, "alt"));
            case FILE -> new NormalizedContentPart.FilePart(Json.text(node, "partId"),
                decodeBackendFile(Json.obj(node, "file")));
            case MESSAGE_REFERENCE -> {
                List<NormalizedContentPart.Text> preview = new ArrayList<>();
                for (JsonNode item : Json.optArray(node, "preview")) {
                    NormalizedContentPart decoded = decodeNormalizedPart(item);
                    if (decoded instanceof NormalizedContentPart.Text text) {
                        preview.add(text);
                    } else {
                        throw Json.invalid("normalized preview may only contain text");
                    }
                }
                yield new NormalizedContentPart.MessageReference(Json.text(node, "partId"),
                    Json.text(node, "backendMessageId"), preview, Json.optBool(node, "truncated", false));
            }
        };
    }

    public static List<NormalizedContentPart> decodeNormalizedContent(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw Json.invalid("content array is required");
        }
        List<NormalizedContentPart> parts = new ArrayList<>();
        for (JsonNode part : node) {
            parts.add(decodeNormalizedPart(part));
        }
        return parts;
    }

    public static ObjectNode encode(BackendFileGrant grant) {
        ObjectNode node = Json.obj().put("backendFileId", grant.backendFileId());
        node.set("metadata", encode(grant.metadata()));
        return node;
    }

    public static ObjectNode encode(FileMetadata metadata) {
        return Json.obj().put("name", metadata.name()).put("mediaType", metadata.mediaType())
            .put("sizeBytes", metadata.sizeBytes());
    }

    public static FileMetadata decodeMetadata(JsonNode node) {
        Json.onlyFields(node, "name", "mediaType", "sizeBytes");
        return new FileMetadata(Json.text(node, "name"), Json.text(node, "mediaType"),
            Json.reqLong(node, "sizeBytes"));
    }

    public static BackendFileGrant decodeBackendFile(JsonNode node) {
        Json.onlyFields(node, "backendFileId", "metadata");
        return new BackendFileGrant(Json.text(node, "backendFileId"), decodeMetadata(Json.obj(node, "metadata")));
    }

    // ---- interaction prompt ----------------------------------------------------------

    public static ObjectNode encode(InteractionPrompt prompt) {
        ObjectNode node = Json.obj();
        switch (prompt) {
            case InteractionPrompt.Approval approval -> {
                node.put("kind", "approval");
                node.set("content", encodeContent(approval.content()));
                if (approval.operation() != null) {
                    ObjectNode operation = node.putObject("operation").put("name", approval.operation().name());
                    operation.set("arguments", approval.operation().arguments());
                    if (approval.operation().editSchemaRef() != null) {
                        operation.put("editSchemaRef", approval.operation().editSchemaRef());
                    }
                }
                node.set("allowedResponses", encodeAllowed(approval.allowedResponses()));
            }
            case InteractionPrompt.Questions questions -> {
                node.put("kind", "questions");
                node.set("content", encodeContent(questions.content()));
                ArrayNode items = node.putArray("questions");
                questions.questions().forEach(item -> items.add(encode(item)));
                node.set("allowedResponses", encodeAllowed(questions.allowedResponses()));
            }
        }
        return node;
    }

    public static InteractionPrompt decodePrompt(JsonNode node) {
        Json.onlyFields(node, "kind", "content", "operation", "allowedResponses", "questions");
        String kind = Json.text(node, "kind");
        JsonNode contentNode = node.get("content");
        // A prompt may carry its text in the questions or operation without an introductory paragraph.
        List<ContentPart> content = contentNode != null && contentNode.isArray() && contentNode.isEmpty()
            ? List.of() : decodeContent(contentNode);
        Set<InteractionPrompt.AllowedResponse> allowed = decodeAllowed(node.get("allowedResponses"));
        return switch (kind) {
            case "approval" -> {
                JsonNode operationNode = Json.optObj(node, "operation");
                InteractionPrompt.Approval.Operation operation = null;
                if (operationNode != null) {
                    Json.onlyFields(operationNode, "name", "arguments", "editSchemaRef");
                    operation = new InteractionPrompt.Approval.Operation(Json.text(operationNode, "name"),
                        operationNode.get("arguments"), Json.optText(operationNode, "editSchemaRef"));
                }
                yield new InteractionPrompt.Approval(content, operation, allowed);
            }
            case "questions" -> {
                List<QuestionItem> items = new ArrayList<>();
                for (JsonNode item : Json.array(node, "questions")) {
                    items.add(decodeQuestion(item));
                }
                yield new InteractionPrompt.Questions(content, items, allowed);
            }
            default -> throw Json.invalid("unknown prompt kind: " + kind);
        };
    }

    private static ArrayNode encodeContent(List<ContentPart> content) {
        ArrayNode array = Json.arr();
        content.forEach(part -> array.add(encode(part)));
        return array;
    }

    private static ArrayNode encodeAllowed(Set<InteractionPrompt.AllowedResponse> allowed) {
        ArrayNode array = Json.arr();
        allowed.forEach(value -> array.add(value.wire()));
        return array;
    }

    private static Set<InteractionPrompt.AllowedResponse> decodeAllowed(JsonNode array) {
        Set<InteractionPrompt.AllowedResponse> allowed = EnumSet.noneOf(InteractionPrompt.AllowedResponse.class);
        if (array == null || array.isNull()) {
            return allowed;
        }
        if (!array.isArray()) {
            throw Json.invalid("allowedResponses must be an array");
        }
        for (JsonNode item : array) {
            String wire = item.asText();
            boolean matched = false;
            for (InteractionPrompt.AllowedResponse value : InteractionPrompt.AllowedResponse.values()) {
                if (value.wire().equals(wire)) {
                    allowed.add(value);
                    matched = true;
                }
            }
            if (!matched) {
                throw Json.invalid("unknown allowed response: " + wire);
            }
        }
        return allowed;
    }

    public static ObjectNode encode(QuestionItem item) {
        ObjectNode node = Json.obj().put("questionId", item.questionId()).put("required", item.required());
        node.set("content", encodeContent(item.content()));
        ObjectNode input = node.putObject("input");
        switch (item.input()) {
            case QuestionItem.Input.TextInput ignored -> input.put("type", "text");
            case QuestionItem.Input.ChoiceInput choice -> {
                input.put("type", "choice");
                input.put("multiple", choice.multiple());
                input.put("allowOther", choice.allowOther());
                ArrayNode options = input.putArray("options");
                choice.options().forEach(option -> {
                    ObjectNode optionNode = Json.obj().put("optionId", option.optionId())
                        .put("label", option.label()).put("recommended", option.recommended());
                    if (option.description() != null) {
                        optionNode.put("description", option.description());
                    }
                    options.add(optionNode);
                });
            }
        }
        return node;
    }

    public static QuestionItem decodeQuestion(JsonNode node) {
        Json.onlyFields(node, "questionId", "content", "required", "input");
        JsonNode inputNode = Json.obj(node, "input");
        Json.onlyFields(inputNode, "type", "multiple", "allowOther", "options");
        String inputType = Json.text(inputNode, "type");
        QuestionItem.Input input = switch (inputType) {
            case "text" -> new QuestionItem.Input.TextInput();
            case "choice" -> {
                List<QuestionItem.ChoiceOption> options = new ArrayList<>();
                for (JsonNode option : Json.array(inputNode, "options")) {
                    Json.onlyFields(option, "optionId", "label", "description", "recommended");
                    options.add(new QuestionItem.ChoiceOption(Json.text(option, "optionId"),
                        Json.text(option, "label"), Json.optText(option, "description"),
                        Json.optBool(option, "recommended", false)));
                }
                yield new QuestionItem.Input.ChoiceInput(Json.optBool(inputNode, "multiple", false),
                    Json.optBool(inputNode, "allowOther", false), options);
            }
            default -> throw Json.invalid("unknown question input type: " + inputType);
        };
        return new QuestionItem(Json.text(node, "questionId"), decodeContent(node.get("content")),
            Json.optBool(node, "required", false), input);
    }

    // ---- interaction response --------------------------------------------------------

    public static ObjectNode encode(InteractionResponse response) {
        ObjectNode node = Json.obj();
        switch (response) {
            case InteractionResponse.Approve approve -> {
                node.put("kind", "approve");
                if (approve.modifiedArguments() != null) {
                    node.set("modifiedArguments", approve.modifiedArguments());
                }
            }
            case InteractionResponse.Reject reject -> {
                node.put("kind", "reject");
                if (reject.reason() != null) {
                    node.put("reason", reject.reason());
                }
            }
            case InteractionResponse.Answer answer -> {
                node.put("kind", "answer");
                ArrayNode answers = node.putArray("answers");
                answer.answers().forEach(item -> answers.add(encode(item)));
            }
        }
        return node;
    }

    public static InteractionResponse decodeResponse(JsonNode node) {
        Json.onlyFields(node, "kind", "modifiedArguments", "reason", "answers");
        String kind = Json.text(node, "kind");
        return switch (kind) {
            case "approve" -> new InteractionResponse.Approve(node.get("modifiedArguments"));
            case "reject" -> new InteractionResponse.Reject(Json.optText(node, "reason"));
            case "answer" -> {
                List<QuestionAnswer> answers = new ArrayList<>();
                for (JsonNode item : Json.array(node, "answers")) {
                    answers.add(decodeAnswer(item));
                }
                yield new InteractionResponse.Answer(answers);
            }
            default -> throw Json.invalid("unknown response kind: " + kind);
        };
    }

    public static ObjectNode encode(QuestionAnswer answer) {
        ObjectNode node = Json.obj().put("questionId", answer.questionId());
        switch (answer) {
            case QuestionAnswer.TextAnswer text -> {
                node.put("kind", "text");
                node.set("content", encodeContent(text.content()));
            }
            case QuestionAnswer.ChoiceAnswer choice -> {
                node.put("kind", "choice");
                ArrayNode optionIds = node.putArray("optionIds");
                choice.optionIds().forEach(optionIds::add);
                if (choice.otherText() != null) {
                    node.put("otherText", choice.otherText());
                }
            }
        }
        return node;
    }

    public static QuestionAnswer decodeAnswer(JsonNode node) {
        Json.onlyFields(node, "questionId", "kind", "content", "optionIds", "otherText");
        String kind = Json.text(node, "kind");
        return switch (kind) {
            case "text" -> new QuestionAnswer.TextAnswer(Json.text(node, "questionId"),
                decodeContent(node.get("content")));
            case "choice" -> {
                List<String> optionIds = new ArrayList<>();
                for (JsonNode option : Json.optArray(node, "optionIds")) {
                    optionIds.add(option.asText());
                }
                yield new QuestionAnswer.ChoiceAnswer(Json.text(node, "questionId"), optionIds,
                    Json.optText(node, "otherText"));
            }
            default -> throw Json.invalid("unknown answer kind: " + kind);
        };
    }
}
