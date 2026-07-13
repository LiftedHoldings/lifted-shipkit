# Good first issues

New to ShipKit? These are scoped, self-contained starter tasks — each touches a
small, well-understood surface and has a clear "done." Pick one, open an issue
that references it (or comment on the tracking issue), and read
[CONTRIBUTING.md](../CONTRIBUTING.md) for the branch/PR flow.

Maintainers: this list seeds the **`good first issue`** and **`help wanted`**
labels. When you open the corresponding GitHub issue, link back here and apply
the label so drive-by contributors can find it.

> Every change follows the same bar: a topic branch off `main`, a linked issue,
> Conventional-Commit messages signed off (`-s`), and green CI. Small and
> focused merges fastest.

## Docs & examples

1. **Add an Angular widget example.** Mirror
   [`ShipWidget.tsx`](../src/main/resources/public/examples/ShipWidget.tsx) as an
   Angular component that mounts the widget in `ngAfterViewInit` and destroys it
   in `ngOnDestroy`. Add it to the
   [framework snippets](integration.md#framework-snippets).
   _Files: `src/main/resources/public/examples/`, `docs/integration.md`._

2. **Add a SolidJS example.** Same shape as the Svelte example, using
   `onMount`/`onCleanup`.
   _Files: `src/main/resources/public/examples/`._

3. **Docs typo & link sweep.** Read every file in `docs/` and `README.md` as a
   first-time reader; fix typos, broken relative links, and stale anchors. One
   PR, one concern.
   _Files: `docs/`, `README.md`._

4. **Document the `carriers` filter end-to-end.** Show a worked
   `ShipKit.init({ carriers: ['USPS','UPS'] })` example in
   [integration.md](integration.md) with a screenshot of the filtered rate list.
   _Files: `docs/integration.md`._

5. **Add a "common errors" table to the API reference.** Collect the real error
   messages a caller hits (unverified address, empty rates with `messages`,
   expired session) and what each means.
   _Files: `docs/api.md`._

## Widget & frontend

6. **Add a `data-carriers` attribute example to `plain.html`.** The option
   exists in [integration.md](integration.md); show the script-tag form in the
   [plain HTML example](../src/main/resources/public/examples/plain.html).
   _Files: `src/main/resources/public/examples/plain.html`._

7. **Add a "Copy tracking code" affordance to the widget's result view.** A
   small copy button next to the tracking code that reuses the existing toast.
   _Files: `src/main/resources/public/js/shipkit.js` (+ a test)._

## Backend & tests

8. **Add a unit test for the `carriers` rate filter.** Assert that a
   `carriers: ['USPS']` config narrows the returned rate list and leaves an empty
   filter untouched.
   _Files: `src/test/…`._

9. **Add a test asserting `weight_oz` must be > 0.** `/api/shipment/create` should
   reject a zero/negative parcel weight with a `400` before calling EasyPost.
   _Files: `src/test/…`._

10. **Reject a malformed markup body.** Add a test that `POST /api/config/markup`
    with a negative `percentage_markup` or non-numeric `fixed_fee_cents` returns
    `400`.
    _Files: `src/test/…`._

11. **Add a `GET /api/health` smoke test.** Assert it returns `200` with no API
    key (it's an intentionally unauthenticated probe).
    _Files: `src/test/…`._

## Tooling

12. **Add a `.editorconfig` check to CI, or a `docs` link-checker.** A lightweight
    CI step that fails on a dead relative link in `docs/` or `README.md`.
    _Files: `.github/workflows/`._

---

Don't see your idea here? Open a Discussion or a feature request first so we can
align before you build. See [SUPPORT.md](../SUPPORT.md).

---

Maintained by Daniel Wilson Kemp · Lifted Holdings.
