package com.fishi.mediahud;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * Liest den aktuell abgespielten Titel über die Windows-Schnittstelle
 * "GlobalSystemMediaTransportControlsSessionManager" (das ist dieselbe Quelle,
 * die Windows für die Medienanzeige bei der Lautstärke-Einblendung nutzt).
 *
 * Java kommt an diese WinRT-API nicht direkt heran. Deshalb wird ein einziger
 * PowerShell-Prozess im Hintergrund gestartet (Skript: media.ps1), der einmal pro
 * Sekunde den Wiedergabe-Zustand ausgibt (Format siehe Skript).
 *
 * Alles hier ist bewusst defensiv: Jeder Fehler führt nur dazu, dass nichts
 * angezeigt wird. Das Spiel wird nie beeinträchtigt.
 */
public final class MediaWatcher {
	private static final Logger LOGGER = LoggerFactory.getLogger("mediahud");

	/** Nach so vielen Millisekunden ohne neue Zeile gilt die Anzeige als veraltet. */
	private static final long STALE_AFTER_MS = 5_000;
	/** Wartezeit, bevor ein beendeter PowerShell-Prozess neu gestartet wird. */
	private static final long RESTART_DELAY_MS = 10_000;
	private static final int MAX_TEXT_LENGTH = 120;

	/** PowerShell-Skript (Windows PowerShell 5.1, auf Windows 10/11 immer vorhanden). */
	private static final String SCRIPT_RESOURCE = "/assets/mediahud/media.ps1";

	/** Maximale Größe eines Covers (Base64), alles darüber wird ignoriert. */
	private static final int MAX_COVER_BASE64_LENGTH = 1_000_000;

	/**
	 * Aktueller Titel. Unveränderlich, damit Render-Thread und Lese-Thread nie halbe Daten sehen.
	 *
	 * @param coverId 0 = kein Cover vorhanden
	 */
	public record Track(String title, String artist, boolean playing, long positionMs, long durationMs,
			int coverId, long receivedAtMs) {
		/** Position zum Zeitpunkt {@code nowMs}; beim Abspielen seit der letzten Meldung hochgerechnet. */
		public long positionAt(long nowMs) {
			long position = positionMs;
			if (playing) {
				position += Math.max(0, nowMs - receivedAtMs);
			}
			return Math.max(0, Math.min(position, durationMs));
		}
	}

	/** Cover als PNG-Daten. */
	public record Cover(int id, byte[] png) {
	}

	private static volatile Track track = null;
	private static volatile Cover cover = null;
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
			LOGGER.info("Media HUD: kein Windows erkannt ({}), Anzeige bleibt deaktiviert.", os);
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
	 * Gibt den aktuellen Titel zurück oder {@code null}, wenn nichts angezeigt werden soll.
	 * Wird jeden Frame vom Render-Thread aufgerufen und ist daher sehr billig.
	 */
	public static Track getTrack() {
		Track t = track;
		if (t == null || System.currentTimeMillis() - t.receivedAtMs() > STALE_AFTER_MS) {
			return null;
		}
		return t;
	}

	/** Zuletzt empfangenes Cover oder {@code null}. */
	public static Cover getCover() {
		return cover;
	}

	private static void runLoop() {
		boolean loggedFailure = false;
		while (running) {
			try {
				runProcessOnce();
			} catch (Exception e) {
				if (!loggedFailure) {
					LOGGER.warn("Media HUD: Medien-Abfrage fehlgeschlagen, wird später erneut versucht.", e);
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
			try {
				Thread.sleep(RESTART_DELAY_MS);
			} catch (InterruptedException e) {
				break;
			}
		}
	}

	private static void runProcessOnce() throws Exception {
		String encoded = Base64.getEncoder().encodeToString(loadScript().getBytes(StandardCharsets.UTF_16LE));
		ProcessBuilder builder = new ProcessBuilder(
			"powershell.exe",
			"-NoProfile",
			"-NonInteractive",
			"-ExecutionPolicy", "Bypass",
			"-EncodedCommand", encoded);
		builder.redirectError(ProcessBuilder.Redirect.DISCARD);

		Process p = builder.start();
		process = p;
		// Das Skript liest nichts von stdin.
		p.getOutputStream().close();

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while (running && (line = reader.readLine()) != null) {
				handleLine(line, System.currentTimeMillis());
			}
		}
	}

	private static String loadScript() throws IOException {
		try (InputStream in = MediaWatcher.class.getResourceAsStream(SCRIPT_RESOURCE)) {
			if (in == null) {
				throw new IOException("Ressource fehlt: " + SCRIPT_RESOURCE);
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
				// Nichts zu tun, der Prozess ist dann schon weg.
			}
		}
	}

	static void handleLine(String line, long nowMs) {
		String[] parts = line.split("\t", -1);
		if (parts.length == 3 && parts[0].equals("A")) {
			Cover c = parseCover(parts);
			if (c != null) {
				cover = c;
			}
			return;
		}
		// Jede andere Zeile (auch eine leere) ist eine Statusmeldung.
		track = parseTrack(parts, nowMs);
	}

	/** {@code S <Status> <Künstler> <Titel> <PositionMs> <DauerMs> <CoverId>}; sonst {@code null}. */
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

	/** {@code A <CoverId> <PNG als Base64>}; sonst {@code null}. */
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

	/** Entfernt BOM und Steuerzeichen, damit nur normaler Text gerendert wird. */
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
