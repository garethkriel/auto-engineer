package ae;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ResultTest {
    @Test
    void mapTransformsOkOnly() {
        Result<Integer, String> ok = new Result.Ok<>(2);
        Result<Integer, String> err = new Result.Err<>("bad");
        assertEquals(new Result.Ok<Integer, String>(4), ok.map(x -> x * 2));
        assertEquals(new Result.Err<Integer, String>("bad"), err.map(x -> x * 2));
    }

    @Test
    void flatMapChainsAndShortCircuits() {
        Result<Integer, String> ok = new Result.Ok<>(10);
        Result<Integer, String> r = ok.flatMap(x -> x > 5 ? new Result.Err<Integer, String>("big") : new Result.Ok<>(x));
        assertEquals(new Result.Err<Integer, String>("big"), r);
        int[] calls = {0};
        new Result.Err<Integer, String>("e").flatMap(x -> { calls[0]++; return new Result.Ok<Integer, String>(x); });
        assertEquals(0, calls[0]);
    }

    @Test
    void mapErrorAndFallbacks() {
        Result<Integer, String> err = new Result.Err<>("abc");
        assertEquals(new Result.Err<Integer, Integer>(3), err.mapError(String::length));
        assertEquals(7, err.orElse(7));
        assertEquals(3, err.getOrElseGet(String::length));
        assertEquals(Optional.empty(), err.toOptional());
        assertEquals(Optional.of(1), new Result.Ok<Integer, String>(1).toOptional());
        assertTrue(new Result.Ok<Integer, String>(1).isOk());
        assertFalse(err.isOk());
    }

    @Test
    void ofCapturesRuntimeException() {
        Result<Integer, RuntimeException> r = Result.of(() -> Integer.parseInt("x"));
        assertFalse(r.isOk());
        assertInstanceOf(NumberFormatException.class, ((Result.Err<Integer, RuntimeException>) r).error());
        assertEquals(5, Result.of(() -> 5).orElse(0));
    }

    @Test
    void sequenceCollectsOrFailsFirst() {
        List<Result<Integer, String>> good = List.of(new Result.Ok<>(1), new Result.Ok<>(2));
        assertEquals(new Result.Ok<List<Integer>, String>(List.of(1, 2)), Result.sequence(good));
        List<Result<Integer, String>> bad = List.of(new Result.Ok<>(1), new Result.Err<>("a"), new Result.Err<>("b"));
        assertEquals(new Result.Err<List<Integer>, String>("a"), Result.sequence(bad));
        assertEquals(new Result.Ok<List<Integer>, String>(List.of()), Result.sequence(List.<Result<Integer, String>>of()));
    }

    @Test
    void sequenceResultIsImmutableAndDetached() {
        List<Result<Integer, String>> src = new ArrayList<>(List.of(new Result.Ok<>(1)));
        Result<List<Integer>, String> r = Result.sequence(src);
        src.add(new Result.Ok<>(2));
        List<Integer> out = ((Result.Ok<List<Integer>, String>) r).value();
        assertEquals(List.of(1), out);
        assertThrows(UnsupportedOperationException.class, () -> out.add(3));
    }

    @Test
    void nullsRejected() {
        assertThrows(NullPointerException.class, () -> new Result.Ok<String, String>(null));
        assertThrows(NullPointerException.class, () -> new Result.Err<String, String>(null));
    }
}
