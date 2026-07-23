package orsc.util;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class Utils {
	private static DateFormat df;
	private static long timeCorrection;
	private static long lastTimeUpdate;

	// Browser build: fonts come from the canvas / bundled CSS, and links open via the host page —
	// the AWT Font/Desktop/ImageIcon variants are dropped (Client_Base doesn't call getFont/getImage).
	public static void openWebpage(final String url) {
	}

	public static synchronized long currentTimeMillis() {
		final long l = System.currentTimeMillis();
		if (l < Utils.lastTimeUpdate) {
			Utils.timeCorrection += Utils.lastTimeUpdate - l;
		}
		Utils.lastTimeUpdate = l;
		return l + Utils.timeCorrection;
	}

	public static String getServerTime() {
		if (Utils.df == null) {
			(Utils.df = new SimpleDateFormat("h:mm:ss a")).setTimeZone(TimeZone.getTimeZone("America/New_York"));
		}
		return Utils.df.format(new Date());
	}

	public static String stripHtml(final String text) {
		return text.replaceAll("\\<.*?\\>", "");
	}

	public static int getJavaVersion() {
		try {
			String versionText = System.getProperty("java.version");
			if (versionText.startsWith("1.")) {
				versionText = versionText.substring(2);
			}

			if (versionText.contains(".")) {
				return Integer.parseInt(versionText.substring(0, versionText.indexOf(".")));
			} else {
				return Integer.parseInt(versionText);
			}
		} catch (Exception e) {
			return -1;
		}
	}

	public static boolean isWindowsOS() {
		return System.getProperty("os.name").contains("Windows");
	}

	public static boolean isModernWindowsOS() {
		return "Windows 11".equals(System.getProperty("os.name"))
			|| "Windows 10".equals(System.getProperty("os.name"))
			|| "Windows 8.1".equals(System.getProperty("os.name"));
	}

	public static boolean isMacOS() {
		String os = System.getProperty("os.name").toLowerCase(Locale.ENGLISH);
		return (os.contains("mac") || os.contains("darwin"));
	}
}
