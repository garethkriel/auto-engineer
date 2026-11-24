package ae.polyfp;

import java.util.List;

/** {@code unit * product(factor_i ^ multiplicity_i)}; factors sorted by degree then coefficients. */
public record Factorization(long unit, List<Factor> factors) {
    public Factorization {
        factors = List.copyOf(factors);
    }
}
