package ae.chess;

/** Test helper: colour/rank mirror of a FEN. */
final class TestFens {
    private TestFens() { }

    static String mirror(String fen) {
        String[] f = fen.split(" ");
        String[] rows = f[0].split("/");
        StringBuilder placement = new StringBuilder();
        for (int i = 7; i >= 0; i--) {
            for (char c : rows[i].toCharArray()) {
                if (Character.isUpperCase(c)) {
                    placement.append(Character.toLowerCase(c));
                } else if (Character.isLowerCase(c)) {
                    placement.append(Character.toUpperCase(c));
                } else {
                    placement.append(c);
                }
            }
            if (i > 0) {
                placement.append('/');
            }
        }
        String side = f[1].equals("w") ? "b" : "w";
        String castling = f[2];
        if (!castling.equals("-")) {
            StringBuilder sb = new StringBuilder();
            for (char c : castling.toCharArray()) {
                sb.append(Character.isUpperCase(c) ? Character.toLowerCase(c) : Character.toUpperCase(c));
            }
            // canonical order KQkq
            String s = sb.toString();
            StringBuilder ordered = new StringBuilder();
            for (char c : "KQkq".toCharArray()) {
                if (s.indexOf(c) >= 0) {
                    ordered.append(c);
                }
            }
            castling = ordered.toString();
        }
        String ep = f[3];
        if (!ep.equals("-")) {
            ep = "" + ep.charAt(0) + (ep.charAt(1) == '3' ? '6' : '3');
        }
        String tail = f.length >= 6 ? " " + f[4] + " " + f[5] : "";
        return placement + " " + side + " " + castling + " " + ep + tail;
    }
}
