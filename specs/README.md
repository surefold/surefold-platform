# Specs

A spec says what should be true when a piece of work is done. It exists before
any code does. An agent drafts it, a human edits it, and the edited version is
the only brief the implementation gets — and the thing a reviewer checks the
diff against.

## Layout

One file per piece of work, `specs/NNNN-kebab-name.md`, numbered in order from
0001: specs written before the split stay in surefold-core. Start from
[TEMPLATE.md](TEMPLATE.md). Specs are drafted in parallel, so take the next
number free on `main` and in open branches.

A draft is one page, near enough. Every heading in the template gets an answer,
even where the answer is "nothing". Longer than that means the spec is
proposing too much at once and should be two specs.

The **Stack** section is the important one: each entry becomes one branch and
one pull request, in order, each stacked on the one below it. If an entry
cannot be reviewed on its own, or clearly runs past ~200 changed lines, split
it before anyone writes code.

## Lifecycle

1. **Draft.** Ask for a spec and an agent writes one from what it can read in
   the codebase and what you said. It ends its turn there. No branch, no code.
2. **Edit.** You cut, redirect, and answer the open questions. This is the step
   the whole process is for: arguing with a page is cheap, arguing with six
   thousand lines is not. Delete whole stack entries here rather than later.
3. **Commit.** The spec lands on `main` on its own, with no implementation in
   the commit. That commit is the go-ahead — an agent does not build from an
   uncommitted draft.
4. **Build.** One branch per stack entry, bottom-up. `AGENTS.md` has the git
   commands. An agent stops after each entry.
5. **Close.** As each PR merges, tick its stack entry and link the PR.

A shipped spec stays in the tree. It is the record of why the code looks the
way it does, which is why the source comments do not have to be.

## What a spec is not

- Not documentation. `docs/` and the README teach the DSL; a spec argues for a
  change.
- Not a design essay. Where a decision needs pages of reasoning, the reasoning
  goes under **Why this shape** in a paragraph, not a chapter.
- Not a backlog. One spec, one change.
