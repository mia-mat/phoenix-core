package ws.mia.phoenix.core.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ws.mia.phoenix.api.model.Route;
import ws.mia.phoenix.api.model.response.*;
import ws.mia.phoenix.core.routing.CachedRouteService;
import ws.mia.phoenix.core.routing.RouteService;

@RestController
@RequestMapping("/api")
public class PhoenixAPIController {

	private final RouteService routeService;
	private final BuildProperties buildProperties;

	public PhoenixAPIController(RouteService routeService, BuildProperties buildProperties) {
		this.routeService = routeService;
		this.buildProperties = buildProperties;
	}

	@PostMapping("flush-route-cache")
	public Mono<FlushRouteCacheResponse> flushRouteCache(@RequestParam(required = false) String source) {
		if (!(routeService instanceof CachedRouteService cachedRouteService)) {
			throw new IllegalStateException("PhoenixAPIController requires a CachedRouteService for Cache operations");
		}

		if (source == null || source.isBlank()) {
			cachedRouteService.clearCaches();
			return Mono.just(FlushRouteCacheResponse.SUCCESS);
		}

		return cachedRouteService.routeExists(source).map(b -> {
			if (b) {
				return cachedRouteService.clearCaches(source)
						? FlushRouteCacheResponse.SUCCESS
						: FlushRouteCacheResponse.ROUTE_NOT_CACHED;
			} else return FlushRouteCacheResponse.ROUTE_NOT_FOUND;
		});
	}

	@GetMapping("route")
	public Mono<Route> getRoutes(@RequestParam String source) {
		if (source == null || source.isBlank()) return Mono.empty();
		return routeService.findRoute(source);
	}

	@GetMapping("routes")
	public Flux<Route> getRoutes() {
		return routeService.findAllRoutes();
	}

	@PostMapping("push-route")
	Mono<PushRouteResponse> pushRoute(@RequestBody Route route) {
		return routeService.addRoute(route)
				.map(b -> b
						? PushRouteResponse.SUCCESS
						: PushRouteResponse.ROUTE_ALREADY_EXISTS);
	}

	@PostMapping("modify-route")
	Mono<ModifyRouteResponse> modifyRoute(@RequestParam String source, @RequestBody Route newRoute) {
		return routeService.modifyRoute(source, newRoute)
				.map(b -> b
						? ModifyRouteResponse.SUCCESS
						: ModifyRouteResponse.ROUTE_NOT_FOUND);
	}

	@PostMapping("remove-route")
	Mono<RemoveRouteResponse> removeRoute(@RequestParam String source) {
		return routeService.removeRoute(source)
				.map(b -> b
						? RemoveRouteResponse.SUCCESS
						: RemoveRouteResponse.ROUTE_NOT_FOUND);
	}

	@GetMapping("requires-password")
	Mono<PasswordProtectedResponse> isPasswordProtected(@RequestParam(required = false) String source) {
		if (source == null || source.isBlank()) {
			return Mono.just(PasswordProtectedResponse.ROUTE_NOT_FOUND);
		}

		return routeService.findRoute(source)
				.map(r -> r.isPasswordProtected()
						? PasswordProtectedResponse.YES
						: PasswordProtectedResponse.NO
				).defaultIfEmpty(PasswordProtectedResponse.ROUTE_NOT_FOUND);

	}

	@GetMapping("route-exists")
	public Mono<Boolean> routeExists(@RequestParam(required = false) String source) {
		return routeService.routeExists(source);
	}

	@GetMapping(value = "version", produces = "text/plain")
	public String getVersion() {
		return buildProperties.getVersion();
	}

	@PostMapping("ping")
	public Mono<Void> ping(ServerWebExchange exchange) {
		ServerHttpRequest request = exchange.getRequest();
		ServerHttpResponse response = exchange.getResponse();

		response.getHeaders().setContentType(MediaType.APPLICATION_OCTET_STREAM);

		// echo
		return response.writeWith(request.getBody());
	}


}
