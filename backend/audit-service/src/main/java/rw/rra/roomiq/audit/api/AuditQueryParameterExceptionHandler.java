package rw.rra.roomiq.audit.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import rw.rra.roomiq.common.web.ApiError;
import rw.rra.roomiq.common.web.CorrelationIdFilter;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice(basePackageClasses = AuditEventSearchController.class)
public class AuditQueryParameterExceptionHandler {
    @ExceptionHandler({TypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> handleInvalidQueryParameter(Exception exception, HttpServletRequest request) {
        String correlationId = CorrelationIdFilter.resolveCorrelationId(request);
        ApiError error = new ApiError("INVALID_REQUEST", "One or more query parameters are invalid",
                request.getRequestURI(), HttpStatus.BAD_REQUEST.value(), correlationId, List.of(), Instant.now());
        return ResponseEntity.badRequest().body(error);
    }
}
