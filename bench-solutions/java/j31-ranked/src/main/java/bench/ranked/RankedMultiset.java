package bench.ranked;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Sorted multiset backed by a treap with per-node duplicate counts and subtree sizes. */
public final class RankedMultiset<E> {
    private static final class Node<E> {
        final E key;
        final int prio;
        int count = 1;
        int size = 1;
        Node<E> left;
        Node<E> right;

        Node(E key, int prio) {
            this.key = key;
            this.prio = prio;
        }
    }

    private final Comparator<? super E> cmp;
    private Node<E> root;
    private int seed = 0x2545F491;

    public RankedMultiset(Comparator<? super E> cmp) {
        if (cmp == null) throw new IllegalArgumentException("comparator is null");
        this.cmp = cmp;
    }

    private int nextPrio() {
        int x = seed;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        seed = x;
        return x;
    }

    private static <E> int sz(Node<E> n) {
        return n == null ? 0 : n.size;
    }

    private static <E> void update(Node<E> n) {
        n.size = n.count + sz(n.left) + sz(n.right);
    }

    private static <E> Node<E> rotateRight(Node<E> n) {
        Node<E> l = n.left;
        n.left = l.right;
        l.right = n;
        update(n);
        update(l);
        return l;
    }

    private static <E> Node<E> rotateLeft(Node<E> n) {
        Node<E> r = n.right;
        n.right = r.left;
        r.left = n;
        update(n);
        update(r);
        return r;
    }

    private static void check(Object e) {
        if (e == null) throw new IllegalArgumentException("element is null");
    }

    public void add(E e) {
        check(e);
        root = insert(root, e);
    }

    private Node<E> insert(Node<E> n, E e) {
        if (n == null) return new Node<>(e, nextPrio());
        int c = cmp.compare(e, n.key);
        if (c == 0) {
            n.count++;
        } else if (c < 0) {
            n.left = insert(n.left, e);
            if (n.left.prio > n.prio) return rotateRight(n);
        } else {
            n.right = insert(n.right, e);
            if (n.right.prio > n.prio) return rotateLeft(n);
        }
        update(n);
        return n;
    }

    public boolean remove(E e) {
        check(e);
        boolean[] removed = new boolean[1];
        root = delete(root, e, removed);
        return removed[0];
    }

    private Node<E> delete(Node<E> n, E e, boolean[] removed) {
        if (n == null) return null;
        int c = cmp.compare(e, n.key);
        if (c < 0) {
            n.left = delete(n.left, e, removed);
        } else if (c > 0) {
            n.right = delete(n.right, e, removed);
        } else {
            removed[0] = true;
            if (n.count > 1) {
                n.count--;
            } else {
                return merge(n.left, n.right);
            }
        }
        update(n);
        return n;
    }

    private Node<E> merge(Node<E> a, Node<E> b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.prio > b.prio) {
            a.right = merge(a.right, b);
            update(a);
            return a;
        }
        b.left = merge(a, b.left);
        update(b);
        return b;
    }

    public int size() {
        return sz(root);
    }

    public int count(E e) {
        check(e);
        Node<E> n = root;
        while (n != null) {
            int c = cmp.compare(e, n.key);
            if (c == 0) return n.count;
            n = c < 0 ? n.left : n.right;
        }
        return 0;
    }

    public int rank(E e) {
        check(e);
        int r = 0;
        Node<E> n = root;
        while (n != null) {
            if (cmp.compare(e, n.key) <= 0) {
                n = n.left;
            } else {
                r += sz(n.left) + n.count;
                n = n.right;
            }
        }
        return r;
    }

    public E select(int index) {
        if (index < 0 || index >= size()) throw new IndexOutOfBoundsException("index " + index + ", size " + size());
        Node<E> n = root;
        int i = index;
        while (true) {
            int ls = sz(n.left);
            if (i < ls) {
                n = n.left;
            } else if (i < ls + n.count) {
                return n.key;
            } else {
                i -= ls + n.count;
                n = n.right;
            }
        }
    }

    public int countBetween(E lo, E hi) {
        check(lo);
        check(hi);
        if (cmp.compare(lo, hi) >= 0) return 0;
        return rank(hi) - rank(lo);
    }

    public List<E> toList() {
        List<E> out = new ArrayList<>(size());
        collect(root, out);
        return out;
    }

    private void collect(Node<E> n, List<E> out) {
        if (n == null) return;
        collect(n.left, out);
        for (int i = 0; i < n.count; i++) out.add(n.key);
        collect(n.right, out);
    }
}
