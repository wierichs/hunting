# Offline-Positionsnachrichten

Implementierter Stand vom 18.11.2026. Dieser direkte Nearby-Austausch
ergänzt die nur für den Server lesbaren Relay-Umschläge aus dem
[Kommunikationskonzept](../../../CommunicationConcept.md). Er ersetzt
weder den Server-Upload noch die Hundekopplung.

## Nachrichtenformat und Empfang

[`OfflinePositionMessage.kt`](../../../app/src/main/java/org/wierichs/dogtracker/communication/OfflinePositionMessage.kt)
ist die Referenz für das UTF-8-JSON-Format `offline_position` (Version 1,
maximal 8192 Byte).

| Felder | Bedeutung |
| --- | --- |
| `profile_id`, `sender_device_id` | Revier und sendendes Gerät |
| `subject_type`, `subject_id` | `participant` oder `dog` und zugehörige Benutzer- bzw. Hunde-ID |
| `sequence`, `recorded_at_ms` | Sequenz als Dezimalstring und Messzeit als Unix-Zeit in Millisekunden |
| `latitude`, `longitude`, `accuracy_m` | Messung und Genauigkeit |
| `signature` | RSA-Signatur als Base64url ohne Padding |

Die Signatur bindet die Felder unter `jagdbruder-offline-position-v1`
aneinander. Nearby verschlüsselt den Transport; es gibt keinen gemeinsamen
Revierschlüssel. Beide Geräte müssen zuvor für dasselbe Revier online
eingerichtet worden sein und gültige, einander enthaltende Verzeichnisse
besitzen. Nur das authentifizierte Originalgerät darf seine Positionsmeldung
übermitteln. Der Empfänger prüft Revier, Absendergerät, Subjekttyp, Subjekt-ID
und Signatur gegen das serverseitig signierte Offline-Verzeichnis. Ein
Gerätename und die Vertrauensannahme zwischen Reviermitgliedern ersetzen
diese Prüfung nicht. RLS autorisiert keine Offline-Nachrichten.

Das PWA-Projekt liefert `subject_id` über die Migration
`202611180001_offline_position_subjects.sql` und `location-relay`
(`offline_directory`). Zuerst die Migration bereitstellen, dann die Edge
Function aktualisieren und die Verzeichnisse online erneuern. Alte
Verzeichnisse ohne `subject_id` unterstützen die bisherige Serverweitergabe,
aber keine direkte Positionsübertragung.

## Lokale Speicherung und Anzeige

Der Empfänger speichert den letzten Stand je Konto, Revier und Absendergerät
verschlüsselt mit Android Keystore und AES-GCM; die Datenbank ist von Backup
und Gerätetransfer ausgeschlossen. Duplikate und ältere Positionen
überschreiben den jüngsten Stand nicht. Die lokale Empfangsquittung ist
keine Serverbestätigung.

Die Karte zeigt je Teilnehmer bzw. Hund einen Marker. Bei gleich neuer
oder neuerer nutzbarer Servermessung hat diese Vorrang; andernfalls zeigt
die Karte die Nearby-Position mit Quelle und Messzeit. Sie erscheint ab
120 Sekunden als „veraltet“ und bleibt bis zum bekannten Jagdmodusende
sichtbar. Sobald dieses bekannt wird, löscht die App den lokalen Stand
des Reviers und blockiert nachlaufende Meldungen. Der Jagdmodus wird
serverseitig gesteuert; Nearby übermittelt seinen Status derzeit nicht.
Die Eigenposition über das Fadenkreuz ist davon unabhängig.

## Bestätigte Feldtests

Am 18.11.2026 auf Motorola und Xiaomi:

- **Teilnehmer:** Beide Geräte waren ohne Internet. Beide zeigten die
  Position des jeweils anderen mit „Per Nearby empfangen“ und einer
  Messzeit nach der Netztrennung. Das Fadenkreuz zeigte auf beiden den
  eigenen Standort, beim Xiaomi mit Verzögerung.
- **Hund:** Ein Offline-DogTracker lieferte eine frische Position per
  Nearby an die Jagdkarte, zunächst an ein Online-Gerät und anschließend
  auch bei beiden Geräten ohne Internet. Der erste Versuch mit noch
  aktiven mobilen Daten war kein Offline-Nachweis. Ein aktiver Tracker
  oder eine gültige Nearby-Berechtigung allein belegt weder einen GPS-Fix
  noch den Empfang einer Position.
- **Veraltung und Jagdende:** Nach Abschalten der Standortfreigabe auf dem
  Xiaomi blieb dessen Marker auf dem offline betriebenen Motorola sichtbar
  und wurde ab 120 Sekunden als „veraltet“ angezeigt. Das anschließende
  online ausgelöste Jagdende änderte die Offline-Anzeige zunächst nicht.
  Nach Wiederverbindung und automatischem Abruf des Revierstatus verschwand
  der Marker ohne App-Neustart.

Ein belastbarer Offline-Nachweis erfordert auf dem Empfänger die Quelle
„Per Nearby empfangen“ und eine Messzeit nach der Netztrennung. Für diesen
Aufbau bleiben WLAN und Bluetooth eingeschaltet, aber beide Geräte ohne
Internetzugang. Offene Abnahmen stehen in den
[Testaufgaben](../../testing/Testaufgaben.md).
