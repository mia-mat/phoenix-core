package ws.mia.phoenix.core;

import com.google.common.net.InternetDomainName;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import ws.mia.phoenix.core.proxy.PhoenixRequestService;

import java.net.InetSocketAddress;

/// Makes sure `phoenix` subdomain passes through to our controllers while all others are reverse-proxied, and manages API Auth
@Component
public class PhoenixRoutingFilter implements WebFilter {

	private String phoenixHostSubdomain;

	private final PhoenixRequestService phoenixRequestService;

	private final String API_AUTH_TOKEN = System.getenv("PHOENIX_API_TOKEN");

	public PhoenixRoutingFilter(PhoenixRequestService phoenixRequestService) {
		this.phoenixRequestService = phoenixRequestService;
		this.phoenixHostSubdomain = System.getenv("PHOENIX_SUBDOMAIN");
		if (phoenixHostSubdomain == null) phoenixHostSubdomain = "phoenix";
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		final InetSocketAddress host = exchange.getRequest().getHeaders().getHost();
		if (host == null) return forwardToRequestService(exchange);
		final String hostname = host.getHostName();

		// We use Guava since knowing if a part of a string is a subdomain is tricky
		// (i.e. consider phoenix.org as a domain here, or if we were looking at splitting for dots, something.co.uk)
		InternetDomainName idn = InternetDomainName.from(hostname);

		// topPrivateDomain() gives you the domain without a subdomain
		if (!idn.hasPublicSuffix()) {
			return forwardToRequestService(exchange);
		}

		final String topPrivateDomainName = idn.topPrivateDomain().toString();

		if (!topPrivateDomainName.equals(hostname) // has a subdomain
				&& hostname.substring(0, hostname.length() - topPrivateDomainName.length() - 1  /* -1 for dot */)
				.equalsIgnoreCase(phoenixHostSubdomain)) {
			// we're in our subdomain.

			String[] path = exchange.getRequest().getPath().value().split("/"); // always starts with /, so will always be at least 1 element
			if (!(path.length > 1 && path[1].equals("api"))) {
				return chain.filter(exchange); // no auth
			}

			// auth for our API endpoints
			if (!authenticateApiAccess(exchange)) {
				exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
				return exchange.getResponse().setComplete();
			}

			return chain.filter(exchange);

		}

		return forwardToRequestService(exchange);
	}

	private Mono<Void> forwardToRequestService(ServerWebExchange exchange) {
		return phoenixRequestService.handleRequest(exchange);
	}

	public boolean authenticateApiAccess(ServerWebExchange exchange) {
		if (API_AUTH_TOKEN == null) return true;

		String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
		if (authHeader == null || !authHeader.startsWith("Bearer ")) {
			return false;
		}

		String token = authHeader.substring("Bearer ".length());
		return token.equals(API_AUTH_TOKEN);
	}

}
