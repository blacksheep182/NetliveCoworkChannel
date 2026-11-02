package com.netlivecowork.channel.contract;

/**
 * 内部契约违规：字段、枚举或组合不合法。基础设施与业务异常不得直接穿透到平台入口，
 * 由各层统一转换为 ChannelError（INV-52）。
 */
public class ChannelContractException extends RuntimeException {
    private final ChannelErrorCode code;

    public ChannelContractException(ChannelErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ChannelContractException(String message) {
        this(ChannelErrorCode.INVALID_ARGUMENT, message);
    }

    public ChannelErrorCode code() {
        return code;
    }

    public ChannelError toError() {
        return ChannelError.of(code, getMessage());
    }
}
