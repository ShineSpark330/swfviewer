package com.swfmanager.core;

/** Thrown when an SWF (or part of one) cannot be parsed. */
public class SwfException extends RuntimeException {
    public SwfException(String message) {
        super(message);
    }

    public SwfException(String message, Throwable cause) {
        super(message, cause);
    }
}
