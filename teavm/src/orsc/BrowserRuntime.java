package orsc;

/**
 * Runtime configuration for the browser client, resolved once from the page URL in {@link
 * BrowserMain}. The client is a plain RuneScape Classic web client: it fetches its baked assets
 * relative to the page and connects to the game-server WebSocket given by {@code ?server}. It has no
 * notion of how any particular deployment is wired — a deployment just points it at a server.
 */
public final class BrowserRuntime {

  /** Base URL prefix for baked assets; relative to the page by default. Override with {@code ?assets=}. */
  public static String assetBase = "";

  /**
   * The game-server WebSocket URL to connect to, resolved in {@link BrowserMain} from {@code ?server}
   * (a full {@code ws(s)://…} URL, or a {@code host:port} shorthand). {@code null} falls back to the
   * address in the cache's {@code ip.txt}/{@code port.txt}, like the desktop client.
   */
  public static String serverUrl = null;

  private BrowserRuntime() {
  }

  /** Base for raw cache files (config.txt, spritepacks, audio) fetched via {@code BrowserCache}. */
  public static String cacheBase() {
    return assetBase + "cache/";
  }

  /** Base for pre-packed, gzipped archives fetched via {@code BrowserZipFile}. */
  public static String packBase() {
    return assetBase + "pack/";
  }
}
