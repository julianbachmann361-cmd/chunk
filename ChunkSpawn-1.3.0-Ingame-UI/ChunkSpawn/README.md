# ChunkSpawn 1.1.0

Paper/Bukkit plugin using Java 17, Vault and an Economy provider (for example EssentialsX Economy).

## Build
Run `mvn package` in this folder. Output: `target/ChunkSpawn-1.1.0.jar`.

## Install
1. Install Paper 1.20.4 or a compatible version, Vault and an economy plugin.
2. Copy `ChunkSpawn-1.1.0.jar` to `plugins/`.
3. Start the server once to create `plugins/ChunkSpawn/config.yml`.

## Commands
- `/chunk info`
- `/chunk home`
- `/chunk upgrade [norden|osten|sueden|westen]`
- `/chunk trust <Spieler>`
- `/chunk connect allow <Spieler>`
- `/chunk connect remove <Spieler>`
- `/chunk reload` (admin)

Prices start at $5,000 and increase by 25% for each successful upgrade. Plot data, levels, trusted players and connection permissions are saved in `plugins/ChunkSpawn/claims.yml`.

Note: connect commands store/revoke explicit connection permission. They do not transfer ownership or permit claiming another player's chunks. Upgrade expansion only claims unowned adjacent chunks.


## Version 1.2.0 Änderungen
- Upgrade-Startpreis: 5.000 $
- Preissteigerung: 8,5 % pro Upgrade (Multiplikator 1.085)
- Upgrade-Level und Grundstücke werden in claims.yml gespeichert.
- Verbindungsfreigaben lassen sich mit `/chunk connect allow <Spieler>`, `/chunk connect deny <Spieler>` und `/chunk connect remove <Spieler>` verwalten.
- Hinweis: Die Verbindungsfreigabe wird gespeichert, aber diese Version erstellt noch keine physischen Brücken-Chunks und verschmilzt keine Grundstücke.


## Version 1.3.0 – Ingame-Menü
- `/chunk menu` öffnet ein pixelartiges Inventar-Menü.
- Die Chunk-Karte zeigt eigene Claims (grün), fremde Claims (rot), direkt erweiterbare Chunks (gelb) und sonstige freie Flächen (grau).
- Klick auf einen gelben Chunk kauft genau diesen angrenzenden Chunk; die Upgrade-Kosten folgen der konfigurierten Preissteigerung.
- Das Upgrade-Menü und die automatische Erweiterung bleiben verfügbar.
- Unter „Verbindungen“ lassen sich Freigaben für online Spieler per Klick erteilen oder widerrufen. Diese Freigaben werden gespeichert; sie bauen keine physischen Brücken und verschmelzen keine Grundstücke.
- Die Karte ist eine schematische Inventaransicht der gespeicherten Chunk-Claims, keine Weltkarte.
