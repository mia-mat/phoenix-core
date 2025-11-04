# Phoenix
A dynamic application-layer HTTPS reverse proxy built with Spring Boot and WebFlux.

Phoenix routes incoming requests based on configurable hostname/path rules. Routes can be proxied or redirected, optionally password-protected, and managed at runtime via a REST API without restarts.

## Route Schema

| Field         | Type       | Required | Description                                                                                                        |
|---------------|------------|----------|--------------------------------------------------------------------------------------------------------------------|
| `source`      | `string`   | Yes*     | Hostname + optional path prefix. Normalized to lowercase, no trailing slash. *May be omitted if `fallback` is true |
| `aliases`     | `string[]` | No       | Additional sources that route to the same destination                                                              |
| `destination` | `string`   | Yes      | Target URI. Supports `<path>` token to inject the request path instead of appending                                |
| `fallback`    | `boolean`  | No       | If true, matches any request with no other matching route. If multiple fallbacks exist, one is chosen at random    |
| `verbose`     | `boolean`  | No       | If true, forwards to the destination as-is without appending the request path                                      |
| `redirect`    | `boolean`  | No       | If true, returns a redirect response instead of proxying                                                           |
| `password`    | `string`   | No       | If set, requires HTTP Basic Auth to access the route. Incompatible with `redirect`                                 |

Example:
```json
{
  "source": "example.com/api",
  "aliases": ["alias.example.com"],
  "destination": "http://backend.local:8081",
  "fallback": false,
  "verbose": false,
  "redirect": false,
  "password": null
}
```

## Routing
Phoenix matches requests against a source (hostname + optional path prefix), with the longest matching prefix winning. If no route is found, a fallback route is used.

The destination supports a `<path>` token to control path resolution:

| Destination format                 | Behaviour                                                        |
|------------------------------------|------------------------------------------------------------------|
| `https://example.com`              | Appends the unmatched path suffix from the request               |
| `https://example.com/<path>/other` | Replaces `<path>` with the unmatched path portion of the request |
| Verbose URI                        | Forwards to the destination as-is                                |


## Caching
Routes are cached in memory and refreshed every 30 minutes. The cache can also be flushed on demand via the API.

## Environment Variables

| Variable                        | Required                   | Description                                                                    |
|---------------------------------|----------------------------|--------------------------------------------------------------------------------|
| `PHOENIX_MONGO_URI`             | Yes                        | MongoDB connection URI. The database name must be included in the URI.         |
| `PHOENIX_API_TOKEN`             | No                         | Bearer token for the management API. If unset, the API is unauthenticated.     |
| `PHOENIX_SUBDOMAIN`             | No (defaults to `phoenix`) | Subdomain reserved for the Phoenix management API (e.g. `phoenix.example.com`) |
| `PHOENIX_ROUTE_COLLECTION_NAME` | No (defaults to `routes`)  | MongoDB collection name for route storage.                                     |


## Java API and Model
Model classes and a Java API client for Phoenix can be found at [phoenix-api](https://gh.mia.ws/phoenix-api)

## REST API
All endpoints are served under the reserved Phoenix subdomain and require a `Bearer` token if `PHOENIX_API_TOKEN` is set.

| Endpoint                 | Method | Description                                            |
|--------------------------|--------|--------------------------------------------------------|
| `/api/routes`            | `GET`  | List all routes                                        |
| `/api/route`             | `GET`  | Get a route by `?source=`                              |
| `/api/route-exists`      | `GET`  | Check if a route exists by `?source=`                  |
| `/api/requires-password` | `GET`  | Check if a route is password-protected by `?source=`   |
| `/api/push-route`        | `POST` | Add a new route                                        |
| `/api/modify-route`      | `POST` | Modify a route by `?source=`                           |
| `/api/remove-route`      | `POST` | Remove a route by `?source=`                           |
| `/api/flush-route-cache` | `POST` | Flush the route cache, optionally scoped to `?source=` |
| `/api/ping`              | `POST` | Echo endpoint                                          |