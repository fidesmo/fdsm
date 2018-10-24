package com.fidesmo.fdsm;

public class UserCancelledException extends RuntimeException {
    UserCancelledException(String message) {
        super(message);
    }
}
