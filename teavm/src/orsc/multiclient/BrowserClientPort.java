package orsc.multiclient;

import com.openrsc.client.model.Sprite;
import java.io.ByteArrayInputStream;
import orsc.BrowserRuntime;
import orsc.mudclient;
import org.teavm.jso.JSBody;
import org.teavm.jso.canvas.CanvasRenderingContext2D;
import org.teavm.jso.canvas.ImageData;
import org.teavm.jso.dom.events.Event;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.dom.events.KeyboardEvent;
import org.teavm.jso.dom.events.MouseEvent;
import org.teavm.jso.dom.html.HTMLCanvasElement;
import org.teavm.jso.dom.html.HTMLDocument;
import org.teavm.jso.typedarrays.Uint8ClampedArray;

/**
 * Browser (TeaVM) platform boundary for the OpenRSC custom client. The client renders into
 * {@code mudclient.getSurface().pixelData} (an int[] ARGB framebuffer); {@link #draw()} blits that
 * to a &lt;canvas&gt;. Cache reads go over XHR (see {@code getCacheLocation()} + {@code BrowserCache}),
 * and networking is a WebSocket (see {@code WebSocketConn}).
 */
public class BrowserClientPort implements ClientPort {

  private mudclient client;
  private HTMLCanvasElement canvas;
  private CanvasRenderingContext2D ctx;
  private ImageData imageData;
  private Uint8ClampedArray pixels;
  private int width;
  private int height;
  private int lastWinW = -1;
  private int lastWinH = -1;

  /** Set by {@code BrowserMain} right after the client is constructed. */
  public void attach(mudclient client) {
    this.client = client;
  }

  @Override
  public String getCacheLocation() {
    // Baked raw cache assets (config.txt, spritepacks, audio), relative to the page by default so the
    // site is hostable anywhere. Fetched via XHR in BrowserCache. See BrowserRuntime / CacheBaker.
    return BrowserRuntime.cacheBase();
  }

  @Override
  public void initGraphics() {
    this.width = client.getGameWidth();
    this.height = client.getGameHeight() + 12;
    HTMLDocument doc = HTMLDocument.current();
    this.canvas = (HTMLCanvasElement) doc.createElement("canvas");
    this.canvas.setWidth(width);
    this.canvas.setHeight(height);
    this.canvas.setAttribute("tabindex", "-1");
    doc.getBody().appendChild(canvas);
    // Fill the iframe: the canvas keeps its 512×346 backing store but CSS-scales to the window size,
    // so the draggable/resizable window in the parent page zooms the view (mouse coords are unscaled
    // back to backing-store space in canvasX/canvasY). pixelated keeps the RSC art crisp when enlarged.
    styleCanvasFill(canvas);
    this.ctx = (CanvasRenderingContext2D) canvas.getContext("2d");
    this.imageData = ctx.createImageData(width, height);
    this.pixels = imageData.getData();
    wireInput();
  }

  // The canvas backing store is kept equal to the window size (see draw()/resized()), so it displays
  // 1:1 — the client renders the game at the window's actual resolution (UI stays correct, the 3D
  // viewport uses the extra space), exactly like the desktop client. No CSS stretch or letterbox.
  @JSBody(params = {"c"}, script =
      "document.body.style.margin='0';"
          + "document.body.style.overflow='hidden';"
          + "document.body.style.background='#000';"
          + "c.style.position='fixed';"
          + "c.style.top='0';"
          + "c.style.left='0';"
          + "c.style.width='100vw';"
          + "c.style.height='100vh';"
          + "c.style.display='block';")
  private static native void styleCanvasFill(HTMLCanvasElement c);

  @JSBody(params = {}, script = "return window.innerWidth | 0;")
  private static native int windowInnerWidth();

  @JSBody(params = {}, script = "return window.innerHeight | 0;")
  private static native int windowInnerHeight();

  /** Anchor X for middle-button camera drag; -1 when not rotating. */
  private int rotateAnchorX = -1;

  /** Anchor Y for middle-button camera drag (pitch); tracked alongside rotateAnchorX. */
  private int rotateAnchorY = -1;

  /** Feed canvas mouse events into the client's input fields, mirroring the desktop ORSCApplet. */
  private void wireInput() {
    canvas.addEventListener("mousedown", (EventListener<MouseEvent>) e -> {
      e.preventDefault();
      // preventDefault() suppresses the default focus, so grab keyboard focus explicitly — otherwise
      // clicking back into the iframe wouldn't route keydown to it.
      focusGame(canvas);
      setMousePos(e);
      if (e.getButton() == 1) { // middle → camera rotate anchor, NOT a click (desktop BUTTON2)
        rotateAnchorX = canvasX(e, canvas);
        rotateAnchorY = canvasY(e, canvas);
      } else {
        int button = e.getButton() == 2 ? 2 : 1; // browser 0=left/2=right → client 1/2
        client.currentMouseButtonDown = button;
        client.lastMouseButtonDown = button;
        client.lastMouseAction = 0;
        client.addMouseClick(button, client.mouseX, client.mouseY);
      }
    });
    canvas.addEventListener("mouseup", (EventListener<MouseEvent>) e -> {
      setMousePos(e);
      client.currentMouseButtonDown = 0;
      rotateAnchorX = -1;
      rotateAnchorY = -1;
    });
    canvas.addEventListener("mousemove", (EventListener<MouseEvent>) e -> {
      setMousePos(e);
      int buttons = buttonsMask(e);
      if ((buttons & 4) != 0) { // middle held → yaw by the horizontal delta, pitch by the vertical
        if (rotateAnchorX < 0) {
          rotateAnchorX = canvasX(e, canvas);
          rotateAnchorY = canvasY(e, canvas);
        }
        int dx = (canvasX(e, canvas) - rotateAnchorX) / 2;
        int dy = canvasY(e, canvas) - rotateAnchorY;
        rotateAnchorX = canvasX(e, canvas); // no Robot recenter in the browser — track per-move delta
        rotateAnchorY = canvasY(e, canvas);
        if (dx != 0) {
          rotateCamera(dx);
        }
        if (dy != 0) {
          pitchCamera(dy);
        }
        client.currentMouseButtonDown = 0;
      } else {
        rotateAnchorX = -1;
        rotateAnchorY = -1;
        // Keep the held-button state during a left/right drag; 0 when nothing is pressed.
        client.currentMouseButtonDown = (buttons & 2) != 0 ? 2 : ((buttons & 1) != 0 ? 1 : 0);
      }
    });
    canvas.addEventListener("mouseout", (EventListener<MouseEvent>) e -> {
      client.currentMouseButtonDown = 0;
      rotateAnchorX = -1;
      rotateAnchorY = -1;
    });
    // Right/middle click are game actions — suppress the browser context menu over the canvas.
    canvas.addEventListener("contextmenu", (EventListener<Event>) Event::preventDefault);

    // Mouse wheel: zoom the camera over the game view, or scroll an open UI tab (mirrors ORSCApplet).
    canvas.addEventListener("wheel", (EventListener<Event>) e -> {
      e.preventDefault();
      int rotation = wheelDeltaY(e) > 0 ? 1 : -1;
      if (client.showUiTab == 0) {
        int newZoom = orsc.osConfig.C_LAST_ZOOM + rotation * 10;
        if (newZoom >= 0 && newZoom <= 255) {
          orsc.osConfig.C_LAST_ZOOM = newZoom;
        }
      } else {
        client.runScroll(rotation);
      }
    });

    // Keyboard on the whole iframe document (the canvas may not hold focus after a click).
    HTMLDocument doc = HTMLDocument.current();
    doc.addEventListener("keydown", (EventListener<KeyboardEvent>) e -> {
      int code = keyCode(e);
      int ch = keyChar(e);
      // Stop the browser from scrolling/navigating on keys the game consumes.
      if (code == 8 || code == 9 || code == 32 || (code >= 33 && code <= 40)) {
        e.preventDefault();
      }
      client.handleKeyPress((byte) 126, ch);
      client.lastMouseAction = 0;
      if (code == 39) client.keyRight = true;
      else if (code == 37) client.keyLeft = true;
      else if (code == 38) client.keyUp = true;
      else if (code == 40) client.keyDown = true;
      else if (code == 33) client.pageUp = true;
      else if (code == 34) client.pageDown = true;
      if (code == 13 || code == 10) client.enterPressed = true;

      // Text fields: login/dialog input + chat. Only accept renderable characters.
      if (ch > 0 && orsc.graphics.two.Fonts.inputFilterChars.indexOf(ch) >= 0) {
        if (client.inputTextCurrent.length() < 20) {
          client.inputTextCurrent = client.inputTextCurrent + (char) ch;
        }
        if (client.chatMessageInput.length() < 80 && !client.getIsSleeping()) {
          client.chatMessageInput = client.chatMessageInput + (char) ch;
        }
      }
      if (ch == '\b') {
        if (client.inputTextCurrent.length() > 0) {
          client.inputTextCurrent =
              client.inputTextCurrent.substring(0, client.inputTextCurrent.length() - 1);
        }
        if (client.chatMessageInput.length() > 0) {
          client.chatMessageInput =
              client.chatMessageInput.substring(0, client.chatMessageInput.length() - 1);
        }
      }
      if (ch == '\n' || ch == '\r') {
        client.inputTextFinal = client.inputTextCurrent;
        client.chatMessageInputCommit = client.chatMessageInput;
      }
    });
    doc.addEventListener("keyup", (EventListener<KeyboardEvent>) e -> {
      int code = keyCode(e);
      if (code == 39) client.keyRight = false;
      else if (code == 37) client.keyLeft = false;
      else if (code == 38) client.keyUp = false;
      else if (code == 40) client.keyDown = false;
      else if (code == 33) client.pageUp = false;
      else if (code == 34) client.pageDown = false;
    });

    canvas.focus();
  }

  /** Focus this iframe's window + canvas so document keydown fires here (even after clicking away). */
  @JSBody(params = {"c"}, script = "try { window.focus(); c.focus({preventScroll: true}); } catch (ex) {}")
  private static native void focusGame(HTMLCanvasElement c);

  @JSBody(params = {"e"}, script = "return e.deltaY | 0;")
  private static native int wheelDeltaY(Event e);

  @JSBody(params = {"e"}, script = "return e.keyCode | 0;")
  private static native int keyCode(KeyboardEvent e);

  /** The character for a keydown: printable char code, or 8/9/13/27 for backspace/tab/enter/escape. */
  @JSBody(params = {"e"}, script =
      "var k = e.key;"
          + "if (k === 'Enter') return 13;"
          + "if (k === 'Backspace') return 8;"
          + "if (k === 'Tab') return 9;"
          + "if (k === 'Escape') return 27;"
          + "return (k && k.length === 1) ? k.charCodeAt(0) : 0;")
  private static native int keyChar(KeyboardEvent e);

  /** Middle-drag camera rotation, mirroring ORSCApplet.mouseDragged (C_SWIPE_TO_ROTATE_MODE=normal). */
  private void rotateCamera(int distanceX) {
    if (!client.getOptionCameraModeAuto()) {
      client.cameraRotation = 255 & (client.cameraRotation + distanceX);
    } else if (distanceX < 0) {
      client.keyLeft = true;
    } else {
      client.keyRight = true;
    }
  }

  /**
   * Middle-drag pitch by the vertical delta, same wrap + clamp as the arrow-key pitch handler
   * (mudclient's keyUp/keyDown path): the pitch stays on the right-side-up half circle,
   * [768..1023] ∪ [0..256]. Drag down = pitch increases, matching the down-arrow key.
   */
  private void pitchCamera(int distanceY) {
    if (!client.cameraAllowPitchModification) {
      return;
    }
    int pitch = (client.cameraPitch + distanceY) & 1023;
    if (pitch > 256 && pitch <= 512) {
      pitch = 256;
    }
    if (pitch < 768 && pitch > 512) {
      pitch = 768;
    }
    client.cameraPitch = pitch;
  }

  private void setMousePos(MouseEvent e) {
    client.mouseX = canvasX(e, canvas) - client.screenOffsetX;
    client.mouseY = canvasY(e, canvas) - client.screenOffsetY;
  }

  // Cursor position in the canvas's 512×346 backing-store space: offsetX/Y are in CSS-display pixels,
  // so scale by (backing size / displayed size) since the canvas is CSS-stretched to fill the window.
  @JSBody(params = {"e", "c"}, script =
      "return c.clientWidth ? Math.round(e.offsetX * c.width / c.clientWidth) : (e.offsetX | 0);")
  private static native int canvasX(MouseEvent e, HTMLCanvasElement c);

  @JSBody(params = {"e", "c"}, script =
      "return c.clientHeight ? Math.round(e.offsetY * c.height / c.clientHeight) : (e.offsetY | 0);")
  private static native int canvasY(MouseEvent e, HTMLCanvasElement c);

  /** MouseEvent.buttons bitmask (1=left, 2=right) — not exposed by TeaVM's MouseEvent. */
  @JSBody(params = {"e"}, script = "return e.buttons | 0;")
  private static native int buttonsMask(MouseEvent e);

  @Override
  public void draw() {
    if (ctx == null || client == null) {
      return;
    }
    // Match the client's render resolution to the window. Setting resizeWidth/Height makes the client's
    // reposition() re-render at that size next tick and call resized() (which resizes our canvas).
    int ww = windowInnerWidth();
    int wh = windowInnerHeight();
    if (ww > 0 && wh > 0 && (ww != lastWinW || wh != lastWinH)) {
      lastWinW = ww;
      lastWinH = wh;
      client.resizeWidth = ww;
      client.resizeHeight = wh;
    }
    int[] px = client.getSurface().pixelData;
    if (px == null) {
      return;
    }
    int n = Math.min(px.length, width * height);
    for (int i = 0; i < n; i++) {
      int p = px[i];
      int j = i << 2;
      pixels.set(j, (p >> 16) & 0xff);
      pixels.set(j + 1, (p >> 8) & 0xff);
      pixels.set(j + 2, p & 0xff);
      pixels.set(j + 3, 255);
    }
    ctx.putImageData(imageData, 0, 0);
  }

  @Override
  public boolean drawLoading(int i) {
    // "Is the client ready to keep loading?" — must be true, or startGame() aborts before fetching
    // server configs (and never opens the WebSocket). The browser has no separate loading surface.
    return true;
  }

  @Override
  public void showLoadingProgress(int percentage, String status) {
  }

  @Override
  public void initListeners() {
  }

  @Override
  public void crashed() {
  }

  @Override
  public void drawLoadingError() {
  }

  @Override
  public void drawOutOfMemoryError() {
  }

  @Override
  public boolean isDisplayable() {
    return true;
  }

  @Override
  public void drawTextBox(String line2, byte var2, String line1) {
  }

  @Override
  public void close() {
  }

  @Override
  public Sprite getBattery(int level) {
    return null;
  }

  @Override
  public int getBatteryPercent() {
    return 100;
  }

  @Override
  public boolean getBatteryCharging() {
    return false;
  }

  @Override
  public Sprite getConnectivity(int level) {
    return null;
  }

  @Override
  public String getConnectivityText() {
    return "";
  }

  @Override
  public void resized() {
    // Called by the client's reposition() after it re-rendered at a new resolution. Re-point our
    // canvas backing store + ImageData at the new game size so draw() blits the right pixel count.
    if (canvas == null || ctx == null || client == null) {
      return;
    }
    this.width = client.getGameWidth();
    this.height = client.getGameHeight() + 12;
    canvas.setWidth(width);
    canvas.setHeight(height);
    this.imageData = ctx.createImageData(width, height);
    this.pixels = imageData.getData();
  }

  @Override
  public Sprite getSpriteFromByteArray(ByteArrayInputStream byteArrayInputStream) {
    return null;
  }

  @Override
  public void playSound(byte[] soundData, int offset, int dataLength) {
  }

  @Override
  public void stopSoundPlayer() {
  }

  @Override
  public void drawKeyboard() {
  }

  @Override
  public void closeKeyboard() {
  }

  @Override
  public void setTitle(String title) {
    HTMLDocument.current().setTitle(title);
  }

  @Override
  public void setIconImage(String serverName) {
  }
}
