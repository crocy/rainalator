# Frontend

Angular 19 + Leaflet UI for querying accumulated radar rainfall over a map-drawn polygon.
Standalone components, inline templates, `styleUrl` for SCSS. Generated using
[Angular CLI](https://github.com/angular/angular-cli) version 19.2.23.

## UI

The sidebar (`components/query-panel`) drives everything; the map area holds the overlay,
the legend and the timeline.

**Time interval tabs** — the panel opens on **Last**, an amount plus a unit
(minutes / hours / days), defaulting to 2 hours. **From – To** is the explicit
date+time pair. `now` resolves at click time on each *Load Radar* / *Analyze*; nothing
auto-refreshes. Switching Last → From – To prefills the date/time fields with the window
the relative tab just resolved, so the user lands where they already were.

**Bucket size** sits below the tab bodies because it applies to both. It defaults to
5 minutes, which means *bucketing off* — one frame per radar scan. Anything larger groups
scans into buckets, and the timeline steps buckets instead of scans. The panel converts the
unit to minutes for the API and mirrors the server's rules client-side (at least 5, a
multiple of 5, no longer than the window), disabling both buttons with an inline message so
the 400 is only a backstop.

**Colour scale** appears only while bucketing is on: Fixed mm / Auto-fit / Rate-equivalent
(`fixed` / `autofit` / `rate`). Per-scan frames are always painted with `rate`.

**Legend** (`components/legend`) renders in the map corner from the `X-Legend-*` headers of
the overlay response — never from a client-side copy of the band table, so it cannot
disagree with the painted image.

**Results** are one row per bucket: `Period | Depth (mm) | Mean (mm/h) | Peak (mm/h)`.
The oldest bucket is clipped to the window start; when it is, the response marks it
`partial` and the row is badged so it does not read as a quiet period.

## API

Dev server proxies `/api` → `:8080` (`proxy.conf.json`). Three endpoints, all UTC ISO-8601:

| Endpoint | Notes |
| --- | --- |
| `POST /api/rainfall/query` | Body `{ polygon, from, to, bucketMinutes? }`. Returns `buckets[]` plus window totals. `bucketMinutes` is omitted for per-scan frames so the server anchors frames on real scan times. |
| `GET /api/rainfall/frames` | `?from&to&bucketMinutes?&scale?` → `{ frames[], count, bucketMinutes, scaleMaxMm }`. `scaleMaxMm` is non-null only for `scale=autofit`, and every frame in the window shares it. |
| `GET /api/rainfall/overlay` | `?start&end&scale&scaleMax` → `image/png` with `X-Bounds-*` and `X-Legend-{Unit,Thresholds,Colors}` headers. `scaleMax` is sent only for `autofit`. |

## Development server

To start a local development server, run:

```bash
ng serve
```

Once the server is running, open your browser and navigate to `http://localhost:4200/`. The application will automatically reload whenever you modify any of the source files.

## Code scaffolding

Angular CLI includes powerful code scaffolding tools. To generate a new component, run:

```bash
ng generate component component-name
```

For a complete list of available schematics (such as `components`, `directives`, or `pipes`), run:

```bash
ng generate --help
```

## Building

To build the project run:

```bash
ng build
```

This will compile your project and store the build artifacts in the `dist/` directory. By default, the production build optimizes your application for performance and speed.

## Running unit tests

To execute unit tests with the [Karma](https://karma-runner.github.io) test runner, use the following command:

```bash
ng test
```

For a single non-interactive run (CI, or an agent verifying a change):

```bash
npm test -- --watch=false --browsers=ChromeHeadless
```

## Running end-to-end tests

For end-to-end (e2e) testing, run:

```bash
ng e2e
```

Angular CLI does not come with an end-to-end testing framework by default. You can choose one that suits your needs.

## Additional Resources

For more information on using the Angular CLI, including detailed command references, visit the [Angular CLI Overview and Command Reference](https://angular.dev/tools/cli) page.
