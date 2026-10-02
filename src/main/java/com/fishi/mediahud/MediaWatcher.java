package com.fishi.mediahud;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Reads the currently playing track through the Windows API
 * "GlobalSystemMediaTransportControlsSessionManager" (the same source Windows
 * uses for the media display in the volume flyout).
 *
 * Java cannot access this WinRT API directly. Therefore a single PowerShell
 * process is started in the background (script: media.ps1), which prints the
 * playback state once per second (format: see script).
 *
 * Everything here is deliberately defensive: any error only means that nothing
 * is shown. The game is never affected.
 */
public final class MediaWatcher {
	private static final Logger LOGGER = LoggerFactory.getLogger("mediahud");

	/** After this many milliseconds without a new line the data counts as stale. */
	private static final long STALE_AFTER_MS = 5_000;
	/** Delay before a terminated PowerShell process is restarted. */
	private static final long RESTART_DELAY_MS = 10_000;
	private static final int MAX_TEXT_LENGTH = 120;

	/** PowerShell script (Windows PowerShell 5.1, always present on Windows 10/11). */
	private static final String SCRIPT_RESOURCE = "/assets/mediahud/media.ps1";

	/** Maximum size of a cover (Base64), anything larger is ignored. */
	private static final int MAX_COVER_BASE64_LENGTH = 1_000_000;
	/** Upper limit for the number of reported sources. */
	private static final int MAX_SOURCES = 32;
	/** Environment variable that passes the whitelist to the script. */
	private static final String SOURCES_ENV = "MEDIAHUD_SOURCES";

	/**
	 * Current track. Immutable so that render thread and reader thread never see partial data.
	 *
	 * @param coverId 0 = no cover available
	 */
	public record Track(String title, String artist, boolean playing, long positionMs, long durationMs,
			int coverId, long receivedAtMs) {
		/** Position at {@code nowMs}; while playing, extrapolated since the last report. */
		public long positionAt(long nowMs) {
			long position = positionMs;
			if (playing) {
				position += Math.max(0, nowMs - receivedAtMs);
			}
			return Math.max(0, Math.min(position, durationMs));
		}
	}

	/** Cover as PNG data. */
	public record Cover(int id, byte[] png) {
	}

	/** Programs currently reporting media (Windows source ids). */
	private record Sources(List<String> ids, long receivedAtMs) {
	}

	private static volatile Track track = null;
	private static volatile Cover cover = null;
	private static volatile Sources sources = null;
	private static volatile boolean running = false;
	private static volatile Process process = null;
	private static Thread thread = null;

	private MediaWatcher() {
	}

	public static synchronized void start() {
		if (running) {
			return;
		}
		String os = System.getProperty("os.name", "");
		if (!os.toLowerCase(Locale.ROOT).startsWith("windows")) {
			LOGGER.info("Media HUD: not running on Windows ({}), display stays disabled.", os);
			return;
		}
		running = true;
		thread = new Thread(MediaWatcher::runLoop, "MediaHUD-Watcher");
		thread.setDaemon(true);
		thread.start();
	}

	public static synchronized void stop() {
		running = false;
		destroyProcess();
		if (thread != null) {
			thread.interrupt();
			thread = null;
		}
	}

	/**
	 * Returns the current track, or {@code null} if nothing should be shown.
	 * Called every frame by the render thread, therefore very cheap.
	 */
	public static Track getTrack() {
		Track t = track;
		if (t == null || System.currentTimeMillis() - t.receivedAtMs() > STALE_AFTER_MS) {
			return null;
		}
		return t;
	}

	/** Last received cover or {@code null}. */
	public static Cover getCover() {
		return cover;
	}

	/** Source ids of all programs currently reporting media; empty if unknown. */
	public static List<String> getSources() {
		Sources s = sources;
		if (s == null || System.currentTimeMillis() - s.receivedAtMs() > STALE_AFTER_MS) {
			return List.of();
		}
		return s.ids();
	}

	private static void runLoop() {
		boolean loggedFailure = false;
		while (running) {
			boolean restartNow = false;
			try {
				restartNow = runProcessOnce();
			} catch (Exception e) {
				if (!loggedFailure) {
					LOGGER.warn("Media HUD: media query failed, will retry later.", e);
					loggedFailure = true;
				}
			} finally {
				destroyProcess();
				track = null;
				cover = null;
			}

			if (!running) {
				break;
			}
			if (restartNow) {
				continue;
			}
			try {
				Thread.sleep(RESTART_DELAY_MS);
			} catch (InterruptedException e) {
				break;
			}
		}
	}

	/** Returns {@code true} if the process was ended because the whitelist changed (restart right away). */
	private static boolean runProcessOnce() throws Exception {
		String encoded = Base64.getEncoder().encodeToString(loadScript().getBytes(StandardCharsets.UTF_16LE));
		ProcessBuilder builder = new ProcessBuilder(
			"powershell.exe",
			"-NoProfile",
			"-NonInteractive",
			"-ExecutionPolicy", "Bypass",
			"-EncodedCommand", encoded);
		builder.redirectError(ProcessBuilder.Redirect.DISCARD);
		List<String> whitelist = MediaHudConfig.activeWhitelist();
		builder.environment().remove(SOURCES_ENV);
		if (!whitelist.isEmpty()) {
			builder.environment().put(SOURCES_ENV, String.join("\t", whitelist));
		}

		Process p = builder.start();
		process = p;
		// The script reads nothing from stdin.
		p.getOutputStream().close();

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while (running && (line = reader.readLine()) != null) {
				handleLine(line, System.currentTimeMillis());
				// The script receives the whitelist only at start, so restart it after a change.
				if (!MediaHudConfig.activeWhitelist().equals(whitelist)) {
					return true;
				}
			}
		}
		return false;
	}

	private static String loadScript() throws IOException {
		try (InputStream in = MediaWatcher.class.getResourceAsStream(SCRIPT_RESOURCE)) {
			if (in == null) {
				throw new IOException("Missing resource: " + SCRIPT_RESOURCE);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static void destroyProcess() {
		Process p = process;
		process = null;
		if (p != null) {
			try {
				p.destroyForcibly();
			} catch (Exception ignored) {
				// Nothing to do, the process is already gone.
			}
		}
	}

	static void handleLine(String line, long nowMs) {
		String[] parts = line.split("\t", -1);
		if (clean(parts[0]).equals("L")) {
			sources = new Sources(parseSources(parts), nowMs);
			return;
		}
		if (parts.length == 3 && parts[0].equals("A")) {
			Cover c = parseCover(parts);
			if (c != null) {
				cover = c;
			}
			return;
		}
		// Any other line (including an empty one) is a status report.
		track = parseTrack(parts, nowMs);
	}

	/** {@code S <Status> <Artist> <Title> <PositionMs> <DurationMs> <CoverId>}; otherwise {@code null}. */
	static Track parseTrack(String[] parts, long nowMs) {
		if (parts.length != 7 || !clean(parts[0]).equals("S")) {
			return null;
		}
		String status = clean(parts[1]);
		if (!status.equals("Playing") && !status.equals("Paused")) {
			return null;
		}
		String title = limit(clean(parts[3]));
		if (title.isEmpty()) {
			return null;
		}
		try {
			long duration = Math.max(0, Long.parseLong(parts[5].trim()));
			long position = Math.max(0, Math.min(Long.parseLong(parts[4].trim()), duration));
			int coverId = Integer.parseInt(parts[6].trim());
			return new Track(title, limit(clean(parts[2])), status.equals("Playing"), position, duration, coverId, nowMs);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** {@code L <SourceId> <SourceId> ...}; empty, overly long and duplicate entries are skipped. */
	static List<String> parseSources(String[] parts) {
		List<String> ids = new ArrayList<>();
		for (int i = 1; i < parts.length && ids.size() < MAX_SOURCES; i++) {
			// Not shortened: the id must stay exact so the whitelist matches.
			String id = clean(parts[i]);
			if (!id.isEmpty() && id.length() <= MAX_TEXT_LENGTH && !ids.contains(id)) {
				ids.add(id);
			}
		}
		return List.copyOf(ids);
	}

	/** {@code A <CoverId> <PNG as Base64>}; otherwise {@code null}. */
	static Cover parseCover(String[] parts) {
		String data = parts[2].trim();
		if (data.isEmpty() || data.length() > MAX_COVER_BASE64_LENGTH) {
			return null;
		}
		try {
			int id = Integer.parseInt(parts[1].trim());
			if (id <= 0) {
				return null;
			}
			return new Cover(id, Base64.getDecoder().decode(data));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static String limit(String s) {
		return s.length() > MAX_TEXT_LENGTH ? s.substring(0, MAX_TEXT_LENGTH - 3) + "..." : s;
	}

	/** Removes BOM and control characters so that only plain text is rendered. */
	private static String clean(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\uFEFF' || Character.isISOControl(c)) {
				continue;
			}
			sb.append(c);
		}
		return sb.toString().trim();
	}
}
