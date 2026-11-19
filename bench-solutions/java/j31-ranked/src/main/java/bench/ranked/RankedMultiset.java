package bench.ranked;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Sorted multiset backed by an AVL tree of distinct values with multiplicities and subtree sizes. */
public final class RankedMultiset<E> {
    private static final class Node<E> {
        final E val;
        int cnt = 1;
        int size = 1;
        int height = 1;
        Node<E> left;
        Node<E> right;

        Node(E val) {
            this.val = val;
        }
    }

    private final Comparator<? super E> cmp;
    private Node<E> root;

    public RankedMultiset(Comparator<? super E> cmp) {
        if (cmp == null) throw new IllegalArgumentException("comparator is null");
        this.cmp = cmp;
    }

    private static void need(Object e) {
        if (e == null) throw new IllegalArgumentException("element is null");
    }

    private static <E> int h(Node<E> n) {
        return n == null ? 0 : n.height;
    }

    private static <E> int sz(Node<E> n) {
        return n == null ? 0 : n.size;
    }

    private static <E> void upd(Node<E> n) {
        n.height = 1 + Math.max(h(n.left), h(n.right));
        n.size = n.cnt + sz(n.left) + sz(n.right);
    }

    private static <E> Node<E> rotR(Node<E> y) {
        Node<E> x = y.left;
        y.left = x.right;
        x.right = y;
        upd(y);
        upd(x);
        return x;
    }

    private static <E> Node<E> rotL(Node<E> x) {
        Node<E> y = x.right;
        x.right = y.left;
        y.left = x;
        upd(x);
        upd(y);
        return y;
    }

    private static <E> Node<E> balance(Node<E> n) {
        upd(n);
        int bf = h(n.left) - h(n.right);
        if (bf > 1) {
            if (h(n.left.left) < h(n.left.right)) n.left = rotL(n.left);
            return rotR(n);
        }
        if (bf < -1) {
            if (h(n.right.right) < h(n.right.left)) n.right = rotR(n.right);
            return rotL(n);
        }
        return n;
    }

    public void add(E e) {
        need(e);
        root = insert(root, e);
    }

    private Node<E> insert(Node<E> n, E e) {
        if (n == null) return new Node<>(e);
        int c = cmp.compare(e, n.val);
        if (c == 0) {
            n.cnt++;
            n.size++;
            return n;
        }
        if (c < 0) n.left = insert(n.left, e);
        else n.right = insert(n.right, e);
        return balance(n);
    }

    public boolean remove(E e) {
        need(e);
        if (count(e) == 0) return false;
        root = delete(root, e);
        return true;
    }

    private Node<E> delete(Node<E> n, E e) {
        int c = cmp.compare(e, n.val);
        if (c < 0) {
            n.left = delete(n.left, e);
        } else if (c > 0) {
            n.right = delete(n.right, e);
        } else {
            if (n.cnt > 1) {
                n.cnt--;
                n.size--;
                return n;
            }
            if (n.left == null) return n.right;
            if (n.right == null) return n.left;
            Node<E> m = n.right;
            while (m.left != null) m = m.left;
            Node<E> r = removeMin(n.right);
            m.right = r;
            m.left = n.left;
            n = m;
        }
        return balance(n);
    }

    private Node<E> removeMin(Node<E> n) {
        if (n.left == null) return n.right;
        n.left = removeMin(n.left);
        return balance(n);
    }

    public int size() {
        return sz(root);
    }

    public int count(E e) {
        need(e);
        Node<E> n = root;
        while (n != null) {
            int c = cmp.compare(e, n.val);
            if (c == 0) return n.cnt;
            n = c < 0 ? n.left : n.right;
        }
        return 0;
    }

    public int rank(E e) {
        need(e);
        int r = 0;
        Node<E> n = root;
        while (n != null) {
            int c = cmp.compare(e, n.val);
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
        if (index < 0 || index >= size()) throw new IndexOutOfBoundsException("index " + index + ", size " + size());
        Node<E> n = root;
        int i = index;
        while (true) {
            int ls = sz(n.left);
            if (i < ls) {
                n = n.left;
            } else if (i < ls + n.cnt) {
                return n.val;
            } else {
                i -= ls + n.cnt;
                n = n.right;
            }
        }
    }

    public int countBetween(E lo, E hi) {
        need(lo);
        need(hi);
        if (cmp.compare(lo, hi) >= 0) return 0;
        return rank(hi) - rank(lo);
    }

    public List<E> toList() {
        List<E> out = new ArrayList<>(size());
        fill(root, out);
        return out;
    }

    private void fill(Node<E> n, List<E> out) {
        if (n == null) return;
        fill(n.left, out);
        for (int i = 0; i < n.cnt; i++) out.add(n.val);
        fill(n.right, out);
    }
}
