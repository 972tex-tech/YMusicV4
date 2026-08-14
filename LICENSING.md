# Licensing

YMusic is open source under the **GNU General Public License v3.0** (see [LICENSE](LICENSE)),
with a permissive carve-out for its reusable foundation libraries:

| Directory | License |
|---|---|
| everything not listed below | GPL-3.0 |
| `kmpCommon/` | Apache-2.0 (see `kmpCommon/LICENSE`) |
| `kmpHttp/` | Apache-2.0 (see `kmpHttp/LICENSE`) |

## Why the split

`kmpCommon` (io/log/error primitives) and `kmpHttp` (the Kotlin Multiplatform HTTP stack) are
general-purpose libraries, offered permissively so any project can reuse them — including
closed-source ones. The application itself — the engines, player, UI, and shells built on top —
is copyleft: if you distribute a modified YMusic, you must offer your changes under GPL-3.0 too.

Apache-2.0 is one-way compatible with GPL-3.0, so the app's use of the permissive modules raises
no conflict. The reverse edge is the invariant to maintain: **nothing under `kmpCommon/` or
`kmpHttp/` may depend on a GPL module**, or the carve-out stops being real. (`kmpHttp` depends on
`kmpCommon`; `kmpCommon` sits at the bottom of the module graph, on Apache/MIT third-party
dependencies only.)

## Contributions

Inbound = outbound: a contribution is accepted under the license of the directory it touches —
Apache-2.0 for the two permissive modules, GPL-3.0 elsewhere.
