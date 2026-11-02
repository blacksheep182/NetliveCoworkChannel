package com.netlivecowork.channel.contract;

/** 可靠顺序范围。scopeRef 专指顺序范围，不是入口群聊 scopeRef（data-contracts.md §7）。 */
public record EventOrder(String scopeRef, long sequence) {
    public EventOrder {
        Producer.requireText(scopeRef, "order.scopeRef");
    }
}
