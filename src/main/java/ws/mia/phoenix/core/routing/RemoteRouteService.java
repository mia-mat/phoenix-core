package ws.mia.phoenix.core.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ws.mia.phoenix.api.model.Route;
import ws.mia.phoenix.core.routing.repository.RouteRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class RemoteRouteService implements CachedRouteService {

	private static final Logger log = LoggerFactory.getLogger(RemoteRouteService.class);
	private final RouteRepository routeRepository;
	private final Map<String, Route> routeCache; // maps route source -> route

	private final List<Route> fallbackCache;

	public RemoteRouteService(RouteRepository routeRepository) {
		this.routeCache = new ConcurrentHashMap<>();
		this.routeRepository = routeRepository;
		this.fallbackCache = new CopyOnWriteArrayList<>();
	}

	@Scheduled(fixedRate = 3600000/2) // every half-hour
	public void scheduledCacheRefresh() {
		log.info("Starting scheduled cache refresh");
		refreshCaches();
	}

	@Override
	public boolean cacheRoute(Route route) {
		if (route.getSource() != null) {
			routeCache.put(route.getSource(), route);
		}

		if (route.isFallback() && !fallbackCache.contains(route)) {
			fallbackCache.add(route);
		}

		return true;
	}

	@Override
	public boolean cacheRoute(String source) {
		return Boolean.TRUE.equals(routeRepository.findBySource(source)
				.map(route -> {
					routeCache.put(route.getSource(), route);
					if (route.isFallback() && !fallbackCache.contains(route)) {
						fallbackCache.add(route);
					}
					return true;
				})
				.defaultIfEmpty(false)
				.block());
	}

	@Override
	public Map<String, Route> getRouteCache() {
		return Collections.unmodifiableMap(routeCache);
	}

	@Override
	public List<Route> getFallbackCache() {
		return Collections.unmodifiableList(fallbackCache);
	}

	@Override
	public void clearCaches() {
		routeCache.clear();
		fallbackCache.clear();
	}

	@Override
	public boolean clearCaches(Route route) {
		boolean fallback = fallbackCache.remove(route);
		boolean main = route.getSource() != null && routeCache.remove(route.getSource(), route);
		return fallback || main;
	}

	@Override
	public boolean clearCaches(String source) {
		boolean fallback = fallbackCache.removeIf(r -> {
			return r.getSource() != null && r.getSource().equals(source.toLowerCase());
		});
		boolean main = routeCache.remove(source.toLowerCase()) != null;
		return fallback || main;
	}

	@Override
	public void refreshCaches() {
		clearCaches();

		findAllRoutes().blockLast();
	}

	@Override
	public Mono<Route> findRoute(String source) {
		source = source.toLowerCase();
		Route cachedRoute = routeCache.get(source);
		if (cachedRoute != null) return Mono.just(cachedRoute);

		// if we error here, we go through our error controller anyway, so we're fine
		// simply returning this
		Mono<Route> repoRoute = routeRepository.findBySource(source);

		final String fSource = source;
		return repoRoute
				.doOnNext(this::cacheRoute)
				.doOnError(e -> log.warn("Failed to fetch route for {}", fSource, e));
	}

	@Override
	public Mono<Boolean> routeExists(String source) {
		source = source.toLowerCase();
		if (routeCache.containsKey(source)) return Mono.just(true);

		return routeRepository.existsBySource(source);
	}

	@Override
	public Flux<Route> findAllRoutes() {
		// Our route cache doesn't save *all* routes, just the fetched ones,
		// so we need to always fetch from the routeRepository for this.
		// (also allows us to refresh our whole cache)

		return routeRepository.findAll()
				.doOnNext(this::cacheRoute);
	}

	@Override
	public Mono<Route> findFallbackRoute() {
		return Mono.defer(() -> {
			// synchronization
			List<Route> fallbackCacheSnapshot = new ArrayList<>(fallbackCache);
			if (!fallbackCacheSnapshot.isEmpty()) {
				return Mono.just(fallbackCacheSnapshot.get(ThreadLocalRandom.current().nextInt(fallbackCacheSnapshot.size())));
			}

			// try to find one from repo if none are cached
			return routeRepository.findAllFallbacks()
					.collectList()
					.filter(list -> !list.isEmpty())
					.doOnNext(list -> // may as well cache
							list.forEach(this::cacheRoute)
					)
					.map(list -> list.get(ThreadLocalRandom.current().nextInt(list.size())));
		});

	}

	@Override
	public Mono<Boolean> addRoute(Route route) {
		return routeRepository.add(route)
				.map(retRoute -> {
					cacheRoute(retRoute);
					return true;
				}).defaultIfEmpty(false);
	}

	@Override
	public Mono<Boolean> removeRoute(String source) {
		return routeRepository.removeBySource(source)
				.map(r -> {
					clearCaches(source);
					return true;
				})
				.defaultIfEmpty(false);
	}

	@Override
	public Mono<Boolean> modifyRoute(String source, Route newRoute) {
		return routeRepository.modifyBySource(source, newRoute)
				.map(nr -> {
					// Might be modifying source, so we remove the old cache manually
					clearCaches(source);
					cacheRoute(nr);
					return true;
				})
				.defaultIfEmpty(false);
	}


}
