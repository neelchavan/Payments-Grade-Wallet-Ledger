package com.paymentsgrade.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.status()).body(error(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("INTERNAL_ERROR", "Unexpected error"));
    }

    // Covers every Spring MVC exception (validation, bad JSON, missing header, 404, 405...).
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.valueOf(statusCode.value());
        String code = status.name();
        String message = status.is4xxClientError() ? clientMessage(ex, status) : "Unexpected error";
        return new ResponseEntity<>(error(code, message), headers, statusCode);
    }

    private static String clientMessage(Exception ex, HttpStatus status) {
        if (ex instanceof org.springframework.web.bind.MethodArgumentNotValidException manv) {
            return manv.getBindingResult().getFieldErrors().stream()
                    .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                    .sorted()
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("Validation failed");
        }
        if (ex instanceof org.springframework.web.bind.MissingRequestHeaderException mh) {
            return "Missing required header: " + mh.getHeaderName();
        }
        return status.getReasonPhrase();
    }

    private static ApiError error(String code, String message) {
        return new ApiError(code, message, MDC.get(RequestIdFilter.MDC_KEY));
    }
}
