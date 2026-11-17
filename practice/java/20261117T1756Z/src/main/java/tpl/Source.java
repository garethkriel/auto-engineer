package tpl;

import java.util.ArrayList;
import java.util.List;

/** Maps character offsets of a template text to 1-based line and column numbers. */
final class Source {
    private final int[] lineStarts;

    Source(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
    }

    int line(int offset) {
        int lo = 0;
        int hi = lineStarts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStarts[mid] <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo + 1;
    }

    int column(int offset) {
        return offset - lineStarts[line(offset) - 1] + 1;
    }

    TemplateSyntaxException syntax(String message, int offset) {
        return new TemplateSyntaxException(message, line(offset), column(offset));
    }

    TemplateRenderException render(String message, int offset) {
        return new TemplateRenderException(message, line(offset), column(offset));
    }
}
