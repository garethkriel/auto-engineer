package bench.ranked;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/** Sorted multiset backed by an AVL tree with per-node counts and subtree sizes. */
public final class RankedMultiset<E> {
    private static final class Node<E> {
        E e;
        int cnt = 1;
        int size = 1;
        int h = 1;
        Node<E> l;
        Node<E> r;
        Node(E e) { this.e = e; }
    }

    private final Comparator<? super E> cmp;
    private Node<E> root;
    private boolean removed;

    public RankedMultiset(Comparator<? super E> cmp) {
        if (cmp == null) throw new IllegalArgumentException("comparator is null");
        this.cmp = cmp;
    }

    private static void check(Object o) {
        if (o == null) throw new IllegalArgumentException("null element");
    }

    private static <E> int h(Node<E> n) { return n == null ? 0 : n.h; }
    private static <E> int sz(Node<E> n) { return n == null ? 0 : n.size; }

    private static <E> void upd(Node<E> n) {
        n.h = 1 + Math.max(h(n.l), h(n.r));
        n.size = n.cnt + sz(n.l) + sz(n.r);
    }

    private static <E> Node<E> rotR(Node<E> n) {
        Node<E> l = n.l;
        n.l = l.r;
        l.r = n;
        upd(n);
        upd(l);
        return l;
    }

    private static <E> Node<E> rotL(Node<E> n) {
        Node<E> r = n.r;
        n.r = r.l;
        r.l = n;
        upd(n);
        upd(r);
        return r;
    }

    private static <E> Node<E> bal(Node<E> n) {
        upd(n);
        int b = h(n.l) - h(n.r);
        if (b > 1) {
            if (h(n.l.l) < h(n.l.r)) n.l = rotL(n.l);
            return rotR(n);
        }
        if (b < -1) {
            if (h(n.r.r) < h(n.r.l)) n.r = rotR(n.r);
            return rotL(n);
        }
        return n;
    }

    public void add(E e) {
        check(e);
        root = ins(root, e);
    }

    private Node<E> ins(Node<E> n, E e) {
        if (n == null) return new Node<>(e);
        int c = cmp.compare(e, n.e);
        if (c < 0) n.l = ins(n.l, e);
        else if (c > 0) n.r = ins(n.r, e);
        else n.cnt++;
        return bal(n);
    }

    public boolean remove(E e) {
        check(e);
        removed = false;
        root = del(root, e);
        return removed;
    }

    private Node<E> del(Node<E> n, E e) {
        if (n == null) return null;
        int c = cmp.compare(e, n.e);
        if (c < 0) n.l = del(n.l, e);
        else if (c > 0) n.r = del(n.r, e);
        else {
            removed = true;
            if (n.cnt > 1) {
                n.cnt--;
            } else {
                if (n.l == null) return n.r;
                if (n.r == null) return n.l;
                Node<E> m = n.r;
                while (m.l != null) m = m.l;
                n.e = m.e;
                n.cnt = m.cnt;
                n.r = delMin(n.r);
            }
        }
        return bal(n);
    }

    private Node<E> delMin(Node<E> n) {
        if (n.l == null) return n.r;
        n.l = delMin(n.l);
        return bal(n);
    }

    public int size() { return sz(root); }

    public int count(E e) {
        check(e);
        Node<E> n = root;
        while (n != null) {
            int c = cmp.compare(e, n.e);
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
            int c = cmp.compare(e, n.e);
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
        while (true) {
            int ls = sz(n.l);
            if (index < ls) n = n.l;
            else if (index < ls + n.cnt) return n.e;
            else {
                index -= ls + n.cnt;
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
        Deque<Node<E>> st = new ArrayDeque<>();
        Node<E> n = root;
        while (n != null || !st.isEmpty()) {
            while (n != null) { st.push(n); n = n.l; }
            n = st.pop();
            for (int i = 0; i < n.cnt; i++) out.add(n.e);
            n = n.r;
        }
        return out;
    }
}
