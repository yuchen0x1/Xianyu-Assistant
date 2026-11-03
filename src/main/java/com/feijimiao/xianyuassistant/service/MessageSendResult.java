package com.feijimiao.xianyuassistant.service;

/**
 * Result of a message send that requires an explicit server acknowledgement.
 */
public enum MessageSendResult {
    /** The server explicitly acknowledged the message with code 200. */
    SUCCESS,

    /** The server explicitly rejected the message, or it provably failed before any frame was written. */
    FAILED,

    /**
     * The frame may already have been written (or a local failure happened after the write),
     * but no definitive server acknowledgement arrived. Callers must not assume failure.
     */
    UNKNOWN;

    public static MessageSendResult fromResponseCode(Integer code) {
        return code != null && code == 200 ? SUCCESS : FAILED;
    }
}
