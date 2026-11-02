package com.netlivecowork.channel.contract;

/** 文件权威元数据。以登记记录为准，不在 ImagePart/FilePart 重复维护（data-contracts.md §4.3）。 */
public record FileMetadata(String name, String mediaType, long sizeBytes) {
    public FileMetadata {
        Producer.requireText(name, "file.name");
        Producer.requireText(mediaType, "file.mediaType");
        if (sizeBytes < 0) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "file.sizeBytes must be >= 0");
        }
    }
}
