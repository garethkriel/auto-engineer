#!/usr/bin/env python3
"""Mutation pass over OrderBook.java: each row is (name, old, new); every mutant must make `mvn test` fail."""
import subprocess, sys, shutil, os
F = "src/main/java/ae/book/OrderBook.java"
M = [
 ("price-priority", "return side == Side.BUY ? bids : asks;", "return side == Side.BUY ? asks : bids;"),
 ("cross-buy", "takerSide == Side.BUY ? makerPrice <= limit : makerPrice >= limit", "takerSide == Side.BUY ? makerPrice < limit : makerPrice >= limit"),
 ("cross-sell", "takerSide == Side.BUY ? makerPrice <= limit : makerPrice >= limit", "takerSide == Side.BUY ? makerPrice <= limit : makerPrice > limit"),
 ("post-cross", "crosses(side, limit, opp.firstKey())", "makerOnlyTouch(side, limit, opp.firstKey())"),
 ("fok-own-level", "if (r.owner.equals(owner)) return false;", "if (false) return false;"),
 ("fok-hidden", "levelTotal += r.total;", "levelTotal += r.visible;"),
 ("fok-limit-break", "if (!market && !crosses(side, limit, e.getKey())) break;", "if (false) break;"),
 ("stp-off", "if (maker.owner.equals(owner)) {", "if (false) {"),
 ("reload-back", "level.remove(maker.id);\n                level.put(maker.id, maker); // reloaded slice goes to the back", "// no move"),
 ("slice", "return display > 0 ? Math.min(display, total) : total;", "return display > 0 ? display : total;"),
 ("amend-down-visible", "r.visible = Math.min(r.visible, newQty);", "r.visible = r.visible;"),
 ("amend-up-priority", "level.remove(r.id);\n            r.total = newQty;", "r.total = newQty;"),
 ("amend-up-visible", "r.visible = sliceFor(r.display, newQty);", "r.visible = newQty;"),
 ("replace-display", "newQty, Tif.GTC, false, r.display, false)", "newQty, Tif.GTC, false, 0, false)"),
 ("trade-price", "trades.add(new Trade(id, maker.id, price, q));", "trades.add(new Trade(id, maker.id, limit, q));"),
 ("level-cleanup", "if (level.isEmpty()) opp.remove(price);", "if (false) opp.remove(price);"),
 ("remove-empty-level", "if (level.isEmpty()) m.remove(r.price);", "if (false) m.remove(r.price);"),
 ("live-id", "if (byId.containsKey(id)) throw", "if (false) throw"),
 ("blank-owner", "if (owner.isBlank()) throw", "if (owner.isEmpty()) throw"),
 ("display-neg", "if (display < 0) throw", "if (display < -5) throw"),
 ("market-reason", '"MARKET"', '"IOC"'),
 ("invariant-cross", "bids.firstKey() >= asks.firstKey()", "bids.firstKey() > asks.firstKey()"),
 ("invariant-visible", "r.visible <= 0 || r.visible > r.total", "r.visible <= 0"),
 ("invariant-index", "if (count != byId.size())", "if (false)"),
 ("depth-visible", "for (Resting r : e.getValue().values()) qty += r.visible;", "for (Resting r : e.getValue().values()) qty += r.total;"),
 ("selftrade-status", '"SELF_TRADE"', '"IOC"'),
]
src = open(F).read()
shutil.copy(F, "/tmp/OrderBook.orig")
survivors = []
try:
    for name, old, new in M:
        if src.count(old) < 1:
            print("PATTERN NOT FOUND", name); sys.exit(2)
        # first occurrence only; "post-cross" is a compile-time mutant check via helper
        mut = src.replace(old, new, 1)
        if name == "post-cross":
            mut = mut.replace("makerOnlyTouch(side, limit, opp.firstKey())", "(opp.firstKey() == limit)")
        open(F, "w").write(mut)
        r = subprocess.run(["mvn", "-q", "-B", "clean", "test"], capture_output=True, text=True, timeout=300)
        out = r.stdout + r.stderr
        if "COMPILATION ERROR" in out:
            print("COMPILE ERROR (bad mutant)", name); survivors.append(name + "(compile)"); continue
        print(("KILLED   " if r.returncode != 0 else "SURVIVED ") + name, flush=True)
        if r.returncode == 0: survivors.append(name)
finally:
    open(F, "w").write(src)
print("survivors:", survivors)
sys.exit(1 if survivors else 0)
