package com.aiswarya.songtagger.service;

import org.springframework.http.HttpStatus;

public class WorkflowException extends RuntimeException {
    private final HttpStatus status;

    public WorkflowException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public WorkflowException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
