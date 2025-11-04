package ws.mia.phoenix.core.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ws.mia.phoenix.api.model.response.PhoenixErrorResponse;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** <pre>
 * Global error handler for Phoenix.
 * Catches all unhandled exceptions and returns standardized JSON error responses.
 *
 * Error JSON Format (example): <pre>
 * {@code
 * {
 *   "_": "Phoenix Error - vX.X",
 *   "timestamp": "1970-01-01T00:00:00.000000000Z",
 *   "path": "/path",
 *   "status": 404,
 *   "error": "Not Found",
 *   "message": "No static resource path."
 * }
 * }
 * </pre>
 * </pre>
 */
@Component
@Order(-2) // Spring's default ErrorWebExceptionHandler is -1, -2 gets executed first
public class PhoenixErrorController implements ErrorWebExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(PhoenixErrorController.class);
	private static final String GENERIC_ERROR_MESSAGE = "An unexpected error occurred";

	private final ObjectMapper objectMapper;
	private final BuildProperties buildProperties;

	public PhoenixErrorController(ObjectMapper objectMapper, BuildProperties buildProperties) {
		this.objectMapper = objectMapper;
		this.buildProperties = buildProperties;
	}

	@Override
	public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
		final ServerHttpResponse response = exchange.getResponse();
		final ServerHttpRequest request = exchange.getRequest();

		final HttpStatus status = determineStatus(ex);
		final String message = extractMessage(ex, status);

		if(status.is5xxServerError() && !propagate5xxError(ex)) {
			log.warn("Encountered a {} error", status, ex);
		}

		response.setStatusCode(status);
		response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

		PhoenixErrorResponse errorResponse = new PhoenixErrorResponse();
		errorResponse.setPhoenix("Phoenix Proxy Error - v" + buildProperties.getVersion());
		errorResponse.setTimestamp(Instant.now().toString());
		errorResponse.setPath(request.getPath().value());
		errorResponse.setStatus(status.value());
		errorResponse.setError(status.getReasonPhrase());
		errorResponse.setMessage(message);

		try {
			byte[] bytes = objectMapper.writeValueAsBytes(errorResponse);
			DataBuffer buffer = response.bufferFactory().wrap(bytes);
			return response.writeWith(Mono.just(buffer));
		} catch (JsonProcessingException e) {
			// Fallback to minimal JSON
			String fallbackJson = String.format(
					"""
							{
							"_": "Phoenix Proxy Error - v%s",
							"timestamp":"%s",
							"status":500,
							"error":"Internal Server Error",
							"message":"%s"
							}
					""",
					buildProperties.getVersion(),
					Instant.now().toString(),
					GENERIC_ERROR_MESSAGE
			);
			DataBuffer buffer = response.bufferFactory()
					.wrap(fallbackJson.getBytes(StandardCharsets.UTF_8));
			return response.writeWith(Mono.just(buffer));
		}
	}

	private HttpStatus determineStatus(Throwable ex) {
		if (ex instanceof ResponseStatusException rse) {
			return HttpStatus.resolve(rse.getStatusCode().value());
		}

		return HttpStatus.INTERNAL_SERVER_ERROR;
	}

	private String extractMessage(Throwable ex, HttpStatus status) {
		if (ex instanceof ResponseStatusException rse) {
			String reason = rse.getReason();
			if (reason != null && !reason.isBlank()) {
				return reason;
			}
		}

		// Don't expose internal error details for 5xx
		if (status.is5xxServerError() && !propagate5xxError(ex)) {
			return GENERIC_ERROR_MESSAGE;
		}

		String exMessage = ex.getMessage();
		return (exMessage != null && !exMessage.isBlank())
				? exMessage
				: status.getReasonPhrase();
	}

	private boolean propagate5xxError(Throwable ex) {
		return (ex instanceof WebClientRequestException ||
				ex.getCause() instanceof UnknownHostException ||
				ex.getCause() instanceof ConnectException ||
				ex.getCause() instanceof SocketTimeoutException);
	}

}