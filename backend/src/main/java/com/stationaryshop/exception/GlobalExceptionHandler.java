package com.stationaryshop.exception;

import com.stationaryshop.dto.ApiError;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every exception into the same JSON error body { status, error, message, field_errors }.
 * Stack traces, SQL text and credentials are written to the server log only, never sent to the browser.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final Pattern DUPLICATE = Pattern.compile("Key \\((.+?)\\)=\\((.*?)\\) already exists");

    /** Field names in error bodies use the same snake_case as the rest of the API (costPrice -> cost_price). */
    static String snake(String s) {
        return s.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message, Map<String, String> fields) {
        return ResponseEntity.status(status).body(new ApiError(status.value(), status.getReasonPhrase(), message, fields));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        return build(ex.getStatus(), ex.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(snake(f.getField()), f.getDefaultMessage()));
        String msg = String.join("; ", fields.values());
        return build(HttpStatus.BAD_REQUEST, msg.isEmpty() ? "Validation failed" : msg, fields);
    }

    /** Bean-validation failure raised by the JPA layer (second line of defence after @Valid). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (ConstraintViolation<?> v : ex.getConstraintViolations()) fields.putIfAbsent(snake(v.getPropertyPath().toString()), v.getMessage());
        String msg = String.join("; ", fields.values());
        return build(HttpStatus.BAD_REQUEST, msg.isEmpty() ? "Validation failed" : msg, fields);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> handleBadInput(Exception ex) {
        return build(HttpStatus.BAD_REQUEST, "The request contains a missing or invalid value (check numbers and dates)", null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "Resource not found", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "This operation is not supported for this address", null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMedia(HttpMediaTypeNotSupportedException ex) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Send the request body as JSON", null);
    }

    /** Database errors: the PostgreSQL SQLSTATE code selects a friendly message. */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> handleDatabase(DataAccessException ex) {
        SQLException sql = null;
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException s) sql = s;
        }
        if (sql == null) {
            log.error("Database error", ex);
            return build(HttpStatus.INTERNAL_SERVER_ERROR, "Database error. Please try again.", null);
        }
        String raw = sql.getMessage() == null ? "" : sql.getMessage();
        String state = sql.getSQLState() == null ? "" : sql.getSQLState();
        switch (state) {
            case "23505": {
                Matcher m = DUPLICATE.matcher(raw);
                String msg = m.find()
                        ? "A record with " + m.group(1) + " '" + m.group(2) + "' already exists"
                        : "A record with the same unique value already exists";
                return build(HttpStatus.CONFLICT, msg, null);
            }
            case "23503":
                return build(HttpStatus.CONFLICT,
                        "This record is linked to other records (for example orders or products) or refers to a record that does not exist", null);
            case "23514":
                return build(HttpStatus.BAD_REQUEST, "A value breaks a database rule (for example a negative price, selling price below cost or a discount above 50 percent)", null);
            case "23502":
                return build(HttpStatus.BAD_REQUEST, "A required value is missing", null);
            case "40P01":   // deadlock detected
            case "40001":   // serialization failure
            case "55P03":   // lock not available
                return build(HttpStatus.CONFLICT, "The shop database is busy with another change. Please try again.", null);
            case "P0001": { // RAISE EXCEPTION from one of our triggers: the text is written for users
                String msg = raw.startsWith("ERROR: ") ? raw.substring(7) : raw;
                int nl = msg.indexOf('\n');
                return build(HttpStatus.CONFLICT, nl > 0 ? msg.substring(0, nl) : msg, null);
            }
            default:
                log.error("Unhandled database error, SQLSTATE {}", state, ex);
                return build(HttpStatus.INTERNAL_SERVER_ERROR, "Database error (" + state + ")", null);
        }
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleOther(Exception ex) {
        log.error("Unexpected error", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error", null);
    }
}
