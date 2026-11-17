package com.meetingnotes.worker;

/** 处理失败携带稳定错误码（task.error_code VARCHAR(64) 只放得下码，原因进 dead_letter.reason TEXT）。
 *  T4.35a 从 TranscriptionWorker 内部类提为同包独立文件（M3 行数上限），语义逐字不变。 */
final class ProcessingException extends RuntimeException {

    final String code;

    ProcessingException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
