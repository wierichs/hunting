# Jagdkarte fuer Android

Dieser Branch stellt die Funktionalitaet der Jagdkarte zusaetzlich als native
Android-App mit Kotlin, Jetpack Compose und einer nativen osmdroid-Karte bereit,
ohne WebView. Die Android-App ergaenzt die PWA und soll sie nicht abloesen.
Die PWA bleibt als eigenstaendiges Angebot mit ihrem jetzigen Funktionsumfang
bestehen; ihre Funktionen werden weder entfernt noch auf Android verlagert.
Die bestehende Hundetracker-Funktion bleibt als Ansicht **DogTracker** erhalten.
Das separate Web-Projekt wird nicht veraendert. Beide Clients verwenden dieselben
vorhandenen Supabase-Daten und Berechtigungen. Erweiterungen des gemeinsamen
Backends muessen zu den bestehenden PWA-Funktionen und Schnittstellen
rueckwaertskompatibel bleiben.

Die Android-App hat die eigene App-ID `org.wierichs.jagdkarte`. Sie kann neben
`org.wierichs.dogtracker` installiert werden; dessen Einstellungen und
Geraetekopplung werden nicht automatisch uebernommen. Mindestversion: Android 12.

## Stand der Portierung

Die Funktionen werden schrittweise zusaetzlich nativ angeboten. Der aktuelle
Android-Funktionsumfang ist kleiner als der der PWA; deren bestehendes Angebot
bleibt davon unberuehrt.

- Native OpenStreetMap-Karte mit Zoom, Verschieben und Quellenangabe.
- Anmeldung und Registrierung mit E-Mail-Code gegen das bestehende Supabase-Auth.
	Benutzer- und Tracker-Geheimnisse liegen getrennt, Keystore-verschluesselt im
	privaten App-Speicher. Benutzersitzungen werden erneuert; Abmelden entfernt die
	lokale Sitzung auch bei einem Serverfehler.
- Auswahl eigener und geteilter, bereits vorhandener Revierprofile. Die
	serverseitigen RLS-Regeln bestimmen den Zugriff.
- Gespeicherte Polygon-/MultiPolygon-Grenzen einschliesslich Aussparungen,
	Markierungen und deren Ablaufzeiten.
- Native Markeranlage mit Kartenpositionswahl, Markerart, Bezeichnung und Notiz.
	Owner und Bearbeiter koennen Einrichtungen und Standplaetze planen; Mitglieder
	koennen Anschuss und Wildsichtung mit einem frischen GPS-Fix im Revier melden.
- Teilnehmer- und Hundepositionen im aktiven Jagdmodus, Aktualisierung alle
	30 Sekunden bei sichtbarer Kartenansicht. Teilnehmerpositionen verschwinden
	nach 90 Sekunden; Hundepositionen bleiben auch ausserhalb der Reviergrenze
	sichtbar und werden nach zwei Minuten als veraltet markiert.
- Explizite Teilnehmer-Standortfreigabe pro ausgewaehltem Revier. Ein frischer
	GPS-Fix und die Grenzpruefung mit JTS sind erforderlich. Freigabe und
	Berechtigung werden nicht aus einer frueheren Android-Zustimmung abgeleitet.
	Beim Wechsel, Abmelden, Ausschalten des Jagdmodus oder Pausieren der Ansicht
	endet die Uebertragung. Beim Fortsetzen derselben Ansicht kann eine noch
	aktivierte Freigabe wieder anlaufen. Nach einem Neustart ist sie aus.
- Jagdmodus-Steuerung durch Owner und Bearbeiter sowie Hunderegistrierung und
	Smartphone-Kopplung ueber die vorhandenen, serverseitig geschuetzten RPCs.
	Einmalige Schluessel erscheinen als QR-Code in einem gegen Screenshots
	geschuetzten Dialog; eine neue Kopplung muss bestaetigt werden.
- Vorhandener DogTracker mit Foreground Service, persistenter Outbox und
	experimentellem Ein-Hop-Relay. Der Nearby-Sicherheitsdialog ist auch auf der
	Kartenansicht erreichbar.
- Gemeinsame Kommunikationsbasis mit optionaler Revier-Relaisfreigabe ohne
	Hundekopplung, kurzer Teilnehmer-Outbox und signierten Serverbestaetigungen.
	Die neue Serverstufe wurde manuell im Supabase-Testprojekt deployed und
	abgenommen; sie ist nicht in Produktion deployed. Einrichtung:
	[Anleitung zur Relay-Serverstufe](https://github.com/wierichs/wierichs.github.io/blob/main/Jagdkarte/supabase/location-relay.md).
	Nearby kann Android-Berechtigungen fuer groben und praezisen Standortzugriff
	zur Geraetesuche verlangen. Das aktiviert nicht die eigene Revierfreigabe.

Kartenkacheln werden im privaten App-Cache gespeichert. Das ist kein garantierter
Offline-Kartenbestand. Private Revierdaten werden nicht als dauerhafte
Offline-Kopie gespeichert. Ohne Verbindung werden keine alten Kartenpositionen
als aktuelle Live-Daten weiter angezeigt. Kann eine Teilnehmerposition beim
Stoppen nicht entfernt werden, kann sie bis zur 90-Sekunden-Grenze sichtbar
bleiben. Ohne Relaisfreigabe werden Teilnehmerpositionen nicht offline
nachgesendet. Mit bereitgestelltem Relay-Server bleiben eigene Meldungen nur
bis zu 90 Sekunden ab Messzeit ausstehend; eine Historie wird nicht aufgebaut.

### Android-Portierungsstatus

Die noch offenen beziehungsweise zu pruefenden PWA-Funktionen und ihr
Umsetzungs-/Abnahmestatus stehen in der ungeordneten
[Portierungsstatus-Dokumentation](PORTIERUNGSSTATUS.md). Die Reihenfolge ist
keine Priorisierung; den jeweils aktuellen Arbeitspunkt legen wir gemeinsam
fest. Die PWA bleibt unabhaengig verfuegbar und voll funktionsfaehig.

### Markierungen setzen

1. Anmelden und ein bestehendes Revier auswaehlen, dessen Grenze hinterlegt ist.
2. **Markierung setzen** waehlen, die Karte unter der Positionsnadel verschieben
   und **Hier setzen** bestaetigen. Alternativ einen Kartenpunkt lange beruehren.
3. Markerart, optionale Bezeichnung und Notiz eingeben und **Speichern** waehlen.
   Ohne Bezeichnung wird der Name der Markerart verwendet.

Owner und Bearbeiter koennen gespeicherte Marker auf der Karte antippen, ihre
Bezeichnung und Notiz bearbeiten oder den Marker nach ausdruecklicher
Bestaetigung loeschen. Markerart und Position bleiben dabei unveraendert. Die App
laedt Rolle und Revier vor dem Schreibzugriff erneut; die bestehenden
Serverrechte entscheiden abschliessend. Fuer Aenderungen ist eine
Internetverbindung erforderlich. Bei fehlender Serverbestaetigung bleibt der
Dialog mit den Eingaben geoeffnet; es gibt keinen automatischen Wiederholungsversuch.

Das Ziel muss innerhalb der Reviergrenze und ausserhalb von Aussparungen liegen.
Gaeste sehen nur **Anschuss** und **Wildsichtung**. Dafuer fragt die App beim
Speichern einmalig einen aktuellen, genauen GPS-Standort ab; auch dieser muss
innerhalb des Reviers liegen. Die Standortfreigabe wird dadurch nicht aktiviert.
Owner und Bearbeiter koennen ausserdem die Einrichtungen des Web-Katalogs und
**Standplatz** anlegen. Diese Planung benoetigt keinen GPS-Zugriff. Wie im
bestehenden Serververtrag haengt die Markeranlage nicht vom Jagdmodus ab.

Die App liest Rolle und Grenze vor jedem Schreibzugriff erneut. Dauerhafte
Einrichtungen werden ueber den vorhandenen Tabellen-Insert angelegt, temporaere
Marker ausschliesslich ueber `create_temporary_hunting_marker`. Die bestehenden
Serverrechte bleiben unveraendert. Die 48-Stunden-Frist fuer Anschuss,
Wildsichtung und Standplatz legt allein der Server fest; der Client sendet kein
Ablaufdatum. Bezeichnungen sind auf 80, Notizen auf 300 Zeichen begrenzt.

Fuer die Anlage ist eine Internetverbindung erforderlich. Nach Erfolg wird die
Karte aktualisiert; bei Fehlern bleibt der Dialog mit den Eingaben geoeffnet.
Es gibt keinen automatischen Wiederholungsversuch und keine Offline-Warteschlange
fuer Marker. Nach einer unterbrochenen Serverantwort vor einem erneuten Versuch
pruefen, ob der Marker bereits angelegt wurde. Ein Profil-/Kontowechsel verwirft
den offenen Entwurf.

### Kommunikation und Servergrenzen

Das [Kommunikationskonzept](CommunicationConcept.md) bleibt die Zielarchitektur.
Die bestehende Hundekommunikation wird weiterverwendet, nicht durch einen zweiten
Tracker ersetzt. Jagdkarte und DogTracker nutzen unabhaengig von ihrer App-ID
die gemeinsame Nearby-Dienstkennung `org.wierichs.dogtracker`.

Der neue Endpunkt `location-relay` und eine additive Migration ergaenzen nun
Benutzergeraete, gruppenbezogene Schluesselregistrierung, Widerruf, typisierte
Teilnehmermeldungen und atomare Nachrichten-ID-Deduplizierung. Verzeichnisse
und Empfangsbestaetigungen werden serverseitig signiert. Diese Stufe wurde
manuell im Supabase-Testprojekt bereitgestellt und dort abgenommen; sie wurde
nicht in Produktion deployed. Ohne Bereitstellung in einem Zielprojekt bleibt
die normale Jagdkarte nutzbar, die neue Relaisfreigabe kann dort jedoch nicht
erfolgreich eingerichtet werden.

Benutzer-JWTs und Hundeschluessel bleiben auf ihrem jeweiligen Geraet und
werden nie an ein Relais gegeben. Gateway und eigener Standort sind getrennte
Einwilligungen. Die Gateway-Benachrichtigung kann im Hintergrund weiterlaufen,
waehrend eigene Teilnehmerfixes weiterhin nur bei aktiver Karte erfasst werden.
Alte DogTracker-Nachrichten koennen weitergeleitet werden; alte APKs
unterstuetzen die neuen Rueckbestaetigungen noch nicht. Grenzen,
Schluesselverwaltung und Bereitstellung: [Anleitung zur Relay-Serverstufe](https://github.com/wierichs/wierichs.github.io/blob/main/Jagdkarte/supabase/location-relay.md).

### Einrichtung und Pruefung

Die App verwendet die oeffentliche Supabase-Projektkonfiguration der Web-App.
Voraussetzung ist das dort dokumentierte aktuelle Datenbankschema samt Auth-
E-Mail-Vorlagen und `dog-location`/`dog-location-relay`. Die native Anmeldung
verwendet eingegebene E-Mail-Codes; Web-Magic-Links bleiben Web-Links.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

Ein JDK und das Android SDK muessen eingerichtet sein. Die zweite Pruefung
benoetigt einen laufenden Emulator oder ein angeschlossenes Geraet. Die APK liegt
unter `app/build/outputs/apk/debug/app-debug.apk`.

Verifiziert: 27 JVM-Tests fuer Nachrichten, Relay-Kryptografie, Grenzpruefung,
Positionsalter, Teilnehmer-RPC sowie Marker-Schreib-, Bearbeitungs- und
Loeschvertraege sowie 18 Instrumentierungstests auf dem API-37-Emulator fuer
App-Start, Navigation, Login-Dialog, SQLite-Migration, atomare
Relay-Bestaetigungen, Keystore, Kartenpositionswahl, Marker-Dialoge,
Markerbearbeitung, Loeschbestaetigung und ausdrueckliche Relaiszustimmung.
Die Marker-Dialoge werden mit lokalen Testdaten, ohne Produktionsschreibzugriff
und auch bei 320 dp geprueft. Eine Anmeldung gegen Produktionsdaten, echte Rollen-/RLS-Schreibtests,
reale GPS-Erfassung und Nearby-Transfers zwischen mehreren physischen Geraeten
sind noch nicht abgenommen. Fuer die neue Serverstufe gibt es zusaetzlich
20 lokale Deno-/PostgreSQL-Fixture-Tests; sie ersetzen keine echte
Supabase-/PostGIS-Abnahme und keinen physischen Nearby-Transfer.

## DogTracker

Der Hundetracker übermittelt den GPS-Standort eines Smartphones an ein Hund-Profil in der [Jagdkarte](https://wierichs.github.io/Jagdkarte/). So kann das Smartphone beim Hund mitgeführt werden und dessen Position im zugehörigen Revierprofil sichtbar machen.

## Einrichtung

1. In der Jagdkarte anmelden, ein Revierprofil öffnen und den Jagdmodus einschalten.
2. Unter **Hunde im Revier** einen Hund registrieren und **Smartphone koppeln** auswählen.
3. Den angezeigten QR-Code mit dem Hundetracker scannen oder den Geräteschlüssel manuell einfügen. Der Schlüssel wird in der Jagdkarte nur einmal angezeigt und sollte geheim bleiben.
4. Das Standortintervall festlegen und **Tracking starten** wählen. Beim ersten Start benötigt die App Zugriff auf den genauen Standort und, ab Android 13, auf Benachrichtigungen.

Die App ist für Android ab Version 12 vorgesehen. Pro Hund kann derzeit ein Smartphone gleichzeitig gekoppelt sein. Wenn ein Smartphone ersetzt wird oder der Schlüssel verloren geht, muss es in der Jagdkarte erneut gekoppelt werden.

## Funktionen

- QR-Code scannen oder den 64-stelligen Geräteschlüssel manuell eingeben.
- Standortintervall zwischen 15 Sekunden und 60 Minuten wählen; voreingestellt sind 60 Sekunden.
- Standort im Hintergrund über einen Android-Standortdienst erfassen. Eine dauerhafte Benachrichtigung zeigt die aktive Sitzung an und bietet auch eine Stopp-Schaltfläche.
- Position, GPS-Genauigkeit und Messzeitpunkt über eine verschlüsselte HTTPS-Verbindung übertragen.
- Bei fehlender Internetverbindung Messungen lokal puffern und den Upload bei verfügbarer Verbindung erneut versuchen.
- Optional als Tracker-Relais teilnehmen: verschlüsselte und signierte Positionsmeldungen werden nach einer bestätigten Nearby-Verbindung per Bluetooth/WLAN an einen anderen Tracker übergeben. Relay läuft nur während des aktiven Trackings und benötigt eine vorherige Online-Kopplung.
- Der Status unterscheidet lokal gespeicherte, noch ausstehende Meldungen von einer vom Server bestätigten Übertragung und zeigt weitere Wartemeldungen an.
- Den Geräteschlüssel geschützt im Android Keystore speichern.

Das Tracking beginnt erst nach **Tracking starten** und lässt sich in der App oder über die Benachrichtigung beenden. Ist der Jagdmodus in der Jagdkarte ausgeschaltet, stoppt der Tracker nach Rückmeldung des Servers und löscht noch gepufferte Positionen.

## Hinweise

Für die Erfassung sind aktivierter Standort und die erforderlichen Android-Berechtigungen nötig. Die Übertragung benötigt eine Internetverbindung; ohne Verbindung werden Positionen zunächst auf dem Smartphone gespeichert. Android-Energiesparfunktionen, Empfang und GPS-Bedingungen können Aktualisierungen verzögern. Der Tracker ist keine garantierte Echtzeitortung und kein Notrufsystem.

Die experimentelle Relaisfunktion ist standardmäßig ausgeschaltet. Beim Verbinden muss der Sicherheitscode auf beiden Geräten verglichen und bestätigt werden. Unterstützt wird derzeit ein einzelner Relay-Hop; die Ende-zu-Ende-Strecke ist noch nicht auf mehreren realen Geräten validiert.

Die Jagdkarte zeigt Hundepositionen im gekoppelten Revierprofil an, auch außerhalb der Reviergrenze. Weitere Informationen zur Registrierung und Kopplung stehen in der [Hilfe zur Jagdkarte](https://wierichs.github.io/Jagdkarte/hilfe.html#hunde).