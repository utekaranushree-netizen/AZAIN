package com.stationaryshop.dto;

import java.util.Map;

/** JSON error body: { status, error, message, field_errors }. */
public record ApiError(int status, String error, String message, Map<String, String> fieldErrors) {
}
