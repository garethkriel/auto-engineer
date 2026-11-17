# j30-miniserver: HTTP/1.1 server on raw sockets

Java 21, standard library only. Put your solution under `bench-solutions/java/j30-miniserver/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.miniserver;
public final class MiniServer implements AutoCloseable {
    public MiniServer(int port, Handler handler);
    public void start() throws java.io.IOException;
    public int port();
    public void close();

    @FunctionalInterface
    public interface Handler {
        Response handle(Request request) throws Exception;
    }

    public record Request(String method, String path, java.util.Map<String, String> query,
                          java.util.Map<String, String> headers, byte[] body) { }

    public record Response(int status, java.util.Map<String, String> headers, byte[] body) { }
}
```

## Lifecycle

- The constructor takes a port from 0 to 65535, where 0 means any free port, and a non-null handler. Otherwise it throws `IllegalArgumentException`.
- `start()` listens on the loopback address. `port()` returns the actual port, and throws `IllegalStateException` before `start`.
- `close()` stops accepting connections and closes every open connection, idle ones included, within about a second. After it returns, new connections to the port are refused. It is idempotent.
- Each connection is served independently: a slow handler on one connection must not delay requests on others.

## Requests

- **Request line:** `METHOD SP target SP version`, where METHOD is one or more uppercase ASCII letters, the target starts with `/`, and the version is `HTTP/1.1` or `HTTP/1.0`.
- **Line endings:** lines end in CRLF. A bare LF is accepted too.
- **Headers:** `name: value` lines until an empty line.
  - Names are case-insensitive and are passed to the handler in lower case.
  - Values have leading and trailing spaces and tabs removed.
  - Repeated headers are joined in order with `", "`.
- **Target:** the part before the first `?` is the path, which is percent-decoded as UTF-8, with `+` left as `+`.
  - The rest is the query, split on `&`, skipping empty pieces.
  - Each piece splits at its first `=`. A piece without `=` has the value `""`.
  - Query names and values are percent-decoded as UTF-8, with `+` meaning a space.
  - If a name repeats, the last value wins.
- **Body:** with `Transfer-Encoding: chunked`, the body is decoded from chunks:
  - Each chunk is a hex size line, optionally followed by `;extensions`, which are ignored. Then come the data and a CRLF.
  - A zero-size chunk ends the body. It is followed by optional trailer lines, which are ignored, and then an empty line.

  Otherwise `Content-Length` gives the body size. With neither header, the body is empty.
- The handler receives unmodifiable `query` and `headers` maps.

## Responses

- The status line is `HTTP/1.1 <status> <reason>`, with any reason phrase. Then come the handler's headers, a blank line and the body, all with CRLF line endings.
- The server always sends `Content-Length` with the real body length. It ignores any `Content-Length` or `Connection` header the handler returns. A null `headers` map or `body` counts as empty.
- If the handler throws, returns `null` or returns a status outside 100–599, the response is `500` with an empty body. The connection stays usable.
- A malformed request gets a `400` response with `Connection: close`, and then the connection is closed. Malformed means:
  - a bad request line or version;
  - a header line without a name before `:`;
  - a `Content-Length` that isn't a non-negative integer;
  - a bad chunk size or chunk ending;
  - an invalid `%` escape;
  - input ending in the middle of a request.

## Connections

- HTTP/1.1 connections stay open for more requests. If the request has `Connection: close` (any case), the server sends the response with `Connection: close` and then closes the connection.
- HTTP/1.0 connections close after the response, with `Connection: close`, unless the request has `Connection: keep-alive`.
- Pipelining: a client may send several requests without waiting. The responses come back in request order.
