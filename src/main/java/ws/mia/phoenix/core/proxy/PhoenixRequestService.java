package ws.mia.phoenix.core.proxy;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ws.mia.phoenix.api.model.Route;
import ws.mia.phoenix.core.routing.RouteService;
import ws.mia.phoenix.core.routing.RouteUtil;

@Service
public class PhoenixRequestService {

	private final PhoenixProxyHandlerFactory phoenixProxyHandlerFactory;
	private final RouteService routeService;

	public PhoenixRequestService(PhoenixProxyHandlerFactory phoenixProxyHandlerFactory, RouteService routeService) {
		this.phoenixProxyHandlerFactory = phoenixProxyHandlerFactory;
		this.routeService = routeService;
	}

	public Mono<Void> handleRequest(ServerWebExchange exchange) {
		final ServerHttpRequest req = exchange.getRequest();

		final String path = RouteUtil.getNormalizedPath(req.getURI());
		String sourceToMatch = RouteUtil.getNormalizedHost(req.getURI()) + "/" + path;
		if (sourceToMatch.endsWith("/")) sourceToMatch = sourceToMatch.substring(0, sourceToMatch.length()-1);

		return routeService.findRouteOrFallback(sourceToMatch)
				.switchIfEmpty(Mono.defer(() ->
						Mono.error(new RuntimeException("Phoenix could not find a fallback route for this request."))
				))
				.flatMap(route -> {
					if (!route.isRedirect()) {
						return handleProxy(exchange, route);
					} else {
						return handleRedirect(exchange, route);
					}
				});
	}


	private Mono<Void> handleRedirect(ServerWebExchange exchange, Route route) {
		final ServerHttpRequest req = exchange.getRequest();
		final ServerHttpResponse res = exchange.getResponse();

		res.setStatusCode(HttpStatus.TEMPORARY_REDIRECT);

		res.getHeaders().setLocation(RouteUtil.resolveDestinationUri(route, req.getURI()));
		return res.setComplete();
	}

	private Mono<Void> handleProxy(ServerWebExchange exchange, Route route) {
		return phoenixProxyHandlerFactory.create(exchange, route).resolve();
	}
}
