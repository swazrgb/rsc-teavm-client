package orsc;

/**
 * Browser stub of the window/scaling manager. Enough for the client to compile; the browser render
 * surface (canvas + framebuffer, via BrowserClientPort) is wired in the TeaVM phase.
 */
public class ScaledWindow {

  public enum ScalingAlgorithm {
    INTEGER_SCALING,
    BILINEAR_INTERPOLATION,
    BICUBIC_INTERPOLATION
  }

  private static final ScaledWindow INSTANCE = new ScaledWindow();

  public static ScaledWindow getInstance() {
    return INSTANCE;
  }

  public void validateAppletSize() {
  }
}
