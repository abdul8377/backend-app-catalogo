package com.abdul.catalogo.shared.exception;

public class ResourceNotFoundException extends BusinessRuleException {
    public ResourceNotFoundException(String code, String message) {
        super(code, message);
    }
}
