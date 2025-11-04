package ws.mia.phoenix.core.proxy;

import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import ws.mia.phoenix.api.model.Route;

@Component
public class PhoenixProxyHandlerFactory {

	private final WebClient webClient;
	private final BuildProperties buildProperties;

	public PhoenixProxyHandlerFactory(WebClient.Builder webBuilder, BuildProperties buildProperties) {
		this.webClient = webBuilder.build();
		this.buildProperties = buildProperties;
	}

	public PhoenixProxyHandler create(ServerWebExchange exchange, Route route) {
		return new BasicPhoenixProxyHandler(exchange, route, webClient, buildProperties);
	}

}
