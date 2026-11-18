package ae.vfs;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VfsTest {
    Vfs fs;
    Vfs.Session root;

    @BeforeEach
    void setUp() {
        fs = new Vfs(1000);
        root = fs.root();
    }

    static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static String s(byte[] x) {
        return new String(x, StandardCharsets.UTF_8);
    }

    static Errno errOf(Runnable r) {
        VfsException e = assertThrows(VfsException.class, r::run);
        return e.errno();
    }

    void healthy() {
        assertEquals(List.of(), fs.fsck());
    }

    @Test
    void basicFileLifecycle() {
        root.mkdir("/a");
        root.write("/a/f.txt", b("hello"));
        assertEquals("hello", s(root.read("/a/f.txt")));
        root.append("/a/f.txt", b(" world"));
        assertEquals("hello world", s(root.read("/a/f.txt")));
        assertEquals(List.of("f.txt"), root.list("/a"));
        assertEquals(11, fs.usedBytes());
        root.write("/a/f.txt", b("x"));
        assertEquals(1, fs.usedBytes());
        root.unlink("/a/f.txt");
        assertEquals(0, fs.usedBytes());
        root.rmdir("/a");
        assertEquals(List.of(), root.list("/"));
        healthy();
    }

    @Test
    void errnoTable() {
        root.mkdir("/d");
        root.write("/d/f", b("1"));
        assertEquals(Errno.ENOENT, errOf(() -> root.read("/nope")));
        assertEquals(Errno.EEXIST, errOf(() -> root.mkdir("/d")));
        assertEquals(Errno.EEXIST, errOf(() -> root.mkdir("/")));
        assertEquals(Errno.ENOTDIR, errOf(() -> root.mkdir("/d/f/x")));
        assertEquals(Errno.ENOTDIR, errOf(() -> root.list("/d/f")));
        assertEquals(Errno.EISDIR, errOf(() -> root.read("/d")));
        assertEquals(Errno.EISDIR, errOf(() -> root.write("/d", b("x"))));
        assertEquals(Errno.EISDIR, errOf(() -> root.unlink("/d")));
        assertEquals(Errno.ENOTEMPTY, errOf(() -> root.rmdir("/d")));
        assertEquals(Errno.ENOTDIR, errOf(() -> root.rmdir("/d/f")));
        assertEquals(Errno.EINVAL, errOf(() -> root.rmdir("/")));
        assertEquals(Errno.EINVAL, errOf(() -> root.read("relative")));
        assertEquals(Errno.ENOENT, errOf(() -> root.mkdir("/x/y")));
        assertEquals(Errno.ENOENT, errOf(() -> root.unlink("/d/none")));
        assertEquals(Errno.EPERM, errOf(() -> root.link("/d", "/d2")));
        assertEquals(Errno.EEXIST, errOf(() -> root.link("/d/f", "/d")));
        assertEquals(Errno.EINVAL, errOf(() -> root.readlink("/d/f")));
        healthy();
    }

    @Test
    void mkdirsCreatesChainAndIsIdempotentButRejectsFileInChain() {
        root.mkdirs("/a/b/c");
        root.mkdirs("/a/b/c");
        assertEquals(List.of("b"), root.list("/a"));
        root.write("/a/b/f", b("z"));
        assertEquals(Errno.ENOTDIR, errOf(() -> root.mkdirs("/a/b/f/g")));
        healthy();
    }

    @Test
    void dotDotAndRepeatedSlashes() {
        root.mkdirs("/a/b");
        root.write("/a/b/f", b("v"));
        assertEquals("v", s(root.read("//a///b/./f")));
        assertEquals("v", s(root.read("/a/b/../b/f")));
        assertEquals("v", s(root.read("/../../a/b/f")));
        assertEquals(Errno.ENOENT, errOf(() -> root.read("/a/nope/../b/f")));
        assertEquals(Errno.ENOTDIR, errOf(() -> root.read("/a/b/f/../f")));
        assertEquals("/a", root.realpath("/a/b/.."));
        assertEquals("/", root.realpath("/a/.."));
        assertEquals("/a/b/f", root.realpath("/a/b/./f"));
    }

    @Test
    void hardLinksShareDataAndCountOnce() {
        root.write("/f", b("abc"));
        root.link("/f", "/g");
        assertEquals(2, root.stat("/f").nlink());
        assertEquals(root.stat("/f").inode(), root.stat("/g").inode());
        root.append("/g", b("d"));
        assertEquals("abcd", s(root.read("/f")));
        assertEquals(4, fs.usedBytes());
        root.unlink("/f");
        assertEquals(4, fs.usedBytes());
        assertEquals(1, root.stat("/g").nlink());
        root.unlink("/g");
        assertEquals(0, fs.usedBytes());
        healthy();
    }

    @Test
    void symlinkFollowingRelativeAndAbsolute() {
        root.mkdirs("/data/sub");
        root.write("/data/sub/f", b("F"));
        root.symlink("sub", "/data/rel");
        root.symlink("/data/sub", "/abs");
        assertEquals("F", s(root.read("/data/rel/f")));
        assertEquals("F", s(root.read("/abs/f")));
        assertEquals("/data/sub/f", root.realpath("/abs/f"));
        assertEquals("sub", root.readlink("/data/rel"));
        assertEquals(FileType.SYMLINK, root.lstat("/abs").type());
        assertEquals(FileType.DIR, root.stat("/abs").type());
        // ".." after a symlink goes to the real parent of the target, not the link's directory
        root.symlink("/data/sub", "/data/rel2");
        assertEquals("/data", root.realpath("/data/rel2/.."));
        healthy();
    }

    @Test
    void symlinkLoopsAndDangling() {
        root.symlink("/b", "/a");
        root.symlink("/a", "/b");
        assertEquals(Errno.ELOOP, errOf(() -> root.read("/a")));
        root.symlink("/missing", "/dangling");
        assertEquals(Errno.ENOENT, errOf(() -> root.read("/dangling")));
        assertEquals(FileType.SYMLINK, root.lstat("/dangling").type());
        // writing through a dangling link creates its target
        root.write("/dangling", b("made"));
        assertEquals("made", s(root.read("/missing")));
        // unlink removes the link, not the target
        root.unlink("/dangling");
        assertEquals("made", s(root.read("/missing")));
        // 40 hops is fine, 41 is a loop
        root.write("/t", b("T"));
        String prev = "/t";
        for (int i = 0; i < 40; i++) {
            root.symlink(prev, "/h" + i);
            prev = "/h" + i;
        }
        assertEquals("T", s(root.read("/h39")));
        root.symlink("/h39", "/h40");
        assertEquals(Errno.ELOOP, errOf(() -> root.read("/h40")));
        healthy();
    }

    @Test
    void renameMatrix() {
        root.mkdirs("/d1/sub");
        root.mkdir("/empty");
        root.mkdirs("/full");
        root.write("/full/x", b("x"));
        root.write("/f", b("F"));
        root.write("/g", b("G"));
        root.rename("/f", "/g");
        assertEquals("F", s(root.read("/g")));
        assertEquals(Errno.ENOENT, errOf(() -> root.read("/f")));
        assertEquals(2, fs.usedBytes()); // "F" (replaced g) + "x" in /full
        root.rename("/d1", "/empty");
        assertEquals(List.of("sub"), root.list("/empty"));
        assertEquals(Errno.ENOTEMPTY, errOf(() -> root.rename("/empty", "/full")));
        assertEquals(Errno.EISDIR, errOf(() -> root.rename("/g", "/full")));
        assertEquals(Errno.ENOTDIR, errOf(() -> root.rename("/full", "/g")));
        assertEquals(Errno.EINVAL, errOf(() -> root.rename("/empty", "/empty/sub/inner")));
        assertEquals(Errno.EINVAL, errOf(() -> root.rename("/empty", "/empty/x")));
        assertEquals(Errno.ENOENT, errOf(() -> root.rename("/none", "/y")));
        assertEquals(Errno.ENOENT, errOf(() -> root.rename("/g", "/nodir/y")));
        root.rename("/g", "/g");
        assertEquals("F", s(root.read("/g")));
        healthy();
    }

    @Test
    void renameSameInodeViaHardLinkIsNoOp() {
        root.write("/a", b("1"));
        root.link("/a", "/b");
        root.rename("/a", "/b");
        assertEquals(List.of("a", "b"), root.list("/"));
        assertEquals(2, root.stat("/a").nlink());
        healthy();
    }

    @Test
    void renamedDirectoryKeepsDotDotWorking() {
        root.mkdirs("/p/q");
        root.mkdir("/r");
        root.rename("/p/q", "/r/q");
        assertEquals("/r", root.realpath("/r/q/.."));
        root.write("/r/q/f", b("k"));
        assertEquals("k", s(root.read("/r/q/../q/f")));
        healthy();
    }

    @Test
    void permissionMatrix() {
        Vfs.Session alice = fs.login(1000, 100);
        Vfs.Session bob = fs.login(2000, 100); // same group as alice
        Vfs.Session eve = fs.login(3000, 300);
        root.mkdir("/home");
        root.chmod("/home", 0777);
        alice.mkdir("/home/alice");
        alice.write("/home/alice/secret", b("s"));
        alice.chmod("/home/alice/secret", 0640);
        alice.chmod("/home/alice", 0750);
        assertEquals("s", s(alice.read("/home/alice/secret")));
        assertEquals("s", s(bob.read("/home/alice/secret")));
        assertEquals(Errno.EACCES, errOf(() -> bob.write("/home/alice/secret", b("x"))));
        assertEquals(Errno.EACCES, errOf(() -> eve.read("/home/alice/secret")));
        assertEquals(Errno.EACCES, errOf(() -> eve.list("/home/alice")));
        assertEquals(Errno.EACCES, errOf(() -> bob.write("/home/alice/new", b("x")))); // no w on dir
        // without x on a directory, even a readable file inside is unreachable
        alice.chmod("/home/alice", 0640);
        assertEquals(Errno.EACCES, errOf(() -> alice.read("/home/alice/secret")));
        assertEquals(List.of("secret"), alice.list("/home/alice"));
        alice.chmod("/home/alice", 0750);
        assertEquals(Errno.EPERM, errOf(() -> bob.chmod("/home/alice/secret", 0777)));
        assertEquals(Errno.EPERM, errOf(() -> alice.chown("/home/alice/secret", 2000, 100)));
        root.chown("/home/alice/secret", 2000, 100);
        assertEquals(2000, root.stat("/home/alice/secret").uid());
        assertEquals("s", s(root.read("/home/alice/secret")));
        // root bypasses everything
        root.chmod("/home/alice", 0);
        assertEquals("s", s(root.read("/home/alice/secret")));
        assertEquals(Errno.EACCES, errOf(() -> eve.unlink("/home/alice/secret")));
        healthy();
    }

    @Test
    void createdEntriesOwnedByCreatorWithDefaultModes() {
        Vfs.Session u = fs.login(5, 6);
        root.chmod("/", 0777);
        u.mkdir("/d");
        u.write("/f", b("a"));
        assertEquals(5, u.stat("/d").uid());
        assertEquals(6, u.stat("/d").gid());
        assertEquals(0755, u.stat("/d").mode());
        assertEquals(0644, u.stat("/f").mode());
        assertEquals(Errno.EPERM, errOf(() -> u.chmod("/", 0700)));
    }

    @Test
    void quotaFailureLeavesStateUnchanged() {
        root.write("/a", new byte[600]);
        assertEquals(Errno.ENOSPC, errOf(() -> root.write("/b", new byte[401])));
        assertEquals(List.of("a"), root.list("/"));
        assertEquals(600, fs.usedBytes());
        assertEquals(Errno.ENOSPC, errOf(() -> root.append("/a", new byte[401])));
        assertEquals(600, root.read("/a").length);
        root.write("/b", new byte[400]);
        assertEquals(1000, fs.usedBytes());
        // overwrite with same-size or smaller is fine at the limit; growth is not
        root.write("/a", new byte[100]);
        assertEquals(500, fs.usedBytes());
        assertEquals(Errno.ENOSPC, errOf(() -> root.write("/a", new byte[601])));
        assertEquals(100, root.read("/a").length);
        healthy();
    }

    @Test
    void snapshotIsIndependentAndPreservesLinks() {
        root.mkdir("/d");
        root.write("/d/f", b("one"));
        root.link("/d/f", "/g");
        root.symlink("/d", "/s");
        Vfs.Snapshot snap = fs.snapshot();
        root.append("/g", b("-two"));
        root.unlink("/d/f");
        root.unlink("/g");
        root.mkdir("/new");
        assertEquals(0, fs.usedBytes());
        fs.restore(snap);
        assertEquals(List.of("d", "g", "s"), root.list("/"));
        assertEquals("one", s(root.read("/s/f")));
        assertEquals(2, root.stat("/g").nlink());
        assertEquals(root.stat("/g").inode(), root.stat("/d/f").inode());
        assertEquals(3, fs.usedBytes());
        healthy();
        // writing after restore must not corrupt the snapshot; restoring twice gives the same state
        root.append("/g", b("!"));
        assertEquals("one!", s(root.read("/d/f"))); // link identity survived
        fs.restore(snap);
        assertEquals("one", s(root.read("/d/f")));
        assertEquals(3, fs.usedBytes());
        // directory parents of the restored tree are the restored dirs, not the snapshot's
        root.mkdirs("/d/x");
        assertEquals("/d", root.realpath("/d/x/.."));
        fs.restore(snap);
        assertEquals(List.of(), root.list("/d").stream().filter(n -> n.equals("x")).toList());
        healthy();
    }

    @Test
    void sessionsShareStateAcrossLogins() {
        Vfs.Session u = fs.login(7, 7);
        root.chmod("/", 0777);
        u.write("/mine", b("u"));
        assertEquals("u", s(root.read("/mine")));
        assertEquals(7, root.stat("/mine").uid());
    }

    @Test
    void invalidArgumentsRejected() {
        assertThrows(NullPointerException.class, () -> root.read(null));
        assertThrows(IllegalArgumentException.class, () -> new Vfs(-1));
        assertEquals(Errno.EINVAL, errOf(() -> root.chmod("/", 01000)));
        assertEquals(Errno.EINVAL, errOf(() -> root.symlink("", "/e")));
    }
}
