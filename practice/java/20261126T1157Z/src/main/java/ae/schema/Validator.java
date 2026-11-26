package ae.schema;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validator for a JSON Schema 2020-12 subset over plain Java values (see TASK.md).
 * Instances of this class are immutable and safe to share between threads.
 */
public final class Validator {
    private static final Set<String> TYPES = Set.of("null", "boolean", "string", "number", "integer", "object", "array");

    private final Object root;
    /** Schema node (by identity) that carries a {@code $ref} -> its resolved target. Read-only after compile. */
    private final IdentityHashMap<Object, Object> refTargets = new IdentityHashMap<>();
    private final Map<String, Pattern> patterns = new HashMap<>();

    private Validator(Object schema) {
        this.root = copy(schema, "");
        check(root, "", Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /** Compiles (deep-copies and shape-checks) a schema. */
    public static Validator compile(Object schema) {
        return new Validator(schema);
    }

    /** Validates an instance. Throws {@link IllegalArgumentException} for unsupported values. */
    public Result validate(Object instance) {
        checkInstance(instance);
        List<Violation> out = new ArrayList<>();
        eval(root, instance, "", "", out, new IdentityHashMap<>(), new Ann());
        return new Result(out);
    }

    // ===================================================================
    // compile: copy + shape check
    // ===================================================================

    private static Object copy(Object v, String path) {
        if (v == null || v instanceof Boolean || v instanceof String) return v;
        if (v instanceof Number) {
            toDecimal(v, path);
            return v;
        }
        if (v instanceof List<?> list) {
            List<Object> c = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) c.add(copy(list.get(i), path + "/" + i));
            return c;
        }
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> c = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String k)) throw new SchemaException(at(path) + ": object key is not a String");
                c.put(k, copy(e.getValue(), path + "/" + escape(k)));
            }
            return c;
        }
        throw new SchemaException(at(path) + ": unsupported value of type " + v.getClass().getName());
    }

    private static String at(String path) {
        return path.isEmpty() ? "<root>" : path;
    }

    private static BigDecimal toDecimal(Object n, String path) {
        try {
            return decimal(n);
        } catch (IllegalArgumentException e) {
            throw new SchemaException(at(path) + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void check(Object s, String path, Set<Object> seen) {
        if (s instanceof Boolean) return;
        if (!(s instanceof Map<?, ?>)) throw new SchemaException(at(path) + ": a schema must be a boolean or an object");
        if (!seen.add(s)) return; // already checked (shared via $ref)
        Map<String, Object> m = (Map<String, Object>) s;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            String p = path + "/" + escape(k);
            switch (k) {
                case "type" -> {
                    if (v instanceof String t) requireType(t, p);
                    else if (v instanceof List<?> ts && !ts.isEmpty()) {
                        Set<String> uniq = new HashSet<>();
                        for (Object t : ts) {
                            if (!(t instanceof String name)) throw new SchemaException(p + ": type names must be strings");
                            requireType(name, p);
                            if (!uniq.add(name)) throw new SchemaException(p + ": duplicate type " + name);
                        }
                    } else throw new SchemaException(p + ": type must be a name or a non-empty array of names");
                }
                case "enum" -> requireList(v, p);
                case "required" -> requireUniqueStrings(v, p);
                case "minLength", "maxLength", "minItems", "maxItems", "minProperties", "maxProperties",
                        "minContains", "maxContains" -> requireCount(v, p);
                case "multipleOf" -> {
                    if (!(v instanceof Number) || toDecimal(v, p).signum() <= 0)
                        throw new SchemaException(p + ": multipleOf must be a number greater than zero");
                }
                case "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum" -> {
                    if (!(v instanceof Number)) throw new SchemaException(p + ": must be a number");
                }
                case "pattern" -> {
                    if (!(v instanceof String re)) throw new SchemaException(p + ": pattern must be a string");
                    pattern(re, p);
                }
                case "uniqueItems" -> {
                    if (!(v instanceof Boolean)) throw new SchemaException(p + ": uniqueItems must be a boolean");
                }
                case "properties", "$defs", "dependentSchemas" -> {
                    for (Map.Entry<String, Object> c : requireMap(v, p).entrySet())
                        check(c.getValue(), p + "/" + escape(c.getKey()), seen);
                }
                case "patternProperties" -> {
                    for (Map.Entry<String, Object> c : requireMap(v, p).entrySet()) {
                        pattern(c.getKey(), p);
                        check(c.getValue(), p + "/" + escape(c.getKey()), seen);
                    }
                }
                case "allOf", "anyOf", "oneOf", "prefixItems" -> {
                    List<Object> list = requireList(v, p);
                    if (list.isEmpty()) throw new SchemaException(p + ": must not be empty");
                    for (int i = 0; i < list.size(); i++) check(list.get(i), p + "/" + i, seen);
                }
                case "not", "if", "then", "else", "items", "contains", "additionalProperties", "propertyNames",
                        "unevaluatedItems", "unevaluatedProperties" -> check(v, p, seen);
                case "dependentRequired" -> {
                    for (Map.Entry<String, Object> c : requireMap(v, p).entrySet())
                        requireUniqueStrings(c.getValue(), p + "/" + escape(c.getKey()));
                }
                case "$ref" -> {
                    if (!(v instanceof String ref)) throw new SchemaException(p + ": $ref must be a string");
                    Object target = resolve(ref, p);
                    refTargets.put(m, target);
                    check(target, p + " -> " + ref, seen);
                }
                default -> { /* unknown keywords are annotations */ }
            }
        }
    }

    private static void requireType(String t, String p) {
        if (!TYPES.contains(t)) throw new SchemaException(p + ": unknown type '" + t + "'");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> requireList(Object v, String p) {
        if (!(v instanceof List<?>)) throw new SchemaException(p + ": must be an array");
        return (List<Object>) v;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireMap(Object v, String p) {
        if (!(v instanceof Map<?, ?>)) throw new SchemaException(p + ": must be an object");
        return (Map<String, Object>) v;
    }

    private static void requireUniqueStrings(Object v, String p) {
        Set<String> uniq = new HashSet<>();
        for (Object x : requireList(v, p)) {
            if (!(x instanceof String s)) throw new SchemaException(p + ": must contain only strings");
            if (!uniq.add(s)) throw new SchemaException(p + ": duplicate entry '" + s + "'");
        }
    }

    private static void requireCount(Object v, String p) {
        if (!(v instanceof Number)) throw new SchemaException(p + ": must be a non-negative integer");
        BigDecimal d = toDecimal(v, p);
        if (d.signum() < 0 || !isIntegral(d)) throw new SchemaException(p + ": must be a non-negative integer");
    }

    private Pattern pattern(String re, String p) {
        Pattern c = patterns.get(re);
        if (c == null) {
            try {
                c = Pattern.compile(re);
            } catch (PatternSyntaxException e) {
                throw new SchemaException(p + ": invalid regular expression '" + re + "'");
            }
            patterns.put(re, c);
        }
        return c;
    }

    // ---- $ref resolution ------------------------------------------------

    private Object resolve(String ref, String p) {
        if (!ref.equals("#") && !ref.startsWith("#/"))
            throw new SchemaException(p + ": only '#' and '#/pointer' references are supported: " + ref);
        Object cur = root;
        if (ref.length() > 1) {
            String frag = percentDecode(ref.substring(1), p);
            for (String raw : frag.substring(1).split("/", -1)) {
                String tok = raw.replace("~1", "/").replace("~0", "~");
                if (cur instanceof Map<?, ?> map) {
                    if (!map.containsKey(tok)) throw new SchemaException(p + ": unresolvable reference " + ref);
                    cur = map.get(tok);
                } else if (cur instanceof List<?> list) {
                    if (!tok.matches("0|[1-9][0-9]*") || tok.length() > 9 || Integer.parseInt(tok) >= list.size())
                        throw new SchemaException(p + ": unresolvable reference " + ref);
                    cur = list.get(Integer.parseInt(tok));
                } else {
                    throw new SchemaException(p + ": unresolvable reference " + ref);
                }
            }
        }
        if (!(cur instanceof Boolean) && !(cur instanceof Map<?, ?>))
            throw new SchemaException(p + ": reference " + ref + " does not point at a schema");
        return cur;
    }

    private static String percentDecode(String s, String p) {
        if (s.indexOf('%') < 0) return s;
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) throw new SchemaException(p + ": bad percent escape in reference");
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) throw new SchemaException(p + ": bad percent escape in reference");
                bytes.write(hi * 16 + lo);
                i += 2;
            } else {
                byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                if (Character.isSurrogate(c)) {
                    int cp = s.codePointAt(i);
                    b = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
                    i += Character.charCount(cp) - 1;
                }
                bytes.write(b, 0, b.length);
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    // ===================================================================
    // numbers, equality, pointers
    // ===================================================================

    private static BigDecimal decimal(Object n) {
        if (n instanceof BigDecimal d) return d;
        if (n instanceof BigInteger b) return new BigDecimal(b);
        if (n instanceof Byte || n instanceof Short || n instanceof Integer || n instanceof Long)
            return BigDecimal.valueOf(((Number) n).longValue());
        if (n instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) throw new IllegalArgumentException("non-finite number");
            return new BigDecimal(Double.toString(d));
        }
        if (n instanceof Float f) {
            if (f.isNaN() || f.isInfinite()) throw new IllegalArgumentException("non-finite number");
            return new BigDecimal(Float.toString(f));
        }
        throw new IllegalArgumentException("unsupported number type " + n.getClass().getName());
    }

    private static boolean isIntegral(BigDecimal d) {
        return d.stripTrailingZeros().scale() <= 0; // BigDecimal.ZERO strips to scale 0
    }

    private static String escape(String k) {
        return k.replace("~", "~0").replace("/", "~1");
    }

    private static void checkInstance(Object v) {
        if (v == null || v instanceof Boolean || v instanceof String) return;
        if (v instanceof Number) {
            decimal(v);
            return;
        }
        if (v instanceof List<?> list) {
            for (Object x : list) checkInstance(x);
            return;
        }
        if (v instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String)) throw new IllegalArgumentException("object key is not a String");
                checkInstance(e.getValue());
            }
            return;
        }
        throw new IllegalArgumentException("unsupported instance value of type " + v.getClass().getName());
    }

    /** JSON equality: numbers by value, objects unordered, arrays ordered. */
    private static boolean jsonEquals(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number && b instanceof Number) return decimal(a).compareTo(decimal(b)) == 0;
        if (a instanceof Boolean || b instanceof Boolean || a instanceof String || b instanceof String)
            return a.equals(b);
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!jsonEquals(x.get(i), y.get(i))) return false;
            return true;
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (x.size() != y.size()) return false;
            for (Map.Entry<?, ?> e : x.entrySet()) {
                if (!y.containsKey(e.getKey()) || !jsonEquals(e.getValue(), y.get(e.getKey()))) return false;
            }
            return true;
        }
        return false;
    }

    private static String kind(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean) return "boolean";
        if (v instanceof String) return "string";
        if (v instanceof Number) return "number";
        if (v instanceof List) return "array";
        return "object";
    }

    private static boolean hasType(Object v, String t) {
        String k = kind(v);
        if (t.equals(k)) return true;
        return t.equals("integer") && k.equals("number") && isIntegral(decimal(v));
    }

    // ===================================================================
    // evaluation
    // ===================================================================

    /** Annotations produced by one schema object at one instance location. */
    private static final class Ann {
        final Set<String> props = new HashSet<>();
        final BitSet items = new BitSet();
        boolean allItems;

        void merge(Ann o) {
            props.addAll(o.props);
            items.or(o.items);
            allItems |= o.allItems;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean eval(Object schema, Object inst, String ip, String sp, List<Violation> out,
            IdentityHashMap<Object, Set<String>> active, Ann ann) {
        if (schema instanceof Boolean b) {
            if (b) return true;
            out.add(new Violation(ip, sp, "false", "schema is false: nothing is valid"));
            return false;
        }
        Map<String, Object> m = (Map<String, Object>) schema;
        Set<String> at = active.computeIfAbsent(m, k -> new HashSet<>());
        if (!at.add(ip)) throw new SchemaException("$ref loop: schema at '" + sp + "' re-entered at instance '" + ip + "'");
        try {
            int before = out.size();
            keywords(m, inst, ip, sp, out, active, ann);
            return out.size() == before;
        } finally {
            at.remove(ip);
            if (at.isEmpty()) active.remove(m);
        }
    }

    /** Evaluates a child schema in place: its annotations are merged into {@code ann} only when it is valid. */
    private boolean inPlace(Object schema, Object inst, String ip, String sp, List<Violation> out,
            IdentityHashMap<Object, Set<String>> active, Ann ann) {
        Ann child = new Ann();
        boolean ok = eval(schema, inst, ip, sp, out, active, child);
        if (ok) ann.merge(child);
        return ok;
    }

    private boolean applies(Object schema, Object inst, String ip, String sp, List<Violation> out,
            IdentityHashMap<Object, Set<String>> active) {
        return eval(schema, inst, ip, sp, out, active, new Ann());
    }

    @SuppressWarnings("unchecked")
    private void keywords(Map<String, Object> m, Object inst, String ip, String sp, List<Violation> out,
            IdentityHashMap<Object, Set<String>> active, Ann ann) {
        if (m.containsKey("$ref")) {
            inPlace(refTargets.get(m), inst, ip, sp + "/$ref", out, active, ann);
        }
        if (m.containsKey("type")) {
            Object t = m.get("type");
            List<String> names = t instanceof String s ? List.of(s) : (List<String>) (List<?>) t;
            boolean any = false;
            for (String n : names) any |= hasType(inst, n);
            if (!any) out.add(new Violation(ip, sp + "/type", "type", "expected " + String.join(" or ", names) + " but was " + kind(inst)));
        }
        if (m.containsKey("enum")) {
            boolean any = false;
            for (Object e : (List<Object>) m.get("enum")) any |= jsonEquals(e, inst);
            if (!any) out.add(new Violation(ip, sp + "/enum", "enum", "value is not one of the allowed values"));
        }
        if (m.containsKey("const") && !jsonEquals(m.get("const"), inst)) {
            out.add(new Violation(ip, sp + "/const", "const", "value is not equal to the required constant"));
        }
        if (m.containsKey("allOf")) {
            List<Object> subs = (List<Object>) m.get("allOf");
            for (int i = 0; i < subs.size(); i++) inPlace(subs.get(i), inst, ip, sp + "/allOf/" + i, out, active, ann);
        }
        if (m.containsKey("anyOf")) {
            List<Object> subs = (List<Object>) m.get("anyOf");
            boolean any = false;
            for (int i = 0; i < subs.size(); i++)
                any |= inPlace(subs.get(i), inst, ip, sp + "/anyOf/" + i, new ArrayList<>(), active, ann);
            if (!any) out.add(new Violation(ip, sp + "/anyOf", "anyOf", "value matches none of the " + subs.size() + " schemas"));
        }
        if (m.containsKey("oneOf")) {
            List<Object> subs = (List<Object>) m.get("oneOf");
            int matched = 0;
            Ann only = new Ann();
            for (int i = 0; i < subs.size(); i++) {
                Ann a = new Ann();
                if (eval(subs.get(i), inst, ip, sp + "/oneOf/" + i, new ArrayList<>(), active, a)) {
                    matched++;
                    only = a;
                }
            }
            if (matched == 1) ann.merge(only);
            else out.add(new Violation(ip, sp + "/oneOf", "oneOf", "value matches " + matched + " of the " + subs.size() + " schemas, expected exactly one"));
        }
        if (m.containsKey("not") && applies(m.get("not"), inst, ip, sp + "/not", new ArrayList<>(), active)) {
            out.add(new Violation(ip, sp + "/not", "not", "value must not match the schema"));
        }
        if (m.containsKey("if")) {
            if (inPlace(m.get("if"), inst, ip, sp + "/if", new ArrayList<>(), active, ann)) {
                if (m.containsKey("then")) inPlace(m.get("then"), inst, ip, sp + "/then", out, active, ann);
            } else if (m.containsKey("else")) {
                inPlace(m.get("else"), inst, ip, sp + "/else", out, active, ann);
            }
        }
        if (inst instanceof Number) numeric(m, decimal(inst), ip, sp, out);
        if (inst instanceof String s) string(m, s, ip, sp, out);
        if (inst instanceof List<?> list) array(m, list, ip, sp, out, active, ann);
        if (inst instanceof Map<?, ?> map) object(m, (Map<String, Object>) map, ip, sp, out, active, ann);
        if (inst instanceof List<?> list && m.containsKey("unevaluatedItems")) {
            for (int i = 0; i < list.size(); i++) {
                if (ann.allItems || ann.items.get(i)) continue;
                String where = ip + "/" + i;
                if (Boolean.FALSE.equals(m.get("unevaluatedItems")))
                    out.add(new Violation(where, sp + "/unevaluatedItems", "unevaluatedItems", "item " + i + " is not allowed"));
                else applies(m.get("unevaluatedItems"), list.get(i), where, sp + "/unevaluatedItems", out, active);
            }
            ann.allItems = true;
        }
        if (inst instanceof Map<?, ?> map && m.containsKey("unevaluatedProperties")) {
            for (String k : sortedKeys((Map<String, Object>) map)) {
                if (ann.props.contains(k)) continue;
                String where = ip + "/" + escape(k);
                if (Boolean.FALSE.equals(m.get("unevaluatedProperties")))
                    out.add(new Violation(where, sp + "/unevaluatedProperties", "unevaluatedProperties", "property '" + k + "' is not allowed"));
                else applies(m.get("unevaluatedProperties"), map.get(k), where, sp + "/unevaluatedProperties", out, active);
                ann.props.add(k);
            }
        }
    }

    private void numeric(Map<String, Object> m, BigDecimal d, String ip, String sp, List<Violation> out) {
        if (m.containsKey("minimum") && d.compareTo(decimal(m.get("minimum"))) < 0)
            out.add(new Violation(ip, sp + "/minimum", "minimum", d.toPlainString() + " is less than the minimum " + decimal(m.get("minimum")).toPlainString()));
        if (m.containsKey("maximum") && d.compareTo(decimal(m.get("maximum"))) > 0)
            out.add(new Violation(ip, sp + "/maximum", "maximum", d.toPlainString() + " is greater than the maximum " + decimal(m.get("maximum")).toPlainString()));
        if (m.containsKey("exclusiveMinimum") && d.compareTo(decimal(m.get("exclusiveMinimum"))) <= 0)
            out.add(new Violation(ip, sp + "/exclusiveMinimum", "exclusiveMinimum", d.toPlainString() + " is not greater than " + decimal(m.get("exclusiveMinimum")).toPlainString()));
        if (m.containsKey("exclusiveMaximum") && d.compareTo(decimal(m.get("exclusiveMaximum"))) >= 0)
            out.add(new Violation(ip, sp + "/exclusiveMaximum", "exclusiveMaximum", d.toPlainString() + " is not less than " + decimal(m.get("exclusiveMaximum")).toPlainString()));
        if (m.containsKey("multipleOf") && d.remainder(decimal(m.get("multipleOf"))).signum() != 0)
            out.add(new Violation(ip, sp + "/multipleOf", "multipleOf", d.toPlainString() + " is not a multiple of " + decimal(m.get("multipleOf")).toPlainString()));
    }

    private void string(Map<String, Object> m, String s, String ip, String sp, List<Violation> out) {
        int len = s.codePointCount(0, s.length());
        if (m.containsKey("minLength") && below(len, m.get("minLength")))
            out.add(new Violation(ip, sp + "/minLength", "minLength", "length " + len + " is below " + count(m.get("minLength"))));
        if (m.containsKey("maxLength") && above(len, m.get("maxLength")))
            out.add(new Violation(ip, sp + "/maxLength", "maxLength", "length " + len + " is above " + count(m.get("maxLength"))));
        if (m.containsKey("pattern") && !pattern((String) m.get("pattern"), sp).matcher(s).find())
            out.add(new Violation(ip, sp + "/pattern", "pattern", "does not match pattern " + m.get("pattern")));
    }

    private static BigInteger count(Object v) {
        return decimal(v).toBigIntegerExact();
    }

    private static boolean below(int n, Object limit) {
        return BigInteger.valueOf(n).compareTo(count(limit)) < 0;
    }

    private static boolean above(int n, Object limit) {
        return BigInteger.valueOf(n).compareTo(count(limit)) > 0;
    }

    private void array(Map<String, Object> m, List<?> list, String ip, String sp, List<Violation> out,
            IdentityHashMap<Object, Set<String>> active, Ann ann) {
        int n = list.size();
        int prefix = 0;
        if (m.containsKey("prefixItems")) {
            List<?> ps = (List<?>) m.get("prefixItems");
            prefix = Math.min(n, ps.size());
            for (int i = 0; i < prefix; i++) applies(ps.get(i), list.get(i), ip + "/" + i, sp + "/prefixItems/" + i, out, active);
            ann.items.set(0, prefix);
        }
        if (m.containsKey("items")) {
            for (int i = prefix; i < n; i++) applies(m.get("items"), list.get(i), ip + "/" + i, sp + "/items", out, active);
            ann.allItems = true;
        }
        if (m.containsKey("contains")) {
            BitSet hits = new BitSet();
            for (int i = 0; i < n; i++)
                if (applies(m.get("contains"), list.get(i), ip + "/" + i, sp + "/contains", new ArrayList<>(), active)) hits.set(i);
            int c = hits.cardinality();
            int min = m.containsKey("minContains") ? count(m.get("minContains")).intValue() : 1;
            if (c < min) {
                out.add(new Violation(ip, sp + "/contains", "contains", "only " + c + " items match, need at least " + min));
            }
            if (m.containsKey("maxContains") && above(c, m.get("maxContains"))) {
                out.add(new Violation(ip, sp + "/maxContains", "maxContains", c + " items match, at most " + count(m.get("maxContains")) + " allowed"));
            }
            ann.items.or(hits); // annotations of an invalid schema are dropped by the caller
        }
        if (m.containsKey("minItems") && below(n, m.get("minItems")))
            out.add(new Violation(ip, sp + "/minItems", "minItems", n + " items, need at least " + count(m.get("minItems"))));
        if (m.containsKey("maxItems") && above(n, m.get("maxItems")))
            out.add(new Violation(ip, sp + "/maxItems", "maxItems", n + " items, at most " + count(m.get("maxItems")) + " allowed"));
        if (Boolean.TRUE.equals(m.get("uniqueItems"))) {
            outer:
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (jsonEquals(list.get(i), list.get(j))) {
                        out.add(new Violation(ip, sp + "/uniqueItems", "uniqueItems", "items " + i + " and " + j + " are equal"));
                        break outer;
                    }
                }
            }
        }
    }

    private static List<String> sortedKeys(Map<String, Object> map) {
        List<String> keys = new ArrayList<>(map.keySet());
        Collections.sort(keys);
        return keys;
    }

    @SuppressWarnings("unchecked")
    private void object(Map<String, Object> m, Map<String, Object> obj, String ip, String sp, List<Violation> out,
            IdentityHashMap<Object, Set<String>> active, Ann ann) {
        List<String> keys = sortedKeys(obj);
        Map<String, Object> props = m.containsKey("properties") ? (Map<String, Object>) m.get("properties") : Map.of();
        Map<String, Object> pats = m.containsKey("patternProperties") ? (Map<String, Object>) m.get("patternProperties") : Map.of();
        if (!props.isEmpty()) {
            for (String k : keys) {
                if (props.containsKey(k)) {
                    applies(props.get(k), obj.get(k), ip + "/" + escape(k), sp + "/properties/" + escape(k), out, active);
                    ann.props.add(k);
                }
            }
        }
        for (Map.Entry<String, Object> pe : pats.entrySet()) {
            Pattern re = pattern(pe.getKey(), sp);
            for (String k : keys) {
                if (re.matcher(k).find()) {
                    applies(pe.getValue(), obj.get(k), ip + "/" + escape(k), sp + "/patternProperties/" + escape(pe.getKey()), out, active);
                    ann.props.add(k);
                }
            }
        }
        if (m.containsKey("additionalProperties")) {
            for (String k : keys) {
                if (props.containsKey(k)) continue;
                boolean patterned = false;
                for (String re : pats.keySet()) patterned |= pattern(re, sp).matcher(k).find();
                if (patterned) continue;
                Object sub = m.get("additionalProperties");
                if (Boolean.FALSE.equals(sub)) {
                    out.add(new Violation(ip + "/" + escape(k), sp + "/additionalProperties", "additionalProperties", "property '" + k + "' is not allowed"));
                } else {
                    applies(sub, obj.get(k), ip + "/" + escape(k), sp + "/additionalProperties", out, active);
                }
                ann.props.add(k);
            }
        }
        if (m.containsKey("propertyNames")) {
            for (String k : keys) applies(m.get("propertyNames"), k, ip, sp + "/propertyNames", out, active);
        }
        if (m.containsKey("required")) {
            for (Object r : (List<Object>) m.get("required")) {
                if (!obj.containsKey(r)) out.add(new Violation(ip, sp + "/required", "required", "missing property '" + r + "'"));
            }
        }
        if (m.containsKey("minProperties") && below(obj.size(), m.get("minProperties")))
            out.add(new Violation(ip, sp + "/minProperties", "minProperties", obj.size() + " properties, need at least " + count(m.get("minProperties"))));
        if (m.containsKey("maxProperties") && above(obj.size(), m.get("maxProperties")))
            out.add(new Violation(ip, sp + "/maxProperties", "maxProperties", obj.size() + " properties, at most " + count(m.get("maxProperties")) + " allowed"));
        if (m.containsKey("dependentRequired")) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("dependentRequired")).entrySet()) {
                if (!obj.containsKey(e.getKey())) continue;
                for (Object need : (List<Object>) e.getValue()) {
                    if (!obj.containsKey(need))
                        out.add(new Violation(ip, sp + "/dependentRequired/" + escape(e.getKey()), "dependentRequired",
                                "property '" + e.getKey() + "' requires '" + need + "'"));
                }
            }
        }
        if (m.containsKey("dependentSchemas")) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("dependentSchemas")).entrySet()) {
                if (obj.containsKey(e.getKey()))
                    inPlace(e.getValue(), obj, ip, sp + "/dependentSchemas/" + escape(e.getKey()), out, active, ann);
            }
        }
    }
}
