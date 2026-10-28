package com.stationaryshop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.stationaryshop.dto.ApiError;
import com.stationaryshop.exception.ApiException;
import com.stationaryshop.exception.GlobalExceptionHandler;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Unit tests (no database needed): the error-handling rules of the API. */
class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private ResponseEntity<ApiError> db(String sqlState, String message) {
        return handler.handleDatabase(new DataIntegrityViolationException("wrapped", new SQLException(message, sqlState)));
    }

    @Test
    void apiExceptionKeepsItsStatusAndMessage() {
        ResponseEntity<ApiError> r = handler.handleApi(ApiException.conflict("Insufficient stock"));
        assertEquals(HttpStatus.CONFLICT, r.getStatusCode());
        ApiError body = r.getBody();
        assertNotNull(body);
        assertEquals(409, body.status());
        assertEquals("Insufficient stock", body.message());
    }

    @Test
    void notFoundIs404() {
        assertEquals(HttpStatus.NOT_FOUND, handler.handleApi(ApiException.notFound("Product")).getStatusCode());
    }

    @Test
    void duplicateKeyIs409WithAFriendlyMessage() {
        ResponseEntity<ApiError> r = db("23505",
                "ERROR: duplicate key value violates unique constraint \"customers_email_key\"\n  Detail: Key (email)=(a@b.com) already exists.");
        assertEquals(HttpStatus.CONFLICT, r.getStatusCode());
        assertEquals("A record with email 'a@b.com' already exists", r.getBody().message());
    }

    @Test
    void foreignKeyViolationIs409() {
        assertEquals(HttpStatus.CONFLICT, db("23503", "ERROR: update or delete violates foreign key constraint").getStatusCode());
    }

    @Test
    void checkViolationIs400() {
        assertEquals(HttpStatus.BAD_REQUEST, db("23514", "ERROR: new row violates check constraint \"orders_discount_percent_check\"").getStatusCode());
    }

    @Test
    void triggerMessageIsShownWithoutTheTechnicalTail() {
        ResponseEntity<ApiError> r = db("P0001",
                "ERROR: Insufficient stock for product 6: available 47, requested 9999\n  Where: PL/pgSQL function trg_before_order_item() line 22");
        assertEquals(HttpStatus.CONFLICT, r.getStatusCode());
        assertEquals("Insufficient stock for product 6: available 47, requested 9999", r.getBody().message());
        assertFalse(r.getBody().message().contains("PL/pgSQL"));
    }

    @Test
    void deadlockAsksTheUserToTryAgain() {
        ResponseEntity<ApiError> r = db("40P01", "ERROR: deadlock detected");
        assertEquals(HttpStatus.CONFLICT, r.getStatusCode());
        assertFalse(r.getBody().message().contains("deadlock"));
    }

    @Test
    void unknownDatabaseErrorNeverLeaksSqlDetails() {
        ResponseEntity<ApiError> r = db("XX999", "ERROR: relation \"secret_table\" does not exist");
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, r.getStatusCode());
        assertFalse(r.getBody().message().contains("secret_table"));
    }

    @Test
    void unexpectedErrorsAreGenericAndHideTheirText() {
        ResponseEntity<ApiError> r = handler.handleOther(new IllegalStateException("password=hunter2"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, r.getStatusCode());
        assertFalse(r.getBody().message().contains("hunter2"));
    }
}
