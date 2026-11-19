# Capstone: mini Redis-style server (package `ae.resp`)

## Design
Layers:
1. `Resp` - RESP2 codec. `sealed interface Value` = `Simple(String)`, `Err(String)`, `Int(long)`, `Bulk(String|null)`, `Arr(List<Value>|null)`.
   `Resp.parse(InputStream)` returns `null` at clean EOF before a value, throws `RespException` (an `IOException`) on any protocol error (bad prefix, bad length, missing CRLF, truncated input, nesting > 32, bulk > 1 MiB, array > 1024 items). Bulk lengths are in UTF-8 bytes. `Resp.encode(Value)` -> bytes. `Resp.command(String...)` builds an array of bulks.
2. `Engine(LongSupplier clockMillis, Path aofOrNull)` - key space of strings and lists, per-key expiry (lazy deletion), per-connection `Session` (MULTI queue). `Value execute(Session, List<String> args)`; case-insensitive command names. Thread safe via one lock.
   Commands: PING [msg], ECHO, SET k v [EX s|PX ms|EXAT s|PXAT ms] [NX|XX], GET, DEL k..., EXISTS k..., APPEND, STRLEN, INCR/DECR/INCRBY/DECRBY (signed 64-bit, overflow -> error), MGET, MSET, TYPE, EXPIRE/PEXPIRE/PEXPIREAT, TTL/PTTL (-2 missing, -1 no expiry), PERSIST, LPUSH/RPUSH (multi value), LPOP/RPOP, LLEN, LRANGE (negative indexes), KEYS pattern (`*` `?` only), DBSIZE, FLUSHALL, MULTI/EXEC/DISCARD.
   Errors: `ERR ...` generic, `WRONGTYPE Operation against a key holding the wrong kind of value`, unknown command, wrong arg count. Errors inside EXEC do not abort the queue (Redis semantics); a command that fails *arity/syntax at queue time* marks the transaction dirty and EXEC returns `EXECABORT`. Nested MULTI is an error; EXEC/DISCARD without MULTI is an error.
   Setting an empty list key (popping last) deletes the key. SET clears any expiry; INCR/APPEND keep it.
3. AOF - every successful write is appended as a RESP command array; relative TTLs are rewritten to absolute (`PXAT`/`PEXPIREAT`) so replay is time-independent. A transaction's writes are appended in one write after EXEC. On construction the file is replayed (a truncated final record is ignored and truncated away; garbage in the middle -> `IOException`). `rewrite()` writes a compact image (SET/RPUSH + PEXPIREAT) to a temp file then atomically moves it over the AOF.
4. `Server` - thread-per-connection TCP server on a given port (0 = ephemeral) reading RESP arrays (and inline `PING\r\n` is NOT supported: protocol error closes the connection after an `ERR Protocol error` reply).

## Success criteria
`mvn -q -B clean test`: codec round trips + malformed inputs, command semantics with a fake clock, transactions, AOF replay/rewrite/truncation, differential test against a naive `HashMap` model with random commands, socket test with pipelining and two clients.
Assumptions: single database, string values are UTF-8 text, KEYS returns sorted keys.
