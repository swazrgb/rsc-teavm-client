package orsc.net;

import java.io.IOException;
import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.ajax.XMLHttpRequest;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Fetches OpenRSC content archives over HTTP for the browser build. The client reads its cache via
 * {@code DataOperations.streamFromPath(getCacheLocation() + file)}; here {@code getCacheLocation()}
 * is an HTTP base (the baked {@code cache/} tree, see {@code BrowserRuntime}/{@code CacheBaker}),
 * relative to the page so the site is hostable anywhere. The fetch is an async {@link XMLHttpRequest}
 * blocked via {@link Async}, so under TeaVM
 * green threads it yields the event loop instead of freezing it.
 */
public final class BrowserCache {

  private BrowserCache() {
  }

  @Async
  public static native byte[] fetch(String url) throws IOException;

  public static void fetch(String url, AsyncCallback<byte[]> callback) {
    XMLHttpRequest xhr = XMLHttpRequest.create();
    xhr.open("GET", url);
    xhr.setResponseType("arraybuffer");
    xhr.setOnReadyStateChange(() -> {
      if (xhr.getReadyState() != XMLHttpRequest.DONE) {
        return;
      }
      int status = xhr.getStatus();
      if (status != 200 && status != 0) {
        callback.error(new IOException("HTTP " + status + " for " + url));
        return;
      }
      ArrayBuffer buf = (ArrayBuffer) xhr.getResponse();
      if (buf == null) {
        callback.error(new IOException("empty response for " + url));
        return;
      }
      Int8Array arr = Int8Array.create(buf);
      byte[] out = new byte[arr.getLength()];
      for (int i = 0; i < out.length; i++) {
        out[i] = arr.get(i);
      }
      callback.complete(out);
    });
    xhr.send();
  }

  /**
   * Fetch a gzipped resource and inflate it natively via the browser's {@code DecompressionStream} —
   * a single fast gzip stream, unlike TeaVM's slow pure-JS inflate. Used for the baked {@code pack/}
   * containers (see {@code CacheBaker}); the raw bytes the client sees are the decompressed container.
   */
  @Async
  public static native byte[] fetchInflate(String url) throws IOException;

  public static void fetchInflate(String url, AsyncCallback<byte[]> callback) {
    jsFetchInflate(url,
        buf -> {
          if (buf == null) {
            callback.error(new IOException("empty response for " + url));
            return;
          }
          Int8Array arr = Int8Array.create(buf);
          byte[] out = new byte[arr.getLength()];
          for (int i = 0; i < out.length; i++) {
            out[i] = arr.get(i);
          }
          callback.complete(out);
        },
        err -> callback.error(new IOException(err)));
  }

  @JSFunctor
  private interface ArrayBufferConsumer extends JSObject {
    void accept(ArrayBuffer buf);
  }

  @JSFunctor
  private interface StringConsumer extends JSObject {
    void accept(String message);
  }

  @JSBody(params = {"url", "ok", "err"}, script =
      "fetch(url).then(function (r) {"
    + "  if (!r.ok && r.status !== 0) throw new Error('HTTP ' + r.status + ' for ' + url);"
    + "  return r.arrayBuffer();"
    + "}).then(function (ab) {"
    + "  var stream = new Blob([ab]).stream().pipeThrough(new DecompressionStream('gzip'));"
    + "  return new Response(stream).arrayBuffer();"
    + "}).then(function (buf) { ok(buf); }).catch(function (e) { err('' + e); });")
  private static native void jsFetchInflate(String url, ArrayBufferConsumer ok, StringConsumer err);
}
