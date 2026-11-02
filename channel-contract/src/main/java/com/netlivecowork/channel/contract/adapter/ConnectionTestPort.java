package com.netlivecowork.channel.contract.adapter;

import com.netlivecowork.channel.contract.port.SecretAccess;
import java.util.concurrent.CompletionStage;

/** 管理端连接测试：用受限凭据验证配置，不提交业务任务。 */
public interface ConnectionTestPort {

    CompletionStage<ProbeResult> testConnection(AdapterConfig config, SecretAccess secrets, long timeoutMs);
}
