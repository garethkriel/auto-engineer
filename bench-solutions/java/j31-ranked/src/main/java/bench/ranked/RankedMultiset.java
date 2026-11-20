package bench.ranked;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

/** Sorted multiset with O(log n) order statistics, backed by a treap of (key, count) nodes. */
public final class RankedMultiset<E> {
    private static final class Node<E> {
        final E key;
        int cnt = 1;
        int total = 1;
        final long prio;
        Node<E> left;
        Node<E> right;

        Node(E key, long prio) {
            this.key = key;
            this.prio = prio;
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

    private static void check(Object e) {
        if (e == null) {
            throw new IllegalArgumentException("element is null");
        }
    }

    private static <E> int tot(Node<E> n) {
        return n == null ? 0 : n.total;
    }

    private static <E> void upd(Node<E> n) {
        n.total = n.cnt + tot(n.left) + tot(n.right);
    }

    private Node<E> rotR(Node<E> n) {
        Node<E> l = n.left;
        n.left = l.right;
        l.right = n;
        upd(n);
        upd(l);
        return l;
    }

    private Node<E> rotL(Node<E> n) {
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
        if (n == null) {
            return new Node<>(e, rnd.nextLong());
        }
        int c = cmp.compare(e, n.key);
        if (c == 0) {
            n.cnt++;
        } else if (c < 0) {
            n.left = insert(n.left, e);
            if (n.left.prio > n.prio) {
                n = rotR(n);
                return n;
            }
        } else {
            n.right = insert(n.right, e);
            if (n.right.prio > n.prio) {
                n = rotL(n);
                return n;
            }
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
                return merge(n.left, n.right);
            }
        }
        upd(n);
        return n;
    }

    private Node<E> merge(Node<E> a, Node<E> b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        if (a.prio > b.prio) {
            a.right = merge(a.right, b);
            upd(a);
            return a;
        }
        b.left = merge(a, b.left);
        upd(b);
        return b;
    }

    public int size() {
        return tot(root);
    }

    public int count(E e) {
        check(e);
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
        check(e);
        int r = 0;
        Node<E> n = root;
        while (n != null) {
            int c = cmp.compare(e, n.key);
            if (c <= 0) {
                n = n.left;
            } else {
                r += tot(n.left) + n.cnt;
                n = n.right;
            }
        }
        return r;
    }

    public E select(int index) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException("index " + index + ", size " + size());
        }
        Node<E> n = root;
        int i = index;
        while (true) {
            int l = tot(n.left);
            if (i < l) {
                n = n.left;
            } else if (i < l + n.cnt) {
                return n.key;
            } else {
                i -= l + n.cnt;
                n = n.right;
            }
        }
    }

    public int countBetween(E lo, E hi) {
        check(lo);
        check(hi);
        if (cmp.compare(lo, hi) >= 0) {
            return 0;
        }
        return rank(hi) - rank(lo);
    }

    public List<E> toList() {
        List<E> out = new ArrayList<>(size());
        Node<E> n = root;
        java.util.ArrayDeque<Node<E>> stack = new java.util.ArrayDeque<>();
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
