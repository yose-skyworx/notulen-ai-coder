package com.ilkeiapps.skyworx.notulenai.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.ilkeiapps.skyworx.notulenai.bean.web.ApiResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * Centralized exception handling. Unhandled exceptions thrown while processing a request are mapped
 * here to the standard {@link ApiResponse} wrapper with an explicit HTTP status, instead of falling
 * through to Spring's {@code /error} dispatch.
 * <p>
 * Why this matters: under the stateless JWT setup the {@code JwtAuthenticationFilter} is an
 * {@code OncePerRequestFilter}, which by default skips the internal ERROR dispatch. With no
 * authentication on that dispatch and {@code /error} not permitted, Spring Security's authorization
 * filter returned {@code 403} for what was really a {@code 5xx} — masking the true error. Handling the
 * exception here means no ERROR dispatch happens, so the real status is returned.
 * <p>
 * Client input errors (e.g. a non-numeric path variable) are mapped to {@code 400} so they are not
 * swept into the generic {@code 500}. Exception messages are never echoed to the client.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	/** Bad request binding (e.g. {@code /get/abc} where a {@code Long} id is expected). */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiResponse<Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
		log.warn("bad request parameter: {}", ex.getMessage());
		return build(HttpStatus.BAD_REQUEST, "Permintaan tidak valid");
	}

	/** Anything else: a genuine server-side error. Logged once here, with a safe generic message. */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Object>> handleUnexpected(Exception ex) {
		log.error("unhandled exception during request processing", ex);
		return build(HttpStatus.INTERNAL_SERVER_ERROR, "Terjadi kesalahan pada server");
	}

	private ResponseEntity<ApiResponse<Object>> build(HttpStatus status, String message) {
		var wrap = new ApiResponse<Object>();
		wrap.setStatus(false);
		wrap.setMessage(message);
		return ResponseEntity.status(status).body(wrap);
	}
}
