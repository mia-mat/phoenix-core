package ws.mia.phoenix.core.routing.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndReplaceOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.reactivestreams.client.MongoCollection;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;
import ws.mia.phoenix.api.model.Route;

import java.net.URI;
import java.util.*;

@Repository
public class MongoRouteRepository implements RouteRepository {

	private static final Logger log = LoggerFactory.getLogger(MongoRouteRepository.class);
	private final MongoCollection<Document> routeCollection;

	private final ObjectMapper objectMapper;

	public MongoRouteRepository(MongoCollection<Document> routesCollection, ObjectMapper objectMapper) {
		this.routeCollection = routesCollection;
		this.objectMapper = objectMapper;

		verifyIndexes();
		validateAndNormalizeSources();
		validateDestinations();
	}

	private void verifyIndexes() {
		Flux.from(routeCollection.listIndexes())
				.collectList()
				.subscribe(indexes -> {
					boolean hasSourceIndex = indexes.stream()
							.anyMatch(doc -> {
								Document key = doc.get("key", Document.class);
								return key != null && key.containsKey("source");
							});

					boolean hasFallbackIndex = indexes.stream()
							.anyMatch(doc -> {
								Document key = doc.get("key", Document.class);
								return key != null && key.containsKey("fallback");
							});

					if (!hasSourceIndex) {
						log.warn("Missing index on 'source' field - performance will be degraded!");
					}

					if (!hasFallbackIndex) {
						log.warn("Missing index on 'fallback' field - performance will be degraded!");
					}
				});
	}

	/**
	 * Route sources are always lowercase and don't end with a trailing slash.
	 * This function normalizes all Mongo sources to match that schema and ensures
	 * no duplicate sources/aliases exist across routes.
	 */
	private void validateAndNormalizeSources() {
		Long count = Flux.from(routeCollection.find())
				.collectList()
				.flatMap(documents -> {
					Map<String, String> seenSources = new HashMap<>();
					List<Mono<Integer>> updates = new ArrayList<>();

					for (Document document : documents) {
						String id = document.getObjectId("_id").toString();
						String source = document.getString("source");
						List<String> aliases = document.getList("aliases", String.class, new ArrayList<>());

						boolean needsUpdate = false;
						Document updateDoc = new Document();
						List<String> validAliases = new ArrayList<>();

						// Normalize and validate source
						if (source != null) {
							String normalized = source.toLowerCase();
							if (normalized.endsWith("/")) {
								normalized = normalized.substring(0, normalized.length() - 1);
							}

							if (seenSources.containsKey(normalized)) {
								log.error("Duplicate source '{}' found in routes {} and {}",
										normalized, seenSources.get(normalized), id);
							} else {
								seenSources.put(normalized, id);

								if (!source.equals(normalized)) {
									updateDoc.append("source", normalized);
									needsUpdate = true;
								}
							}
						}

						// Normalize and validate aliases
						for (String alias : aliases) {
							String normalized = alias.toLowerCase();
							if (normalized.endsWith("/")) {
								normalized = normalized.substring(0, normalized.length() - 1);
							}

							if (seenSources.containsKey(normalized)) {
								log.warn("Duplicate alias '{}' in route {} conflicts with route {} - removing alias",
										normalized, id, seenSources.get(normalized));
								needsUpdate = true;
							} else {
								seenSources.put(normalized, id);
								validAliases.add(normalized);
							}
						}

						// Update aliases if they changed
						if (!aliases.equals(validAliases)) {
							updateDoc.append("aliases", validAliases);
							needsUpdate = true;
						}

						if (needsUpdate) {
							log.info("Normalizing route {}: {}", id, updateDoc.toJson());
							Mono<Integer> update = Mono.from(routeCollection.updateOne(
									Filters.eq("_id", document.getObjectId("_id")),
									new Document("$set", updateDoc)
							)).then(Mono.just(1));
							updates.add(update);
						}
					}

					return Flux.fromIterable(updates)
							.flatMap(mono -> mono)
							.count();
				})
				.block();

		if (count != null && count > 0) {
			log.info("Normalized and deduplicated {} route(s)", count);
		}
	}

	/**
	 * Verifies that all destinations in Mongo are valid URI's
	 */
	private void validateDestinations() {
		Long count = Flux.from(routeCollection.find())
				.handle(this::addRouteToSinkFromDocument)
				.flatMap(route -> {
					String destination = route.getDestination();

					if (destination == null || destination.isBlank()) {
						return Flux.error(new IllegalStateException("Route '" + route.getSource() + "' has a null or empty destination - fix your config."));
					}

					if (destination.contains("<path>")) {
						return Mono.empty();
					}

					try {
						URI uri = URI.create(destination);

						if (uri.getScheme() == null) {
							String fixedDestination = "https://" + destination;
							log.info("Route {} missing scheme, updating destination from '{}' to '{}'",
									route.getSource(), destination, fixedDestination);

							URI.create(fixedDestination); // validate

							Route updated = new Route.Builder()
									.from(route)
									.destination(fixedDestination)
									.build();
							return modifyBySource(route.getSource(), updated).thenReturn(1);
						}

						return Mono.empty();
					} catch (IllegalArgumentException e) {
						return Flux.error(new IllegalStateException("Route '" + route.getSource() + "' has an invalid destination URI '" + destination + "': " + e.getMessage() + " - fix your config."));
					}
				})
				.count()
				.block();

		if (count != null && count > 0) {
			log.info("Fixed {} route destination(s)", count);
		}
	}

	/// Example:
	/// `source = foo.example.com/alpha/beta/gamma` <br>
	/// -> returns `[foo.example.com, foo.example.com/alpha, foo.example.com/alpha/beta, foo.example.com/alpha/beta/gamma]` <br>
	/// (and versions with a trailing slash)
	///
	/// @return A list of valid routes which would correspond to `source` given that source may have a longer
	/// path part than a source specified in DB.
	private List<String> generatePrefixes(String source) {
		List<String> prefixes = new ArrayList<>();
		String[] parts = source.split("/");
		StringBuilder prefix = new StringBuilder();
		for (int i = 0; i < parts.length; i++) {
			if (i > 0) prefix.append("/");
			prefix.append(parts[i]);
			String p = prefix.toString();
			prefixes.add(p);
			prefixes.add(p + "/"); // if we have a trailing slash in Mongo, as we filter before converting to our DTO which normalizes
		}
		return prefixes;
	}

	private Route routeFromDocument(Document document) throws JsonProcessingException {
		return Route.fromJsonNode(objectMapper.readTree(document.toJson()));
	}

	private void addRouteToSinkFromDocument(Document document, SynchronousSink<Route> sink) {
		try {
			sink.next(routeFromDocument(document));
		} catch (JsonProcessingException e) {
			log.warn("Skipping malformed route document: {}", document, e);
		}
	}

	@Override
	public Mono<Route> findBySource(String source) {
		if (source == null || source.isBlank()) return Mono.empty();
		source = source.toLowerCase();

		// all of our route sources are normalized to not have trailing slashes, so prune here too
		if (source.endsWith("/")) source = source.substring(0, source.length() - 1);

		List<String> prefixes = generatePrefixes(source);
		if (prefixes.isEmpty()) return Mono.empty();

		return Flux.from(routeCollection.find(Filters.or(
						Filters.in("source", prefixes),
						Filters.in("aliases", prefixes)
				)))
				.<Route>handle(this::addRouteToSinkFromDocument)
				.sort(Comparator.comparingInt((Route r) -> -r.getSource().length())) // sort by longest matching prefix
				.next();
	}

	@Override
	public Mono<Boolean> existsBySource(String source) {
		if (source == null) return Mono.just(false);
		source = source.toLowerCase();
		if (source.endsWith("/")) source = source.substring(0, source.length() - 1);

		return Mono.from(routeCollection.find(Filters.or(
				Filters.eq("source", source),
				Filters.in("aliases", source)
		)).first()).hasElement();
	}

	@Override
	public Flux<Route> findAll() {
		return Flux.from(routeCollection.find()).handle(this::addRouteToSinkFromDocument);
	}

	@Override
	public Flux<Route> findAllFallbacks() {
		return Flux.from(routeCollection.find(Filters.eq("fallback", true)))
				.handle(this::addRouteToSinkFromDocument);
	}

	@Override
	public Mono<Route> add(Route route) {
		if (route == null || route.getSource() == null) {
			return Mono.empty();
		}

		List<Mono<Boolean>> checks = route.getAllSources().stream()
				.map(this::existsBySource)
				.toList();

		return Flux.fromIterable(checks)
				.flatMap(mono -> mono)
				.any(exists -> exists)
				.flatMap(anyExists -> {
					if (anyExists) return Mono.empty();

					return Mono.fromCallable(() -> {
								String json = objectMapper.writeValueAsString(route);
								return Document.parse(json);
							})
							.flatMap(document ->
									Mono.from(routeCollection.insertOne(document)).thenReturn(route)
							);
				});
	}

	@Override
	public Mono<Route> modifyBySource(String source, Route route) {
		if (source == null || source.isBlank() || route == null) {
			return Mono.empty();
		}

		source = source.toLowerCase();
		if (source.endsWith("/")) {
			source = source.substring(0, source.length() - 1);
		}

		String fSource = source;
		return Mono.fromCallable(() -> { // Route -> Doc
					String json = objectMapper.writeValueAsString(route);
					return Document.parse(json);
				})
				.flatMap(newDocument -> Mono.from(routeCollection.findOneAndReplace(
						Filters.eq("source", fSource),
						newDocument,
						new FindOneAndReplaceOptions().returnDocument(ReturnDocument.AFTER) // get doc post-replacement
				)))
				.flatMap(document -> {
					if (document == null) return Mono.empty();
					try {
						return Mono.just(routeFromDocument(document));
					} catch (JsonProcessingException e) {
						log.error("Failed to deserialize modified route", e);
						return Mono.error(e);
					}
				});
	}

	@Override
	public Mono<Route> removeBySource(String source) {
		if (source == null || source.isBlank()) {
			return Mono.empty();
		}

		source = source.toLowerCase();
		if (source.endsWith("/")) {
			source = source.substring(0, source.length() - 1);
		}

		final String fSource = source;
		return Mono.from(routeCollection.findOneAndDelete(Filters.eq("source", fSource)))
				.flatMap(document -> {
					if (document == null) return Mono.empty();
					try {
						return Mono.just(routeFromDocument(document));
					} catch (JsonProcessingException e) {
						log.error("Failed to deserialize removed route", e);
						return Mono.error(e);
					}
				});
	}
}
