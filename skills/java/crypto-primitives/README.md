# crypto-primitives

- **Description:** Symmetric crypto from scratch with no `MessageDigest`/`Cipher` in main code: streaming SHA-256, HMAC-SHA256, HKDF, PBKDF2, AES-128/192/256 (computed S-box), CBC+PKCS#7, CTR (128-bit counter), GCM (96-bit IV) and a constant-time compare.
- **Language:** java
- **Tags:** sha256, hmac, hkdf, pbkdf2, aes, gcm, cbc, ctr, constant-time, differential-testing
- **Source round:** practice/java/20261125T1956Z
- **Tests:** RFC 4231/5869, FIPS 180-4/197, NIST GCM and SP 800-38A literals, randomized JDK differentials, every-bit-flip GCM tamper test, CBC padding table with a valid control.
- **Notes:** Educational; not hardened against cache/timing side channels beyond the constant-time tag compare. Decryption errors use one message per mode (`bad padding`, `bad tag`).
