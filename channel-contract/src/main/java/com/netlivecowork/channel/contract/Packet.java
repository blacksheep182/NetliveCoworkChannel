package com.netlivecowork.channel.contract;

/** 规范包公共部分。kind 区分 command / receipt / event。 */
public interface Packet {

    String version();

    String packetId();

    String kind();

    java.time.Instant createdAt();

    Correlation correlation();

    PacketContext context();

    Producer producer();

    static void requireVersionOne(String version) {
        if (!"1".equals(version)) {
            throw new ChannelContractException(ChannelErrorCode.UNSUPPORTED_VERSION,
                "unsupported packet version: " + version);
        }
    }
}
