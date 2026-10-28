package com.limitactivitysystem.whitelist.exception;

public class QualificationDeniedException extends RuntimeException {

    public static final String CODE = "QUALIFICATION_DENIED";

    public QualificationDeniedException() {
        super("Current user is not eligible to participate");
    }
}
