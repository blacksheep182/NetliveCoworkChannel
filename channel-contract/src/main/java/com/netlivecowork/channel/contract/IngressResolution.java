package com.netlivecowork.channel.contract;

/**
 * resolve 结果：admissionRef 只绑定接入、身份、范围、回复目标与请求标识，不代表业务授权通过（INV-08）。
 */
public record IngressResolution(String admissionRef, IngressRequest.Identity identity, IngressRequest.Routing routing) {
    public IngressResolution {
        Producer.requireText(admissionRef, "admissionRef");
        if (identity == null || routing == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "resolution requires identity and routing");
        }
    }
}
