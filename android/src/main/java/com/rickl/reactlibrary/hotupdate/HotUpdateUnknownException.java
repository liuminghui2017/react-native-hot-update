package com.rickl.reactlibrary.hotupdate;

class HotUpdateUnknownException extends RuntimeException {

    public HotUpdateUnknownException(String message, Throwable cause) {
        super(message, cause);
    }

    public HotUpdateUnknownException(String message) {
        super(message);
    }
}