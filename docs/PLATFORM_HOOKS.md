# Platform hooks in core/

Every change to `core/` (stock L2J Mobius) must be a **platform hook**: a few lines marked with
`// [HOOK:<id>]` that call into the platform and do nothing when no module is registered.
CI (`tools/check-hooks.sh`) fails on unmarked changes and on hook ids missing from this table.

Phase 0: **no hooks yet** — `core/` is byte-identical to the vendor import.

| Id | Core file(s) | What it does | Since |
|---|---|---|---|
| — | — | — | — |
