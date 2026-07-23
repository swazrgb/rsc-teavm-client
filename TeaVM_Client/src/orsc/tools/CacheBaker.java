package orsc.tools;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import com.openrsc.data.DataFileDecrypter;

/**
 * Build-time cache baker: turns an OpenRSC client {@code Cache} tree into a self-contained static
 * asset tree the browser (TeaVM) client can load with no server-side repacking.
 *
 * <p><b>Why this exists.</b> The browser can't cheaply read the raw OpenRSC cache. The sprite and
 * landscape {@code .orsc} archives are ZIPs of thousands of tiny deflate entries — TeaVM's pure-JS
 * {@code Inflater} spent ~1.2s per archive on them — and the model/library archives are headerless
 * bzip2, a format the browser has no native decoder for. Doing that decode-and-repack once at build
 * time — rather than on every load, or on a server the client would have to run behind — makes the
 * client <b>statically hostable</b>: the packaged client is just files (index.html + JS + these baked
 * assets) that any plain web server or CDN can serve, and from there it can connect to the WebSocket
 * of any OpenRSC server directly. The trade-off — acceptable because the cache is versioned content —
 * is that the client must be rebuilt when the cache changes, since the assets are baked into the build.
 *
 * <p>Run as {@code CacheBaker <cacheDir> <outDir>}. Produces two subtrees under {@code outDir}:
 * <ul>
 *   <li>{@code pack/video/*.orsc} — each {@code video/*.orsc} archive decompressed once and
 *       re-emitted as a flat container ({@code [int count]} then per entry
 *       {@code [u16 nameLen][name UTF-8][int dataLen][data]}), then <b>gzipped</b>. The client fetches
 *       these and gunzips natively (fast single stream), avoiding TeaVM's slow pure-JS inflate over
 *       the archives' thousands of tiny deflate entries. Matches {@code BrowserZipFile}.</li>
 *   <li>{@code cache/**} — every other cache file copied verbatim (config.txt, audio,
 *       {@code video/spritepacks}, {@code *.osar}, …), which the client fetches raw via
 *       {@code BrowserCache}.</li>
 * </ul>
 *
 * <p>Two on-disk archive formats, one wire format: a ZIP ({@code "PK"} magic — sprites, landscapes)
 * flattens one container entry per zip entry; an RSC {@code .jag}/{@code .mem} archive (models,
 * library — headerless bzip2) is decompressed whole and flattened as a single entry (its
 * decompressed body, which is what the client's {@code unpackData} returns). Either way the client
 * reads it with {@code BrowserZipFile}.
 */
public final class CacheBaker {

  private CacheBaker() {
  }

  public static void main(String[] args) throws IOException {
    if (args.length < 2) {
      System.err.println("usage: CacheBaker <cacheDir> <outDir>");
      System.exit(2);
    }
    Path cacheDir = Path.of(args[0]).toAbsolutePath().normalize();
    Path outDir = Path.of(args[1]).toAbsolutePath().normalize();
    if (!Files.isDirectory(cacheDir)) {
      System.err.println("CacheBaker: cache dir not found: " + cacheDir
          + " (set -Dopenrsc.cache.dir=...)");
      System.exit(1);
    }
    Path packOut = outDir.resolve("pack");
    Path cacheOut = outDir.resolve("cache");

    int[] packed = {0};
    long[] copied = {0};
    try (Stream<Path> walk = Files.walk(cacheDir)) {
      walk.filter(Files::isRegularFile).forEach(file -> {
        Path rel = cacheDir.relativize(file);
        try {
          if (isVideoArchive(rel)) {
            byte[] gz = gzip(pack(file, rel.getFileName().toString()));
            Path dst = packOut.resolve(rel);
            Files.createDirectories(dst.getParent());
            Files.write(dst, gz);
            System.out.println("  pack  " + rel + " -> " + gz.length / 1024 + " KB gz");
            packed[0]++;
          } else {
            Path dst = cacheOut.resolve(rel);
            Files.createDirectories(dst.getParent());
            Files.copy(file, dst, StandardCopyOption.REPLACE_EXISTING);
            copied[0]++;
          }
        } catch (IOException e) {
          throw new UncheckedIOException("baking " + rel, e);
        }
      });
    }
    System.out.println("CacheBaker: " + packed[0] + " archives packed, " + copied[0]
        + " files copied -> " + outDir);
  }

  /** A top-level {@code video/*.orsc} archive (packed); {@code .osar} sprite packs stay raw. */
  private static boolean isVideoArchive(Path rel) {
    return rel.getNameCount() == 2
        && rel.getName(0).toString().equals("video")
        && rel.getFileName().toString().endsWith(".orsc");
  }

  /** Flatten one archive into the client's container format (uncompressed; gzipped by the caller). */
  private static byte[] pack(Path archive, String name) throws IOException {
    return isZip(archive) ? flattenZip(archive) : flattenRscArchive(archive, name);
  }

  private static boolean isZip(Path archive) throws IOException {
    try (InputStream in = Files.newInputStream(archive)) {
      byte[] head = new byte[2];
      return in.read(head) == 2 && head[0] == 'P' && head[1] == 'K';
    }
  }

  private static byte[] flattenZip(Path archive) throws IOException {
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      List<? extends ZipEntry> entries = Collections.list(zip.entries());
      ByteArrayOutputStream flat = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(flat);
      out.writeInt(entries.size());
      for (ZipEntry e : entries) {
        byte[] data;
        try (InputStream in = zip.getInputStream(e)) {
          data = in.readAllBytes();
        }
        writeEntry(out, e.getName(), data);
      }
      out.flush();
      return flat.toByteArray();
    }
  }

  private static byte[] flattenRscArchive(Path archive, String name) throws IOException {
    byte[] body = rscBody(Files.readAllBytes(archive));
    ByteArrayOutputStream flat = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(flat);
    out.writeInt(1);
    writeEntry(out, name, body);
    out.flush();
    return flat.toByteArray();
  }

  /**
   * The decompressed whole-archive body of a classic RSC {@code .jag}/{@code .mem} archive — the
   * bytes an RSC client's {@code unpackData} returns. 6-byte header (3-byte uncompressed length,
   * 3-byte compressed length); when they differ the {@code compressed-length} bytes after the header
   * are headerless bzip2, decoded with the client's own {@link DataFileDecrypter} (its desktop
   * {@code unpackData}, mudclient.java). Reusing the client's decoder keeps this build standalone
   * (no external bzip2 dep) and byte-identical to what the client expects.
   */
  private static byte[] rscBody(byte[] data) throws IOException {
    if (data.length < 6) {
      throw new IOException("truncated RSC archive");
    }
    int decmpLen = ((data[0] & 0xFF) << 16) | ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
    int cmpLen = ((data[3] & 0xFF) << 16) | ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
    byte[] body = new byte[cmpLen];
    System.arraycopy(data, 6, body, 0, cmpLen);
    if (cmpLen == decmpLen) {
      return body;
    }
    byte[] dest = new byte[decmpLen];
    DataFileDecrypter.unpackData(dest, decmpLen, body, cmpLen, 0);
    return dest;
  }

  private static void writeEntry(DataOutputStream out, String name, byte[] data) throws IOException {
    byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
    out.writeShort(nameBytes.length);
    out.write(nameBytes);
    out.writeInt(data.length);
    out.write(data);
  }

  private static byte[] gzip(byte[] raw) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
      gz.write(raw);
    }
    return bos.toByteArray();
  }
}
