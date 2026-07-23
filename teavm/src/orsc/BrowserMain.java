package orsc;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import orsc.multiclient.BrowserClientPort;
import orsc.net.WebSocketConn;
import org.teavm.jso.JSBody;

/**
 * TeaVM entry point for the browser build of the OpenRSC client. Configuration comes from the
 * {@code window.OPENRSC} object — index.html resolves it from the page URL plus defaults and this
 * class just reads the fields; it does not parse the URL itself:
 * <ul>
 *   <li>{@code server} — the game-server WebSocket ({@code host:port} or a full {@code ws(s)://…}
 *       URL). Empty → the address baked into the cache ({@code ip.txt}/{@code port.txt}).</li>
 *   <li>{@code user} / {@code pass} — optional auto-login; empty → the normal login screen.</li>
 *   <li>{@code assets} — optional base URL for the baked {@code cache/}+{@code pack/} assets
 *       (relative to the page by default).</li>
 * </ul>
 */
public final class BrowserMain {

  private BrowserMain() {
  }

  public static void main(String[] args) {
    // Open the Web Audio context now (it resumes on the first user gesture) so the first game sound
    // isn't dropped waiting for context setup.
    soundPlayer.init();

    String assets = cfg("assets");
    if (assets != null && !assets.isEmpty()) {
      BrowserRuntime.assetBase = assets;
    }

    String server = cfg("server");
    String user = cfg("user");
    String pass = cfg("pass");

    // Resolve the server address for display/config. openSocket() connects to BrowserRuntime.serverUrl
    // when set; otherwise it builds ws(s)://host:port/ from these (ultimately the cache's ip/port).
    String host = null;
    int port = 43594;
    if (server != null && !server.isEmpty()) {
      if (server.contains("://")) {
        BrowserRuntime.serverUrl = server;
        host = urlHost(server);
        int up = urlPort(server);
        if (up > 0) {
          port = up;
        }
      } else {
        int colon = server.lastIndexOf(':');
        if (colon > 0) {
          host = server.substring(0, colon);
          port = Integer.parseInt(server.substring(colon + 1));
        } else {
          host = server;
        }
        BrowserRuntime.serverUrl = WebSocketConn.serverUrl(host, port);
      }
    }

    Properties p = new Properties();
    if (host != null && !host.isEmpty()) {
      p.setProperty("SERVER_IP", host);
      Config.SERVER_IP = host;
    }
    p.setProperty("SERVER_PORT", String.valueOf(port));
    p.setProperty("SERVER_NAME", "OpenRSC");
    Config.updateServerConfiguration(p);
    Config.SERVER_PORT = port;
    Config.MEMBER_WORLD = true;

    // Render-scaling scalar lists the settings UI reads (the desktop fills these in ScaledWindow; our
    // stub doesn't, and they default to null → NPE when the "General" options tab is drawn).
    List<Float> integerScalars = new ArrayList<>();
    for (float i = 1.0f; i <= 3.0f; i++) {
      integerScalars.add(i);
    }
    mudclient.integerScalars = integerScalars;
    List<Float> interpolationScalars = new ArrayList<>();
    for (float i = 1.0f; i <= 3.0f; i += 0.5f) {
      interpolationScalars.add(i);
    }
    mudclient.interpolationScalars = interpolationScalars;

    BrowserClientPort clientPort = new BrowserClientPort();
    mudclient client = new mudclient(clientPort);
    // The launcher (ORSCApplet.init) wires the packet handler before starting the loop; getServerConfig
    // uses it immediately, so it must exist before startMainThread().
    client.packetHandler = new PacketHandler(client);
    clientPort.attach(client);
    if (user != null && !user.isEmpty()) {
      client.pendingAutoLoginUser = user;
      client.pendingAutoLoginPass = pass != null ? pass : "";
    }
    client.startMainThread();
  }

  /** A field of the page's {@code window.OPENRSC} config object (set by index.html), or null. */
  @JSBody(params = {"key"}, script =
      "return (typeof window !== 'undefined' && window.OPENRSC && window.OPENRSC[key] != null) "
          + "? String(window.OPENRSC[key]) : null;")
  private static native String cfg(String key);

  @JSBody(params = {"u"}, script = "try { return new URL(u).hostname; } catch (e) { return ''; }")
  private static native String urlHost(String u);

  @JSBody(params = {"u"}, script = "try { var p = new URL(u).port; return p ? parseInt(p, 10) : 0; } catch (e) { return 0; }")
  private static native int urlPort(String u);
}
