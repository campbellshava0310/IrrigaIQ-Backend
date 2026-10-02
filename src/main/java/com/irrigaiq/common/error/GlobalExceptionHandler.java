package com.irrigaiq.common.error;

import java.util.List;
import java.util.stream.Stream;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates every failure into an RFC 9457 problem response. Expected failures carry their own
 * {@link ErrorCode}; anything unexpected becomes an opaque 500 that is logged with the request id.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	public record FieldViolation(String field, String message) {
	}

	@ExceptionHandler(ApiException.class)
	ResponseEntity<Object> handleApiException(ApiException ex, WebRequest request) {
		ProblemDetail problem = ProblemDetails.of(ex.getStatus(), ex.getCode(), ex.getMessage());
		ex.getProperties().forEach(problem::setProperty);
		return handleExceptionInternal(ex, problem, new HttpHeaders(), ex.getStatus(), request);
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		Stream<FieldViolation> fieldErrors = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map((FieldError error) -> new FieldViolation(error.getField(), error.getDefaultMessage()));
		Stream<FieldViolation> globalErrors = ex.getBindingResult()
			.getGlobalErrors()
			.stream()
			.map((error) -> new FieldViolation(error.getObjectName(), error.getDefaultMessage()));
		return validationProblem(ex, Stream.concat(fieldErrors, globalErrors).toList(), headers, request);
	}

	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldViolation> violations = ex.getParameterValidationResults()
			.stream()
			.flatMap((result) -> result.getResolvableErrors()
				.stream()
				.map((error) -> new FieldViolation(result.getMethodParameter().getParameterName(),
						error.getDefaultMessage())))
			.toList();
		return validationProblem(ex, violations, headers, request);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
		List<FieldViolation> violations = ex.getConstraintViolations()
			.stream()
			.map((violation) -> new FieldViolation(violation.getPropertyPath().toString(), violation.getMessage()))
			.toList();
		return validationProblem(ex, violations, new HttpHeaders(), request);
	}

	@ExceptionHandler(PropertyReferenceException.class)
	ResponseEntity<Object> handleInvalidSort(PropertyReferenceException ex, WebRequest request) {
		return problem(ex, HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST,
				"Unknown sort property '" + ex.getPropertyName() + "'", request);
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<Object> handleDataIntegrity(DataIntegrityViolationException ex, WebRequest request) {
		log.info("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
		return problem(ex, HttpStatus.CONFLICT, ErrorCode.CONFLICT, "The request conflicts with existing data",
				request);
	}

	@ExceptionHandler(OptimisticLockingFailureException.class)
	ResponseEntity<Object> handleOptimisticLock(OptimisticLockingFailureException ex, WebRequest request) {
		return problem(ex, HttpStatus.CONFLICT, ErrorCode.CONCURRENT_MODIFICATION,
				"The resource was modified concurrently; reload and try again", request);
	}

	// Method-security failures surface here rather than in the security filter chain.
	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
		return problem(ex, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN,
				"You do not have permission to perform this action", request);
	}

	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<Object> handleAuthentication(AuthenticationException ex, WebRequest request) {
		return problem(ex, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "Authentication is required", request);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
		log.error("Unhandled exception", ex);
		return problem(ex, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
				"An unexpected error occurred. Quote the request id when contacting support.", request);
	}

	@Override
	protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode statusCode,
			WebRequest request) {
		if (body instanceof ProblemDetail problem) {
			ProblemDetails.enrich(problem, statusCode);
		}
		return super.createResponseEntity(body, headers, statusCode, request);
	}

	private ResponseEntity<Object> validationProblem(Exception ex, List<FieldViolation> violations,
			HttpHeaders headers, WebRequest request) {
		ProblemDetail problem = ProblemDetails.of(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
				"The request contains invalid fields");
		problem.setProperty("errors", violations);
		return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
	}

	private ResponseEntity<Object> problem(Exception ex, HttpStatus status, ErrorCode code, String detail,
			WebRequest request) {
		return handleExceptionInternal(ex, ProblemDetails.of(status, code, detail), new HttpHeaders(), status,
				request);
	}

}
