package ws.mia.phoenix.core.proxy;

import java.util.List;

public class ProxyUtil {

	/**
	 * @return An immutable list of lowercase hop-by-hop headers
	 */
	public static List<String> getHopByHopHeaders() {
		return List.of("connection", "keep-alive", "transfer-encoding",
				"te", "trailer", "upgrade", "proxy-authenticate", "proxy-authorization");
	}

}
