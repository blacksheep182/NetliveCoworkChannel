package com.netlivecowork.channel.contract.adapter;

/** 激活结果：返回当前配置实际采用的能力与可验证账号；未完成授权时账号为空。 */
public record ActivationResult(AdapterCapabilities capabilities, String externalAccountKey) {
    public ActivationResult {
        if (capabilities == null) {
            throw new IllegalArgumentException("capabilities is required");
        }
        externalAccountKey = externalAccountKey == null || externalAccountKey.isBlank()
            ? null : externalAccountKey;
    }
}
