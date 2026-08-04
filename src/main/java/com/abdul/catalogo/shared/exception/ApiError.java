package com.abdul.catalogo.shared.exception;

import java.time.Instant;
import java.util.Map;

public record ApiError(String code, String message, Map<String, Object> details, Instant timestamp) {
}
