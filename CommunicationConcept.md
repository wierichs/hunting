# Kommunikationskonzept

## Zweck und Geltungsbereich

Jagdbruder überträgt Hunde- und Teilnehmerpositionen an das gemeinsame
Jagdkarte-Backend. Ein erreichbares, berechtigtes Gerät kann verschlüsselte
Meldungen als Relais übernehmen und bei Internetverbindung weiterleiten.
Das verbessert die Zustellchance bei Funklöchern, garantiert aber weder
Echtzeit noch Reichweite oder Zustellung. Die PWA und der bisherige
Hundetracker-Vertrag bleiben eigenständig und müssen kompatibel bleiben.

Dieses Dokument beschreibt die implementierten Übertragungswege und ihre Grenzen.
Bereitstellung und Serververtrag sind in der
[Relay-Serveranleitung](https://github.com/wierichs/wierichs.github.io/blob/main/Jagdkarte/supabase/location-relay.md)
beschrieben; offene Abnahmen stehen in den [Testaufgaben](docs/testing/Testaufgaben.md).

## Kommunikation im Funkloch

- Teilnehmer und Hundegeräte können nach vorheriger Online-Einrichtung
  desselben Reviers und regelmäßiger Erneuerung der Berechtigung auch ohne
  Internet automatisch lokale Verbindungen herstellen, ohne Kopplungsdialog
  bei jeder Begegnung.
  Android-Berechtigungen und die ausdrückliche Zustimmung zur Standortfreigabe
  und zum Relaisbetrieb bleiben erforderlich. Die Hundegerät-Einrichtung für
  einen konkreten Hund bleibt von der automatischen Relaisverbindung getrennt.
- Teilnehmer können freigegebene Teilnehmer- und Hundepositionen auch
  dann auf ihrer Karte sehen können, wenn weder Sender noch Empfänger Internet
  haben. Kartenpakete und benötigte Revierdaten müssen vorher offline
  verfügbar sein.
- Ein Gerät mit Internet kann geeignete Relay-Umschläge an das Backend
  weiterleiten, damit sie auch über Jagdkarte und PWA verfügbar werden.
  Offline-Nachbarn sind nicht selbst mit der PWA oder dem Server verbunden.
- Übliche Abstände von 100 bis 300 Metern sind eine Anforderung für die
  Erprobung, keine zugesicherte Funkreichweite. Gelände, Bewuchs, Position des
  Smartphones und Gerätehardware beeinflussen die Verbindung erheblich.

Als lokaler Transport bleibt zunächst Google Nearby Connections vorgesehen.
Nearby kombiniert Bluetooth, BLE und WLAN selbst und wählt den Funkweg; die
App kann keine bestimmte Kombination oder Reichweite zusagen. Ein eigener
Wi-Fi-Aware-Transport und eine separate BLE-Erkennung werden vorerst nicht
verfolgt. Reichweite, Entdeckungszeit, Zuverlässigkeit und Akkuverbrauch
müssen auf tatsächlichen Geräten im Revier gemessen werden.

## Vorhandener Stand und Grenzen

Die App besitzt einen transportneutralen Nachrichtenkern mit SQLite-Outbox,
HTTPS-Transport, Android-Keystore-Signaturidentität und Relay-Umschlägen.
Google Nearby Connections mit P2P-Cluster-Strategie stellt die lokalen
Verbindungen her. Die gemeinsame Dienstkennung lautet
`org.wierichs.dogtracker`. Die automatische gegenseitige Authentifizierung
mit signiertem Revierverzeichnis und verbindungsbezogenem Nearby-Token ist
implementiert und in einem Geräteszenario geprüft, aber noch nicht umfassend
abgenommen. Es ist keine manuelle Verbindungsbestätigung erforderlich. Ältere
Versionen ohne das neue Verfahren sollen nicht verbunden werden.
Ein einzelner Relay-Hop ist vorgesehen; Mehrhop-Weitergabe ist nicht aktiviert.

Eigene Nachrichten werden direkt per HTTPS oder über ein zugelassenes Relais
an den Server übertragen. Das Relais speichert übernommene Umschläge und lädt
sie mit seiner eigenen Berechtigung hoch. Lokale Funkübergabe bedeutet nicht,
dass der Server die Nachricht bereits angenommen hat. Teilnehmer- und
Hundepositionen haben getrennte Identitäten; ein Benutzergerät ist kein
Hundegerät und benötigt für Teilnehmer-Relay keine Hundekopplung.

Der Ursprung verschlüsselt Relay-Nutzdaten mit AES-256-GCM und RSA-OAEP/SHA-256
für den Server und signiert unveränderliche Metadaten mit
RSASSA-PKCS1-v1_5/SHA-256. Der private Signaturschlüssel bleibt im Android
Keystore. Benutzer-JWTs und Hundegeräte-Bearer-Token werden nicht an Relais
weitergegeben. Die Funkverbindung allein begründet keine Revierberechtigung.
Da diese Umschläge für den Server verschlüsselt sind, dienen sie nicht
der lokalen Kartenanzeige. Diese nutzt stattdessen eigene signierte
[Nearby-Positionsnachrichten](docs/design/offline-positions/OfflinePositionMessageFormat.md).
Der lesbare Revier-Snapshot selbst enthält keine Positionskoordinaten.

### Hundetracker

Die bestehende direkte `dog-location`-Schnittstelle bleibt erhalten; für
weitergeleitete Hundeumschläge gibt es weiterhin `dog-location-relay`. Dessen
Serverpfad prüft unter anderem Signatur, Revierzugehörigkeit, Ablauf und
Positionswerte. Der aktuelle DogTracker-Arbeitsstand lädt empfangene Hunde-
und Teilnehmerumschläge über die gemeinsame `location-relay`-Serverstufe
hoch und prüft deren signierte, meldungsgebundene Bestätigung.
Die vorhandene RPC speichert den letzten Stand und verhindert, dass gleiche
oder ältere Zeitstempel ihn überschreiben. Für diesen bisherigen Pfad gibt es
kein separates Nachrichten-ID-Ledger. Eigene Hundeumschläge bleiben bis zum
Ablauf oder bis zu einer geprüften, exakt passenden Serverbestätigung für
Nearby verfügbar; das Ursprungsgerät lädt seine Position direkt über
`dog-location` hoch, nicht über den Relay-Endpunkt.

Neue Umschläge werden bei einer authentifizierten Verbindung sofort angeboten.
Unbestätigte Pakete werden pro Nachbar frühestens nach 30 Sekunden erneut
versucht; bei einer neuen Verbindung beginnt die Übertragung erneut. Pro
Durchlauf werden höchstens 50 Pakete angeboten, wobei noch nicht gesendete
Pakete Vorrang erhalten. Die temporäre Empfangsbestätigung einer signierten
Serverquittung unterdrückt nur deren erneute Funkübertragung an denselben
Nachbarn während der aktuellen Sitzung. Sie ist keine Serverbestätigung und
löscht keine Nachrichten oder gespeicherten Quittungen. Geht sie verloren,
wird die Quittung erneut angeboten. Auch bei einer neuen Verbindung werden
gespeicherte Quittungen zunächst erneut angeboten.

Der DogTracker erfasst Positionen nur nach ausdrücklich gestartetem Tracking
über einen sichtbaren Vordergrunddienst. Bei fehlender Verbindung bleibt für
den direkten Upload nur der neueste GPS-Fix bis zu 24 Stunden in der lokalen
Outbox; Relay-Nachrichten haben eigene Ablauf- und Bestätigungsregeln.
GPS-Nutzdaten sind lokal verschlüsselt, Zeit- und Warteschlangenmetadaten
bleiben lesbar. Die Outbox ist vom Android-Backup ausgeschlossen.

### Teilnehmer und gemeinsame Relay-Serverstufe

Die additive `location-relay`-Serverstufe registriert und widerruft
Benutzergeräte pro Revier. Registrierung und Erneuerung benötigen Internet.
Die Offline-Erweiterung stellt signierte Verzeichnisse für höchstens
24 Stunden bereit; die App erneuert sie bei Internetzugang. Widerrufe
können offline bis zum Ablauf verzögert wirken, und der Server prüft beim
Upload erneut. Die Serverstufe ergänzt Nachrichten-ID-Ledger, monotone
Sequenzen, Mitgliedschafts- und Widerrufsprüfung sowie signierte
Serverbestätigungen. Standortentscheidung und Annahme der Nachrichten-ID
werden atomar gespeichert. Die App prüft die Bestätigung, bevor sie den
betreffenden Outbox-Eintrag atomar ersetzt.

Die gerätebezogene Einrichtung erklärt und erlaubt eigene Standortfreigabe und
Relaisbetrieb gemeinsam. Android-Berechtigungen allein sind keine Zustimmung.
Im aktiven Jagdmodus des ausgewählten Reviers sind vollständige Berechtigungen
und eine gültige, zuvor online eingerichtete Autorisierung erforderlich.
Teilnehmerpositionen werden nur bei aktiver Karte erfasst. Ihre
Server-Relay-Umschläge gelten höchstens 90 Sekunden ab Messung.
Beim Pausieren der Karte kann das sichtbare Relay-Gateway im
Hintergrund weiterarbeiten; Hintergrund-GPS für Teilnehmer ist nicht
implementiert. Beim Start des DogTrackers wird der Relaisbetrieb übergeben;
die Teilnehmerposition wird dabei nicht parallel geteilt.

Bei einem unerwarteten Dienstende bleiben Gerätekennung und gespeicherte
Fremdmeldungen erhalten. Nach einem Prozessende kann das Gateway mit einem
verschlüsselt gespeicherten, konto- und reviergebundenen Revierstand und
Verzeichnis auch ohne Internet wieder anlaufen. Die Berechtigung ist an den
Android-Bootzähler und die seit Beginn der Online-Abfrage vergangene monotone
Zeit gebunden; eine zurückgestellte Systemzeit verlängert sie nicht. Nach einem
Geräteneustart oder bei fehlendem Bootzähler pausiert das Relais bis zur
Online-Erneuerung. Bereits gespeicherte Berechtigungen älterer App-Versionen
werden nicht übernommen; die neue Version muss zunächst online eingerichtet
werden. Mit gültiger Berechtigung und aktivem Jagdmodus kann die Karte
eigene Teilnehmerpositionen auch offline an erreichbare Teilnehmergeräte senden.

Bei ausdrücklichem Beenden oder Verlust der
Berechtigung wird die Registrierung weiterhin widerrufen. Nach einem Widerruf
kann eine neue Gerätekennung entstehen, die offline befindliche Nachbarn erst
nach einer Verzeichniserneuerung kennen. Fehlgeschlagene Nearby-Starts werden
automatisch erneut versucht. Im Gerätetest nahm das nach einem Prozessende
offline wieder geöffnete Gateway neue Hundemeldungen an; nach Wiederherstellung
des Internetzugangs wurden die ausstehenden Meldungen serverbestätigt.
Nach vollständigem Offline-Geräteneustart pausierten Gateway und DogTracker
jeweils bis zur Online-Erneuerung.

Die Offline-Erweiterung wurde laut Nutzer im gemeinsamen Entwicklungsprojekt
bereitgestellt. Ein Gerätetest bestätigte die Weiterleitung vom offline
befindlichen DogTracker über ein online befindliches Teilnehmergerät zum Server.
Das belegt keine Bereitstellung in anderen Zielprojekten.
Ältere DogTracker-APKs unterstützen die neueren Rückbestätigungen nicht.

## Direkte Offline-Anzeige und Jagdende

Positionen von erreichbaren Geräten werden auf der Karte getrennt vom
Server-Relay verarbeitet. Die Karte kennzeichnet Nearby-Positionen ab
120 Sekunden als veraltet, lässt sie aber bis zum bekannten Ende des
Jagdmodus sichtbar. Das Abschalten der eigenen Standortfreigabe stoppt
neue Positionen, beendet aber nicht den Jagdmodus des Reviers.

Nur ein Online-Gerät kann den Jagdmodus über den Server ändern. Ein
Offline-Gerät erfährt das Ende erst beim erneuten Online-Abruf und
blendet dann die Positionen aus. Nearby überträgt den Status derzeit nicht.
Format, Speicher und bestätigte Gerätetests sind bei den
[Offline-Positionsnachrichten](docs/design/offline-positions/OfflinePositionMessageFormat.md)
dokumentiert. Supabase-Änderungen gehören ausschließlich ins PWA-Projekt;
Bereitstellungen und Geräteinstallationen separat abstimmen.

Android 12 / API 31 bleibt vorerst Mindestversion. Eine Unterstützung von
Android 9 ist eine separate Kompatibilitätsprüfung und nicht Voraussetzung
dieses Konzepts.

## Nearby-Rundnachrichten

Owner und Bearbeiter können im aktiven Jagdmodus eine feste Vorlage an
bereits direkt verbundene Teilnehmergeräte senden. Gäste können empfangen
und bestätigen. Hundegeräte, Serverweitergabe und Nachlieferung sind nicht
beteiligt. Der Empfänger prüft das authentifizierte Absendergerät und
dessen Senderecht im signierten Offline-Verzeichnis. Die Bestätigung
wird dem Teilnehmer erst zugerechnet, wenn sie von einem beim Versand
adressierten Gerät kommt. Sie bedeutet Empfang, nicht Lesen.
Die Übersicht ist auf die laufende Relais-Sitzung begrenzt.

Eine neu angenommene Nachricht löst auf dem Empfängergerät eine normale
Android-Benachrichtigung im separaten Kanal **Nearby-Rundnachrichten** aus.
Duplikate und Empfangsbestätigungen lösen keine aus. Systemberechtigungen
und Kanaleinstellungen bestimmen Ton und Vibration, nicht die Zustellung.

Für den Gerätetest im Supabase-Projekt die Migration
`202611180002_offline_broadcast_roles.sql` aus dem PWA-Repository anwenden.
Die bereits aktualisierte `location-relay`-Funktion signiert das neue Feld
`can_broadcast` automatisch; sie braucht dafür kein erneutes Deployment.
Danach die Offline-Verzeichnisse der Testgeräte online erneuern. Ein
älteres Verzeichnis ohne das Feld erteilt kein Senderecht für Rundnachrichten.

**Feldtest am 18.11.2026:** Direkter Offline-Versand und Anzeige
„Angekommen“ auf Motorola und Xiaomi wurden bestätigt. Der anschließende
Test der Android-Benachrichtigungen steht noch aus.

## Eigenes Hundegerät anstupsen

Angemeldete Reviermitglieder können eigene Hunde registrieren und koppeln.
Nur das registrierende Konto darf das aktuell gekoppelte Hundegerät per
Nearby klingeln oder vibrieren lassen. Beide Geräte prüfen dafür
`dog_registrant_id` aus dem signierten Offline-Verzeichnis. Das Hundegerät
verwirft verspätete Nachrichten und Duplikate und erlaubt höchstens ein
Signal alle fünf Sekunden. Es gibt keine Warteschlange und keine
Weiterleitung. Die Bestätigung belegt, dass der DogTracker eine
Android-Benachrichtigung ausgelöst hat; sie garantiert keinen hörbaren Ton.

Vor dem ersten Gerätetest die Migration
`202611180003_own_dogs_and_nearby_signals.sql` aus dem PWA-Projekt anwenden
und die Offline-Verzeichnisse beider Geräte einmal online erneuern.
Die bestehende Edge Function übernimmt das zusätzliche Feld ohne
erneute Bereitstellung.

## Rückkehr der Internetverbindung

Sobald Android ein nutzbares Standardnetz mit Internetfähigkeit meldet,
versucht die App den Serverzugriff, ohne auf Androids Internetvalidierung
zu warten. Erkannte Anmeldenetze und pausierte Netze lösen keinen Versuch
aus. Erst der erfolgreiche Abruf belegt einen aktuellen Kartenstand.
Bei vorübergehenden Verbindungsfehlern trotz verfügbarer Route versucht die
geöffnete Karte den Abruf nach 2, 5 und 10 Sekunden erneut, danach im
30-Sekunden-Abstand. Das Teilnehmer-Relais stößt die Erneuerung der
Offline-Berechtigung an. Der DogTracker meldet den Upload bei WorkManager
bevorzugt an; Android kann ihn bei ausgeschöpftem Kontingent normal einplanen.
Eine vom Server angeforderte Upload-Wartefrist bleibt gültig. Die
120-Sekunden-Grenze für die Veraltung von Nearby-Positionen ist unabhängig
davon. Der lokale Nearby-Betrieb benötigt weiterhin kein Internet.

**Gerätetest:** Im aktiven Jagdmodus zunächst die Karte bzw. den Hundetracker
online starten. Internet trennen, dann wieder verbinden, ohne die App zu
beenden oder die Karte manuell zu aktualisieren. Prüfen, dass der Serverstand
der Karte wieder aktualisiert wird bzw. der DogTracker eine frische Position
vom Server bestätigt bekommt.

**Ergebnis vom 18.11.2026:** Die automatische Wiederverbindung nach der
Netzunterbrechung wurde für die geöffnete Jagdkarte und den laufenden
DogTracker manuell bestätigt. Eine weitere Rückkehr auf dem Motorola war
verzögert, gelang aber ohne manuelles Aktualisieren; die Dauer wurde nicht
gemessen. Der verschobene Display-Test steht in den
[Testaufgaben](docs/testing/Testaufgaben.md#kommunikation-und-gerätebetrieb).

**Messungen mit der optimierten Version (18.11.2026, Motorola):** Nach dem
Wiedereinschalten der mobilen Daten zeigte die geöffnete Jagdkarte nach
7 Sekunden einen neuen Serverstand, ohne manuelle Aktualisierung. Der
laufende DogTracker zeigte nach etwa 17 Sekunden die nächste
Serverbestätigung bei eingestelltem 30-Sekunden-Standortintervall.
Das sind Einzelmessungen. Die DogTracker-Messung trennt die Zeit für
eine neue GPS-Position nicht von der Zeit für den Upload.

## Mögliche Verbesserung für eine Folgeversion: erster Standort ohne Internet

- **Beobachtung aus dem Feldtest (18.11.2026):** Ohne Internet dauerte es
  deutlich länger, bis ein eigener Standort verfügbar war. Eine mögliche
  Ursache sind fehlende oder veraltete A-GNSS-Hilfsdaten; das ist noch nicht
  gemessen.
- **Grenze des Relais:** Nearby kann bereits gemessene Positionen zustellen,
  aber keinen eigenen GNSS-Fix ersetzen. Die von der App genutzte
  Android-Standort-API bietet keinen unterstützten Weg, über Nearby empfangene
  A-GNSS-Hilfsdaten in den GNSS-Empfänger einzuspeisen. Fremde Positionen
  werden nicht als eigener Standort verwendet.
- **Möglicher Ansatz:** Standort beim ohnehin vorgesehenen Start der Jagdteilnahme
  oder des Hundetrackings möglichst früh anfordern, ohne einen zusätzlichen
  Bedienschritt. Prüfen, ob ein letzter eigener Fix mit sichtbarer Messzeit
  vorübergehend lokal angezeigt werden kann; ihn nicht als frische Messung
  versenden. Der Messauftrag steht separat in den
  [Testaufgaben](docs/testing/Testaufgaben.md#kommunikation-und-gerätebetrieb).

## Grenzen

- Funktransfer setzt laufende, berechtigte Geräte in Reichweite voraus.
  Android-Berechtigungen, Energiesparregeln, Akkustand und Empfang beeinflussen
  die Verfügbarkeit.
- Widerruf und Jagdmodusänderungen sind für vollständig getrennte Geräte nicht
  sofort erkennbar. Begrenzte Gültigkeit und Serverprüfung reduzieren dieses
  Zeitfenster.
- Ein verspäteter älterer Standort darf keinen neueren Stand überschreiben.
  Unterschiedliche Serverpfade haben jedoch unterschiedliche
  Deduplizierungs- und Bestätigungsregeln; ein generelles
  Exactly-once-Versprechen besteht nicht.

Weitere Transportadapter oder Mehrhop-Routing sind mögliche spätere
Erweiterungen, keine zugesagten Funktionen. Dafür wären insbesondere
Hop-Limits, Ablauf, Deduplizierung, Datenschutz und Gerätekompatibilität
gesondert festzulegen und abzunehmen.
