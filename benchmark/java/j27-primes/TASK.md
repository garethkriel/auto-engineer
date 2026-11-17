# j27-primes: Counting primes fast

Java 21, standard library only. Put your solution under `bench-solutions/java/j27-primes/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.primes;
public final class Primes {
    public static long count(long n);
    public static long countInRange(long lo, long hi);
    public static long nth(int n);
}
```

- **`count(n)`:** the number of primes `p` with `p <= n`. It's 0 when `n < 2`. Valid for `0 <= n <= 10^12`.
- **`countInRange(lo, hi)`:** the number of primes `p` with `lo <= p <= hi`. Valid for `0 <= lo <= hi <= 10^14` with `hi - lo <= 10^8`.
- **`nth(n)`:** the `n`-th prime, counting from `nth(1) = 2`. Valid for `1 <= n <= 10^9`.
- **Errors:** any argument outside its valid range throws `IllegalArgumentException`.
- **Performance:** the tests run under time limits, measured on an ordinary CI machine:
  - `count(10^9)`, `count(10^10)`, `count(10^11)` and `count(10^12)` together within 20 seconds;
  - `countInRange` over windows of 10^7 numbers just below 10^14, plus a window of 10^8 numbers below 10^9, within 30 seconds in total;
  - `nth(10^6)`, `nth(10^7)`, `nth(10^8)` and `nth(10^9)` together within 20 seconds.
- **Memory:** assume the JVM's default heap.
