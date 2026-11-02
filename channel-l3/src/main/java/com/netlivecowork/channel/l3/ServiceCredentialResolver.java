package com.netlivecowork.channel.l3;

/**
 * 服务凭证解析端口。把服务登记的 {@code credentialRef} 换成受限设施里的实际凭证值
 * （INV-52）：凭证只经此端口取得，不进入业务包、日志或错误响应。
 */
public interface ServiceCredentialResolver {

    /** 返回 Bearer 凭证值；null 表示该登记没有凭证。实现不得记录或回显返回值。 */
    String resolve(String credentialRef);

    /** 缺省实现：没有任何服务凭证。 */
    ServiceCredentialResolver NOOP = credentialRef -> null;
}
