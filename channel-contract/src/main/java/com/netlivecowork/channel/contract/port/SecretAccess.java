package com.netlivecowork.channel.contract.port;

import com.netlivecowork.channel.contract.SecretValue;
import java.util.concurrent.CompletionStage;

/** 实例限定的凭据访问。replace 用版本比较防并发覆盖（INV-52、57）。 */
public interface SecretAccess {

    CompletionStage<SecretValue> read(String name);

    CompletionStage<Void> replace(String name, long expectedRevision, String value);
}
