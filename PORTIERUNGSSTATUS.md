# Android-Portierungsstatus

Stand: 18.11.2026. Jagdbruder ergänzt die Jagdkarte-PWA und nutzt dasselbe
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
| Jagdmodus und Kommunikation | Gerätezustimmung, DogTracker, Ein-Hop-Weitergabe zum Server und direkter Nearby-Austausch. Offline-Anzeige von Teilnehmern, Hund und Eigenposition sowie automatische Online-Wiederverbindung wurden im Grundablauf auf Motorola und Xiaomi bestätigt. Kein Mehrhop. |
| Hilfe | Dieselbe [Markdown-Hilfe](docs/help/hilfe.md) steht in der App auch offline zur Verfügung. |

## Zuletzt bestätigte Android-Änderungen

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

**Eigene Hunde:** Angemeldete Reviermitglieder können eigene Hunde
registrieren und koppeln. Die Karte kennzeichnet eigene Hunde türkis.
Nur das registrierende Konto kann das gekoppelte Gerät direkt per Nearby
klingeln oder vibrieren lassen. Die neue Migration und der Gerätetest
stehen noch aus.

Die `location-relay`-Serverstufe einschließlich der Offline-Erweiterung
wurde laut Nutzer im gemeinsamen Entwicklungsprojekt bereitgestellt und
geprüft. Daraus folgt keine Bereitstellung in Produktion. Supabase-Änderungen
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

## Bereits vorgemerkte Entwicklungsoptionen

Diese Punkte sind noch nicht priorisiert und keine Zusage für die nächste Version.

- Serverbestätigtes Jagdende an erreichbare Offline-Geräte über Nearby weitergeben.
- Eigenen Offline-GPS-Start verbessern: Standortsuche früher starten oder den
  letzten eigenen Fix mit Messzeit anzeigen, ohne ihn als frisch zu versenden.
  Siehe [Kommunikationskonzept](CommunicationConcept.md#mögliche-verbesserung-für-eine-folgeversion-erster-standort-ohne-internet).
- Mehrhop und zusätzliche Transportadapter bleiben optionale Erweiterungen.
