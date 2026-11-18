package bench.ranked;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

/** Order-statistic multiset backed by a treap with per-node occurrence counts. */
public final class RankedMultiset<E> {

    private static final class Node<E> {
        final E key;
        final int pri;
        int cnt = 1;
        int size = 1;
        Node<E> left;
        Node<E> right;

        Node(E key, int pri) {
            this.key = key;
            this.pri = pri;
        }
    }

    private final Comparator<? super E> cmp;
    private final SplittableRandom rnd = new SplittableRandom(0x9E3779B97F4A7C15L);
    private Node<E> root;
    private boolean removed;

    public RankedMultiset(Comparator<? super E> cmp) {
        if (cmp == null) {
            throw new IllegalArgumentException("comparator is null");
        }
        this.cmp = cmp;
    }

    private static <E> int sz(Node<E> n) {
        return n == null ? 0 : n.size;
    }

    private static <E> void update(Node<E> n) {
        n.size = n.cnt + sz(n.left) + sz(n.right);
    }

    private static <E> Node<E> rotR(Node<E> n) {
        Node<E> l = n.left;
        n.left = l.right;
        l.right = n;
        update(n);
        update(l);
        return l;
    }

    private static <E> Node<E> rotL(Node<E> n) {
        Node<E> r = n.right;
        n.right = r.left;
        r.left = n;
        update(n);
        update(r);
        return r;
    }

    private static void nonNull(Object o) {
        if (o == null) {
            throw new IllegalArgumentException("null element");
        }
    }

    public void add(E e) {
        nonNull(e);
        root = insert(root, e);
    }

    private Node<E> insert(Node<E> n, E e) {
        if (n == null) {
            return new Node<>(e, rnd.nextInt());
        }
        int c = cmp.compare(e, n.key);
        if (c == 0) {
            n.cnt++;
        } else if (c < 0) {
            n.left = insert(n.left, e);
            if (n.left.pri > n.pri) {
                return rotR(n);
            }
        } else {
            n.right = insert(n.right, e);
            if (n.right.pri > n.pri) {
                return rotL(n);
            }
        }
        update(n);
        return n;
    }

    public boolean remove(E e) {
        nonNull(e);
        removed = false;
        root = delete(root, e);
        return removed;
    }

    private Node<E> delete(Node<E> n, E e) {
        if (n == null) {
            return null;
        }
        int c = cmp.compare(e, n.key);
        if (c < 0) {
            n.left = delete(n.left, e);
        } else if (c > 0) {
            n.right = delete(n.right, e);
        } else {
            removed = true;
            if (n.cnt > 1) {
                n.cnt--;
            } else {
                return sink(n);
            }
        }
        update(n);
        return n;
    }

    /** Removes node n (cnt == 1) by rotating it down to a leaf. */
    private Node<E> sink(Node<E> n) {
        if (n.left == null) {
            return n.right;
        }
        if (n.right == null) {
            return n.left;
        }
        if (n.left.pri > n.right.pri) {
            Node<E> l = rotR(n);
            l.right = sink(l.right);
            update(l);
            return l;
        }
        Node<E> r = rotL(n);
        r.left = sink(r.left);
        update(r);
        return r;
    }

    public int size() {
        return sz(root);
    }

    public int count(E e) {
        nonNull(e);
        Node<E> n = root;
        while (n != null) {
            int c = cmp.compare(e, n.key);
            if (c == 0) {
                return n.cnt;
            }
            n = c < 0 ? n.left : n.right;
        }
        return 0;
    }

    public int rank(E e) {
        nonNull(e);
        int r = 0;
        Node<E> n = root;
        while (n != null) {
            int c = cmp.compare(e, n.key);
            if (c <= 0) {
                n = n.left;
            } else {
                r += sz(n.left) + n.cnt;
                n = n.right;
            }
        }
        return r;
    }

    public E select(int index) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException("index " + index + ", size " + size());
        }
        int i = index;
        Node<E> n = root;
        while (true) {
            int ls = sz(n.left);
            if (i < ls) {
                n = n.left;
            } else if (i < ls + n.cnt) {
                return n.key;
            } else {
                i -= ls + n.cnt;
                n = n.right;
            }
        }
    }

    public int countBetween(E lo, E hi) {
        nonNull(lo);
        nonNull(hi);
        if (cmp.compare(lo, hi) >= 0) {
            return 0;
        }
        return rank(hi) - rank(lo);
    }

    public List<E> toList() {
        List<E> out = new ArrayList<>(size());
        ArrayDeque<Node<E>> stack = new ArrayDeque<>();
        Node<E> n = root;
        while (n != null || !stack.isEmpty()) {
            while (n != null) {
                stack.push(n);
                n = n.left;
            }
            n = stack.pop();
            for (int i = 0; i < n.cnt; i++) {
                out.add(n.key);
            }
            n = n.right;
        }
        return out;
    }
}
