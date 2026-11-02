package com.netlivecowork.channel.infra;

/** 基础设施异常：只在本层内传播，对外必须转换为脱敏 ChannelError（INV-52）。 */
public class DbException extends RuntimeException {

    public DbException(String message) {
        super(message);
    }

    public DbException(String message, Throwable cause) {
        super(message, cause);
    }
}
