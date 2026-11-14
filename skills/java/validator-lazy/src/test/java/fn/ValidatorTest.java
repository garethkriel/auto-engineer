package fn;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ValidatorTest {
    record Person(String name, int age, List<String> tags) {}

    static final Validator<String> NOT_BLANK = Validator.of(s -> !s.isBlank(), "must not be blank");
    static final Validator<Integer> ADULT = Validator.of(a -> a >= 18, "must be >= 18");

    @Test
    void passingValueYieldsNoErrors() {
        assertEquals(List.of(), NOT_BLANK.validate("x"));
        assertTrue(NOT_BLANK.isValid("x"));
    }

    @Test
    void failingPredicateYieldsMessage() {
        assertEquals(List.of("must not be blank"), NOT_BLANK.validate("  "));
        assertFalse(NOT_BLANK.isValid(" "));
    }

    @Test
    void andAccumulatesAllErrorsWithoutShortCircuit() {
        Validator<String> v = NOT_BLANK.and(Validator.of(s -> s.length() >= 3, "too short"));
        assertEquals(List.of("must not be blank", "too short"), v.validate(" "));
        assertEquals(List.of("too short"), v.validate("ab"));
    }

    @Test
    void allOfEmptyAlwaysValid() {
        Validator<String> v = Validator.allOf();
        assertEquals(List.of(), v.validate("anything"));
    }

    @Test
    void allOfKeepsOrder() {
        Validator<Integer> v = Validator.allOf(
                Validator.of(i -> i > 0, "a"), Validator.of(i -> i > 10, "b"), Validator.of(i -> i > 20, "c"));
        assertEquals(List.of("b", "c"), v.validate(5));
    }

    @Test
    void fieldPrefixesErrors() {
        Validator<Person> v = Validator.field("name", Person::name, NOT_BLANK)
                .and(Validator.field("age", Person::age, ADULT));
        assertEquals(List.of("name: must not be blank", "age: must be >= 18"),
                v.validate(new Person("", 3, List.of())));
        assertEquals(List.of(), v.validate(new Person("Ann", 30, List.of())));
    }

    @Test
    void eachIndexesElements() {
        Validator<Person> v = Validator.field("tags", Person::tags, Validator.each(NOT_BLANK));
        assertEquals(List.of("tags: [1]: must not be blank", "tags: [3]: must not be blank"),
                v.validate(new Person("A", 20, List.of("a", " ", "b", ""))));
    }

    @Test
    void eachOnEmptyIterableIsValid() {
        assertEquals(List.of(), Validator.each(NOT_BLANK).validate(List.of()));
    }

    @Test
    void contravariantValidatorReusableOnSubtype() {
        Validator<Object> notNull = Validator.of(o -> o != null, "null");
        Validator<String> v = NOT_BLANK.and(notNull);
        assertEquals(List.of(), v.validate("x"));
    }

    @Test
    void requireValidReturnsValueWhenValid() {
        assertEquals("ok", NOT_BLANK.requireValid("ok"));
    }

    @Test
    void requireValidThrowsWithErrors() {
        ValidationException ex = assertThrows(ValidationException.class, () -> NOT_BLANK.requireValid(""));
        assertEquals(List.of("must not be blank"), ex.errors());
        assertTrue(ex.getMessage().contains("must not be blank"));
        assertThrows(UnsupportedOperationException.class, () -> ex.errors().add("x"));
    }

    @Test
    void resultListsAreUnmodifiable() {
        List<String> errs = NOT_BLANK.validate("");
        assertThrows(UnsupportedOperationException.class, () -> errs.add("x"));
    }

    @Test
    void validationExceptionCopiesInput() {
        java.util.ArrayList<String> src = new java.util.ArrayList<>(List.of("e1"));
        ValidationException ex = new ValidationException(src);
        src.add("e2");
        assertEquals(List.of("e1"), ex.errors());
    }

    @Test
    void nullArgumentsRejected() {
        assertThrows(NullPointerException.class, () -> Validator.of(null, "m"));
        assertThrows(NullPointerException.class, () -> Validator.of(s -> true, null));
        assertThrows(NullPointerException.class, () -> NOT_BLANK.and(null));
        assertThrows(NullPointerException.class, () -> Validator.field(null, Person::name, NOT_BLANK));
        assertThrows(NullPointerException.class, () -> Validator.each(null));
    }
}
