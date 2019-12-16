package com.rickl.reactlibrary.hotupdate;

public final class HotUpdateNotInitializedException extends RuntimeException {

    public HotUpdateNotInitializedException(String message, Throwable cause) {
        super(message, cause);
    }

    public HotUpdateNotInitializedException(String message) {
        super(message);
    }
}