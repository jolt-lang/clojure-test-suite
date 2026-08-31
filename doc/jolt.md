# Running the Test Suite with Jolt

[Jolt](https://github.com/jolt-lang/jolt) is a Clojure implementation on
Scheme. It reads Clojure source, analyzes it to a host-neutral IR, emits
Scheme, and runs it on Chez. The compiler is self-hosted.

## Setting Up the Environment

Prebuilt binaries are self-contained, so there is nothing else to install:

```bash
brew install jolt-lang/jolt/jolt
```

Or with the install script, which defaults to `/usr/local/bin`:

```bash
curl -sL https://raw.githubusercontent.com/jolt-lang/jolt/main/install | bash
```

Check it works:

```bash
$ jolt -e '(+ 1 2)'
3
```

Linux x86_64 needs glibc 2.35 or newer, and macOS needs 14 or newer. Anything
else wants a [build from source](https://github.com/jolt-lang/jolt/blob/main/CONTRIBUTING.md#build-from-source).

## Running the Tests

```bash
$ bb test-jolt
... lots of output removed ...
```

That runs every test namespace in one process, which takes a couple of
seconds. The task exits non-zero if anything fails.

The runner is `jolt/jolt_runner.clj` and the `:jolt` alias in `deps.edn`
points at it, so you can also run it directly:

```bash
$ jolt -M:jolt
```

## Reader conditionals

**A `:jolt` branch must come before `:bb`.**

Jolt's reader feature set is `{:jolt :bb :clj :default}` and the first
matching clause wins, so Jolt matches a `:bb` branch as readily as its own.
A `:jolt` branch listed after `:bb` never runs, and nothing warns you:

```clj
#?(:bb :BB :jolt :JOLT)   ;=> :BB
#?(:jolt :JOLT :bb :BB)   ;=> :JOLT
```

Inheriting `:bb` is usually right. A `:bb` branch tends to solve the same
non-JVM problems Jolt has, which is why Jolt reads that key on purpose. Only
add a `:jolt` branch where Jolt genuinely differs from Babashka, and when you
do, put it first. See [writing-tests.md](writing-tests.md#reader-conditional-order).

Watch for `#?@(:bb [] ...)` and `#?(:bb nil ...)`. Those skip the assertion
under Jolt rather than failing it, so a namespace can look green while
testing nothing.

## Differences worth knowing before you conditionalize

Jolt is not the JVM, and these are the ones that show up in this suite:

- **One exact-integer type.** `(byte 0)`, `(short 0)` and `(bigint 0)` all
  report `java.lang.Long`, and a small bigint literal like `10N` *is* a Long.
  A value becomes `clojure.lang.BigInt` only past 64-bit range. Arithmetic
  promotes rather than throwing on overflow.
- **One flonum type.** `(float 0.1)` and `(double 0.1)` are the same value,
  so `double?` answers true for a float.
- **Codepoint strings.** `(count "😀")` is 1, not 2.
- **A different regex engine.** Patterns compile through
  [irregex](https://github.com/ashinn/irregex), not `java.util.regex`, and a
  matcher is not `nth`-able.
- **No Java interop.** No reflection, no `gen-class`, no `proxy`. Interop
  syntax resolves against a shimmed subset of `java.*` written in Scheme.
  Arrays exist but are not the JVM's, so `(contains? (int-array [0]) 0)`
  raises.

The full list is in Jolt's
[README](https://github.com/jolt-lang/jolt#differences-from-clojure), and
every tracked divergence is in
[`test/conformance/known-divergences.edn`](https://github.com/jolt-lang/jolt/blob/main/test/conformance/known-divergences.edn).

## Jolt's own gate

Jolt vendors this suite as a submodule and runs it per-namespace against a
checked-in baseline (`make cts`). That gate fails on any change to a
namespace's counts, better *or* worse, so an improvement here is expected to
land alongside a baseline update there.
