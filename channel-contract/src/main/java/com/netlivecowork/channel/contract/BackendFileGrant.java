package com.netlivecowork.channel.contract;

/**
 * L3 核验后的后端文件关联：后端文件标识 + 权威元数据。它不是 fileRef，也不含访问凭证；
 * L2 据此登记 fileRef（INV-42）。
 */
public record BackendFileGrant(String backendFileId, FileMetadata metadata) {
    public BackendFileGrant {
        Producer.requireText(backendFileId, "backendFileId");
        if (metadata == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "file metadata is required");
        }
    }
}
