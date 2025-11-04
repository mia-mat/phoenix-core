package ws.mia.phoenix.core.routing;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ws.mia.phoenix.api.model.Route;

@Service
public interface RouteService {

	/**
	 * Finds the corresponding route for a source, a fallback route if one cannot be found, or an empty mono if no suitable routes exist.
	 */
	default Mono<Route> findRouteOrFallback(String source) {
		return this.findRoute(source)
				.switchIfEmpty(findFallbackRoute());
	}

	/**
	 * Attempts to find a route given a source
	 * @return A Mono containing a route, an empty Mono if no route was found, or an Error Mono if the remote repository is unavailable
	 */
	Mono<Route> findRoute(String source);

	/**
	 * @return A Mono containing whether a route with the given source exists, or an Error Mono if the remote repository is unavailable
	 */
	Mono<Boolean> routeExists(String source);

	/**
	 * @return A Flux containing all routes, or an Error Mono if the remote repository is unavailable
	 */
	Flux<Route> findAllRoutes();

	/**
	 * @return A Mono containing a random fallback route, or an Error Mono if the remote repository is unavailable
	 */
	Mono<Route> findFallbackRoute();

	/**
	 * Attempts to add a new route.
	 * @return A Mono containing the newly added created, an Empty Mono if the source is taken and hence the route cannot be added,
	 * or an Error Mono if the remote repository is unavailable
	 */
	Mono<Boolean> addRoute(Route route);

	/**
	 * Attempts to remove a route given a source.
	 * @return A Mono containing the removed route, an Empty Mono if no route matching the source exists
	 * or an Error Mono if the remote repository is unavailable
	 */
	Mono<Boolean> removeRoute(String source);

	/**
	 * Attempts to modify a route given a source.
	 * @return A Mono containing the modified route, an Empty Mono if no route matching the source exists
	 * or an Error Mono if the remote repository is unavailable
	 */
	Mono<Boolean> modifyRoute(String source, Route newRoute);

}
