package com.feijimiao.xianyuassistant.service;

import com.feijimiao.xianyuassistant.service.bo.*;

import java.io.OutputStream;
import java.util.List;

public interface DataBackupService {

    List<BackupModuleRespBO> getModules();

    BackupExportRespBO exportData(BackupExportReqBO reqBO);

    /**
     * 把备份 JSON 直接写进给定的输出流。
     *
     * <p>与 {@link #exportData(BackupExportReqBO)} 的内容完全一致，区别只在内存：
     * 逐模块写出、写完即可释放，峰值约等于"最大的单个模块"而不是"全部模块 + 完整
     * JSON 字符串"。HTTP 层用 {@code StreamingResponseBody} 调用它，避免把整份备份
     * 先变成 String 再变成 byte[]。</p>
     */
    void exportDataToStream(OutputStream out, BackupExportReqBO reqBO);

    BackupImportRespBO importData(BackupImportReqBO reqBO);
}
