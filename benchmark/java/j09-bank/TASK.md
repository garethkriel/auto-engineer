# j09-bank: Bank transfers

Java 21, standard library only. Put your solution under `bench-solutions/java/j09-bank/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.bank;
public class InsufficientFundsException extends Exception {   // a checked exception
    public InsufficientFundsException(String message);
}
public final class Bank {
    public Bank();
    public void open(String id, BigDecimal initial);
    public void transfer(String from, String to, BigDecimal amount) throws InsufficientFundsException;
    public BigDecimal balance(String id);
}
```

- Money is `BigDecimal`. Compare amounts by value, not by scale.
- `open` throws `IllegalArgumentException` for a null id or amount, a negative initial balance, or an id that already exists.
- `transfer` moves `amount` from one account to another.
  - It throws `IllegalArgumentException` for an unknown account, a null, zero or negative amount, or `from.equals(to)`.
  - If `from` holds less than `amount`, it throws `InsufficientFundsException` and changes nothing.
  - Transferring the whole balance is allowed.
- `balance` of an unknown account throws `IllegalArgumentException`.
