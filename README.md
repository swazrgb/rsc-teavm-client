# orsc-teavm-client

A [TeaVM](https://teavm.org/) build of the **OpenRSC custom client** ([Client_Base](https://gitlab.com/openrsc/openrsc/-/tree/develop/Client_Base)) that compiles
the Java client to JavaScript so it runs in a web browser.

**▶ Demo: <https://swazrgb.github.io/rsc-teavm-client/>**

It is a near-verbatim port of the desktop client from
[OpenRSC](https://gitlab.com/openrsc/openrsc): the browser build reuses the client's rendering,
model/animation and game logic unchanged, and only swaps out the platform edges a browser can't
provide — the network socket, cache/file I/O, sound and windowing.

## Browser shims

The platform edges a browser can't provide are swapped out by a handful of shims; everything else is
the stock client:

| New file | Replaces the desktop… |
| --- | --- |
| `orsc/BrowserMain` | `main()` entry point |
| `orsc/net/WebSocketConn` | `java.net.Socket` game connection |
| `orsc/net/BrowserCache` | filesystem cache reads (uses XHR) |
| `orsc/graphics/two/BrowserZipFile` | `java.util.zip` cache-archive reader |
| `orsc/multiclient/BrowserClientPort` | canvas / input / window glue |
| `orsc/soundPlayer` · `orsc/Discord` · `orsc/osConfig` · `orsc/ScaledWindow` · `orsc/util/Utils` | AWT / OS / sound / config edges |

Existing client files (`mudclient`, `World`, `GraphicsController`, `Network_Socket`, `Config`, …) are
modified only to route those desktop-only code paths through the shims.

## Build

Needs a JDK (17+) and Maven. TeaVM transpiles the client to JavaScript during `package`:

```bash
mvn package
```

Output lands in `target/`:

- `target/client-base-teavm-1.0-SNAPSHOT/` — the **self-contained** static client: `index.html`,
  the compiled `teavm/classes.js`, and the game assets the build baked from the cache (`cache/` +
  `pack/`). Serve this directory from any static web server or CDN.
- `target/client-base-teavm-1.0-SNAPSHOT.war` — the same, packaged as a WAR.

The build runs a `CacheBaker` step that reads an OpenRSC client cache (defaults to
`../openrsc/Client_Base/Cache`; override with `-Dopenrsc.cache.dir=/path/to/Cache`) and transforms it
into the static `cache/` + `pack/` assets the browser loads — so there is no server-side repacking
and the client is hostable anywhere. Because the assets are baked in, rebuild when the cache changes.

For an unminified, debuggable build:

```bash
mvn package -Dteavm.minify=false -Dteavm.opt=SIMPLE
```

Source maps are on by default (they map the minified JS back to the Java sources and are fetched
only when devtools is open).

## Run it

Out of the box the client connects to the official OpenRSC server and shows the login screen.
Configuration lives in a small snippet in `index.html` that resolves each field from the page URL,
falling back to a default — the client reads `window.OPENRSC`, it does not parse the URL itself. Edit the defaults to host the client elsewhere:

```html
window.OPENRSC = (function () {
  var q = new URLSearchParams(location.search);
  return {
    server: q.get('server') || 'wss://game.openrsc.com:43435/',
    user:   q.get('user')   || '',
    pass:   q.get('pass')   || '',
    assets: q.get('assets') || ''
  };
})();
```

So per-visit URL parameters override the defaults:

- `?server=<host:port>` or `?server=<ws(s)://host:port/path>` — the game-server WebSocket. WebSockets
  are exempt from the same-origin policy, so a page hosted anywhere may connect to any server that
  accepts the handshake.
- `?user=<name>&pass=<pw>` — optional auto-login; omit both to get the normal login screen.
- `?assets=<baseUrl>` — optional override for where `cache/` + `pack/` are served (relative to the
  page by default, so the site works under any base path).

To try it locally, serve the built directory with any static file server and open `index.html`. It
must be served over HTTP (not opened as a `file://` path) — the client loads its assets via `fetch`
and connects over a WebSocket. Python's built-in server needs no configuration (it serves the
gzipped `pack/` files as-is; the client inflates them itself):

```bash
cd target/client-base-teavm-1.0-SNAPSHOT
python3 -m http.server 8000
# then open http://localhost:8000/
```

## Deploy to GitHub Pages

`scripts/publish-gh-pages.sh` builds the client and pushes the self-contained site to the repo's
`gh-pages` branch:

```bash
scripts/publish-gh-pages.sh          # builds, then pushes to origin/gh-pages
```

## License

[GNU Affero General Public License v3.0](LICENSE) — the same license as upstream OpenRSC, from which
this client is derived.
