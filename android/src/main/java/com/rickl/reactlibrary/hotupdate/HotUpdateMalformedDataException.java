package com.rickl.reactlibrary.hotupdate;

import java.net.MalformedURLException;

public class HotUpdateMalformedDataException extends RuntimeException {
    public HotUpdateMalformedDataException(String path, Throwable cause) {
        super("Unable to parse contents of " + path + ", the file may be corrupted.", cause);
    }
    public HotUpdateMalformedDataException(String url, MalformedURLException cause) {
        super("The package has an invalid downloadUrl: " + url, cause);
    }
}