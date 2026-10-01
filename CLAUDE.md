# Projektregeln

- **Öffentliches Repo:** Keine persönlichen Daten (Namen, E-Mails, Pfade, Benutzernamen o. Ä.)
  in Code, Commits, Issues, PRs oder Releases. So anonym wie möglich bleiben.
- **Einfach und defensiv:** Keine unnötigen Features. Nur APIs nutzen, deren Verhalten sicher
  bekannt ist. Fehler dürfen nie das Spiel beeinträchtigen.
- **Versionierung:** `mod_version` in `gradle.properties`. Jeder in `main` gemergte Pull Request
  erhöht den Patch-Teil automatisch um 1 (Workflow `Release`). Major/Minor nur ändern, wenn
  der Nutzer es ausdrücklich sagt.
- **Ziel:** NeoForge, Minecraft 1.21.1, Java 21.
