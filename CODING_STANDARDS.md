# Coding standards

The rules review holds a diff to. `AGENTS.md` covers how to work; this file
covers what the code itself must look like.

## Comments

Comments record what the code cannot: the reason a thing is done the way it is.
They do not restate the code, and they are not essays.

**Write a comment when there is a why.** A surprising API, a constraint from a
dependency, a trade-off that was actually made, a bug that a naive version
reintroduces. One or two sentences.

**Do not write one when there is not.** If the signature and the body already
say it, say nothing.

### Rules

- **KDoc: one line by default.** Two or three only where the reason genuinely
  takes them. Reserve a multi-paragraph block for a decision the reader would
  otherwise undo — there should be very few in a file.
- **No worked examples in KDoc** unless the call is hard to get right from the
  signature. The README and `docs/` carry the tutorial; a code comment is not
  the place to teach the DSL twice.
- **No restating the code.** `/** The status. */ val status: Int` earns
  nothing.
- **No history.** "The alternative was…", "this used to…", "before this it
  was…" belongs in the commit message, not the source. Exception: naming a bug
  the comment exists to stop coming back.
- **No rhetorical framing.** Skip "Note the split of responsibilities", "which
  is the whole point", "and that is the difference that buys". State the fact.
- **Inline `//` notes are for the line below them**, not for paragraphs. If it
  runs past three lines, it is either KDoc or it is too long.

### Shape

```kotlin
// Yes — names a real constraint, once.
/** Pekko's `StatusCodes.get` throws for unregistered codes; `custom` does not. */
private fun statusOf(code: Int): StatusCode = ...

// No — restatement, then an essay about it.
/**
 * Pekko's own status where it knows one, and a custom status where it does not.
 *
 * `StatusCodes.get` is not a lookup: it is `int2StatusCode`, which throws for
 * anything not in the registry. So an endpoint that declared a perfectly legal
 * 419 used to document that status and then answer 500, because the throw
 * landed in the interpreter's own `exceptionally`. ...
 */
```

Same rules in test sources. A test name should carry the claim; the KDoc above
it should not repeat the name in longer words.

## Imports

**No wildcard imports, anywhere, and no unused ones.** One line per name, so an
import block is an inventory of what a file uses and where each piece lives —
and so a reader of the documentation's examples can see exactly what to import.
Two detekt rules fail the build on either mistake, `WildcardImport` and
`UnusedImport`, and `.editorconfig` tells ktlint and the IDE the same thing so
an optimize-imports cannot put a star back.

The same holds in the docs and the examples: every complete example carries its
imports written out, and the `dependencies { }` block naming the modules it
needs, with real coordinates. `example/` generates neither by hand — a file
that grows an import grows a line in its block.

## Values, errors and effects

Descriptions are values. There is no registry and nothing that has to run at
startup: a `Module`, a `Stream` and a `Behaviour` are values that can be split
across files, combined or filtered. Prefer a value to a function when adding a
feature.

Errors a caller was promised are values in the return type: a `Fail<E>`, an
`Either`, a stream's `E`. Throwing is for what nobody declared — a decode
failure, a broken codec, a bug.

Do not wrap a handler in `runCatching` and map the result into a failure. That
produces a second error model beside the declared one. `runCatching` is not used
anywhere, test sources included, and detekt fails the build on one: it catches
interrupts and bugs along with what was meant. Catch the exception you expect,
by name.

In a surefold handler, `fail` is the only way to leave with a declared failure.
A fail unwinds through the body as a `Throwable`, so a `catch (t: Throwable)`
between it and the boundary eats it: such a catch belongs in the one function that
completes the stage from the virtual thread, and detekt permits it nowhere
else.

A `Fail` scope never crosses a thread. Work forked from a handler answers
with an `Either`, and the handler binds it.

Never add an `else` to a `when` over a sealed type. The missing branch is the
compiler naming a case that needs handling in every interpreter.

Public API returns read-only types. A mutable accumulator is allowed inside a
builder that freezes it before returning. `FunctionalStyleTest` lists every
file permitted one, each with its reason; a new file needs an entry and a
reason, not a path added to get green.

The handler chain carries `CompletionStage<Any?>`. `suspend` cannot appear
anywhere in `surefold` — `NoOtherDependenciesTest` would say so.
