package ws.mia.phoenix.core.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ws.mia.phoenix.api.model.Route;
import ws.mia.phoenix.core.routing.RouteUtil;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/// A basic implementation of a handler.
/// Handles potential connections Via Cloudflare.
public class BasicPhoenixProxyHandler extends PhoenixProxyHandler {
    private static final Logger log = LoggerFactory.getLogger(BasicPhoenixProxyHandler.class);

    private URI targetUri;

    public BasicPhoenixProxyHandler(ServerWebExchange exchange, Route route, WebClient webClient, BuildProperties buildProperties) {
        super(exchange, route, webClient, buildProperties);

        targetUri = RouteUtil.resolveDestinationUri(getRoute(), getRequest().getURI());
    }

    @Override
    public Mono<Void> resolve() {
        return readInboundBody().flatMap(inboundBody -> {
            if (!isAuthenticated(getRequest().getHeaders())) {
                return rejectWithAuthChallenge(); // TODO a proper webpage for password input
            }

            OutboundRequestDetails outboundRequestDetails = new OutboundRequestDetails(
                    targetUri, getRequest().getMethod(), this::generateOutboundRequestHeaders, inboundBody);
            return sendOutboundRequest(outboundRequestDetails);
        });
    }

    private boolean isAuthenticated(HttpHeaders headers) {
        if(!getRoute().isPasswordProtected()) return true;

        String authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Basic ")) return false;

        String decoded = new String(Base64.getDecoder().decode(authHeader.substring(6)));
        // decoded is "username:password"
        String password = decoded.contains(":") ? decoded.substring(decoded.indexOf(':') + 1) : decoded;

        return MessageDigest.isEqual(
                getRoute().getPassword().getBytes(StandardCharsets.UTF_8),
                password.getBytes(StandardCharsets.UTF_8)
        );
    }

    private Mono<Void> rejectWithAuthChallenge() {
        ServerHttpResponse response = getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"" + getRoute().getSource() + " via phoenix\""); // multiple routes may be on the same origin, so unique routes
        return response.setComplete();
    }


    private Set<String> getConnectionDeclaredHeaders(HttpHeaders headers) {
        // comma-separated list of header names that are hop-by-hop for this message leg only.
        // these must be stripped and dealt with before forwarding.
        // (per RFC 9110 7.6.1)
        return headers.getOrEmpty(HttpHeaders.CONNECTION).stream()
                .flatMap(v -> Arrays.stream(v.split(",")))
                .map(s -> s.trim().toLowerCase())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private void generateOutboundRequestHeaders(HttpHeaders headerObj) {
        HttpHeaders inboundRequestHeaders = getRequest().getHeaders();
        Set<String> connectionDeclaredHeaders = getConnectionDeclaredHeaders(inboundRequestHeaders);

        inboundRequestHeaders.entrySet().stream()
                .filter(he -> {
                    String headerName = he.getKey().toLowerCase();
                    if (headerName.startsWith("cf-")) return false; // we don't propagate Cloudflare headers
                    if (headerName.equals("host")) return false; // host is set by us to be the outbound dest.
                    if (headerName.equals("referer")) return false; // potential leaks
                    if (ProxyUtil.getHopByHopHeaders().contains(headerName)) return false;
                    if (connectionDeclaredHeaders.contains(headerName)) return false;
                    if (headerName.equals(HttpHeaders.ACCEPT_ENCODING.toLowerCase())) return false; // todo actually handle gzip correctly
                    return true;
                })
                .forEach(he -> headerObj.addAll(he.getKey(), he.getValue()));

        // Append client IP to X-Forwarded-For (non-standard but mentioned by RFC 7239)
        String cfConnectingIp = getCloudflareConnectingIP(); // prefer a client IP from CF if one is available
        String clientIp = cfConnectingIp != null
                ? cfConnectingIp
                : getExchange().getRequest().getRemoteAddress() != null
                ? getExchange().getRequest().getRemoteAddress().getAddress().getHostAddress()
                : null;
        if (clientIp != null) headerObj.add("X-Forwarded-For", clientIp);

        if (inboundRequestHeaders.getHost() != null) {
            headerObj.set("X-Forwarded-Host", inboundRequestHeaders.getHost().getHostString());
        }

        // per RFC 9112 9.3: mirror the client's connection intent onto the proxy->origin leg back.
        boolean clientWantsClose = connectionDeclaredHeaders.contains("close");
        headerObj.set(HttpHeaders.CONNECTION, clientWantsClose ? "close" : "keep-alive");

        headerObj.add("Via", "1.1 phoenix/" + getBuildProperties().getVersion()); // RFC 9110 7.6.3
    }

    private Mono<byte[]> readInboundBody() {
        return DataBufferUtils.join(getRequest().getBody())
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                    return bytes;
                })
                .defaultIfEmpty(new byte[0]);
    }

    /**
     * Sends the outbound request, following redirects.
     * Then handles the outbound response.
     *
     * @return The response from the destination server
     */
    private Mono<Void> sendOutboundRequest(OutboundRequestDetails outboundRequestDetails) {
        WebClient.RequestHeadersSpec<?> outboundRequestSpec = outboundRequestDetails.generateRequestSpec();

        return outboundRequestSpec.exchangeToMono(outboundResponse -> {

            if (outboundResponse.statusCode().is3xxRedirection()) {
                String location = outboundResponse.headers().header("Location").stream()
                        .filter(loc -> loc != null && !loc.isBlank())
                        .findFirst()
                        .orElse(null);

                if (location != null) {
                    HttpStatus status = HttpStatus.resolve(outboundResponse.statusCode().value());

                    // per RFC 9110 15.4, HTTP 301/302/303's change method to GET
                    if (status == HttpStatus.MOVED_PERMANENTLY  || status == HttpStatus.FOUND || status == HttpStatus.SEE_OTHER) {
                        outboundRequestDetails.method = HttpMethod.GET;
                        outboundRequestDetails.body = new byte[0];
                    }
                    // 307/308 preserve method+body

                    // mild counter to SSRF since Phoenix is inside Docker: just only allow HTTPS connections (all internal services are http, so blocks them off)
                    URI redirectUri = URI.create(location);
                    if (!"https".equalsIgnoreCase(redirectUri.getScheme())) {
                        return handleOutboundResponse(outboundResponse);
                    }
                    outboundRequestDetails.uri = redirectUri;
                    return outboundResponse.releaseBody().then(sendOutboundRequest(outboundRequestDetails));

                }
                // 304 Not Modified is technically 3xx so is caught here, but has no Location, can fall through.
                // also handles any malformed redirect missing a Location header.
            }

            // handle here since body is otherwise consumed outside this scope.
            // TODO actually separate this out somehow
            return handleOutboundResponse(outboundResponse);
        });
    }

    /**
     * @param headerObj ref which is written to
     */
    private void generateInboundResponseHeaders(HttpHeaders headerObj, HttpHeaders outboundResponseHeaders) {
        Set<String> connectionDeclaredHeaders = getConnectionDeclaredHeaders(outboundResponseHeaders);

        outboundResponseHeaders.entrySet().stream()
                .filter(he -> {
                    String headerName = he.getKey().toLowerCase();
                    if (headerName.startsWith("cf-")) return false;
                    if (headerName.equals("server")) return false;
                    if (ProxyUtil.getHopByHopHeaders().contains(headerName)) return false;
                    if (connectionDeclaredHeaders.contains(headerName)) return false;
                    return true;
                })
                .forEach(he -> headerObj.addAll(he.getKey(), he.getValue()));

        // per RFC 9112 6.3, content-length must not be forwarded for streaming responses.
        // we also strip for SSE streams, which Netty rechunks regardless of any origin-supplied length.
        // (this would cause weird issues with the stream being truncated early)
        boolean isStreaming = outboundResponseHeaders.containsKey(HttpHeaders.TRANSFER_ENCODING)
                || "text/event-stream".equalsIgnoreCase(outboundResponseHeaders.getFirst(HttpHeaders.CONTENT_TYPE));
        if (isStreaming) {
            headerObj.remove(HttpHeaders.CONTENT_LENGTH);
        }

    }

    /**
     * Sets inbound response details
     */
    private Mono<Void> handleOutboundResponse(ClientResponse outboundResponse) {
        ServerHttpResponse inboundResponse = getResponse();
        inboundResponse.setStatusCode(outboundResponse.statusCode());

        generateInboundResponseHeaders(inboundResponse.getHeaders(), outboundResponse.headers().asHttpHeaders());

        return inboundResponse.writeWith(outboundResponse.bodyToFlux(DataBuffer.class));
    }

    private class OutboundRequestDetails {
        private URI uri;
        private Consumer<HttpHeaders> headerConsumer;
        private HttpMethod method;
        private byte[] body;

        private OutboundRequestDetails(URI uri, HttpMethod method, Consumer<HttpHeaders> headersConsumer, byte[] body) {
            this.uri = uri;
            this.headerConsumer = headersConsumer;
            this.method = method;
            this.body = body;
        }

        private OutboundRequestDetails(HttpMethod method, Consumer<HttpHeaders> headersConsumer, byte[] body) {
            this(targetUri, method, headersConsumer, body);
        }

        private WebClient.RequestHeadersSpec<?> generateRequestSpec() {
            WebClient.RequestBodySpec spec = getWebClient().method(method).uri(uri).headers(headerConsumer);

            // some endpoints may be weird with an existing but empty body
            if (body != null && body.length > 0) {
                return spec.bodyValue(body);
            }
            return spec;
        }

    }


    private String getClientCountryISO() {
        String cf = getRequest().getHeaders().getFirst("CF-IPCountry");
        if(cf == null) return "Unknown";
        return cf;
    } // CF Exclusive

    private String getCloudflareConnectingIP() {
        return getRequest().getHeaders().getFirst("CF-Connecting-IP");
    } // CF Exclusive
}