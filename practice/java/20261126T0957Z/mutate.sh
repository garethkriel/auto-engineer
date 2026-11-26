#!/bin/bash
# Mutation check: each line is file|from|to (literal, must match once). Prints KILLED/SURVIVED.
cd "$(dirname "$0")"
M=src/main/java/ae/cc
while IFS='|' read -r f from to; do
  cp "$M/$f" /tmp/mut.bak
  python3 - "$M/$f" "$from" "$to" <<'PY' || { echo "NOMATCH $f $from"; continue; }
import sys
p,a,b=sys.argv[1:4]; s=open(p).read()
if s.count(a)!=1: sys.exit(1)
open(p,'w').write(s.replace(a,b))
PY
  if timeout 300 mvn -q -B clean test >/tmp/mut.out 2>&1; then echo "SURVIVED $f: $from -> $to"; else
    if grep -q "COMPILATION ERROR" /tmp/mut.out; then echo "COMPILEFAIL $f: $from"; else echo "KILLED $f: $from -> $to"; fi; fi
  cp /tmp/mut.bak "$M/$f"
done <<'LIST'
ChaCha20.java|Integer.rotateLeft(x[d] ^ x[a], 8)|Integer.rotateLeft(x[d] ^ x[a], 7)
ChaCha20.java|first + blocks > 0x1_0000_0000L|first + blocks > 0x1_0000_0001L
ChaCha20.java|st[12] = counter;|st[12] = counter + 1;
Poly1305.java|0ffffffc0ffffffc0ffffffc0fffffff|0ffffffc0ffffffc0ffffffc0ffffffff
Poly1305.java|.setBit(8 * n)|.setBit(128)
Poly1305.java|.mod(MOD128)|.mod(P)
Aead.java|m.writeBytes(new byte[(16 - aad.length % 16) % 16]);|
Aead.java|m.writeBytes(Bytes.toLe(java.math.BigInteger.valueOf(aad.length), 8));|
Aead.java|if (sealed.length < TAG_SIZE) {|if (sealed.length < TAG_SIZE - 1) {
Aead.java|ChaCha20.block(key, nonce, 0)|ChaCha20.block(key, nonce, 1)
X25519.java|kc[31] &= 127;|
X25519.java|kc[31] |= 64;|
X25519.java|uc[31] &= 127; // the top bit|// the top bit
X25519.java|if (acc == 0) {|if (acc == 1) {
X25519.java|kc[0] &= (byte) 248;|
Ed25519.java|if (y.compareTo(P) >= 0) {|if (y.compareTo(P) > 0) {
Ed25519.java|if (s.compareTo(L) >= 0) {|if (s.compareTo(L) > 0) {
Ed25519.java|if (a == null \|\| r == null) {|if (a == null) {
Ed25519.java|if (x.signum() == 0 && sign) {|if (false) {
Ed25519.java|x = x.multiply(SQRT_M1).mod(P);|
Ed25519.java|sha512(Arrays.copyOfRange(sig, 0, 32), pub, msg)|sha512(Arrays.copyOfRange(sig, 0, 32), msg)
LIST
