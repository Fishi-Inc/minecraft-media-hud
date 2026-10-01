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
 * Sekunde eine Zeile "Künstler<TAB>Titel" ausgibt. Läuft nichts, kommt eine leere Zeile.
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

	/** Unveränderlicher Zustand, damit Render-Thread und Lese-Thread nie halbe Daten sehen. */
	private record Snapshot(String text, long receivedAtMs) {
	}

	private static volatile Snapshot snapshot = new Snapshot(null, 0);
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
	 * Gibt den anzuzeigenden Text zurück oder {@code null}, wenn nichts angezeigt werden soll.
	 * Wird jeden Frame vom Render-Thread aufgerufen und ist daher sehr billig.
	 */
	public static String getCurrentText() {
		Snapshot s = snapshot;
		if (s.text() == null) {
			return null;
		}
		if (System.currentTimeMillis() - s.receivedAtMs() > STALE_AFTER_MS) {
			return null;
		}
		return s.text();
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
				snapshot = new Snapshot(null, 0);
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
				snapshot = new Snapshot(parseLine(line), System.currentTimeMillis());
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

	/** "Künstler<TAB>Titel" -> "Künstler - Titel". Ohne Titel wird nichts angezeigt. */
	static String parseLine(String line) {
		if (line == null) {
			return null;
		}
		String artist = "";
		String title;
		int tab = line.indexOf('\t');
		if (tab >= 0) {
			artist = clean(line.substring(0, tab));
			title = clean(line.substring(tab + 1));
		} else {
			title = clean(line);
		}
		if (title.isEmpty()) {
			return null;
		}
		String text = artist.isEmpty() ? title : artist + " - " + title;
		if (text.length() > MAX_TEXT_LENGTH) {
			text = text.substring(0, MAX_TEXT_LENGTH - 3) + "...";
		}
		return text;
	}

	/** Entfernt BOM und Steuerzeichen, damit nur normaler Text gerendert wird. */
	private static String clean(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '﻿' || Character.isISOControl(c)) {
				continue;
			}
			sb.append(c);
		}
		return sb.toString().trim();
	}
}
