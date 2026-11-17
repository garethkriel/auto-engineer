package ae.mw;

import java.util.Objects;

/** Immutable response with a status in 100..599. */
public record Response(int status, String body) {
    public Response {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("bad status: " + status);
        }
        Objects.requireNonNull(body, "body");
    }
}
