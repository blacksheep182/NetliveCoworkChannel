package com.netlivecowork.channel.contract.port;

import com.netlivecowork.channel.contract.FileReadResult;
import java.util.concurrent.CompletionStage;

/**
 * L1 → L2 出站文件读取：必须确认文件属于当前获准 deliveryId 并复验披露权限（INV-45）。
 * 入站准备、上传与完成确认端口待补。
 */
public interface FileAccess {

    CompletionStage<FileReadResult> read(String fileRef, String deliveryId);
}
