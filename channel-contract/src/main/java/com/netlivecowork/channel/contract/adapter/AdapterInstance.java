package com.netlivecowork.channel.contract.adapter;

import com.netlivecowork.channel.contract.port.DeliveryPort;
import com.netlivecowork.channel.contract.port.DeliveryQueryPort;
import java.util.concurrent.CompletionStage;

/**
 * 单个 Adapter 实例。工厂可共享，账号可变状态不共享；未声明的能力返回 null，
 * 调用方必须按能力声明使用，不得静默降级。
 */
public interface AdapterInstance {

    String entryRef();

    CompletionStage<ActivationResult> activate();

    CompletionStage<Void> deactivate(StopReason reason);

    default ConnectionPort connection() {
        return null;
    }

    default DeliveryPort delivery() {
        return null;
    }

    default DeliveryQueryPort deliveryQuery() {
        return null;
    }

    default AuthPort auth() {
        return null;
    }
}
