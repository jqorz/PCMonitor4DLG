package com.jqorz.pcmonitor4dlg.model

/**
 * OTA固件升级状态
 *
 * 文件选择阶段：fileName/filePath/fileSize/fileVersion/fileError 由校验结果填充；
 * 传输阶段：running=true 且 progress 递增；
 * 结束后：resultMessage 携带成功/失败信息，success 标记结果。
 */
data class OtaInfo(
    val fileName: String = "",
    val filePath: String = "",
    val fileSize: Int = 0,
    val fileVersion: Int = -1,
    val fileError: String = "",
    val running: Boolean = false,
    val progress: Int = 0,           // 0..100
    val resultMessage: String = "",
    val success: Boolean = false
) {
    /** 已选择且校验通过，可以开始升级 */
    val fileValid: Boolean get() = filePath.isNotEmpty() && fileError.isEmpty()
}
