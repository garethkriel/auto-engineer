package bench.ranked;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** Sorted multiset with order statistics, backed by a treap of (key, count) nodes. */
public final class RankedMultiset<E> {
    private static final class Node<E> {
        final E key;
        int cnt = 1;
        int size = 1;
        final int pri;
        Node<E> l;
        Node<E> r;

        Node(E key, int pri) {
            this.key = key;
            this.pri = pri;
        }
    }

    private final Comparator<? super E> cmp;
    private final Random rnd = new Random(0x5eedL);
    private Node<E> root;
    private boolean removed;

    public RankedMultiset(Comparator<? super E> cmp) {
        if (cmp == null) throw new IllegalArgumentException("comparator");
        this.cmp = cmp;
    }

    private static <E> int sz(Node<E> n) {
        return n == null ? 0 : n.size;
    }

    private static <E> void upd(Node<E> n) {
        n.size = n.cnt + sz(n.l) + sz(n.r);
    }

    private static void check(Object e) {
        if (e == null) throw new IllegalArgumentException("null element");
    }

    private Node<E> rotRight(Node<E> n) {
        Node<E> l = n.l;
        n.l = l.r;
        l.r = n;
        upd(n);
        upd(l);
        return l;
    }

    private Node<E> rotLeft(Node<E> n) {
        Node<E> r = n.r;
        n.r = r.l;
        r.l = n;
        upd(n);
        upd(r);
        return r;
    }

    public void add(E e) {
        check(e);
        root = insert(root, e);
    }

    private Node<E> insert(Node<E> n, E e) {
        if (n == null) return new Node<>(e, rnd.nextInt());
        int c = cmp.compare(e, n.key);
        if (c == 0) {
            n.cnt++;
        } else if (c < 0) {
            n.l = insert(n.l, e);
            if (n.l.pri > n.pri) n = rotRight(n);
        } else {
            n.r = insert(n.r, e);
            if (n.r.pri > n.pri) n = rotLeft(n);
        }
        upd(n);
        return n;
    }

    public boolean remove(E e) {
        check(e);
        removed = false;
        root = delete(root, e);
        return removed;
    }

    private Node<E> delete(Node<E> n, E e) {
        if (n == null) return null;
        int c = cmp.compare(e, n.key);
        if (c < 0) {
            n.l = delete(n.l, e);
        } else if (c > 0) {
            n.r = delete(n.r, e);
        } else {
            removed = true;
            if (n.cnt > 1) {
                n.cnt--;
            } else {
                return merge(n.l, n.r);
            }
        }
        upd(n);
        return n;
    }

    private Node<E> merge(Node<E> a, Node<E> b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.pri > b.pri) {
            a.r = merge(a.r, b);
            upd(a);
            return a;
        }
        b.l = merge(a, b.l);
        upd(b);
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
            if (c == 0) return n.cnt;
            n = c < 0 ? n.l : n.r;
        }
        return 0;
    }

    public int rank(E e) {
        check(e);
        int res = 0;
        Node<E> n = root;
        while (n != null) {
            int c = cmp.compare(e, n.key);
            if (c <= 0) {
                n = n.l;
            } else {
                res += sz(n.l) + n.cnt;
                n = n.r;
            }
        }
        return res;
    }

    public E select(int index) {
        if (index < 0 || index >= size()) throw new IndexOutOfBoundsException("index " + index);
        Node<E> n = root;
        int i = index;
        while (true) {
            int ls = sz(n.l);
            if (i < ls) {
                n = n.l;
            } else if (i < ls + n.cnt) {
                return n.key;
            } else {
                i -= ls + n.cnt;
                n = n.r;
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
        // iterative in-order traversal
        List<Node<E>> stack = new ArrayList<>();
        Node<E> n = root;
        while (n != null || !stack.isEmpty()) {
            while (n != null) {
                stack.add(n);
                n = n.l;
            }
            n = stack.remove(stack.size() - 1);
            for (int i = 0; i < n.cnt; i++) out.add(n.key);
            n = n.r;
        }
        return out;
    }
}
