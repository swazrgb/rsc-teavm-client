package orsc.graphics.two;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import orsc.BrowserRuntime;
import orsc.net.BrowserCache;

/**
 * Browser stand-in for {@link java.util.zip.ZipFile}. TeaVM has no random-access filesystem <em>and</em>
 * its pure-JS {@code Inflater} is slow (decompressing the archives' thousands of tiny deflate entries
 * cost ~1.2s per load). So the build-time {@code CacheBaker} pre-decompresses each archive once into a
 * flat container and stores it gzipped under {@code pack/}. Here we fetch that with a native gunzip
 * ({@link BrowserCache#fetchInflate}) and just walk the container — no JS deflate-inflate. Container
 * layout: {@code [int count]} then per entry {@code [u16 nameLen][name][int dataLen][data]}.
 */
public final class BrowserZipFile {

  private final Map<String, byte[]> entries = new HashMap<>();

  public BrowserZipFile(String path) throws IOException {
    byte[] container = BrowserCache.fetchInflate(packUrl(path));
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(container));
    int count = in.readInt();
    for (int i = 0; i < count; i++) {
      byte[] name = new byte[in.readUnsignedShort()];
      in.readFully(name);
      byte[] data = new byte[in.readInt()];
      in.readFully(data);
      entries.put(new String(name, StandardCharsets.UTF_8), data);
    }
  }

  /**
   * The single entry's bytes, for archives baked as one blob (models/library — an RSC {@code .jag}
   * decompressed whole). Returns {@code null} if empty. Used by {@code unpackData}.
   */
  public byte[] soleEntry() {
    return entries.isEmpty() ? null : entries.values().iterator().next();
  }

  public ZipEntry getEntry(String name) {
    return entries.containsKey(name) ? new ZipEntry(name) : null;
  }

  public InputStream getInputStream(ZipEntry entry) {
    byte[] b = entry == null ? null : entries.get(entry.getName());
    return b == null ? null : new ByteArrayInputStream(b);
  }

  /**
   * Re-anchor the archive path under the baked {@code pack/} base. The client builds this path from
   * {@code Config.F_CACHE_DIR}, which the boot's "Set Cache dir" logic resets to "." (current dir), so
   * we pin any {@code video/…} archive to the pack base (see {@code BrowserRuntime}), which serves the
   * flat gzip container (see the class javadoc) rather than the raw ZIP.
   */
  private static String packUrl(String path) {
    String p = path.replace('\\', '/');
    while (p.contains("//")) {
      p = p.replace("//", "/");
    }
    int idx = p.indexOf("video/");
    return idx >= 0 ? BrowserRuntime.packBase() + p.substring(idx) : p;
  }
}
