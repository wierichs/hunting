# Android-Portierungsstatus

Stand der Dokumentation: 2026-11-16. Die Einordnung beschreibt den im
Repository sichtbaren Stand und die bisher dokumentierten Abnahmen; sie ist
keine Aussage über den aktuellen Deployment- oder Installationsstand.
Jagdbruder ergänzt die PWA, ersetzt sie nicht. Die Liste ist keine priorisierte
Roadmap. Frühere Arbeitsschritte und Testergebnisse stehen in der Git-Historie.

## Status

**Umgesetzt** bedeutet implementiert, aber nicht vollständig unter realen
Bedingungen abgenommen. **Angepasst** bezeichnet eine bewusste Abweichung von
der PWA. Einzelne bereits bestätigte Teilprüfungen ersetzen keine vollständige
Live-, Rechte- oder Geräteabnahme.

| Bereich | Stand und Grenzen |
| --- | --- |
| Reviersuche | Angepasst: anonyme Suche, Vorschau, 15 mitgelieferte Quellen und lokal verwaltete eigene GeoJSON-/WFS-Quellen. Keine automatische Bevorzugung offizieller Quellen. Live-Abruf und Profilanlage je Protokoll noch prüfen. |
| Profile und Zusammenarbeit | Umgesetzt: Profile, Rollen, E-Mail-Einladungen, Gastlinks, Anzeigename und lokales Ausblenden geteilter Reviere. Ausblenden wurde manuell bestätigt; Live-Mail-, RPC- und RLS-Abnahme bleibt offen. |
| Grenzen und Dateien | Umgesetzt: Grenzeditor, JWD-/JSON-/GeoJSON-Import als neues Profil oder Merge und JWD-Export. Dateiauswahl und Neu-/Merge-Dialog wurden manuell bestätigt; Schreib-, Rollback- und Rechteprüfung bleibt offen. |
| Markierungen | Umgesetzt: Anlage, Bearbeitung, Löschen und Verschieben abhängig von Rolle und Standort. Markersymbole, Größenwahl und Rollenlegende wurden automatisiert geprüft; echte GPS- und RLS-Abnahme bleibt offen. |
| Karte und Offline | Angepasst: OpenStreetMap, NRW-Luftbilder und Mapsforge statt PWA-PMTiles; lokale öffentliche Grenzen und verschlüsselter Read-only-Revier-Snapshot. Luftbildabdeckung ist nicht deutschlandweit; reale Geräte-, große Kartenpaket- und Flugmodusprüfungen bleiben offen. |
| Konto und Hunde | Umgesetzt: E-Mail-Link oder Zahlencode, Hundeverwaltung und zwei Kopplungswege. QR-Scanner und ein PWA-Gastlink wurden auf Android-Geräten geprüft; echte Mail-, Kopplungs-, Lösch- und RLS-Abnahme bleibt offen. |
| Jagdmodus und Kommunikation | Teilweise umgesetzt: gerätebezogene Zustimmung, Teilnehmerpositionen, DogTracker und experimentelles Ein-Hop-Relay mit automatischer Authentifizierung. Ein Offline-Hund über ein Online-Gateway wurde auf Geräten geprüft. Gegenseitige Positionsanzeige ohne Internet und Mehrhop sind noch nicht umgesetzt; Details stehen im Kommunikationskonzept. |
| Hilfe | Umgesetzt: dieselbe Markdown-Datei wird in der App als Hilfe angezeigt und ist aus der README verlinkt. |

Die additive Serverstufe `location-relay` einschließlich Offline-Erweiterung
wurde laut Nutzer im gemeinsamen Entwicklungsprojekt bereitgestellt und geprüft.
Das belegt keine Bereitstellung in Produktion. Die tatsächliche
Konfiguration und der Migrationsstand des jeweiligen Zielprojekts sind
gesondert zu prüfen. Details: [Kommunikationskonzept](CommunicationConcept.md)
und [Serveranleitung](https://github.com/wierichs/wierichs.github.io/blob/main/Jagdkarte/supabase/location-relay.md).

## Nächster Entwicklungsschritt: lokale Offline-Positionsanzeige

Ziel ist die direkte Anzeige freigegebener Teilnehmer- und Hundepositionen auf
Android, auch wenn beide beteiligten Geräte kein Internet haben. Die Geräte
müssen vorher für dasselbe Revier online eingerichtet worden sein; Kartenpaket
und Revierstand müssen lokal verfügbar sein. Mehrhop ist nicht Teil dieser
Ausbaustufe. Die Schritte 3 und 4 im
[Kommunikationskonzept](CommunicationConcept.md#erforderliche-umsetzung)
beschreiben Nachrichtenformat und Kartenintegration; sie entsprechen zusammen
dem zuletzt vereinbarten nächsten Schritt 4.

Vorhanden sind automatische Peer-Authentifizierung, signierte Verzeichnisse,
Relay-Transport, geschützte Offline-Berechtigungen und Kartenanzeige. Nicht
vorhanden sind ein für berechtigte Smartphones lesbares Positionsformat, dessen
Schlüsselverteilung und ein lokaler Positionsbestand. Neue eigene
Teilnehmerpositionen werden derzeit nur bei Internetzugang erstellt; auch die
Aktivierung der Standortfreigabe benötigt einen Serveraufruf. Die gewünschte
Offline-Sendung muss beides berücksichtigen.

Zuerst Nachrichtenformat, Schlüsselverteilung und -rotation, Absender- und
Revierbindung, Ablauf, Replay-Schutz sowie den Umgang mit Freigabeende und
Positionsalter festlegen. Danach lokale und Serverpositionen nach Identität
und Aktualität zusammenführen und Quelle beziehungsweise Alter erkennbar
machen. Offene Sicherheits- und Abnahmefragen stehen im
[Kommunikationskonzept](CommunicationConcept.md#noch-festzulegen).
Bestehende Serverumschläge und der PWA-Vertrag dürfen nicht stillschweigend
geändert werden. Nötige Supabase-Änderungen ausschließlich im PWA-Projekt
`D:\NoScan\wierichs.github.io\Jagdkarte\` entwickeln; Bereitstellung separat
abstimmen.

Einstiegspunkte sind
[NearbyRelayTransport.kt](app/src/main/java/org/wierichs/dogtracker/communication/NearbyRelayTransport.kt),
[RelayOfflineAuthorization.kt](app/src/main/java/org/wierichs/dogtracker/communication/RelayOfflineAuthorization.kt),
[RelayGateway.kt](app/src/main/java/org/wierichs/dogtracker/communication/RelayGateway.kt),
[LocationTrackingService.kt](app/src/main/java/org/wierichs/dogtracker/LocationTrackingService.kt)
und [JagdkarteScreen.kt](app/src/main/java/org/wierichs/dogtracker/jagdkarte/JagdkarteScreen.kt).
Die bestehende Keystore-Identität in
[RelaySigningIdentity.kt](app/src/main/java/org/wierichs/dogtracker/communication/RelaySigningIdentity.kt)
ist eine Signaturidentität, keine bereits implementierte
Empfänger-Schlüsselverteilung.

Für den zuletzt genutzten Zwei-Geräte-Test konnte das Motorola direkt per ADB
aktualisiert werden; beim Xiaomi wurde die signierte APK in den
Download-Ordner kopiert und manuell installiert. Vor weiteren Gerätetests
Geräteverfügbarkeit und Zustimmung prüfen, die Signatur mit den installierten
Apps vergleichen und keine vorhandenen Download-Dateien überschreiben.
Installationen nicht deinstallieren, damit Kopplungen und lokale Daten
erhalten bleiben. Nach einer Änderung des gespeicherten
Berechtigungsformats sowie nach einem Geräteneustart müssen die Geräte ihre
Offline-Berechtigungen zunächst online erneuern.

## Offene Abnahme

Nur ein isoliertes Supabase-Testprojekt, Testkonten und Testdaten verwenden;
keine produktiven oder fremden Profile verändern. Ergebnisse und getestete
Rollen dokumentieren.

### Backend, Rollen und Dateien

- Anmeldung mit echter Test-Mail und beiden Rückgabewegen prüfen; Einladungen
  für bereits angemeldete Konten und Gastlinks mit echten Rollen testen.
- Owner-, Bearbeiter- und Gastrechte sowie RLS für Profile, Markierungen,
  Grenzbearbeitung, Einladungen und Hundelöschung einschließlich Cascade prüfen.
- Beide Hundekopplungswege mit echten RPC-Rechten und zwei Geräten prüfen:
  Vormerken, Bestätigen, Abbruch, ausstehenden Ersatz, Wiederholung und
  Statusanzeige. Zuvor den Migrationsstand des Testprojekts feststellen.
- Neuimport und Merge von JWD, JSON und GeoJSON einschließlich Löchern,
  Mehrfach-Features, Duplikaten, Fehlern und Rollback prüfen; JWD-Export und
  Reimport auch mit PWA beziehungsweise anderer unterstützter Software prüfen.
- Live-Suchtreffer je Quellprotokoll sowie eigene Quellen, Geometrie,
  Quellenangaben, Grenzen und Profilanlage prüfen.

### Karte, Offline und Geräte

- Auf realen Geräten Erststart, Zustimmung, Berechtigungsentzug, Konto- und
  Revierwechsel, Standortanzeige, Grenzeditor und Marker prüfen.
- NRW-Luftbilder einschließlich Randabdeckung und Quellenangabe prüfen.
  Weitere Regionen sind nicht angebunden.
- Mapsforge-Pakete und Extraktgrenzen, gemeinsame Abdeckung mehrerer Pakete,
  große Dateien, knappen Speicher und Fehlerfälle auf realen Geräten prüfen.
- Flugmodus, Neustart und Revierwechsel mit Offlinekarte, gespeicherten
  öffentlichen Grenzen und Read-only-Snapshot prüfen; kein stiller
  Online-Fallback und keine veralteten Livepositionen.
- Teilnehmer- und Hundestandorte, Bewegungsrichtung, DogTracker-Outbox und
  Relay mit mindestens zwei physischen Geräten prüfen: automatische Authentifizierung,
  Funkabbrüche, Wiederholungen, Widerruf und Serverbestätigung.

## Bekannte technische Grenzen

- Ohne bereitgestelltes `location-relay` kann die neue Teilnehmer-Relayfreigabe
  nicht erfolgreich eingerichtet werden. Ältere DogTracker-APKs unterstützen
  neue Rückbestätigungen nicht.
- Ein Gerätetest bestätigte die automatische Weiterleitung vom offline
  befindlichen DogTracker über ein Online-Gateway, auch nach einem Neustart
  des Gateways. Weitere Unterbrechungen und Szenarien sind noch nicht
  abgenommen. Geschützte Offline-Berechtigungen sind nun zusätzlich an die
  monotone Zeit und den Bootzähler gebunden; nach einem Geräteneustart ist
  eine Online-Erneuerung nötig. Der manuelle Prozesswiederanlauf des Gateways
  ohne Internet und die Sperre nach Offline-Geräteneustart wurden auf zwei
  Geräten geprüft; der automatische Android-Prozesswiederanlauf bleibt offen.
- Die Begrenzung wiederholter Funkübertragungen und die Verarbeitung
  signierter Serverbestätigungen sind durch gezielte Tests geprüft; ihre
  Funk- und Akkubelastung muss noch auf Geräten gemessen werden. Gespeicherte
  Quittungen werden nach einer neuen Verbindung zunächst erneut angeboten;
  die Empfangsbestätigung unterdrückt Wiederholungen nur je Sitzung.
- Lokal empfangene Relay-Umschläge sind für den Server verschlüsselt und
  ermöglichen noch keine gegenseitige Offline-Positionsanzeige.
- Teilnehmer-GPS läuft nur bei aktiver Karte; das Relay kann sichtbar im
  Hintergrund weiterarbeiten. Es gibt keine Zustell- oder Echtzeitgarantie.
- Die Grenzbearbeitung erkennt bereits gespeicherte fremde Änderungen vor dem
  Schreiben, hat aber keine atomare serverseitige Versionsprüfung.
- Die lokale Kartenkamera wird in SharedPreferences gespeichert. Ob diese
  Position als sensibel gilt und zusätzlichen Schutz benötigt, ist offen.
- Bestehende Android-Lint- und Abhängigkeitswarnungen sind bei Gelegenheit
  hinsichtlich tatsächlicher Auswirkungen zu bewerten.
