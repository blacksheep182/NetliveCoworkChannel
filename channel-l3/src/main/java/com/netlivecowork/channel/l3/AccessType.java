package com.netlivecowork.channel.l3;

/** 访问路径类型。云端/地端/连接器本身不足以定位服务，实际入口仍由服务登记给出（INV-11）。 */
public enum AccessType {
    CLOUD, LOCAL, CONNECTOR
}
