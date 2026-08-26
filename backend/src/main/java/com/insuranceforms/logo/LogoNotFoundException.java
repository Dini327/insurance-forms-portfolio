package com.insuranceforms.logo;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class LogoNotFoundException extends RuntimeException {
    public LogoNotFoundException(UUID id) {
        super("No logo found for id " + id);
    }
}
