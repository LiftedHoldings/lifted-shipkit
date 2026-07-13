# Contributing to ShipKit

Thanks for helping build **ShipKit** — the open shipping toolkit, secured by
Lifted Payments 3-D Secure. This guide keeps contributions fast to review and
easy to merge. Read it once; it takes five minutes.

## Ways to contribute

- Fix a bug or improve carrier / rate handling.
- Improve the drop-in JS widget or its integration docs.
- Sharpen documentation, examples, or the demo.
- Triage issues and help other developers in Discussions.

New here? Look for issues labeled **`good first issue`** and **`help wanted`** —
they are scoped for a first contribution and we are glad to guide you through
them.

## Ground rules

- Be excellent to each other. All activity is governed by our
  [Code of Conduct](CODE_OF_CONDUCT.md).
- Keep changes focused. One concern per pull request.
- Do not commit secrets. Every credential loads from the environment — see
  [`.env.example`](.env.example). If you think you exposed a secret, follow
  [SECURITY.md](SECURITY.md).

## Branch model

Trunk is **`main`** and is protected. Never push directly to `main`.

Create a topic branch off the latest `main`, named by type:

| Prefix   | Use for                                   |
| -------- | ----------------------------------------- |
| `feat/`  | A new feature or capability               |
| `fix/`   | A bug fix                                 |
| `docs/`  | Documentation only                        |
| `chore/` | Build, CI, tooling, dependencies, cleanup |

Example: `feat/smartrates-fallback`, `fix/address-verify-null`.

## Commit messages — Conventional Commits

Write commits in the [Conventional Commits](https://www.conventionalcommits.org)
format:

```
<type>(optional scope): <short summary>

<optional body explaining what and why>

<optional footer(s)>
```

Common types: `feat`, `fix`, `docs`, `chore`, `refactor`, `test`, `ci`, `perf`.

Examples:

```
feat(shipping): add SmartRates delivery-window fallback
fix(payments): surface 3-D Secure challenge errors to the widget
docs(integration): add Vue quickstart snippet
```

### Sign off your commits (DCO)

We ask contributors to sign off, certifying the
[Developer Certificate of Origin](https://developercertificate.org). Add the
sign-off automatically with:

```
git commit -s -m "fix(payments): surface 3DS challenge errors"
```

This appends a `Signed-off-by:` line with your name and email.

## Pull request checklist

Open PRs against `main`. Keep them small and reviewable.

- [ ] The PR is scoped to **one concern**.
- [ ] It **links an issue** (`Closes #123`). No issue yet? Open one first so we
      can align before you build.
- [ ] Commits follow **Conventional Commits** and are **signed off** (`-s`).
- [ ] `./gradlew build` passes locally (build + tests).
- [ ] **CI is green** on the PR.
- [ ] Docs updated when behavior or config changes (including
      [`.env.example`](.env.example)).
- [ ] No secrets, credentials, IPs, or private infrastructure in the diff.

A maintainer reviews every PR. We **squash-merge** so `main` stays a clean,
linear history — write your PR title as the intended squash commit (Conventional
Commits format).

## Local development

```bash
cp .env.example .env       # fill in your own keys locally; never commit .env
./gradlew build            # compile + run tests
./gradlew run              # start the server on http://localhost:8080
```

ShipKit targets **Kotlin 2.0.21**, **JVM 17**, and **Javalin 5**. See
[docs/quickstart.md](docs/quickstart.md) for the full setup.

## Questions and support

- General questions and ideas → **GitHub Discussions**.
- Developer help → **support@liftedholdings.com** (see [SUPPORT.md](SUPPORT.md)).
- Security vulnerabilities → **private disclosure** via
  [SECURITY.md](SECURITY.md). Never open a public issue for a vulnerability.

Thank you for contributing.
