package orsc.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.dom.events.Event;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.dom.events.MessageEvent;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.websocket.CloseEvent;
import org.teavm.jso.websocket.WebSocket;

/**
 * A WebSocket-backed connection exposing blocking {@link InputStream}/{@link OutputStream}, so the
 * stock {@link Network_Socket} (which only ever touches streams) drives it unchanged. Ported from
 * mudclient177's browser Socket: incoming binary messages are buffered as {@link Int8Array} chunks,
 * and {@code read()}/{@code readBytes()} block by {@code Thread.sleep(5)}+recurse — under TeaVM's
 * green threads that yields the event loop so the {@code onMessage} callback can deliver more bytes.
 */
public final class WebSocketConn {

  private final String url;
  private WebSocket client;
  private volatile boolean connected;
  private volatile boolean closed;

  private final ArrayList<Int8Array> buffers = new ArrayList<>();
  private Int8Array currentBuffer;
  private int offset;
  private int bytesLeft;
  private int bytesAvailable;

  public WebSocketConn(String url) {
    this.url = url;
  }

  /** Blocking connect (green-thread) — completes when the socket opens, throws on error. */
  @Async
  public native void connect() throws IOException;

  public void connect(AsyncCallback<Void> callback) {
    this.client = WebSocket.create(url, "binary");
    this.client.setBinaryType("arraybuffer");
    this.client.onOpen(new EventListener<Event>() {
      @Override
      public void handleEvent(Event e) {
        connected = true;
        callback.complete(null);
      }
    });
    this.client.onError(new EventListener<Event>() {
      @Override
      public void handleEvent(Event e) {
        if (!connected) {
          callback.error(new IOException("WebSocket connect failed: " + url));
        } else {
          closed = true;
        }
      }
    });
    this.client.onClose(new EventListener<CloseEvent>() {
      @Override
      public void handleEvent(CloseEvent e) {
        closed = true;
        connected = false;
      }
    });
    this.client.onMessage(new EventListener<MessageEvent>() {
      @Override
      public void handleEvent(MessageEvent e) {
        Int8Array chunk = Int8Array.create(e.getDataAsArray());
        buffers.add(chunk);
        bytesAvailable += chunk.getLength();
        refreshCurrentBuffer();
      }
    });
  }

  private void refreshCurrentBuffer() {
    if (bytesLeft == 0 && !buffers.isEmpty()) {
      currentBuffer = buffers.remove(0);
      offset = 0;
      bytesLeft = currentBuffer != null ? currentBuffer.getLength() : 0;
    }
  }

  private int read() throws IOException {
    if (bytesLeft > 0) {
      bytesLeft--;
      bytesAvailable--;
      int v = currentBuffer.get(offset++) & 0xff;
      if (bytesLeft == 0) {
        refreshCurrentBuffer();
      }
      return v;
    }
    if (closed) {
      return -1;
    }
    sleep();
    return read();
  }

  private int readBytes(byte[] dst, int off, int length) throws IOException {
    if (length <= 0) {
      return 0;
    }
    if (bytesAvailable >= length) {
      int total = length;
      while (length > 0) {
        dst[off++] = currentBuffer.get(offset++);
        bytesLeft--;
        bytesAvailable--;
        length--;
        if (bytesLeft == 0) {
          refreshCurrentBuffer();
        }
      }
      return total;
    }
    if (closed) {
      return -1;
    }
    sleep();
    return readBytes(dst, off, length);
  }

  private void write(byte[] bytes, int off, int len) {
    if (client == null || client.getReadyState() != 1) {
      return;
    }
    Int8Array out = Int8Array.create(len);
    for (int i = 0; i < len; i++) {
      out.set(i, bytes[off + i]);
    }
    wsSend(client, out); // send the bytes directly (mudclient177 had to patch send("toSend") → send(e))
  }

  /** WebSocket.send(Int8Array) — done via @JSBody so it doesn't depend on a typed-array send overload. */
  @JSBody(params = {"ws", "data"}, script = "ws.send(data);")
  private static native void wsSend(WebSocket ws, Int8Array data);

  /** Game-server WebSocket URL {@code ws(s)://host:port/} (wss when the page is served over https). */
  @JSBody(params = {"host", "port"}, script =
      "return (location.protocol === 'https:' ? 'wss://' : 'ws://') + host + ':' + port + '/';")
  public static native String serverUrl(String host, int port);

  private static void sleep() {
    try {
      Thread.sleep(5);
    } catch (InterruptedException ignored) {
      // green-thread yield point — spurious wakeups are fine
    }
  }

  public void close() {
    closed = true;
    if (client != null) {
      client.close();
    }
  }

  public InputStream getInputStream() {
    return new InputStream() {
      @Override
      public int read() throws IOException {
        return WebSocketConn.this.read();
      }

      @Override
      public int read(byte[] b, int o, int l) throws IOException {
        return WebSocketConn.this.readBytes(b, o, l);
      }

      @Override
      public int available() {
        return bytesAvailable;
      }

      @Override
      public void close() {
        WebSocketConn.this.close();
      }
    };
  }

  public OutputStream getOutputStream() {
    return new OutputStream() {
      @Override
      public void write(int b) {
        WebSocketConn.this.write(new byte[]{(byte) b}, 0, 1);
      }

      @Override
      public void write(byte[] b, int o, int l) {
        WebSocketConn.this.write(b, o, l);
      }

      @Override
      public void close() {
        WebSocketConn.this.close();
      }
    };
  }
}
