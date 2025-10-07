package com.fidesmo.fdsm.exceptions;

public class ServiceNotAvailableException extends FDSMException {
    public static enum ErrorCode {
        NOT_AVAILABLE_FOR_DEVICE,
        NOT_AVAILABLE_FOR_CLIENT,
        NOT_AVAILABLE_IN_CURRENT_STATE,
        UNKNOWN_SERVICE
    }

    public ServiceNotAvailableException(String message, ErrorCode errorCode) {
        super(message);
    }

    public ServiceNotAvailableException(String message, ErrorCode errorCode, Throwable cause) {
        super(message, cause);
    }
  
}
