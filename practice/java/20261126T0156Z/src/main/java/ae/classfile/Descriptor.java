package ae.classfile;

import java.util.ArrayList;
import java.util.List;

/** Parser for field and method descriptors (JVMS 4.3). */
public final class Descriptor {
    private static final int MAX_SLOTS = 255;
    private static final int MAX_DIMS = 255;

    private Descriptor() {
    }

    public static Type parseField(String d) {
        int[] pos = {0};
        Type t = type(d, pos, false);
        if (pos[0] != d.length()) {
            throw bad(d, "trailing characters");
        }
        return t;
    }

    public static MethodDescriptor parseMethod(String d) {
        if (d.isEmpty() || d.charAt(0) != '(') {
            throw bad(d, "must start with '('");
        }
        int[] pos = {1};
        List<Type> params = new ArrayList<>();
        int slots = 0;
        while (true) {
            if (pos[0] >= d.length()) {
                throw bad(d, "missing ')'");
            }
            if (d.charAt(pos[0]) == ')') {
                pos[0]++;
                break;
            }
            Type t = type(d, pos, false);
            slots += t.slots();
            if (slots > MAX_SLOTS) {
                throw bad(d, "more than 255 argument slots");
            }
            params.add(t);
        }
        Type ret = type(d, pos, true);
        if (pos[0] != d.length()) {
            throw bad(d, "trailing characters");
        }
        return new MethodDescriptor(List.copyOf(params), ret);
    }

    private static Type type(String d, int[] pos, boolean voidAllowed) {
        int dims = 0;
        while (pos[0] < d.length() && d.charAt(pos[0]) == '[') {
            dims++;
            pos[0]++;
            if (dims > MAX_DIMS) {
                throw bad(d, "more than 255 array dimensions");
            }
        }
        if (pos[0] >= d.length()) {
            throw bad(d, "unexpected end");
        }
        char c = d.charAt(pos[0]++);
        switch (c) {
            case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z':
                return new Type(c, null, dims);
            case 'V':
                if (!voidAllowed || dims > 0) {
                    throw bad(d, "void is only legal as a return type");
                }
                return new Type('V', null, 0);
            case 'L':
                int semi = d.indexOf(';', pos[0]);
                if (semi < 0) {
                    throw bad(d, "unterminated class name");
                }
                String name = d.substring(pos[0], semi);
                if (name.isEmpty() || name.indexOf('.') >= 0 || name.indexOf('[') >= 0) {
                    throw bad(d, "illegal class name");
                }
                pos[0] = semi + 1;
                return new Type('L', name, dims);
            default:
                throw bad(d, "unknown type letter '" + c + "'");
        }
    }

    private static ClassFormatException bad(String d, String why) {
        return new ClassFormatException("bad descriptor '" + d + "': " + why);
    }
}
