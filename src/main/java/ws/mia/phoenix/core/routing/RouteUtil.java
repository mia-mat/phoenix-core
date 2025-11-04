package ws.mia.phoenix.core.routing;

import ws.mia.phoenix.api.model.Route;

import java.net.URI;

public class RouteUtil {

	public static URI resolveDestinationUri(final Route route, URI requestUri) {
		if (route.hasVerboseDestination()) {
			return URI.create(route.getDestination());
		}

		String extractedRequestPath = getNormalizedPath(requestUri) + "?" + getNormalizedQuery(requestUri);
		if (extractedRequestPath.endsWith("?")) extractedRequestPath = extractedRequestPath.substring(0, extractedRequestPath.length() - 1);

		if(route.getDestination().contains("<path>") && route.getSource() == null) {
			// we don't append, we resolve with <path> replaced.
			return URI.create(route.getDestination().replace("<path>", extractedRequestPath));
		}

		if(route.getSource() == null) {
			return URI.create(route.getDestination() + "/" + extractedRequestPath);
		}

		String matchingSource = route.getAllSources().stream().filter(source -> {
			return (getNormalizedHost(requestUri) + "/" + getNormalizedPath(requestUri)) // doesn't matter if we trail
					.startsWith(source);
		}).findFirst().orElseThrow(() ->
				new IllegalArgumentException("requestUri does not correspond to this route (" + requestUri + " -> " + route + ")")
		);

		String requestSansScheme = getNormalizedHost(requestUri) + "/" + extractedRequestPath;
		if(requestSansScheme.endsWith("/")) requestSansScheme = requestSansScheme.substring(0, requestSansScheme.length()-1);

		String partNotPresentInRouteSource = requestSansScheme.substring(matchingSource.length());

		if(route.getDestination().contains("<path>")) {
			// we don't append, we resolve with <path> replaced.

			// something.mia.ws/https://example.com -> /https://example.com, we want https://example.com
			if(partNotPresentInRouteSource.startsWith("/")) partNotPresentInRouteSource = partNotPresentInRouteSource.substring(1);

			return URI.create(route.getDestination().replace("<path>", partNotPresentInRouteSource));
		}

		return URI.create(route.getDestination() + partNotPresentInRouteSource);
	}

	public static String getNormalizedHost(URI uri) {
		return uri.getHost();
	}

	/**
	 * @return Path without trailing slashes. Does not include query
	 */
	public static String getNormalizedPath(URI uri) {
		String path = uri.getRawPath();
		if(path == null) return "";
		if (path.startsWith("/")) path = path.substring(1);
		if (path.endsWith("/")) path = path.substring(0, path.length()-1);
		return path;
	}

	public static String getNormalizedQuery(URI uri) {
		String query = uri.getRawQuery();
		if(query == null) return "";
		if (query.startsWith("?")) query = query.substring(1);
		return query;
	}

}
