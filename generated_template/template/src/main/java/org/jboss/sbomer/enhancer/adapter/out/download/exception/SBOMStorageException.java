package org.jboss.sbomer.enhancer.adapter.out.download.exception;

public class SBOMStorageException extends RuntimeException {

    private final String errorCode;

    public SBOMStorageException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public SBOMStorageException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}