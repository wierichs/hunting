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
Beim ersten Start oeffnet DogTracker; danach stellt die App den zuletzt genutzten Bereich und das letzte Revier wieder her.

Nutzeranleitungen und Fehlerbehebung stehen in der [Hilfe fuer Android](docs/help/hilfe.md). Diese Markdown-Datei wird auch im nativen Hilfe-Dialog angezeigt.

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
	sichtbar und werden nach zwei Minuten als veraltet markiert. Bei frischen,
	ausreichend genauen Bewegungsfixes zeigt ein kleiner Kartenpfeil den aus zwei
	Positionen abgeleiteten Kurs ueber Grund samt Himmelsrichtung; er zeigt nicht
	die Blick- oder Geraeteausrichtung.
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

Online-Kartenkacheln werden im privaten App-Cache gespeichert. Das ist kein
garantierter Offline-Kartenbestand; dafuer gibt es importierte Mapsforge-Pakete.
Der zuletzt geladene Revierstand (Grenze und Markierungen) wird zusaetzlich
verschluesselt fuer hoechstens sieben Tage und 32 MiB im nicht gesicherten
App-Speicher gehalten. Die Offlinekopie ist nur lesbar, wird nicht synchronisiert
und enthaelt keine Teilnehmer- oder Hund-Livepositionen. Ohne Verbindung werden
alte Karten- oder Standortdaten nicht als aktuelle Live-Daten dargestellt. Kann
eine Teilnehmerposition beim Stoppen nicht entfernt werden, kann sie bis zur
90-Sekunden-Grenze sichtbar bleiben. Ohne Relaisfreigabe werden
Teilnehmerpositionen nicht offline nachgesendet. Mit bereitgestelltem
Relay-Server bleiben eigene Meldungen nur bis zu 90 Sekunden ab Messzeit
ausstehend; eine Historie wird nicht aufgebaut.

### Kartendarstellung und Offlinekarten

Die Kartenaktion **Kartendarstellung** bietet **OpenStreetMap**, **Luftbild**
und bei vollstaendiger lokaler Revierabdeckung **Offline**. Die Auswahl wird
auf dem Geraet gespeichert. Ohne passende Offlinekarte wird diese Option nicht
neu angeboten. War Offline bereits gewaehlt und fehlt nach einem Revierwechsel,
einer Grenzaenderung oder dem Loeschen eines Pakets die Abdeckung, bleibt die
Kartenbasis ohne Online-Fallback leer und zeigt eine Meldung. Die Kartenwahl
schaltet weder Standortfreigabe noch Serverabfragen oder Relay-Kommunikation ab;
sie betrifft ausschliesslich die Kartenbasis.

#### Formatentscheidung fuer Android (2026-11-10)

| Format | Bewertung |
| --- | --- |
| Mapsforge `.map` | Offenes OSM-Vektorformat, etablierter Android-Renderer und fertige frei herunterladbare Regionalpakete. Gewaehlt fuer Android. |
| Vektor-PMTiles / Vektor-MBTiles | Ebenfalls offene, kompakte Container. Benoetigen einen passenden MVT-Renderer, Stil, Schriften und gegebenenfalls Sprites. Technisch geeignet, aber fuer die bestehende osmdroid-Anbindung aufwendiger. |
| Raster-MBTiles | Einfach darstellbar, aber bei grossen Regionen und vielen Detailzoomstufen typischerweise deutlich mehr Speicherbedarf als Vektorkarten. Nicht als Standard gewaehlt. |
| OSM-PBF | Gut fuer Rohdatentransfer, aber kein direkt darstellbares Kartenpaket; vorab waere eine Kartenaufbereitung erforderlich. |

Die [offene Mapsforge-Spezifikation](https://github.com/mapsforge/mapsforge/blob/master/docs/Specification-Binary-Map-File.md)
ist auf effiziente Speicherung und mobiles Rendering ausgelegt. Eine pauschal
kleinste Datei gegenueber jedem komprimierten MVT-Paket wird nicht behauptet;
Region, enthaltene Details und Zoomumfang beeinflussen die Groesse erheblich.
Der Downloadserver bietet Bundeslaender und Laender, nicht nur Revierzuschnitte.
Beispiele des geprueften Angebots: Rheinland-Pfalz etwa 183 MB, NRW etwa 586 MB,
Deutschland etwa 3 GB. Das fuer den Renderer-Test verwendete Liechtenstein-Paket
hat 2.261.314 Bytes. Angebote und Groessen koennen sich aendern.

Die PWA behaelt ihren unabhaengigen PMTiles-Ablauf. Android importiert keine
PMTiles oder MBTiles; es gibt keine Aenderung oder Konvertierung im Originalrepo.

#### Download, Import und Abdeckung

1. Eine passende `.map`-Datei aus den [freien Mapsforge-Downloads](https://download.mapsforge.org/maps/v5/)
	laden, beispielsweise unter `europe/germany/`.
2. Die gleichnamige `.poly`-Extraktgrenze aus dem [Extraktgrenzen-Verzeichnis](https://download.mapsforge.org/maps/poly/)
	laden. Fuer `nordrhein-westfalen.map` ist dies `europe/germany/nordrhein-westfalen.poly`.
3. Unter **Revier und Verwaltung -> Offlinekarten verwalten** mit
	**MAP / Extraktgrenze importieren** beide Dateien auswaehlen. Getrennter Import
	ist ebenfalls moeglich; eine spaeter importierte Extraktgrenze wird ueber den
	gleichen Dateistamm zugeordnet. Alternativ wird gleichnamiges Polygon-/
	MultiPolygon-GeoJSON als Abdeckungsdatei unterstuetzt.

Die Dateiausdehnung allein beweist bei regional zugeschnittenen Paketen keine
vollstaendige Abdeckung. Android schneidet die zugeordnete Extraktgrenze mit
den Dateigrenzen und prueft geometrisch das ganze Revier, nicht nur dessen
Mittelpunkt. Bei Bedarf koennen mehrere Pakete gemeinsam abdecken. Fehlt die
Extraktgrenze, bleibt das Paket installiert, aber nicht als gesichert abdeckend
auswaehlbar. Nur zur Kartendatei passende Extraktgrenzen aus vertrauenswuerdiger
Quelle verwenden; die App kann die Herkunft einer selbst zugeordneten Grenze
nicht verifizieren. OSM-Datenvollstaendigkeit und Aktualitaet sind davon getrennt.

Pakete werden gestreamt, auf gueltige Mapsforge-Metadaten geprueft und im
privaten, vom Backup ausgeschlossenen App-Verzeichnis gespeichert. SHA-256
verhindert identische Kartenkopien. Beim Kopieren bleiben mindestens 32 MiB
Speicherreserve; fehlerhafte temporaere Dateien werden entfernt. Es gibt keinen
Hintergrunddownload und keine Kachel-Massendownloads vom OSM-Standardserver.
Mehrdateiimporte sind keine Gesamttransaktion: Bereits erfolgreich importierte
Pakete bleiben bei einem spaeteren Fehler erhalten und sind in der Verwaltung
sichtbar. Karten koennen dort mit Bestaetigung geloescht werden.

Die Offlineansicht rendert lokal mit Mapsforge und ruft keine Onlinekacheln ab.
Zoom bleibt auf 20 begrenzt; ein groesserer Zoom erzeugt keine zusaetzlichen
OSM-Details. Private Reviergrenzen, Marker und Teilnehmerdaten werden nicht
in die Kartenpakete oder deren Metadaten geschrieben. Ohne Verbindung steht
dadurch nicht automatisch die private Revierauswahl nach einem Kaltstart bereit.

Kartendaten: [OpenStreetMap-Mitwirkende, ODbL](https://www.openstreetmap.org/copyright).
Renderer und Standardstil: [Mapsforge](https://github.com/mapsforge/mapsforge)
(Lizenzhinweise und Quellcode dort); Anbindung ueber osmdroid-mapsforge.

#### Freie Luftbilder statt API-Schluessel

Android verwendet fuer die Luftbildansicht keine kostenpflichtige MapTiler-
Anbindung mehr. Weder Nutzer noch App-Betreiber benoetigen dafuer ein Konto,
einen API-Schluessel oder eine Zahlungsvereinbarung. Die PWA-Konfiguration
bleibt unveraendert; dortige Schluessel werden nicht gelesen oder angepasst.
Eine frueher gespeicherte Auswahl `SATELLITE` wird lokal auf `AERIAL`
uebernommen. Der Menuepunkt heisst **Luftbild**, weil amtliche Orthofotos aus
Luftaufnahmen und keine weltweite Satellitenkarte angezeigt werden.

Zunaechst angebunden ist **Geobasis NRW / WMTS NW DOP**. Die
[amtlichen Dienstmetadaten](https://www.wmts.nrw.de/geobasis/wmts_nw_dop/1.0.0/WMTSCapabilities.xml)
bestaetigen gebuehrenfreie Bereitstellung, Zugriff ohne Anmeldung und die
[Datenlizenz Deutschland - Zero 2.0](https://www.govdata.de/dl-de/zero-2-0).
Die Quelle wird auf der Karte genannt und die Lizenz ist verlinkt.

Die Implementierung nutzt die Web-Mercator-Matrix `EPSG_3857_16` mit 256-Pixel-
Kacheln. Deren Matrix `00` entspricht XYZ-Zoom 5; die Matrixnummer ist deshalb
immer `XYZ-Zoom - 5`, zweistellig formatiert. Android begrenzt diese Ansicht
auf Zoom 5 bis 20. Dadurch bleiben die Karte und Markeranker georeferenziert;
der Cache ist von OpenStreetMap und Offlinekarten getrennt.

**Die Luftbildansicht ist derzeit regional auf NRW begrenzt.** Liegt das ganze
Revier nicht innerhalb der vom Dienst veroeffentlichten rechteckigen Ausdehnung,
ist die Option deaktiviert. Dieses Rechteck ist nur eine raeumliche Vorpruefung,
kein Nachweis vollstaendiger Bildabdeckung: Auch innerhalb dieses Rechtecks
koennen insbesondere ausserhalb der Landesgrenze oder am Rand Kacheln fehlen.
Andere Bundeslaender und Laender sind noch nicht angebunden. Es wird keine
deutschlandweite oder globale Luftbildabdeckung versprochen.

Die Ansicht laedt nur interaktiv angefragte Kacheln; kein Massendownload und
kein automatischer Wechsel zu einem kostenpflichtigen Anbieter. Sie benoetigt
Internet und hat keine Verfuegbarkeitsgarantie. Uebliche Mobilfunk-Datenkosten
koennen anfallen. Bei einer nicht mehr passenden Revierregion bleibt eine
bereits gewaehlte Luftbildansicht mit Hinweis ohne Online-Fallback leer;
OpenStreetMap kann der Nutzer selbst auswaehlen. Offlinekarten bleiben davon
unabhaengig. Eine einzelne NRW-Bildkachel sowie die native Luftbildansicht bei
320 dp wurden automatisiert ohne Anmeldung oder Schluessel erfolgreich geprueft.

### Oeffentliche Reviersuche

Die Suche ist ueber das Lupensymbol in der oberen Kartenleiste erreichbar und
funktioniert ohne Anmeldung. Die App uebernimmt die 15 oeffentlichen
Suchquellen der PWA unveraendert hinsichtlich URL, Protokollparametern,
Namens-/ID-Feldern, Quellenangabe und Lizenzmetadaten: 3 WFS-, 6 ArcGIS-REST-,
5 OGC-API-Features-Dienste und den datierten GeoJSON-Snapshot des Rhein-Kreises
Neuss. Das sind die bei Portierung aus der PWA uebernommenen, dort bereits
geprueften Eintraege; die PWA-Registry und PWA-Funktionen bleiben unangetastet.

Die Suche priorisiert keine als offiziell markierten Anbieter. Der Nutzer
waehlt die gewuenschte Quelle aus und sucht in deren Namen und Featurefeldern.
Treffer zeigen Quelle, verfuegbare Metadaten und Lizenz. **Auf Karte zeigen**
legt nur eine lokale, temporaere Grenzvorschau an. **Eigenes Revier anlegen**
verlangt eine Anmeldung und legt das Profil ueber den bestehenden
`create_hunting_profile`-RPC sowie die vorhandene Grenz-RPC an. Es gibt keine
neue Backendmigration. Bei eigenen Quellen wird das Profil unter dem
vorhandenen `user-imports`-Eintrag gespeichert; externe Quellenmetadaten werden
nicht als neue Serverquelle registriert.

Eine einzelne oeffentliche Grenze kann im Trefferdetail ausdruecklich mit
**Offline speichern** fuer spaeter vorgemerkt werden. Bei Quellen mit nicht
konkret ausgewiesener Lizenz muss der Anwender bestaetigen, dass er die
Nutzungsbedingungen geprueft hat. Unter **Offline-Grenzen** lassen sich die
gespeicherten Umrisse samt Name, Quelle, Lizenz und Speicherzeit ohne
Netzverbindung wieder oeffnen oder entfernen. Es werden keine Profile angelegt
und keine Serverdaten geschrieben. Die Kopien liegen getrennt von
Mapsforge-Basiskarten im privaten, vom Android-Backup ausgeschlossenen
`noBackupFilesDir`; sie werden nicht mit der PWA oder einem Konto synchronisiert.
Grenzen sind auf 4 MiB pro Feature, 64 MiB insgesamt und 100 Eintraege begrenzt.
Es gibt keinen automatischen Download oder automatische Aktualisierung. Zum
Aktualisieren die gespeicherte Grenze entfernen, online neu suchen und erneut
ausdruecklich speichern.

Die Suche selbst braucht Internet. Offline verfuegbar sind nur zuvor gespeicherte
Einzelgrenzen und die unabhaengig installierten Mapsforge-Pakete. Profile,
Marker und Teilnehmer-/Hundepositionen aus Supabase werden durch diese Funktion
nicht dauerhaft lokal gespeichert. Beim App-Neustart ist daher keine private
Profil-/Live-Datenansicht versprochen.

Unter **Quellen verwalten** koennen Anwender eigene HTTPS-GeoJSON- und
WFS-2.0-Quellen hinzufuegen, bearbeiten und lokal loeschen. Je Eintrag sind
Anzeigename, URL, WFS-Layer, Namens-/ID-Felder, Quellenangabe und Lizenzlinks
konfigurierbar. Eigene Eintraege gelten nur auf diesem Geraet und werden nicht
mit der PWA synchronisiert. Die Quellenliste bleibt gleichberechtigt; es gibt
keine Vorrangregel fuer amtliche Anbieter.

Alle Dienste muessen Polygon- oder MultiPolygon-Features als GeoJSON liefern.
WFS wird mit 500 Features pro Seite abgefragt, OGC-API-Seitenlinks duerfen den
Ursprung nicht wechseln, ArcGIS verwendet dessen Feature-/Offset-API. Maximal
40 Seiten beziehungsweise 20.000 Features und 12 MB pro Antwort werden
verarbeitet; ueber alle Seiten zusammen gelten zusaetzlich 48 MB und 90 Sekunden.
Geometrien werden mit JTS normalisiert und auf Gueltigkeit
geprueft, bevor sie angezeigt oder zur Profilanlage angeboten werden. Die
Suche sendet erst nach **Suchen** eine HTTPS-Anfrage direkt vom Geraet an die
ausgewaehlte Quelle; es gibt keinen Proxy. Lokale/private Zieladressen,
Zugangsdaten in URLs, unsichere URLs und unsichere Weiterleitungen werden
abgelehnt. Keine Quellenzugriffe schreiben Daten in die Dienste.

Die PWA-Registry verwendet keinen WPS. WPS ist deshalb kein Eingabetyp in
Android; konkrete WPS-Prozesse koennen spaeter nur bei einem festgelegten
Prozessvertrag mit GeoJSON-Ergebnis ergaenzt werden.

Die JVM-Tests decken GeoJSON-Validierung, Quellensicherheit, WFS-/ArcGIS-
Parameter, OGC-Paginierungsursprung, Umlautsuche und Speicherbudgets ab. Sieben Emulator-Tests
pruefen die 15er-Registry, lokale benutzerdefinierte Quellen, anonyme Suche,
Kartenvorschau, lokale Offline-Grenzen und Fortsetzung der Profilanlage nach Anmeldung. Drei weitere Offline-Store-Tests
pruefen Wiederladen, Deduplizierung, Entfernen und Speicherlimits. Der
Quellenmanager wurde zusaetzlich im Emulator mit dem Ablauf GeoJSON-Adresse
hinzufuegen, auswaehlen und durchsuchen geprueft. Fuer diese Tests wurde keine
echte Quelle und kein Supabase-Schreibzugriff verwendet.

#### Isolierter Offline-Renderertest

`MapAppearanceInstrumentedTest` prueft Menue, fehlenden Online-Fallback,
WMTS-Zoomumrechnung, Importfehler, Deduplizierung, erneutes Laden, Loeschen sowie
echtes Rendering und Bildpixel. Fuer die drei Tests mit echter Karte muessen
`liechtenstein.map` und `liechtenstein.poly` aus den oben genannten Verzeichnissen
im externen Test-App-Verzeichnis
`/sdcard/Android/data/org.wierichs.jagdkarte/files/` liegen. Die APK zuerst im
schreibgeschuetzten AVD installieren, dann beide Dateien per `adb push` dorthin
uebertragen. Ohne Fixture werden nur diese drei Tests uebersprungen. Der
ausgefuehrte Lauf hatte zehn Tests, null Fehler und null uebersprungene Tests.
Der zusaetzliche Luftbild-Livetest wird mit
`-Pandroid.testInstrumentationRunnerArguments.liveAerial=true` aktiviert; ohne
diese ausdrueckliche Aktivierung wird nur dieser Live-Test uebersprungen.
Der ausgefuehrte Lauf hatte sowohl Fixture als auch Live-Test aktiviert und
pruefte echte Luftbilder, Quellenangabe, fehlende Revierabdeckung und die
uebernommene alte Karteneinstellung. Die native Karte wurde bei 320 dp mit
Bildpruefung getestet.
Fuer diese Tests sind weder Anmeldung noch Supabase-Schreibzugriffe erforderlich.

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

Verifiziert: Die vollstaendige Debug-Suite umfasst 80 JVM- und 61
Android-Instrumentierungstests. Abgedeckt sind unter anderem Nachrichten,
Relay-Kryptografie, Grenzpruefung, Positionsalter, Teilnehmer-RPC,
Marker-Schreib-/Bearbeitungsvertraege, App-Start und Navigation,
Login-Dialog, SQLite-Migration, atomare Relay-Bestaetigungen, Keystore,
Kartenpositionswahl, Hunde-/Hilfedialoge, Markerbearbeitung,
Loeschbestaetigung und ausdrueckliche Relaiszustimmung.
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
3. Mit Tracker-Kamera den in Jagdkarte angezeigten QR-Code scannen oder den Schlüssel manuell eingeben. Ohne Tracker-Kamera im DogTracker **QR-Code fuer Jagdkarte anzeigen** wählen, danach in Jagdkarte beim Hund **DogTracker-QR scannen**. Nach der Erfolgsmeldung in Jagdkarte zum Tracker zurückkehren und **Jagdkarte hat gekoppelt** bestätigen.
4. Der QR-Schlüssel wird nur für diesen Kopplungsvorgang angezeigt; geheim halten und nur mit der Jagdkarte des gewünschten Reviers scannen.
5. Das Standortintervall festlegen und **Tracking starten** wählen. Beim ersten Start benötigt die App Zugriff auf den genauen Standort und, ab Android 13, auf Benachrichtigungen.

Der Gegenweg DogTracker-QR benötigt die additive RPC-Migration [`register_hunting_dog_device_token`](https://github.com/wierichs/wierichs.github.io/blob/main/Jagdkarte/supabase/migrations/202611110001_register_hunting_dog_device_token.sql) im gemeinsamen Supabase-Projekt. Sie ist im PWA-Repository vorbereitet, aber noch nicht ausgerollt; bis dahin bleibt **Smartphone koppeln** in Jagdkarte der verfügbare Weg.

Die App ist für Android ab Version 12 vorgesehen. Pro Hund kann derzeit ein Smartphone gleichzeitig gekoppelt sein. Wenn ein Smartphone ersetzt wird oder der Schlüssel verloren geht, muss es in der Jagdkarte erneut gekoppelt werden.

Owner und Bearbeiter können einen registrierten Hund in **Hunde im Revier** nach einer Bestätigung dauerhaft löschen. Dabei werden auch die Serverkopplung und die gespeicherte Hundeposition entfernt; Gäste können keine Hunde löschen.

## Funktionen

- QR-Code scannen oder den 64-stelligen Geräteschlüssel manuell eingeben.
- Standortintervall zwischen 15 Sekunden und 60 Minuten wählen; bei der ersten Einrichtung sind 15 Sekunden voreingestellt. Ein geänderter Wert bleibt gespeichert.
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

Die Jagdkarte zeigt Hundepositionen im gekoppelten Revierprofil an, auch außerhalb der Reviergrenze. Die [Android-Hilfe](docs/help/hilfe.md) und Fehlerbehebung öffnest du auch über das Info-Symbol in der Kartenleiste. Die [PWA-Hilfe](https://wierichs.github.io/Jagdkarte/hilfe.html#hunde) bleibt zusätzlich für die Web-App verfügbar.