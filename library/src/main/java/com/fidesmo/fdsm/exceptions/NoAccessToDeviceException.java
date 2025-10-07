package com.fidesmo.fdsm.exceptions;

public class NoAccessToDeviceException extends FDSMException {

  public NoAccessToDeviceException(String message) {
        super(message);
    }

    public NoAccessToDeviceException(String message, Throwable cause) {
        super(message, cause);
    }
  
}
