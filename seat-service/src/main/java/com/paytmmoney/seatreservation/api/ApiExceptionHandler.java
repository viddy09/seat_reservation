package com.paytmmoney.seatreservation.api;

import com.paytmmoney.seatreservation.common.SqlStates;
import com.paytmmoney.seatreservation.domain.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The single place where exceptions become HTTP responses. Every response it produces uses the {@link ErrorResponse}
 * envelope, including Spring MVC's own exceptions (unknown route, wrong method, unreadable JSON, ...), which
 * {@link ResponseEntityExceptionHandler} already maps to the correct status.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Framework exceptions: keep Spring's status, replace its body with our envelope. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            log.error("Unhandled framework error", ex);
        }
        ErrorResponse envelope = ErrorResponse.of(
                ErrorCodes.forStatus(status), ErrorCodes.messageFor(status), Map.of(), requestId(request));
        return ResponseEntity.status(status).headers(headers).body(envelope);
    }

    /** Syntactically broken JSON, wrong types, or a fractional number where an integer is required. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ErrorResponse envelope = ErrorResponse.of(
                ErrorCodes.INVALID_JSON,
                "Request body is not valid JSON for this endpoint",
                Map.of(),
                requestId(request));
        return ResponseEntity.badRequest().headers(headers).body(envelope);
    }

    /** Well-formed JSON that breaks a rule: 422, with the offending fields. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), String.valueOf(error.getDefaultMessage())));
        return validationFailed(fields, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<String> problems = ex.getAllErrors().stream()
                .map(error -> String.valueOf(error.getDefaultMessage()))
                .toList();
        return validationFailed(Map.of("problems", problems), headers, request);
    }

    /** A malformed id in the path can't name an existing resource: 404, same as an unknown id. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex instanceof MethodArgumentTypeMismatchException mismatch
                && mismatch.getParameter().hasParameterAnnotation(PathVariable.class)) {
            ErrorResponse envelope = ErrorResponse.of(ErrorCodes.NOT_FOUND, "Not Found", Map.of(), requestId(request));
            return ResponseEntity.status(HttpStatus.NOT_FOUND).headers(headers).body(envelope);
        }
        return super.handleTypeMismatch(ex, headers, status, request);
    }

    /**
     * Expected declines. Logged at INFO without a stack trace: under a stampede these are the normal outcome, not
     * errors.
     */
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> handleDomain(DomainException ex, HttpServletRequest request) {
        DomainErrorMapping.Mapped mapped = DomainErrorMapping.map(ex);
        log.info(
                "Request declined: code={} status={}",
                mapped.code(),
                mapped.status().value());
        ErrorResponse envelope =
                ErrorResponse.of(mapped.code(), ex.getMessage(), mapped.details(), RequestIdFilter.current(request));
        ResponseEntity.BodyBuilder response = ResponseEntity.status(mapped.status());
        if (mapped.retryAfterSeconds() > 0) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(mapped.retryAfterSeconds()));
        }
        return response.body(envelope);
    }

    /**
     * Database failures that escaped the service layer, mapped by SQLSTATE (ADR-014): saturation is a 429 the client
     * can retry; an unreachable database is a 503 (fail closed, never guess); anything else is a bug.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccess(DataAccessException ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.current(request);
        return switch (SqlStates.classify(ex)) {
            case BUSY, RETRYABLE -> {
                log.warn("Database saturated: {}", ex.getMessage());
                yield ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, String.valueOf(DomainErrorMapping.RETRY_AFTER_SECONDS))
                        .body(ErrorResponse.of(
                                ErrorCodes.SERVER_BUSY, "Server is busy; retry shortly", Map.of(), requestId));
            }
            case UNAVAILABLE -> {
                log.error("Database unavailable: {}", ex.getMessage());
                yield ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .header(HttpHeaders.RETRY_AFTER, String.valueOf(DomainErrorMapping.RETRY_AFTER_SECONDS))
                        .body(ErrorResponse.of(
                                ErrorCodes.SERVICE_UNAVAILABLE, "Database unavailable", Map.of(), requestId));
            }
            case OTHER -> handleUnexpected(ex, request);
        };
    }

    /**
     * Last resort. Logs the full stack at ERROR because it means a bug; the burst must never trigger it, and the 2am
     * paging list alerts on any 5xx.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        ErrorResponse envelope = ErrorResponse.of(
                ErrorCodes.INTERNAL_ERROR, "Internal error", Map.of(), RequestIdFilter.current(request));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(envelope);
    }

    private ResponseEntity<Object> validationFailed(
            Map<String, Object> details, HttpHeaders headers, WebRequest request) {
        ErrorResponse envelope =
                ErrorResponse.of(ErrorCodes.VALIDATION, "Request validation failed", details, requestId(request));
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .headers(headers)
                .body(envelope);
    }

    private static String requestId(WebRequest request) {
        if (request instanceof NativeWebRequest nativeRequest) {
            HttpServletRequest servletRequest = nativeRequest.getNativeRequest(HttpServletRequest.class);
            if (servletRequest != null) {
                return RequestIdFilter.current(servletRequest);
            }
        }
        return null;
    }
}
