package bench.ranked;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

/** Sorted multiset backed by a treap of distinct keys with occurrence counts and subtree sizes. */
public final class RankedMultiset<E> {
    private static final class Node<E> {
        E key;
        int cnt = 1;
        int size = 1;
        final int prio;
        Node<E> left, right;

        Node(E key, int prio) {
            this.key = key;
            this.prio = prio;
        }
    }

    private final Comparator<? super E> cmp;
    private final SplittableRandom rnd = new SplittableRandom(0x9E3779B97F4A7C15L);
    private Node<E> root;

    public RankedMultiset(Comparator<? super E> cmp) {
        if (cmp == null) throw new IllegalArgumentException("comparator is null");
        this.cmp = cmp;
    }

    private static void check(Object e) {
        if (e == null) throw new IllegalArgumentException("element is null");
    }

    private static <E> int sz(Node<E> n) {
        return n == null ? 0 : n.size;
    }

    private static <E> void upd(Node<E> n) {
        n.size = n.cnt + sz(n.left) + sz(n.right);
    }

    private Node<E> rotRight(Node<E> n) {
        Node<E> l = n.left;
        n.left = l.right;
        l.right = n;
        upd(n);
        upd(l);
        return l;
    }

    private Node<E> rotLeft(Node<E> n) {
        Node<E> r = n.right;
        n.right = r.left;
        r.left = n;
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
            n.left = insert(n.left, e);
            if (n.left.prio > n.prio) n = rotRight(n);
        } else {
            n.right = insert(n.right, e);
            if (n.right.prio > n.prio) n = rotLeft(n);
        }
        upd(n);
        return n;
    }

    public boolean remove(E e) {
        check(e);
        if (count(e) == 0) return false;
        root = delete(root, e);
        return true;
    }

    private Node<E> delete(Node<E> n, E e) {
        int c = cmp.compare(e, n.key);
        if (c < 0) {
            n.left = delete(n.left, e);
        } else if (c > 0) {
            n.right = delete(n.right, e);
        } else {
            if (n.cnt > 1) {
                n.cnt--;
            } else {
                return removeNode(n);
            }
        }
        upd(n);
        return n;
    }

    private Node<E> removeNode(Node<E> n) {
        if (n.left == null) return n.right;
        if (n.right == null) return n.left;
        if (n.left.prio > n.right.prio) {
            Node<E> r = rotRight(n);
            r.right = removeNode(n);
            upd(r);
            return r;
        } else {
            Node<E> r = rotLeft(n);
            r.left = removeNode(n);
            upd(r);
            return r;
        }
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
            n = c < 0 ? n.left : n.right;
        }
        return 0;
    }

    public int rank(E e) {
        check(e);
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
        if (index < 0 || index >= size()) throw new IndexOutOfBoundsException("index " + index);
        Node<E> n = root;
        while (true) {
            int ls = sz(n.left);
            if (index < ls) {
                n = n.left;
            } else if (index < ls + n.cnt) {
                return n.key;
            } else {
                index -= ls + n.cnt;
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
        // iterative in-order
        java.util.ArrayDeque<Node<E>> st = new java.util.ArrayDeque<>();
        Node<E> n = root;
        while (n != null || !st.isEmpty()) {
            while (n != null) {
                st.push(n);
                n = n.left;
            }
            n = st.pop();
            for (int i = 0; i < n.cnt; i++) out.add(n.key);
            n = n.right;
        }
        return out;
    }
}
