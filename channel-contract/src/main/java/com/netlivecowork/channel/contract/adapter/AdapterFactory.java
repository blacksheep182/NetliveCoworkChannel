package com.netlivecowork.channel.contract.adapter;

import com.netlivecowork.channel.contract.port.AdapterHost;

/**
 * Adapter 工厂。describe/validateConfig/create 不执行网络业务；create 不建立连接。
 */
public interface AdapterFactory {

    AdapterDescriptor describe();

    ValidationResult validateConfig(AdapterConfig config);

    AdapterInstance create(AdapterInstanceConfig config, AdapterHost host);

    /** 可选：管理端连接测试。不支持时返回 null。 */
    default ConnectionTestPort connectionTest() {
        return null;
    }
}
