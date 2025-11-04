package ws.mia.phoenix.core.proxy;

import org.springframework.boot.info.BuildProperties;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ws.mia.phoenix.api.model.Route;

public abstract class PhoenixProxyHandler {

	private final ServerWebExchange exchange;
	private Route route;
	private final WebClient webClient;
	private final BuildProperties buildProperties;

	protected PhoenixProxyHandler(ServerWebExchange exchange, Route route, WebClient webClient, BuildProperties buildProperties) {
		this.exchange = exchange;

		if(route.isRedirect()) {
			throw new IllegalStateException("Route passed to PhoenixProxyHandler must proxy, not redirect");
		}
		this.route = route;
		this.webClient = webClient;
		this.buildProperties = buildProperties;
	}

	public ServerWebExchange getExchange() {
		return exchange;
	}

	public ServerHttpRequest getRequest() {
		return exchange.getRequest();
	}

	public ServerHttpResponse getResponse() {
		return exchange.getResponse();
	}

	public Route getRoute() {
		return route;
	}

	protected void setRoute(Route newRoute) {
		this.route = newRoute;
	}

	public WebClient getWebClient() {
		return webClient;
	}

	protected BuildProperties getBuildProperties() {
		return buildProperties;
	}

	public abstract Mono<Void> resolve();

}