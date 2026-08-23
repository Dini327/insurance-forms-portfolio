package com.insuranceforms.agent;

import java.util.Map;

public class RegistrationException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public RegistrationException(Map<String, String> fieldErrors) {
        super("Registration validation failed: " + fieldErrors);
        this.fieldErrors = fieldErrors;
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
