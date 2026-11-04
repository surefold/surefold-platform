# NNNN — short title

## Problem

What is wrong or missing today, in one or two paragraphs. Name who hits it and
what they do instead right now.

## Not doing

The nearby things this change deliberately leaves alone. Be blunt here; it is
the cheapest way to stop scope growing.

## Shape

What the API or behaviour should look like when this is done. Sketch the call
you want to be able to write — an agent will match a sketch rather than invent
one. The sketch compiles against today's types: an annotation or serializer it
needs is part of the shape.

```kotlin
// the call you want to be able to write
```

## Why this shape

The trade-off, in a paragraph. Where the design could have gone another way,
name the alternative in a sentence and say which one is recommended.

## Stack

One entry per pull request, in build order. Each is reviewable alone and stays
under ~200 changed lines.

- [ ] **`branch-name`** — what lands, one line.
      Done when: the checkable claim.
- [ ] **`branch-name`** — what lands, one line.
      Done when: the checkable claim.

## Acceptance

How anyone confirms the whole thing works. Commands, not prose:

```bash
./gradlew build
```

## Open questions

What the drafting agent could not decide from the codebase alone, as questions
with a recommended answer each. An empty list on a first draft usually means
the agent guessed instead of asking.

Before the spec is committed for building, each question is answered in place
or marked deferred. A question left open is the implementer's guess.
