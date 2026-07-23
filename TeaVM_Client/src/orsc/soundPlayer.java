package orsc;

import org.teavm.jso.JSBody;

/**
 * Browser sound playback. Every OpenRSC sound effect is a named WAV in the cache's {@code audio/}
 * tree, and the client only ever plays sounds by name: the server drives them via opcode 204
 * ({@code PacketHandler.playSound} → {@code playSoundFile(name)}), and prayer on/off does the same.
 * OpenRSC uses these individual WAVs on every platform (PC + Android); the authentic packed
 * {@code sounds.mem} (µ-law, as in rsc-c) and {@code ClientPort.playSound(byte[])} are unused here.
 *
 * <p>{@code playSoundFile} fetches {@code audio/<key>.wav} and plays it through the Web Audio API,
 * decoding each clip once into a cached buffer so repeats are instant and clips overlap.
 */
public class soundPlayer {

  /**
   * Open the shared AudioContext up front (called from {@code BrowserMain} at startup) so the first
   * sound isn't lost to context setup or the autoplay gate — the context is created now and resumed
   * on the first user gesture.
   */
  @JSBody(params = {}, script =
      "var g = (window.__orscAudio = window.__orscAudio || {ctx: null, cache: {}});"
    + "if (g.ctx) return;"
    + "var AC = window.AudioContext || window.webkitAudioContext; if (!AC) return;"
    + "g.ctx = new AC();"
    + "var unlock = function () { if (g.ctx.state === 'suspended') { g.ctx.resume(); }"
    + "  document.removeEventListener('pointerdown', unlock);"
    + "  document.removeEventListener('keydown', unlock); };"
    + "document.addEventListener('pointerdown', unlock);"
    + "document.addEventListener('keydown', unlock);")
  public static native void init();

  /** Play the named sound effect (e.g. "combat1a", "prayeron"), unless sound is disabled. */
  public static void playSoundFile(String key) {
    if (mudclient.optionSoundDisabled) {
      return;
    }
    play(BrowserRuntime.cacheBase() + "audio/" + key + ".wav");
  }

  // Fetches + decodes each clip once into a cached AudioBuffer and plays it via a throwaway
  // BufferSource so clips can overlap. Uses the context opened by init() (creating it if init()
  // somehow didn't run) and resumes it in case no gesture has landed yet. Failures are ignored.
  @JSBody(params = {"url"}, script =
      "var g = (window.__orscAudio = window.__orscAudio || {ctx: null, cache: {}});"
    + "if (!g.ctx) { var AC = window.AudioContext || window.webkitAudioContext; if (!AC) return; g.ctx = new AC(); }"
    + "if (g.ctx.state === 'suspended') { g.ctx.resume(); }"
    + "var fire = function (b) { var s = g.ctx.createBufferSource();"
    + "  s.buffer = b; s.connect(g.ctx.destination); s.start(); };"
    + "var cached = g.cache[url];"
    + "if (cached) { fire(cached); return; }"
    + "fetch(url).then(function (r) { return r.arrayBuffer(); })"
    + "  .then(function (ab) { return g.ctx.decodeAudioData(ab); })"
    + "  .then(function (b) { g.cache[url] = b; fire(b); })"
    + "  .catch(function (e) {});")
  private static native void play(String url);
}
