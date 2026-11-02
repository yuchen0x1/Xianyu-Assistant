package com.feijimiao.xianyuassistant.service;

/**
 * Result of a message send that requires an explicit server acknowledgement.
 */
public enum MessageSendResult {
    /** The server explicitly acknowledged the message with code 200. */
    SUCCESS,

    /** The server rejected the message, or it could not be sent locally. */
    FAILED,

    /** The message was written to the socket, but no acknowledgement arrived in time. */
    UNKNOWN;

    public static MessageSendResult fromResponseCode(Integer code) {
        return code != null && code == 200 ? SUCCESS : FAILED;
    }
}
