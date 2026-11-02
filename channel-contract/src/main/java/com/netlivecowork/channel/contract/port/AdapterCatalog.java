package com.netlivecowork.channel.contract.port;

import com.netlivecowork.channel.contract.adapter.AdapterDescriptor;
import com.netlivecowork.channel.contract.adapter.AdapterFactory;
import java.util.List;

/**
 * Adapter 目录（L2 → L1 装配结果）。只列装配成功的类型与有效契约版本；
 * 未知类型不得静默创建实例（INV-03）。
 */
public interface AdapterCatalog {

    List<AdapterDescriptor> descriptors();

    /** 未注册的类型返回 null，由调用方明确拒绝。 */
    AdapterFactory factory(String type);

    AdapterDescriptor descriptor(String type);
}
