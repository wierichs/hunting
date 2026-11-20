# Android-Portierungsstatus

Stand: 20.11.2026. Jagdbruder ergänzt die Jagdkarte-PWA und nutzt dasselbe
Supabase-Backend. „Umgesetzt“ bedeutet nicht vollständig unter realen
Bedingungen abgenommen. Offene Prüfungen stehen ausschließlich in den
[Testaufgaben](docs/testing/Testaufgaben.md).

| Bereich | Umsetzungsstand |
| --- | --- |
| Reviersuche | Anonyme Suche und Vorschau, 15 mitgelieferte Quellen sowie eigene lokale GeoJSON-/WFS-Quellen. Keine automatische Bevorzugung offizieller Quellen. |
| Profile und Zusammenarbeit | Eigene und geteilte Profile, Rollen, E-Mail-Einladungen, Gastlinks, Anzeigename und lokales Ausblenden geteilter Reviere. Das Ausblenden wurde manuell bestätigt. |
| Grenzen und Dateien | Grenzeditor, JWD-/JSON-/GeoJSON-Import als neues Profil oder Merge und JWD-Export. Dateiauswahl und Neu-/Merge-Dialog wurden manuell bestätigt. |
| Markierungen | Anlage, Bearbeitung, Löschen und Verschieben abhängig von Rolle und Standort. Symbole, Größenwahl und Rollenlegende wurden automatisiert geprüft. |
| Karten | OpenStreetMap, NRW-Luftbilder und Mapsforge statt PWA-PMTiles; lokale öffentliche Grenzen und verschlüsselter Revier-Snapshot. Kein stiller Wechsel von Offlinekarten zu Onlinekarten. |
| Konto und Hunde | E-Mail-Link oder Zahlencode, Hundeverwaltung und zwei Kopplungswege. QR-Scanner und ein PWA-Gastlink wurden auf Android-Geräten geprüft. |
| Jagdmodus und Kommunikation | Gerätezustimmung, DogTracker, Ein-Hop-Weitergabe zum Server und direkter Nearby-Austausch. Das serverbestätigte Jagdende kann über berechtigte Teilnehmergeräte weitergegeben werden; allgemeines Mehrhop-Routing für Positionen gibt es nicht. |
| Hilfe | Dieselbe [Markdown-Hilfe](docs/help/hilfe.md) steht in der App auch offline zur Verfügung. |

## Neuere Android-Änderungen

**Profil um eigenen Standort:** Angemeldete Nutzer können unter Revier und
Verwaltung mit einem Namen ein Profil mit 100 m Radius um einen aktuellen
GPS-Fix anlegen. Es verwendet die vorhandenen Import-Schnittstellen und
bleibt nachträglich im Grenzeditor bearbeitbar. Die Fläche ist keine
amtliche Reviergrenze. Der Gerätetest steht noch aus.

**Öffentliche Reviersuche:** Suchergebnisse werden direkt geladen und gefiltert.
Korrigierbare ungültige Geometrien werden repariert und als solche gekennzeichnet;
vor der Übernahme in ein eigenes Revier ist eine Bestätigung erforderlich.

**Jagdende und automatischer Neustart:** Das serverbestätigte Jagdende wurde
direkt vom online verbundenen Motorola an das offline befindliche Xiaomi
übertragen. Bei geöffneter Karte prüft die App einen ausgeschalteten Jagdmodus
online etwa alle 30 Sekunden und lädt bei einem Neustart den vollständigen
Revierstand. Im Zweigerätetest erfolgte die Anzeige nach etwa 16 Sekunden.
Ein neuer Jagdstart wird nicht offline über Nearby übertragen.

- **Bewegungsrichtung:** Eine blaue Pfeilspitze sitzt direkt am Teilnehmer-
  oder Hundemarker. Der Zeichentest auf dem Motorola bestätigte die
  Darstellung in allen vier Himmelsrichtungen mit vorgegebenen Richtungen.
- **Schnellere Wiederverbindung:** Nach Wiedereinschalten der mobilen Daten
  zeigte das Motorola nach **7 Sekunden** einen neuen Serverstand auf der
  Jagdkarte und nach **ca. 17 Sekunden** die nächste DogTracker-Bestätigung
  bei einem Standortintervall von 30 Sekunden. Das sind Einzelmessungen,
  keine garantierten Zeiten. Beim DogTracker wurde die GPS-Wartezeit nicht
  separat gemessen. Ablauf und Messbedingungen stehen im
  [Kommunikationskonzept](CommunicationConcept.md#rückkehr-der-internetverbindung).

**Nearby-Rundnachrichten:** Der direkte Offline-Versand und die
Empfangsbestätigung wurden auf Motorola und Xiaomi bestätigt. Owner und
Bearbeiter senden nur an aktuell verbundene Teilnehmergeräte, ohne
Nachlieferung. Der neue Android-Benachrichtigungskanal ermöglicht
individuelle Ton- und Vibrationseinstellungen.

**Früherer Offline-GPS-Start:** Bei aktiver Jagdteilnahme fordert die Jagdkarte
den ersten eigenen Fix parallel zum Relay-Start an und verwendet ihn nur,
solange er höchstens 30 Sekunden alt ist. Ein letzter eigener Fix wird nicht
separat angezeigt. Der Zeitgewinn ist noch nicht auf Geräten gemessen.

**Eigene Hunde:** Angemeldete Reviermitglieder können eigene Hunde
registrieren und koppeln. Die Karte kennzeichnet eigene Hunde türkis.
Nur das registrierende Konto kann das gekoppelte Gerät direkt per Nearby
klingeln oder vibrieren lassen. Die neue Migration und der Gerätetest
stehen noch aus.

Die `location-relay`-Serverstufe einschließlich der Offline-Erweiterung
wurde laut Nutzer im gemeinsamen Entwicklungsprojekt bereitgestellt und
geprüft. Die Migration für das serverbestätigte Jagdende und die aktualisierte
Edge Function wurden laut Nutzer dort ebenfalls bereitgestellt. Daraus folgt
keine Bereitstellung in Produktion. Supabase-Änderungen
gehören ausschließlich ins PWA-Projekt und werden separat bereitgestellt.
Siehe [Serveranleitung](https://github.com/wierichs/wierichs.github.io/blob/main/Jagdkarte/supabase/location-relay.md).

## Bekannte technische Grenzen

- Teilnehmer-GPS läuft nur bei aktiver Karte. Das Relais kann als sichtbarer
  Dienst weiterlaufen. Es gibt keine Echtzeit-, Reichweiten- oder Zustellgarantie.
- Nearby braucht zuvor online eingerichtete Reviergeräte. Das Offline-Verzeichnis
  gilt höchstens 24 Stunden; nach vollständigem Geräteneustart muss es online
  erneuert werden. Ältere DogTracker-APKs unterstützen neuere Rückbestätigungen
  nicht. Details und bestätigte Abläufe stehen im
  [Kommunikationskonzept](CommunicationConcept.md).
- Der Grenzeditor erkennt bereits gespeicherte fremde Änderungen vor dem
  Schreiben, hat aber keine atomare serverseitige Versionsprüfung. Import und
  Merge bestehen aus mehreren Serveraufrufen; Aufräumen ist kein atomarer Rollback.
- Die lokale Kartenkamera liegt in SharedPreferences. Ob sie zusätzlichen
  Schutz benötigt, ist noch zu entscheiden.
- Eine bei einem Offline-Neustartversuch beobachtete Relais-Authentifizierungs-
  meldung ist nicht abschließend eingegrenzt. Nach erneuter Internetverbindung
  funktionierten Relais und Positionen automatisch wieder.

## Bereits vorgemerkte Entwicklungsoptionen

Diese Punkte sind noch nicht priorisiert und keine Zusage für die nächste Version.

- Letzten eigenen GPS-Fix lokal mit Messzeit anzeigen, ohne ihn als frische
  Position zu versenden.
- Schutz der lokal gespeicherten Kartenkamera prüfen und gegebenenfalls verbessern.
- Allgemeines Mehrhop-Routing und zusätzliche Transportadapter bleiben optionale
  Erweiterungen; ein möglicher Serverbedarf hängt vom gewählten Konzept ab.
