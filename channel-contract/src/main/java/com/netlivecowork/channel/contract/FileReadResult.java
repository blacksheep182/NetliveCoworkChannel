package com.netlivecowork.channel.contract;

/** 出站文件读取结果。字节流不进入业务包；读取必须校验文件归属与投递授权（INV-41～46）。 */
public record FileReadResult(FileMetadata metadata, byte[] data) {
    public FileReadResult {
        if (metadata == null || data == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "file read result requires metadata and data");
        }
    }

    public int size() {
        return data.length;
    }
}
