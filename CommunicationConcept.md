# Kommunikationskonzept

## Zweck und Geltungsbereich

Jagdbruder überträgt Hunde- und Teilnehmerpositionen an das gemeinsame
Jagdkarte-Backend. Ein erreichbares, berechtigtes Gerät kann verschlüsselte
Meldungen als Relais übernehmen und bei Internetverbindung weiterleiten.
Das verbessert die Zustellchance bei Funklöchern, garantiert aber weder
Echtzeit noch Reichweite oder Zustellung. Die PWA und der bisherige
Hundetracker-Vertrag bleiben eigenständig und müssen kompatibel bleiben.

Dieses Dokument unterscheidet den vorhandenen Stand vom Zielbild und den
erforderlichen Umsetzungsschritten. Bereitstellung und Serververtrag sind in der
[Relay-Serveranleitung](https://github.com/wierichs/wierichs.github.io/blob/main/Jagdkarte/supabase/location-relay.md)
beschrieben; offene Abnahmen stehen im [Portierungsstatus](PORTIERUNGSSTATUS.md).

## Zielbild: Kommunikation im Funkloch

- Teilnehmer und Hundegeräte sollen nach vorheriger Online-Einrichtung
  desselben Reviers und regelmäßiger Erneuerung der Berechtigung auch ohne
  Mobilfunk automatisch lokale Verbindungen herstellen. Für jede Begegnung
  darf kein Kopplungsdialog nötig sein.
  Android-Berechtigungen und die ausdrückliche Zustimmung zur Standortfreigabe
  und zum Relaisbetrieb bleiben erforderlich. Die Hundegerät-Einrichtung für
  einen konkreten Hund bleibt von der automatischen Relaisverbindung getrennt.
- Teilnehmer sollen aktuelle freigegebene Teilnehmer- und Hundepositionen auch
  dann auf ihrer Karte sehen können, wenn weder Sender noch Empfänger Internet
  haben. Dies ist ein Ziel, noch keine vorhandene Funktion. Kartenpakete und
  benötigte Revierdaten müssen vorher offline verfügbar sein.
- Ein Gerät mit Internet soll geeignete Meldungen zusätzlich an das Backend
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
Da die Umschläge für den Server verschlüsselt sind, können empfangende
Smartphones die Position daraus nicht lokal anzeigen. Die bisherige
Offline-Revieransicht ist ein lesbarer Snapshot ohne Livepositionen.

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
Teilnehmerpositionen werden nur bei aktiver Karte erfasst und spätestens 90 Sekunden nach der Messung
verworfen. Beim Pausieren der Karte kann das sichtbare Relay-Gateway im
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
werden. Neue eigene Teilnehmerpositionen werden derzeit nur bei
Internetzugang erstellt; die Aktivierung der Standortfreigabe benötigt einen
Serveraufruf.

Bei ausdrücklichem Beenden oder Verlust der
Berechtigung wird die Registrierung weiterhin widerrufen. Nach einem Widerruf
kann eine neue Gerätekennung entstehen, die offline befindliche Nachbarn erst
nach einer Verzeichniserneuerung kennen. Fehlgeschlagene Nearby-Starts werden
automatisch erneut versucht. Im Gerätetest nahm das nach einem Prozessende
offline wieder geöffnete Gateway neue Hundemeldungen an; nach Wiederherstellung
des Internetzugangs wurden die ausstehenden Meldungen serverbestätigt.
Nach vollständigem Offline-Geräteneustart pausierten Gateway und DogTracker
jeweils bis zur Online-Erneuerung. Weitere Unterbrechungs- und
Wiederverbindungstests stehen aus.

Die Offline-Erweiterung wurde laut Nutzer im gemeinsamen Entwicklungsprojekt
bereitgestellt. Ein Gerätetest bestätigte die Weiterleitung vom offline
befindlichen DogTracker über ein online befindliches Teilnehmergerät zum Server.
Weitere Geräte- und Unterbrechungstests stehen aus. Der Stand
anderer Zielprojekte muss gesondert geprüft werden. Ältere DogTracker-APKs
unterstützen die neueren Rückbestätigungen nicht.

## Erforderliche Umsetzung

Der zuletzt vereinbarte nächste Schritt 4 umfasst die nachstehenden Punkte 3
und 4: ein sicheres lokales Positionsformat und dessen Kartenintegration.

1. **Authentifizierung weiter prüfen.** Der Android-Debug-Build und gezielte
   Protokolltests bestehen; umfassende Integrations- und Gerätetests stehen noch aus.
   Beide Seiten müssen ihre
   Revierzugehörigkeit und den Besitz ihres Geräteschlüssels nachweisen; der
   Nachweis muss an die konkrete Verbindung gebunden und gegen Replay und
   fremde Geräte geschützt sein. Vor erfolgreicher Prüfung keine Nutzdaten
   annehmen oder versenden. Bei Ablauf und Revierwechsel bestehende Sitzungen
   beenden. Kein manueller Fallback für ältere App-Versionen.
2. **Offline-Autorisierung und Lebenszyklus prüfen.** Geräte und
   Schlüssel müssen vorab online registriert und die signierten Verzeichnisse
   beider Geräte aktuell genug sein, um einander zu kennen. Der
   Teilnehmerdienst kann innerhalb desselben Gerätebetriebs mit geschütztem
   lokalem Zustand wieder anlaufen; neue eigene Teilnehmerpositionen erfordern
   derzeit Internet. Die Höchstdauer beträgt 24 Stunden und wird zusätzlich
   zur signierten Ablaufzeit monoton begrenzt. Nach Geräteneustart ist eine
   Online-Erneuerung erforderlich. Prozesswiederanlauf und Offline-Geräteneustart
   wurden auf zwei Geräten geprüft; Schlüsselwechsel und verzögerter Widerruf
   bleiben gesondert zu prüfen.
3. **Lokale Positionsnachrichten entwerfen.** Neben dem bestehenden
   serververschlüsselten Umschlag wird ein versioniertes Format für berechtigte
   Offline-Empfänger benötigt. Es muss Vertraulichkeit, unveränderliche
   Absenderidentität, Revierbindung, Aktualität und Replay-Schutz gewährleisten.
   Schlüsselverteilung und -rotation für Teilnehmer und Hundegeräte, der
   Schutz lokaler Schlüssel sowie die Begrenzung auf berechtigte Empfänger
   sind vor der Implementierung festzulegen. Ein sichtbarer Gerätename,
   Reviername oder BLE-Werbung ist kein Berechtigungsnachweis. Der
   Serververtrag und die PWA dürfen dadurch nicht stillschweigend brechen.
4. **Offline-Karte anbinden.** Verifizierte lokale Positionen müssen in
   einen reviergebundenen, begrenzten lokalen Positionsbestand und von dort
   in die Kartenanzeige gelangen. Backend- und Funkdaten müssen anhand von
   Herkunft, Messzeit und Sequenz zusammengeführt werden, sodass ältere
   Positionen neuere nicht überschreiben. Alter und Quelle sind sichtbar zu
   machen; abgelaufene Positionen dürfen nicht als aktuell erscheinen.
   Offline-Kartenpaket, Reviersnapshot, Neustart und Revierwechsel sind dabei
   gesondert zu prüfen.
5. **Weiterleitung stufenweise ausbauen.** Die erste Ausbaustufe unterstützt
   den direkten Austausch erreichbarer Nachbarn und die vorhandene
   Ein-Hop-Weitergabe zum Internet-Gateway. Das Nachrichtenformat soll spätere
   Mehrhop-Weiterleitung berücksichtigen. Für eine Verbindung A → B → C ohne
   direkte Funkverbindung von A zu C ist echte Mehrhop-Weiterleitung nötig;
   Nearby-Cluster allein bietet sie nicht. Diese folgt in einer späteren
   Ausbaustufe. Dafür müssen Hop-Limit, Ablauf, Deduplizierung,
   Speichergrenzen, Wiederholung, Weitergabe bei wiederkehrenden Kontakten und
   Schleifenvermeidung definiert und getestet werden. Lokale Übernahme und
   Serverbestätigung bleiben getrennt.
6. **Backend und Kompatibilität.** Nötige Supabase-Änderungen ausschließlich
   im PWA-Projekt `D:\NoScan\wierichs.github.io\Jagdkarte\` entwickeln und
   mit bestehenden Hunde- und PWA-Verträgen prüfen. Eine Testbereitstellung
   und ein späteres Deployment erfolgen separat nach ausdrücklicher Freigabe;
   Auswirkungen auf gemeinsam genutzte Projekte sind vorher abzustimmen. Die
   neue Android-Version erst gegen ein passend bereitgestelltes Backend testen;
   vorhandene Geräteinstallationen nicht ungefragt ersetzen.
7. **Feldabnahme.** Nach automatisierten Protokoll-, Ablauf-, Rechte- und
   Persistenztests mindestens zwei Geräte ohne Mobilfunk und ein Gerät mit
   Internet einsetzen; bei Mehrhop mindestens drei offline verbundene Geräte
   und zusätzlich ein Internet-Gateway. Verbindung ohne Dialog, gegenseitige
   Offline-Anzeige, Hund → Teilnehmer, Unterbrechung und Wiederverbindung,
   Ablauf, fremdes Revier, alte App-Version und spätere Server-/PWA-Anzeige
   prüfen. Auf 100, 200 und 300 Metern sowie dazwischen Entdeckung, erfolgreiche
   Übertragung, Verzögerung und Akkuverbrauch bei Sichtkontakt und im Wald
   messen. Auch Bildschirm aus, Hintergrundbetrieb und Neustart einbeziehen.
   Für Flugmodus-Tests WLAN und Bluetooth ausdrücklich wieder aktivieren.
   Mindestwerte für Erfolgsquote, Verzögerung und Betriebsdauer sind vor der
   Abnahme gemeinsam festzulegen; die Tests begründen keine allgemeine
   Reichweiten- oder Zustellgarantie.

### Feldabnahme: noch festzulegen

- **Festgelegt am 2026-11-18:** Teilnehmer- und Hundepositionen werden nach
  120 Sekunden als veraltet markiert, bleiben aber bis zum bekannten Ende des
  Jagdmodus sichtbar. Eine veraltete Position ist besser als keine Position.
  Sind Sender und Empfänger vollständig getrennt, kann der Empfänger das Ende
  des Jagdmodus oder den Widerruf nicht sofort erfahren; bis zur nächsten
  Verbindung muss die Position daher sichtbar als veraltet erkennbar bleiben.
- Welche konkreten Abnahmewerte für Funkzuverlässigkeit, Verzögerung und
  Akkulaufzeit sind für den Einsatz akzeptabel?

Android 12 / API 31 bleibt vorerst Mindestversion. Eine Unterstützung von
Android 9 ist eine separate Kompatibilitätsprüfung und nicht Voraussetzung
dieses Konzepts.

## Rückkehr der Internetverbindung

Sobald Android wieder ein Standardnetz mit geprüftem Internetzugang meldet,
aktualisiert die geöffnete Jagdkarte die Serverdaten ohne App-Neustart. Das
Teilnehmer-Relais stößt die Erneuerung der Offline-Berechtigung an; der
laufende DogTracker zieht den Server-Upload und die Prüfung seiner
Revierberechtigung vor. Eine vom Server angeforderte Upload-Wartefrist bleibt
gültig. Der lokale Nearby-Betrieb benötigt weiterhin kein Internet.

**Gerätetest:** Im aktiven Jagdmodus zunächst die Karte bzw. den Hundetracker
online starten. Internet trennen, dann wieder verbinden, ohne die App zu
beenden oder die Karte manuell zu aktualisieren. Prüfen, dass der Serverstand
der Karte wieder aktualisiert wird bzw. der DogTracker eine frische Position
vom Server bestätigt bekommt.

**Ergebnis vom 18.11.2026:** Die automatische Wiederverbindung nach der
Netzunterbrechung wurde für Jagdkarte und DogTracker manuell bestätigt.
Der separate Wiederverbindungstest mit ausgeschaltetem Bildschirm wurde
für Jagdkarte und DogTracker auf eine spätere Feldabnahme verschoben und
ist noch offen.

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
- **Prüfauftrag:** Standort beim ohnehin vorgesehenen Start der Jagdteilnahme
  oder des Hundetrackings möglichst früh anfordern, ohne einen zusätzlichen
  Bedienschritt. Prüfen, ob ein letzter eigener Fix mit sichtbarer Messzeit
  vorübergehend lokal angezeigt werden kann; ihn nicht als frische Messung
  versenden. Auf Motorola und Xiaomi die Zeit bis zur ersten frischen Messung
  online und offline bei vergleichbaren Bedingungen messen und dabei kürzlich
  genutzte Standortbestimmung und Starts nach längerer Pause getrennt betrachten.

## Grenzen und offene Abnahme

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
- Reale Nearby-Verbindungen, Unterbrechungen, Wiederholungen und
  Serverbestätigungen müssen mit mehreren physischen Geräten und einem
  isolierten Backend geprüft werden.

Weitere Transportadapter oder Mehrhop-Routing sind mögliche spätere
Erweiterungen, keine zugesagten Funktionen. Dafür wären insbesondere
Hop-Limits, Ablauf, Deduplizierung, Datenschutz und Gerätekompatibilität
gesondert festzulegen und abzunehmen.
