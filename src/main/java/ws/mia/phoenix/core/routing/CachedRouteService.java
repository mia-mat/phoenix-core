package ws.mia.phoenix.core.routing;

import org.springframework.stereotype.Service;
import ws.mia.phoenix.api.model.Route;

import java.util.List;
import java.util.Map;

///
/// RouteService which uses a cache to find route matches. <br>
/// We don't use reactive returns as in RouteService due to the intended in-memory implementation of caching,
/// which is entirely non-reactive.
///
@Service
public interface CachedRouteService extends RouteService {

	/**
	 * Attempts to cache a route through its source.
	 * @return If the route was successfully cached
	 */
	boolean cacheRoute(Route route);

	/**
	 * Attempts to cache a route given its source, using a route repository.
	 * @return If the route was successfully cached (i.e. if the route exists)
	 */
	boolean cacheRoute(String source);

	/**
	 * @return An unmodifiable map representing each route's source mapped to its corresponding route DTO
	 */
	Map<String, Route> getRouteCache();

	/**
	 * @return An unmodifiable list of fallback routes
	 */
	List<Route> getFallbackCache();

	/**
	 * Clears the route and fallback caches
	 */
	void clearCaches();

	/**
	 * Removes a specific route from the route cache
	 * @return If the specified root was present, and thus removed, from the route cache
	 */
	boolean clearCaches(Route route);

	/**
	 * Removes a specific route from the route cache given its source
	 * @return If the specified root was present, and thus removed, from the route cache
	 */
	boolean clearCaches(String source);

	/**
	 * Clears caches and caches all routes found in its repository
	 */
	void refreshCaches();

}
