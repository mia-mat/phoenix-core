package ws.mia.phoenix.core.routing.repository;

import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ws.mia.phoenix.api.model.Route;

@Repository
public interface RouteRepository {

	/**
	 * Attempts to find a route given a source
	 * @return A Mono containing a route, an empty Mono if no route was found, or an Error Mono if the remote repository is unavailable
	 */
	Mono<Route> findBySource(String source);

	/**
	 * @return A Mono containing whether a route with the given source exists, or an Error Mono if the remote repository is unavailable
	 */
	Mono<Boolean> existsBySource(String source);

	/**
	 * @return A Flux containing all routes, or an Error Mono if the remote repository is unavailable
	 */
	Flux<Route> findAll();

	/**
	 * @return A Flux containing all fallback routes, or an Error Mono if the remote repository is unavailable
	 */
	Flux<Route> findAllFallbacks();

	/**
	 * Attempts to add a new route.
	 * @return A Mono containing the newly added created, an Empty Mono if the source is taken and hence the route cannot be added,
	 * or an Error Mono if the remote repository is unavailable
	 */
	Mono<Route> add(Route route);

	/**
	 * Attempts to modify a route given a source.
	 * @return A Mono containing the modified route, an Empty Mono if no route matching the source exists
     * or an Error Mono if the remote repository is unavailable
	 */
	Mono<Route> modifyBySource(String source, Route route);


	/**
	 * Attempts to remove a route given a source.
	 * @return A Mono containing the removed route, an Empty Mono if no route matching the source exists
	 * or an Error Mono if the remote repository is unavailable
	 */
	Mono<Route> removeBySource(String source);
}
