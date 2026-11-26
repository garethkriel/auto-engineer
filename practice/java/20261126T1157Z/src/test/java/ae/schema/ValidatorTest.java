package ae.schema;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ValidatorTest {

    // ---- builders -------------------------------------------------------
    static Map<String, Object> o(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static List<Object> l(Object... xs) {
        return new ArrayList<>(Arrays.asList(xs));
    }

    static Result run(Object schema, Object instance) {
        return Validator.compile(schema).validate(instance);
    }

    static boolean ok(Object schema, Object instance) {
        return run(schema, instance).valid();
    }

    static void assertValid(Object schema, Object instance) {
        Result r = run(schema, instance);
        assertTrue(r.valid(), () -> "expected valid: " + schema + " / " + instance + " -> " + r);
    }

    static void assertInvalid(Object schema, Object instance) {
        Result r = run(schema, instance);
        assertFalse(r.valid(), () -> "expected invalid: " + schema + " / " + instance);
        assertFalse(r.violations().isEmpty());
    }

    static List<String> keywords(Result r) {
        return r.violations().stream().map(Violation::keyword).collect(Collectors.toList());
    }

    // ---- boolean schemas ------------------------------------------------
    @Test
    void booleanSchemas() {
        assertValid(true, 5);
        assertValid(true, null);
        assertInvalid(false, 5);
        Result r = run(false, l());
        assertEquals(List.of(new Violation("", "", "false", r.violations().get(0).message())), r.violations());
        assertValid(o(), "anything");
    }

    // ---- type -----------------------------------------------------------
    @Test
    void typeNames() {
        assertValid(o("type", "null"), null);
        assertInvalid(o("type", "null"), 0);
        assertValid(o("type", "boolean"), false);
        assertInvalid(o("type", "boolean"), 0);
        assertValid(o("type", "string"), "");
        assertInvalid(o("type", "string"), null);
        assertValid(o("type", "array"), l());
        assertInvalid(o("type", "array"), o());
        assertValid(o("type", "object"), o());
        assertInvalid(o("type", "object"), l());
        assertValid(o("type", "number"), 1);
        assertValid(o("type", "number"), 1.5);
        assertInvalid(o("type", "number"), true);
        assertInvalid(o("type", "number"), "1");
    }

    @Test
    void integerMeansZeroFractionalPart() {
        Object s = o("type", "integer");
        assertValid(s, 1);
        assertValid(s, 1L);
        assertValid(s, 1.0);
        assertValid(s, 1.0f);
        assertValid(s, 1e2);
        assertValid(s, new BigDecimal("1.000"));
        assertValid(s, new BigDecimal("100E-2"));
        assertValid(s, new BigDecimal("0.0"));
        assertValid(s, new BigDecimal("1E+400"));
        assertValid(s, BigInteger.TEN.pow(50));
        assertValid(s, -0.0);
        assertInvalid(s, 1.5);
        assertInvalid(s, new BigDecimal("1.0000000000000000000001"));
        assertInvalid(s, 0.1f);
        assertInvalid(s, "1");
        assertInvalid(s, true);
    }

    @Test
    void typeArrayMatchesAny() {
        Object s = o("type", l("string", "null"));
        assertValid(s, "x");
        assertValid(s, null);
        assertInvalid(s, 1);
        Result r = run(s, 1);
        assertEquals("type", r.violations().get(0).keyword());
        assertEquals("/type", r.violations().get(0).schemaPointer());
    }

    // ---- enum / const ---------------------------------------------------
    @Test
    void constUsesJsonEquality() {
        assertValid(o("const", 1), 1.0);
        assertValid(o("const", new BigDecimal("1.00")), 1);
        assertInvalid(o("const", 1), true);
        assertInvalid(o("const", true), 1);
        assertInvalid(o("const", 0), false);
        assertInvalid(o("const", null), 0);
        assertValid(o("const", null), null);
        assertValid(o("const", o("a", 1, "b", l(1, 2))), o("b", l(1.0, 2), "a", 1));
        assertInvalid(o("const", l(1, 2)), l(2, 1));
        assertInvalid(o("const", o("a", 1)), o("a", 1, "b", 2));
        assertInvalid(o("const", "a"), "b");
        assertInvalid(o("const", l()), o());
    }

    @Test
    void enumAnyMatch() {
        Object s = o("enum", l("red", 2, null, l(1), o("k", 1)));
        assertValid(s, "red");
        assertValid(s, 2.0);
        assertValid(s, null);
        assertValid(s, l(1));
        assertValid(s, o("k", 1.0));
        assertInvalid(s, "blue");
        assertInvalid(s, false);
        assertInvalid(s, l(1, 1));
        assertInvalid(o("enum", l()), 1);
    }

    // ---- strings --------------------------------------------------------
    @Test
    void lengthCountsCodePoints() {
        String clef = new String(Character.toChars(0x1D11E)); // one code point, two chars
        assertEquals(2, clef.length());
        assertValid(o("maxLength", 1), clef);
        assertInvalid(o("minLength", 2), clef);
        assertValid(o("minLength", 2, "maxLength", 2), clef + "a");
        assertValid(o("minLength", 0, "maxLength", 0), "");
        assertInvalid(o("maxLength", 2), "abc");
        assertValid(o("maxLength", 3), "abc");
        assertInvalid(o("minLength", 4), "abc");
        assertValid(o("minLength", 3), "abc");
        assertValid(o("minLength", 5, "maxLength", 1), 7); // ignored for non-strings
    }

    @Test
    void patternIsUnanchoredFind() {
        assertValid(o("pattern", "b+"), "abbbc");
        assertInvalid(o("pattern", "^b+$"), "abbbc");
        assertValid(o("pattern", "^[a-c]+$"), "abcabc");
        assertInvalid(o("pattern", "^[a-c]+$"), "abcd");
        assertValid(o("pattern", "^\\d{3}$"), "123");
        assertValid(o("pattern", "x"), 5);
        assertValid(o("pattern", ""), "");
        assertInvalid(o("pattern", "a"), "");
    }

    // ---- numbers --------------------------------------------------------
    @Test
    void bounds() {
        assertValid(o("minimum", 5), 5);
        assertInvalid(o("minimum", 5), 4.999);
        assertValid(o("maximum", 5), 5.0);
        assertInvalid(o("maximum", 5), 5.0001);
        assertInvalid(o("exclusiveMinimum", 5), 5);
        assertValid(o("exclusiveMinimum", 5), 5.0000001);
        assertInvalid(o("exclusiveMaximum", 5), 5);
        assertValid(o("exclusiveMaximum", 5), 4.9999999);
        assertValid(o("minimum", -3), -3);
        assertInvalid(o("minimum", -3), -3.5);
        assertValid(o("minimum", 1), "text"); // ignored for non-numbers
        assertValid(o("maximum", 1), true);
    }

    @Test
    void boundsAreExactForHugeAndFractionalValues() {
        BigInteger big = BigInteger.ONE.shiftLeft(80);
        assertValid(o("maximum", big), big);
        assertInvalid(o("maximum", big), big.add(BigInteger.ONE));
        assertInvalid(o("minimum", new BigDecimal("0.1")), 0.0999999999999999);
        assertValid(o("minimum", new BigDecimal("0.1")), 0.1);
        assertValid(o("minimum", 1), new BigDecimal("1E+400"));
        assertInvalid(o("maximum", 1), new BigDecimal("1E+400"));
        assertValid(o("maximum", 1), new BigDecimal("-1E+400"));
    }

    @Test
    void multipleOfIsDecimalExact() {
        assertValid(o("multipleOf", 0.01), 0.07); // 0.07/0.01 != 7 in binary doubles
        assertValid(o("multipleOf", 0.0001), 19.99);
        assertInvalid(o("multipleOf", 0.01), 0.075);
        assertValid(o("multipleOf", 3), 9);
        assertInvalid(o("multipleOf", 3), 10);
        assertValid(o("multipleOf", 3), 0);
        assertValid(o("multipleOf", 3), -9);
        assertValid(o("multipleOf", 2.5), 7.5);
        assertInvalid(o("multipleOf", 2.5), 7.6);
        assertValid(o("multipleOf", 1), 4.0);
        assertInvalid(o("multipleOf", 1), 4.5);
        assertValid(o("multipleOf", 7), BigInteger.valueOf(7).pow(30));
        assertInvalid(o("multipleOf", 7), BigInteger.valueOf(7).pow(30).add(BigInteger.ONE));
        assertValid(o("multipleOf", 3), "str");
    }

    // ---- arrays ---------------------------------------------------------
    @Test
    void prefixItemsThenItems() {
        Object s = o("prefixItems", l(o("type", "string"), o("type", "integer")), "items", o("type", "boolean"));
        assertValid(s, l());
        assertValid(s, l("a"));
        assertValid(s, l("a", 1));
        assertValid(s, l("a", 1, true, false));
        assertInvalid(s, l(1));
        assertInvalid(s, l("a", 1, 3));
        Result r = run(s, l("a", 1, true, 3));
        assertEquals(List.of(new Violation("/3", "/items/type", "type", r.violations().get(0).message())), r.violations());
    }

    @Test
    void itemsAloneAppliesToAllElements() {
        Object s = o("items", o("minimum", 0));
        assertValid(s, l(0, 1, 2));
        Result r = run(s, l(1, -1, 2, -5));
        assertEquals(List.of("/1", "/3"), r.violations().stream().map(Violation::instancePointer).collect(Collectors.toList()));
        assertValid(o("items", false), l());
        assertInvalid(o("items", false), l(1));
    }

    @Test
    void prefixItemsWithoutItemsLeavesTheRestFree() {
        Object s = o("prefixItems", l(o("const", 1)));
        assertValid(s, l(1, "anything", null));
        assertInvalid(s, l(2, "anything"));
    }

    @Test
    void containsAndCounts() {
        Object c = o("contains", o("type", "integer"));
        assertValid(c, l("a", 1));
        assertInvalid(c, l("a", "b"));
        assertInvalid(c, l());
        assertValid(o("contains", o("const", 1), "minContains", 2), l(1, 0, 1));
        assertInvalid(o("contains", o("const", 1), "minContains", 2), l(1, 0));
        assertValid(o("contains", o("const", 1), "maxContains", 2), l(1, 1, 0));
        Result r = run(o("contains", o("const", 1), "maxContains", 2), l(1, 1, 1));
        assertEquals(List.of("maxContains"), keywords(r));
        assertValid(o("contains", o("const", 1), "minContains", 0), l(2, 3));
        assertValid(o("contains", o("const", 1), "minContains", 0, "maxContains", 1), l());
        assertInvalid(o("contains", o("const", 1), "minContains", 0, "maxContains", 1), l(1, 1));
        assertValid(o("minContains", 5), l()); // without contains: ignored
        assertValid(c, "not an array");
    }

    @Test
    void itemCounts() {
        assertValid(o("minItems", 2, "maxItems", 3), l(1, 2));
        assertValid(o("minItems", 2, "maxItems", 3), l(1, 2, 3));
        assertInvalid(o("minItems", 2), l(1));
        assertInvalid(o("maxItems", 3), l(1, 2, 3, 4));
        assertValid(o("maxItems", 0), l());
        assertValid(o("minItems", 2), "ab");
    }

    @Test
    void uniqueItemsUsesJsonEquality() {
        Object u = o("uniqueItems", true);
        assertValid(u, l());
        assertValid(u, l(1, 2, 3));
        assertInvalid(u, l(1, 2, 1));
        assertInvalid(u, l(1, 1.0));
        assertValid(u, l(1, true));
        assertValid(u, l(0, false));
        assertValid(u, l("1", 1));
        assertInvalid(u, l(o("a", 1, "b", 2), o("b", 2, "a", 1)));
        assertValid(u, l(l(1, 2), l(2, 1)));
        assertInvalid(u, l(l(1, 2), l(1.0, 2)));
        assertInvalid(u, l(null, null));
        assertValid(u, l(o(), l()));
        assertValid(o("uniqueItems", false), l(1, 1));
    }

    // ---- objects --------------------------------------------------------
    @Test
    void propertiesAndRequired() {
        Object s = o("properties", o("a", o("type", "integer"), "b", o("type", "string")), "required", l("a"));
        assertValid(s, o("a", 1));
        assertValid(s, o("a", 1, "b", "x", "c", null));
        assertInvalid(s, o());
        assertInvalid(s, o("a", "1"));
        Result r = run(s, o("b", 5));
        assertEquals(List.of("type", "required"), keywords(r)); // object keywords: properties before required
        assertEquals(new Violation("/b", "/properties/b/type", "type", r.violations().get(0).message()), r.violations().get(0));
        assertEquals(new Violation("", "/required", "required", r.violations().get(1).message()), r.violations().get(1));
        assertValid(s, "not an object");
    }

    @Test
    void additionalPropertiesAndPatternProperties() {
        Object s = o("properties", o("a", true), "patternProperties", o("^x-", o("type", "integer")),
                "additionalProperties", false);
        assertValid(s, o("a", "anything", "x-1", 5, "x-z", 6));
        assertInvalid(s, o("a", 1, "b", 2));
        assertInvalid(s, o("x-1", "no"));
        Result r = run(s, o("b", 2, "c", 3));
        assertEquals(List.of("/b", "/c"), r.violations().stream().map(Violation::instancePointer).collect(Collectors.toList()));
        assertEquals("/additionalProperties", r.violations().get(0).schemaPointer());
        assertEquals("additionalProperties", r.violations().get(0).keyword());
        Object t = o("additionalProperties", o("type", "string"));
        assertValid(t, o("q", "s"));
        assertInvalid(t, o("q", 1));
        Object both = o("patternProperties", o("a", o("minimum", 1), "b", o("maximum", 5)));
        assertValid(both, o("ab", 3));
        assertInvalid(both, o("ab", 7));
        assertInvalid(both, o("ab", 0));
        assertValid(o("additionalProperties", false), o());
    }

    @Test
    void propertyNamesAreValidatedAsStrings() {
        Object s = o("propertyNames", o("maxLength", 2));
        assertValid(s, o("a", 1, "bb", 2));
        Result r = run(s, o("ccc", 1));
        assertEquals(List.of(new Violation("", "/propertyNames/maxLength", "maxLength", r.violations().get(0).message())), r.violations());
        assertValid(o("propertyNames", false), o());
        assertInvalid(o("propertyNames", false), o("a", 1));
    }

    @Test
    void propertyCounts() {
        assertValid(o("minProperties", 1, "maxProperties", 2), o("a", 1));
        assertInvalid(o("minProperties", 1), o());
        assertInvalid(o("maxProperties", 1), o("a", 1, "b", 2));
        assertValid(o("maxProperties", 0), o());
        assertValid(o("minProperties", 3), l(1));
    }

    @Test
    void dependentKeywords() {
        Object dr = o("dependentRequired", o("card", l("billing", "zip")));
        assertValid(dr, o());
        assertValid(dr, o("billing", 1));
        assertValid(dr, o("card", 1, "billing", 2, "zip", 3));
        Result r = run(dr, o("card", 1));
        assertEquals(List.of("dependentRequired", "dependentRequired"), keywords(r));
        Object ds = o("dependentSchemas", o("a", o("properties", o("b", o("type", "integer")))));
        assertValid(ds, o("b", "x"));
        assertValid(ds, o("a", 1, "b", 2));
        assertInvalid(ds, o("a", 1, "b", "x"));
        assertEquals("/dependentSchemas/a/properties/b/type",
                run(ds, o("a", 1, "b", "x")).violations().get(0).schemaPointer());
    }

    // ---- combinators ----------------------------------------------------
    @Test
    void allOfCollectsEveryFailure() {
        Object s = o("allOf", l(o("minimum", 5), o("maximum", 3), o("type", "integer")));
        assertValid(o("allOf", l(o("minimum", 1), o("maximum", 3))), 2);
        Result r = run(s, 4.5);
        assertEquals(List.of("/allOf/0/minimum", "/allOf/1/maximum", "/allOf/2/type"),
                r.violations().stream().map(Violation::schemaPointer).collect(Collectors.toList()));
    }

    @Test
    void anyOfAndOneOf() {
        Object any = o("anyOf", l(o("type", "string"), o("minimum", 10)));
        assertValid(any, "x");
        assertValid(any, 11);
        assertInvalid(any, 3);
        assertEquals(List.of("anyOf"), keywords(run(any, 3)));
        Object one = o("oneOf", l(o("type", "integer"), o("minimum", 10)));
        assertValid(one, 5); // integer only
        assertValid(one, 10.5); // minimum only
        assertInvalid(one, 12); // both
        assertValid(one, "x"); // type fails, minimum ignores strings -> exactly one branch
        assertInvalid(one, 5.5); // matches neither: not an integer, below minimum
        assertEquals(List.of("oneOf"), keywords(run(one, 5.5)));
        assertEquals(List.of("oneOf"), keywords(run(one, 12)));
    }

    @Test
    void notAndConditionals() {
        assertValid(o("not", o("type", "string")), 1);
        assertInvalid(o("not", o("type", "string")), "s");
        assertEquals(List.of("not"), keywords(run(o("not", true), 1)));
        assertValid(o("not", false), 1);
        Object ite = o("if", o("properties", o("k", o("const", 1))), "then", o("required", l("a")), "else", o("required", l("b")));
        assertValid(ite, o("k", 1, "a", 0));
        assertInvalid(ite, o("k", 1, "b", 0));
        assertValid(ite, o("k", 2, "b", 0));
        assertInvalid(ite, o("k", 2, "a", 0));
        Result r = run(ite, o("k", 1));
        assertEquals(List.of(new Violation("", "/then/required", "required", r.violations().get(0).message())), r.violations());
        assertValid(o("if", false, "else", true), 1);
        assertValid(o("if", o("type", "string")), 1);
        assertValid(o("then", false), 1);
        assertValid(o("else", false), 1);
    }

    // ---- $ref -----------------------------------------------------------
    @Test
    void refToDefs() {
        Object s = o("$defs", o("pos", o("type", "integer", "minimum", 1)), "properties", o("n", o("$ref", "#/$defs/pos")));
        assertValid(s, o("n", 3));
        Result r = run(s, o("n", 0));
        assertEquals(List.of(new Violation("/n", "/properties/n/$ref/minimum", "minimum", r.violations().get(0).message())), r.violations());
    }

    @Test
    void recursiveRefWalksATree() {
        Object tree = o("type", "object", "properties", o("value", o("type", "integer"), "children", o("type", "array", "items", o("$ref", "#"))), "required", l("value"));
        assertValid(tree, o("value", 1, "children", l(o("value", 2), o("value", 3, "children", l(o("value", 4))))));
        Result r = run(tree, o("value", 1, "children", l(o("value", 2), o("value", "x"))));
        assertEquals(1, r.violations().size());
        assertEquals("/children/1/value", r.violations().get(0).instancePointer());
        assertEquals("/properties/children/items/$ref/properties/value/type", r.violations().get(0).schemaPointer());
    }

    @Test
    void refPointerEscapesAndPercentDecoding() {
        Object s = o("$defs", o("a/b", o("type", "string"), "c~d", o("type", "integer"), "e f", o("const", 1), "é", o("const", 2)),
                "properties", o("p", o("$ref", "#/$defs/a~1b"), "q", o("$ref", "#/$defs/c~0d"), "r", o("$ref", "#/$defs/e%20f"),
                        "s", o("$ref", "#/$defs/%C3%A9")));
        assertValid(s, o("p", "x", "q", 1, "r", 1, "s", 2));
        // "~01" decodes to the literal key "~1", never to "/"
        Object tilde = o("$defs", o("~1", o("const", 1), "/", o("const", 2)), "properties", o("a", o("$ref", "#/$defs/~01"), "b", o("$ref", "#/$defs/~1")));
        assertValid(tilde, o("a", 1, "b", 2));
        assertInvalid(tilde, o("a", 2));
        assertInvalid(tilde, o("b", 1));
        assertInvalid(s, o("p", 1));
        assertInvalid(s, o("q", "x"));
        assertInvalid(s, o("r", 2));
        assertInvalid(s, o("s", 1));
    }

    @Test
    void refSiblingsStillApplyAndPointerPassesThroughArrays() {
        Object s = o("$defs", o("n", o("type", "integer")), "$ref", "#/$defs/n", "maximum", 10);
        assertValid(s, 5);
        assertInvalid(s, 11);
        assertInvalid(s, 1.5);
        Object viaList = o("allOf", l(o("minimum", 3)), "properties", o("x", o("$ref", "#/allOf/0")));
        assertValid(viaList, o("x", 3));
        assertInvalid(viaList, o("x", 2));
        Object toBool = o("properties", o("x", o("$ref", "#/$defs/no")), "$defs", o("no", false));
        assertInvalid(toBool, o("x", 1));
        assertValid(toBool, o());
    }

    @Test
    void badRefsAreRejectedAtCompileTime() {
        assertThrows(SchemaException.class, () -> Validator.compile(o("$ref", "#/$defs/missing")));
        assertThrows(SchemaException.class, () -> Validator.compile(o("$ref", "http://example.com/s")));
        assertThrows(SchemaException.class, () -> Validator.compile(o("$ref", "other.json#/x")));
        assertThrows(SchemaException.class, () -> Validator.compile(o("$ref", 5)));
        assertThrows(SchemaException.class, () -> Validator.compile(o("$ref", "#/items/01", "items", l(true, true))));
        assertThrows(SchemaException.class, () -> Validator.compile(o("$ref", "#/x/5", "x", l(true))));
        assertThrows(SchemaException.class, () -> Validator.compile(o("$ref", "#/x", "x", 5))); // target not a schema
        assertThrows(SchemaException.class, () -> Validator.compile(o("$ref", "#/x", "x", o("minLength", -1)))); // target is shape-checked
        assertThrows(SchemaException.class, () -> Validator.compile(o("$ref", "#/%zz")));
        assertDoesNotThrow(() -> Validator.compile(o("$ref", "#/x/0", "x", l(true))));
        assertThrows(SchemaException.class, () -> Validator.compile(o("$ref", "#/x/99999999999", "x", l(true))));
        assertThrows(SchemaException.class, () -> Validator.compile(o("$ref", "#/%4")));
        assertThrows(SchemaException.class, () -> Validator.compile(o("$ref", "#/%")));
    }

    @Test
    void refLoopsFailInsteadOfRecursingForever() {
        Validator self = Validator.compile(o("$ref", "#"));
        assertThrows(SchemaException.class, () -> self.validate(1));
        Validator mutual = Validator.compile(o("$defs", o("a", o("$ref", "#/$defs/b"), "b", o("$ref", "#/$defs/a")), "$ref", "#/$defs/a"));
        assertThrows(SchemaException.class, () -> mutual.validate(1));
        Validator viaAllOf = Validator.compile(o("allOf", l(o("$ref", "#"))));
        assertThrows(SchemaException.class, () -> viaAllOf.validate(o()));
        // the same schema reached at a *different* instance location is fine
        Validator nested = Validator.compile(o("anyOf", l(o("type", "integer"), o("type", "array", "items", o("$ref", "#")))));
        assertTrue(nested.validate(l(1, l(2, l(3)))).valid());
        assertFalse(nested.validate(l(1, l("x"))).valid());
    }

    // ---- unevaluated* ---------------------------------------------------
    @Test
    void unevaluatedPropertiesSeesProperties() {
        Object s = o("properties", o("a", true), "unevaluatedProperties", false);
        assertValid(s, o("a", 1));
        Result r = run(s, o("a", 1, "z", 2));
        assertEquals(List.of(new Violation("/z", "/unevaluatedProperties", "unevaluatedProperties", r.violations().get(0).message())), r.violations());
        Object p = o("patternProperties", o("^x", true), "unevaluatedProperties", false);
        assertValid(p, o("xa", 1));
        assertInvalid(p, o("ya", 1));
        Object schemaForm = o("properties", o("a", true), "unevaluatedProperties", o("type", "string"));
        assertValid(schemaForm, o("a", 1, "b", "s"));
        assertInvalid(schemaForm, o("a", 1, "b", 2));
        assertValid(o("additionalProperties", true, "unevaluatedProperties", false), o("q", 1));
    }

    @Test
    void unevaluatedPropertiesSeesInPlaceApplicators() {
        Object viaAllOf = o("allOf", l(o("properties", o("a", true))), "properties", o("b", true), "unevaluatedProperties", false);
        assertValid(viaAllOf, o("a", 1, "b", 2));
        assertInvalid(viaAllOf, o("a", 1, "b", 2, "c", 3));
        Object viaRef = o("$defs", o("base", o("properties", o("a", true))), "$ref", "#/$defs/base", "unevaluatedProperties", false);
        assertValid(viaRef, o("a", 1));
        assertInvalid(viaRef, o("a", 1, "b", 1));
        Object viaDep = o("dependentSchemas", o("x", o("properties", o("y", true))), "properties", o("x", true), "unevaluatedProperties", false);
        assertValid(viaDep, o("x", 1, "y", 2));
        assertValid(viaDep, o("x", 1));
        assertInvalid(viaDep, o("y", 2)); // dependency not triggered, so y is unevaluated
    }

    @Test
    void unevaluatedPropertiesIgnoresFailedBranches() {
        // branch 0 fails (a must be integer) so its annotation for "a" must not count
        Object s = o("anyOf", l(o("properties", o("a", o("type", "integer"))), o("properties", o("b", true))), "unevaluatedProperties", false);
        assertValid(s, o("a", 1)); // branch 0 valid -> a evaluated
        Result r = run(s, o("a", "x"));
        // branch 0 invalid (a is a string) but branch 1 valid -> only b evaluated -> "a" is unevaluated
        assertEquals(List.of("/a"), r.violations().stream().map(Violation::instancePointer).collect(Collectors.toList()));
        Object oneOfBoth = o("oneOf", l(o("properties", o("a", true)), o("properties", o("b", true))), "unevaluatedProperties", false);
        assertInvalid(oneOfBoth, o("a", 1)); // both branches match -> oneOf fails -> no annotations
        Object oneOfSingle = o("oneOf", l(o("required", l("a")), o("required", l("b"))), "properties", o("a", true, "b", true), "unevaluatedProperties", false);
        assertValid(oneOfSingle, o("a", 1));
        Object notAnn = o("not", o("properties", o("a", false)), "unevaluatedProperties", false);
        assertInvalid(notAnn, o("q", 1));
    }

    @Test
    void unevaluatedPropertiesWithConditionals() {
        Object s = o("if", o("properties", o("kind", o("const", "a"))), "then", o("properties", o("x", true)), "else", o("properties", o("y", true)),
                "unevaluatedProperties", false);
        assertValid(s, o("kind", "a", "x", 1)); // matched "if" evaluates kind; "then" evaluates x
        assertInvalid(s, o("kind", "a", "y", 1)); // y belongs to the branch not taken
        assertInvalid(s, o("kind", "b", "y", 1)); // failed "if" contributes nothing, so kind is unevaluated
        assertEquals(List.of("/kind"), run(s, o("kind", "b", "y", 1)).violations().stream()
                .map(Violation::instancePointer).collect(Collectors.toList()));
    }

    @Test
    void unevaluatedItems() {
        Object s = o("prefixItems", l(true, true), "unevaluatedItems", false);
        assertValid(s, l(1, 2));
        assertValid(s, l(1));
        Result r = run(s, l(1, 2, 3, 4));
        assertEquals(List.of("/2", "/3"), r.violations().stream().map(Violation::instancePointer).collect(Collectors.toList()));
        assertEquals("/unevaluatedItems", r.violations().get(0).schemaPointer());
        assertValid(o("items", true, "unevaluatedItems", false), l(1, 2, 3));
        Object c = o("contains", o("type", "string"), "unevaluatedItems", false);
        assertValid(c, l("a", "b"));
        assertInvalid(c, l("a", 1));
        Object viaAllOf = o("allOf", l(o("prefixItems", l(true))), "unevaluatedItems", o("type", "integer"));
        assertValid(viaAllOf, l("x", 1, 2));
        assertInvalid(viaAllOf, l("x", 1, "y"));
        Object failedContains = o("anyOf", l(o("contains", o("const", 9)), o("prefixItems", l(true))), "unevaluatedItems", false);
        assertInvalid(failedContains, l(1, 2)); // contains matched nothing -> branch invalid, no annotation
        // a nested unevaluatedItems/unevaluatedProperties counts as evaluating everything for its parent
        assertValid(o("allOf", l(o("unevaluatedItems", true)), "unevaluatedItems", false), l(1, 2));
        assertValid(o("allOf", l(o("unevaluatedProperties", true)), "unevaluatedProperties", false), o("a", 1));
        assertInvalid(o("allOf", l(o("unevaluatedProperties", false))), o("a", 1));
        // a failed contains (maxContains exceeded) must not annotate its matches
        Object tooMany = o("anyOf", l(o("contains", o("const", 1), "maxContains", 1), true), "unevaluatedItems", false);
        assertValid(tooMany, l(1)); // first branch valid, item 0 evaluated
        assertInvalid(tooMany, l(1, 1)); // first branch invalid, second adds nothing
        assertValid(o("unevaluatedItems", false), l());
        assertValid(o("unevaluatedItems", false), "not an array");
    }

    // ---- ordering, pointers --------------------------------------------
    @Test
    void keywordOrderIsFixed() {
        Object s = o("required", l("z"), "minimum", 100, "type", "object", "properties", o("a", o("type", "string")), "maxProperties", 0);
        Result r = run(s, o("a", 1));
        assertEquals(List.of("type", "required", "maxProperties"), keywords(r));
        assertEquals(List.of("/a", "", ""), r.violations().stream().map(Violation::instancePointer).collect(Collectors.toList()));
    }

    @Test
    void instanceKeysAreVisitedInAscendingOrder() {
        Map<String, Object> inst = new HashMap<>();
        for (String k : List.of("q", "b", "zz", "a", "m")) inst.put(k, 1);
        Result r = run(o("additionalProperties", false), inst);
        assertEquals(List.of("/a", "/b", "/m", "/q", "/zz"), r.violations().stream().map(Violation::instancePointer).collect(Collectors.toList()));
        // repeatable
        assertEquals(r, run(o("additionalProperties", false), inst));
    }

    @Test
    void instancePointersEscapeSlashAndTilde() {
        Result r = run(o("additionalProperties", false), o("a/b", 1, "c~d", 2, "", 3));
        assertEquals(List.of("/", "/a~1b", "/c~0d"), r.violations().stream().map(Violation::instancePointer).collect(Collectors.toList()));
    }

    @Test
    void deepPointer() {
        Object s = o("properties", o("xs", o("items", o("properties", o("n", o("maximum", 3))))));
        Result r = run(s, o("xs", l(o("n", 1), o("n", 9))));
        assertEquals(1, r.violations().size());
        Violation v = r.violations().get(0);
        assertEquals("/xs/1/n", v.instancePointer());
        assertEquals("/properties/xs/items/properties/n/maximum", v.schemaPointer());
        assertEquals("maximum", v.keyword());
        assertTrue(v.message().contains("9"), v.message());
        assertTrue(v.message().contains("3"), v.message());
    }

    // ---- shape errors ---------------------------------------------------
    static List<Object> badSchemas() {
        return List.of(
                "a string", 5,
                o("type", "foo"), o("type", l()), o("type", l("string", "string")), o("type", l(1)), o("type", 1),
                o("enum", "x"), o("required", "a"), o("required", l(1)), o("required", l("a", "a")),
                o("minLength", -1), o("maxLength", 1.5), o("minLength", "1"), o("minItems", -1), o("maxProperties", -2),
                o("minContains", -1), o("maxContains", true),
                o("multipleOf", 0), o("multipleOf", -1), o("multipleOf", "2"),
                o("minimum", "1"), o("maximum", true), o("exclusiveMinimum", l()), o("exclusiveMaximum", null),
                o("pattern", "("), o("pattern", 1), o("patternProperties", o("[", true)),
                o("properties", l()), o("properties", o("a", 5)), o("properties", o("a", o("type", "nope"))),
                o("$defs", o("a", "x")), o("allOf", l()), o("anyOf", "x"), o("oneOf", l(5)), o("not", 5),
                o("if", "x"), o("then", 5), o("else", l()),
                o("items", 5), o("prefixItems", l()), o("prefixItems", o()), o("contains", "x"),
                o("uniqueItems", "yes"), o("additionalProperties", 1), o("propertyNames", "x"),
                o("dependentRequired", o("a", "b")), o("dependentRequired", o("a", l(1))), o("dependentRequired", o("a", l("b", "b"))),
                o("dependentSchemas", o("a", 5)), o("unevaluatedItems", 1), o("unevaluatedProperties", "x"),
                l(), new Object(), o("properties", o("a", o("items", o("minimum", "x")))),
                o("allOf", l(o("allOf", l(o("maxItems", -1)))))
        );
    }

    @ParameterizedTest
    @MethodSource("badSchemas")
    void malformedSchemasThrowSchemaException(Object schema) {
        assertThrows(SchemaException.class, () -> Validator.compile(schema));
    }

    @Test
    void nonStringSchemaKeysAndNullAreRejected() {
        Map<Object, Object> m = new HashMap<>();
        m.put(5, true);
        assertThrows(SchemaException.class, () -> Validator.compile(m));
        assertThrows(SchemaException.class, () -> Validator.compile(null));
        assertThrows(SchemaException.class, () -> Validator.compile(o("properties", o("a", o("enum", l(new Object()))))));
        assertThrows(SchemaException.class, () -> Validator.compile(o("const", Double.NaN)));
        assertThrows(SchemaException.class, () -> Validator.compile(o("minimum", Double.POSITIVE_INFINITY)));
        assertDoesNotThrow(() -> Validator.compile(o("const", o("any", l(1, "x", null, true)))));
        assertDoesNotThrow(() -> Validator.compile(o("unknownKeyword", new Object[0].length)));
        assertDoesNotThrow(() -> Validator.compile(o("x-note", o("not", "a schema"))));
    }

    // ---- instance errors ------------------------------------------------
    @Test
    void unsupportedInstanceValues() {
        Validator v = Validator.compile(true);
        assertThrows(IllegalArgumentException.class, () -> v.validate(new Object()));
        assertThrows(IllegalArgumentException.class, () -> v.validate(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> v.validate(Float.NEGATIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> v.validate(l(1, l(new Object()))));
        Map<Object, Object> m = new HashMap<>();
        m.put(1, 1);
        assertThrows(IllegalArgumentException.class, () -> v.validate(m));
        assertThrows(IllegalArgumentException.class, () -> v.validate(o("a", new StringBuilder("sb"))));
        assertThrows(IllegalArgumentException.class, () -> v.validate(new java.util.concurrent.atomic.AtomicInteger(1)));
        assertTrue(v.validate(null).valid());
    }

    // ---- isolation ------------------------------------------------------
    @Test
    void schemaIsCopiedAtCompileTime() {
        Map<String, Object> schema = o("type", "string", "enum", l("a"));
        Validator v = Validator.compile(schema);
        schema.put("type", "integer");
        ((List<Object>) schema.get("enum")).add("b");
        assertTrue(v.validate("a").valid());
        assertFalse(v.validate("b").valid());
        assertFalse(v.validate(1).valid());
    }

    @Test
    void validationDoesNotMutateTheInstance() {
        Map<String, Object> inst = o("a", l(1, 2, o("k", "v")), "b", new BigDecimal("1.50"));
        String before = inst.toString();
        run(o("properties", o("a", o("uniqueItems", true, "contains", o("type", "object"))), "unevaluatedProperties", false), inst);
        assertEquals(before, inst.toString());
    }

    @Test
    void validatorIsReusableAcrossThreads() throws Exception {
        Validator v = Validator.compile(o("properties", o("n", o("type", "integer", "minimum", 0)), "unevaluatedProperties", false));
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<Boolean>> fs = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                final int n = i - 100;
                fs.add(ex.submit(() -> v.validate(o("n", n)).valid() == (n >= 0)));
            }
            for (java.util.concurrent.Future<Boolean> f : fs) assertTrue(f.get());
        } finally {
            ex.shutdownNow();
        }
    }

    // ---- value classes ----------------------------------------------------
    @Test
    void violationAndResultValueSemantics() {
        Violation a = new Violation("/a", "/b", "k", "m");
        assertEquals(a, new Violation("/a", "/b", "k", "m"));
        assertEquals(a.hashCode(), new Violation("/a", "/b", "k", "m").hashCode());
        assertNotEquals(a, new Violation("/a", "/b", "k", "other"));
        assertThrows(UnsupportedOperationException.class, () -> run(false, 1).violations().add(a));
        assertTrue(run(true, 1).violations().isEmpty());
        assertTrue(run(false, 1).toString().contains("false"));
    }

    // ---- metamorphic randomised checks -----------------------------------
    static Object randSchema(Random r, int depth) {
        int k = r.nextInt(depth <= 0 ? 8 : 15);
        switch (k) {
            case 0: return r.nextBoolean();
            case 1: return o("type", List.of("string", "integer", "number", "array", "object", "boolean", "null").get(r.nextInt(7)));
            case 2: return o("minimum", r.nextInt(7) - 3);
            case 3: return o("maxLength", r.nextInt(3));
            case 4: return o("enum", l(r.nextInt(3), "a", null));
            case 5: return o("const", r.nextBoolean() ? (Object) 1 : (Object) "a");
            case 6: return o("required", l(List.of("a", "b").get(r.nextInt(2))));
            case 7: return o("uniqueItems", true, "maxItems", r.nextInt(4));
            case 8: return o("properties", o("a", randSchema(r, depth - 1)));
            case 9: return o("items", randSchema(r, depth - 1));
            case 10: return o("anyOf", l(randSchema(r, depth - 1), randSchema(r, depth - 1)));
            case 11: return o("allOf", l(randSchema(r, depth - 1), randSchema(r, depth - 1)));
            case 12: return o("not", randSchema(r, depth - 1));
            case 13: return o("if", randSchema(r, depth - 1), "then", randSchema(r, depth - 1), "else", randSchema(r, depth - 1));
            default: return o("contains", randSchema(r, depth - 1), "minContains", r.nextInt(3));
        }
    }

    static Object randInstance(Random r, int depth) {
        int k = r.nextInt(depth <= 0 ? 6 : 9);
        switch (k) {
            case 0: return null;
            case 1: return r.nextBoolean();
            case 2: return r.nextInt(7) - 3;
            case 3: return r.nextInt(7) - 3 + 0.5;
            case 4: return List.of("", "a", "ab", "abc").get(r.nextInt(4));
            case 5: return (double) (r.nextInt(5) - 2);
            case 6: { List<Object> xs = l(); for (int i = r.nextInt(4); i > 0; i--) xs.add(randInstance(r, depth - 1)); return xs; }
            default: { Map<String, Object> m = o(); if (r.nextBoolean()) m.put("a", randInstance(r, depth - 1)); if (r.nextBoolean()) m.put("b", randInstance(r, depth - 1)); return m; }
        }
    }

    @Test
    void metamorphicIdentities() {
        Random r = new Random(20261126);
        int checked = 0, validCount = 0;
        for (int i = 0; i < 1500; i++) {
            Object s = randSchema(r, 3);
            Object inst = randInstance(r, 3);
            boolean base = ok(s, inst);
            assertEquals(base, ok(o("allOf", l(s, s)), inst), "allOf[s,s] " + s + " / " + inst);
            assertEquals(base, ok(o("anyOf", l(s)), inst), "anyOf[s] " + s + " / " + inst);
            assertEquals(base, ok(o("anyOf", l(s, s)), inst), "anyOf[s,s] " + s + " / " + inst);
            assertEquals(base, ok(o("not", o("not", s)), inst), "not not " + s + " / " + inst);
            assertEquals(base, ok(o("oneOf", l(s, false)), inst), "oneOf[s,false] " + s + " / " + inst);
            assertEquals(base, ok(o("allOf", l(s, true)), inst), "allOf[s,true] " + s + " / " + inst);
            assertEquals(!base, ok(o("not", s), inst), "not " + s + " / " + inst);
            assertEquals(base, ok(o("if", true, "then", s, "else", false), inst), "if true " + s + " / " + inst);
            assertEquals(base, ok(o("if", false, "then", false, "else", s), inst), "if false " + s + " / " + inst);
            assertEquals(base, ok(o("$defs", o("s", s), "$ref", "#/$defs/s"), inst), "$ref " + s + " / " + inst);
            assertEquals(base, ok(o("properties", o("p", s)), o("p", inst)), "properties " + s + " / " + inst);
            assertEquals(base, ok(o("items", s), l(inst)), "items " + s + " / " + inst);
            assertEquals(base, ok(o("prefixItems", l(s)), l(inst)), "prefixItems " + s + " / " + inst);
            assertEquals(base, ok(o("unevaluatedProperties", s), o("p", inst)), "unevaluatedProperties " + s + " / " + inst);
            assertEquals(base, ok(o("unevaluatedItems", s), l(inst)), "unevaluatedItems " + s + " / " + inst);
            assertEquals(base, ok(o("additionalProperties", s), o("p", inst)), "additionalProperties " + s + " / " + inst);
            assertEquals(ok(s, "p"), ok(o("propertyNames", s), o("p", 1)), "propertyNames " + s);
            assertEquals(base, run(s, inst).violations().isEmpty(), "valid <=> no violations " + s + " / " + inst);
            checked++;
            if (base) validCount++;
        }
        assertEquals(1500, checked);
        assertTrue(validCount > 300 && validCount < 1200, "generator should yield both outcomes, valid=" + validCount);
    }

    @Test
    void randomisedValidityAgreesWithViolationList() {
        Random r = new Random(7);
        for (int i = 0; i < 800; i++) {
            Object s = randSchema(r, 3);
            Object inst = randInstance(r, 3);
            Result res = run(s, inst);
            assertEquals(res.valid(), res.violations().isEmpty());
            for (Violation v : res.violations()) {
                assertTrue(v.instancePointer().isEmpty() || v.instancePointer().startsWith("/"));
                assertTrue(v.schemaPointer().isEmpty() || v.schemaPointer().startsWith("/"));
                assertFalse(v.keyword().isEmpty());
                assertFalse(v.message().isEmpty());
            }
        }
    }
}
