# Jagdbruder fuer Android

Dieser Branch stellt die Funktionalitaet der Jagdkarte zusaetzlich als native
Android-App **Jagdbruder** mit Kotlin, Jetpack Compose und einer nativen osmdroid-Karte bereit,
ohne WebView. Die Android-App ergaenzt die PWA und soll sie nicht abloesen.
Die PWA bleibt als eigenstaendiges Angebot mit ihrem jetzigen Funktionsumfang
bestehen; ihre Funktionen werden weder entfernt noch auf Android verlagert.
Die bestehende Hundetracker-Funktion bleibt als Ansicht **DogTracker** erhalten.
Das separate Web-Projekt wird nicht veraendert. Beide Clients verwenden dieselben
vorhandenen Supabase-Daten und Berechtigungen. Erweiterungen des gemeinsamen
Backends muessen zu den bestehenden PWA-Funktionen und Schnittstellen
rueckwaertskompatibel bleiben.

Die Android-App heisst Jagdbruder; ihre App-ID bleibt `org.wierichs.jagdkarte`.
Der neue Name aendert weder vorhandene App-Daten und Kopplungen noch
Anmeldelinks, Backend-Vertraege oder den Signaturschluessel.
**Jagdkarte** und **DogTracker** bleiben die Namen der beiden App-Bereiche;
die PWA heisst weiterhin Jagdkarte. Die Android-App kann neben
`org.wierichs.dogtracker` installiert werden; dessen Einstellungen und
Geraetekopplung werden nicht automatisch uebernommen. Mindestversion: Android 12.
Beim ersten Start oeffnet die Jagdkarte mit einer ueberspringbaren Erklaerung
der Jagdberechtigungen; danach stellt die App den zuletzt genutzten Bereich und
das letzte Revier wieder her.

Nutzeranleitungen und Fehlerbehebung stehen in der [Hilfe fuer Android](docs/help/hilfe.md). Diese Markdown-Datei wird auch im nativen Hilfe-Dialog angezeigt.

## App-Symbol

Das native Symbol basiert auf der gelieferten [Logo.png](Logo.png): dunkelgruener
Hintergrund, weiss gefaltete Karte mit gestrichelter Weglinie, goldfarbener
Hirschkopf oben links und Vorstehhund unten rechts. Die Formen und Proportionen
der Vorlage bleiben erhalten; die PWA selbst bleibt unveraendert.

Das Skript trennt die weissen und goldfarbenen Bildanteile vom gruenen Hintergrund
und den weissen aeusseren Bildecken und erzeugt einen transparenten Vordergrund.
Dieser wird ohne Verzerrung innerhalb des sicheren 66-dp-Kreises des adaptiven
Android-Icons platziert. Rastervarianten fuer alle fuenf Bildschirmdichten und
der adaptive Vordergrund werden unter Windows ohne zusaetzliche Grafikpakete
neu erzeugt:

```powershell
.\tools\generate-launcher-icons.ps1
```

Das [Erzeugungsskript](tools/generate-launcher-icons.ps1) prueft den sicheren
Zuschnitt anhand aller nichttransparenten Pixel des erzeugten Vordergrunds.
Das einfarbige Android-Themen-Icon verwendet dessen Transparenzmaske, ohne den
gruenen Hintergrund mitzufaerben.
Mit `-PreviewPath <PNG-Datei>` kann zusaetzlich eine Vorschau mit runden,
abgerundeten, kleinen und einfarbigen Varianten gespeichert werden.

## QR-Scanner

Die QR-Kamera wird ueber den Google Code Scanner der Google Play-Dienste
geoeffnet. Jagdbruder benoetigt dafuer keine eigene Kameraberechtigung.
Das Scanner-Modul muss auf dem Geraet verfuegbar sein; Google Play-Dienste
koennen es beim ersten Aufruf nachladen.

Die [Release-Regeln](app/proguard-rules.pro) erhalten gezielt die reflektiv
aufgerufenen Konstruktoren der ML-Kit-Komponentenregistrierung.
Ohne diese Regel entfernt die Release-Optimierung benoetigte Konstruktoren,
sodass die Scanner-Erstellung vor dem Oeffnen der Kamera abstuerzt.
Die restliche Release-Optimierung bleibt aktiviert.

## Stand der Portierung

Die Funktionen werden schrittweise zusaetzlich nativ angeboten. Der aktuelle
Android-Funktionsumfang ist kleiner als der der PWA; deren bestehendes Angebot
bleibt davon unberuehrt.

- Native OpenStreetMap-Karte mit Zoom, Verschieben und Quellenangabe.
- Anmeldung und Registrierung per E-Mail-Link mit Zahlencode als Alternative
	gegen das bestehende Supabase-Auth.
	Benutzer- und Tracker-Geheimnisse liegen getrennt, Keystore-verschluesselt im
	privaten App-Speicher. Benutzersitzungen werden erneuert; Abmelden entfernt die
	lokale Sitzung auch bei einem Serverfehler.
- Auswahl eigener und geteilter, bereits vorhandener Revierprofile. Die
	serverseitigen RLS-Regeln bestimmen den Zugriff.
	Ausstehende E-Mail-Einladungen werden vor dem Laden der Revierauswahl bei
	Start, Rueckkehr zur Kartenansicht und Aktualisierung fuer das bestehende
	Konto angenommen, nicht nur nach einer neuen Anmeldung. PWA-Einladungslinks
	ohne lokale Mailanforderung wechseln ein angemeldetes Konto nicht automatisch;
	die serverseitig bestaetigte Konto-E-Mail bestimmt den Revierzugang.
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
- Geraetebezogene Einrichtung unter **Benutzerkonto -> Berechtigungen und
	Freigaben einrichten**. Genauer Standort, Nearby-Bluetooth/-WLAN, lokales
	Netzwerk und Benachrichtigungen werden je nach Android-Version gemeinsam
	erklaert und angefordert. Ohne vollstaendige Berechtigungen und ausdrueckliche
	App-Freigabe bleiben Jagdmodus-Steuerung, Livepositionen und Tracking-Start
	ausgeblendet; Karten- und Verwaltungsfunktionen bleiben nutzbar.
- Nach der geraetebezogenen Zustimmung laufen Relais und eigene Standortfreigabe
	im Jagdmodus des ausgewaehlten Reviers automatisch; eine weitere Revierfreigabe
	oder ein separater Relaisschalter entfaellt, auch in der Benachrichtigung.
	Ein frischer GPS-Fix, die Grenzpruefung mit JTS und frische Onlineautorisierung
	bleiben erforderlich. Fehlgeschlagene Relaisstarts werden automatisch erneut
	versucht. Bestehende Installationen muessen die erweiterte automatische
	Standortfreigabe einmal in der Einrichtung bestaetigen; alte Zustimmungen
	oder vorhandene Android-Berechtigungen allein reichen nicht.
	Wechsel, Abmelden und Jagdmodus aus beenden die bisherige eigene Uebertragung.
	Beim Pausieren der Karte endet die eigene Standorterfassung; das Relais kann
	sichtbar im Hintergrund weiterarbeiten. **Jagdfunktionen deaktivieren**
	beendet alle Jagddienste. Ein ausdruecklich gestarteter DogTracker uebernimmt
	das Relais erst nach beendetem Revier-Relais, ohne parallele Teilnehmerortung.
- Jagdmodus-Steuerung durch Owner und Bearbeiter sowie Hunderegistrierung und
	Smartphone-Kopplung ueber die vorhandenen, serverseitig geschuetzten RPCs.
	Einmalige Schluessel erscheinen als QR-Code in einem gegen Screenshots
	geschuetzten Dialog; eine neue Kopplung muss bestaetigt werden.
- Vorhandener DogTracker mit Foreground Service, persistenter Outbox und
	experimentellem Ein-Hop-Relay. Der Nearby-Sicherheitsdialog ist auch auf der
	Kartenansicht erreichbar.
- Gemeinsame Kommunikationsbasis mit ausdruecklicher Revier-Relaisfreigabe ohne
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
ausgewaehlte Quelle; es gibt keinen Proxy. Lokale, private und weitere nicht
global routbare IPv4-/IPv6-Sonderbereiche (einschliesslich Shared Address Space
`100.64.0.0/10`) werden blockiert. Jede DNS-Antwort muss global routbar sein;
die geprueften Adressen werden fuer die Verbindung verwendet, waehrend die
TLS-Hostnamepruefung aktiv bleibt. Weiterleitungen sind nur innerhalb desselben
HTTPS-Ursprungs erlaubt. Zugangsdaten in URLs und unsichere URLs werden
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
werden nie an ein Relais gegeben. Die Revierzustimmung erklaert und erlaubt
Gateway und eigene Standortfreigabe gemeinsam; die Android-Berechtigung allein
ist keine Einwilligung zum Teilen. Die Gateway-Benachrichtigung kann im Hintergrund weiterlaufen,
waehrend eigene Teilnehmerfixes weiterhin nur bei aktiver Karte erfasst werden.
Alte DogTracker-Nachrichten koennen weitergeleitet werden; alte APKs
unterstuetzen die neuen Rueckbestaetigungen noch nicht. Grenzen,
Schluesselverwaltung und Bereitstellung: [Anleitung zur Relay-Serverstufe](https://github.com/wierichs/wierichs.github.io/blob/main/Jagdkarte/supabase/location-relay.md).

### Einrichtung und Pruefung

Die App verwendet die oeffentliche Supabase-Projektkonfiguration der Web-App.
Voraussetzung ist das dort dokumentierte aktuelle Datenbankschema samt Auth-
E-Mail-Vorlagen und `dog-location`/`dog-location-relay`. Die native Anmeldung
fordert eine E-Mail mit Rueckkehradresse `https://wierichs.github.io/Jagdkarte/`
an. Den Link aus der neuesten Nachricht auf demselben Handy oeffnen;
alternativ bleibt die Eingabe des Zahlencodes moeglich.

Die bestehenden Vorlagen fuer **Confirm sign up** und **Magic link or OTP**
koennen ihren PWA-kompatiblen Link beibehalten:

```html
<a href="{{ .RedirectTo }}?token_hash={{ .TokenHash }}&amp;type=email">Anmelden</a>
<p>Alternativ in der App diesen Zahlencode eingeben: <strong>{{ .Token }}</strong></p>
```

Die Rueckkehradresse muss im Supabase-Projekt freigegeben sein. Laut Rueckmeldung
wurde `{{ .Token }}` in beiden Vorlagen ergaenzt; eine echte Mail-/Serverabnahme
steht noch aus. Die PWA und der gemeinsame Serververtrag bleiben unveraendert.

Android verifiziert `token_hash` mit `type=email` direkt beim Auth-Server;
auch Standard-PKCE-Rueckgaben mit `code` werden unterstuetzt. Die App speichert
vor der Mailanforderung E-Mail-Adresse und PKCE-Pruefwert verschluesselt und
dauerhaft. Ein Link braucht eine auf diesem Handy gestartete, hoechstens eine
Stunde alte Anmeldeanforderung; die serverseitig bestaetigte E-Mail-Adresse muss
dazu passen. Nur der PKCE-Codeaustausch prueft den lokalen Pruefwert auch
serverseitig, nicht die direkte Token-Hash-Verifikation. URL-Zugangstoken werden
nicht als Sitzung uebernommen. Erfolgreiche Link- oder Codeanmeldung und
Abmelden entfernen die lokale Anmeldeanforderung.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

Ein JDK und das Android SDK muessen eingerichtet sein. Die zweite Pruefung
benoetigt einen laufenden Emulator oder ein angeschlossenes Geraet. Die APK liegt
unter `app/build/outputs/apk/debug/Jagdbruder-debug.apk`.
Die Debug-Variante verwendet weiterhin die Android-Debug-Signatur und kann
eine mit dem Release-Schluessel signierte Installation nicht aktualisieren.

Verifiziert: Die vollstaendige Debug-Suite umfasst 80 JVM- und 61
Android-Instrumentierungstests. Abgedeckt sind unter anderem Nachrichten,
Relay-Kryptografie, Grenzpruefung, Positionsalter, Teilnehmer-RPC,
Marker-Schreib-/Bearbeitungsvertraege, App-Start und Navigation,
Login-Dialog, SQLite-Migration, atomare Relay-Bestaetigungen, Keystore,
Kartenpositionswahl, Hunde-/Hilfedialoge, Markerbearbeitung,
Loeschbestaetigung und ausdrueckliche Revierzustimmung.
Die Marker-Dialoge werden mit lokalen Testdaten, ohne Produktionsschreibzugriff
und auch bei 320 dp geprueft. Eine Anmeldung gegen Produktionsdaten, echte Rollen-/RLS-Schreibtests,
reale GPS-Erfassung und Nearby-Transfers zwischen mehreren physischen Geraeten
sind noch nicht abgenommen. Fuer die neue Serverstufe gibt es zusaetzlich
20 lokale Deno-/PostgreSQL-Fixture-Tests; sie ersetzen keine echte
Supabase-/PostGIS-Abnahme und keinen physischen Nearby-Transfer.

Die neue Berechtigungsbedienung wurde separat mit 20 Android-Tests auf API 37
und 15 JVM-Tests fuer Berechtigungsgrenzen und Standortvertraege geprueft;
Debug- und Release-Build bestehen. Dabei wurden die drei Android-Testklassen
direkt gemeinsam ueber den Instrumentierungstestlaeufer ausgefuehrt, da der
Gradle-Aufruf mit kommasepariertem Klassenparameter nur die erste Klasse
ausfuehrte. Reale Geraete-/Funk- und Live-Serverpruefungen bleiben offen.

## Signierte Android-Release-APK

Der vorhandene Release-Keystore bleibt ausserhalb des Repositorys. Fuer Updates
muss derselbe Signaturschluessel wie bei der bisherigen APK verwendet werden.
Die vier Gradle-Properties `jagdkarteReleaseStoreFile`,
`jagdkarteReleaseStorePassword`, `jagdkarteReleaseKeyAlias` und
`jagdkarteReleaseKeyPassword` werden ausschliesslich lokal in der
Gradle-Benutzerkonfiguration `%USERPROFILE%\.gradle\gradle.properties` gesetzt.
Diese Datei enthaelt die Passwoerter im Klartext: nicht teilen, nicht ins
Repository kopieren und nur fuer den eigenen Benutzer zugaenglich halten.
Backslashes in dieser Properties-Datei muessen verdoppelt werden, auch in
Passwoertern, falls dort Backslashes vorkommen.

```powershell
.\gradlew.bat :app:assembleRelease -PreleaseSigning=true --no-configuration-cache --no-daemon --console=plain
```

Die signierte APK liegt unter `app/build/outputs/apk/release/Jagdbruder.apk`.
Fehlen bei angeforderter Signierung Zugangsdaten oder Keystore, bricht der
Build ab; er faellt nicht auf eine unsignierte APK zurueck. Ohne
`-PreleaseSigning=true` bleibt der bisherige unsignierte Release-Build erhalten.
Keine Build-Scans oder Debug-Logs mit den Signierzugangsdaten weitergeben.
Nach dem Build die APK mit dem `apksigner` aus den Android-SDK-Build-Tools
pruefen und den SHA-256-Zertifikatsfingerabdruck mit der bisherigen signierten
APK vergleichen.

Die lokale Signiereinrichtung wurde am 2026-11-13 mit einem erfolgreichen
Release-Build und `apksigner verify` geprueft; das Signaturzertifikat stimmt mit
der bisherigen, ueber Android Studio erstellten Release-APK ueberein.
Fuer die Umbenennung in Jagdbruder ist keine Aenderung der lokalen
Signierkonfiguration erforderlich: Keystore, Alias und die bestehenden
`jagdkarteRelease...`-Properties bleiben unveraendert.

## DogTracker

Der Hundetracker übermittelt den GPS-Standort eines Smartphones an ein Hund-Profil in der [Jagdkarte](https://wierichs.github.io/Jagdkarte/). So kann das Smartphone beim Hund mitgeführt werden und dessen Position im zugehörigen Revierprofil sichtbar machen.

## Einrichtung

1. In der Jagdkarte anmelden, ein Revierprofil öffnen und den Jagdmodus einschalten.
2. Unter **Hunde im Revier** einen Hund registrieren und **Smartphone koppeln** auswählen.
3. Entweder den von Jagdkarte angezeigten QR-Code im DogTracker scannen (oder den Schlüssel manuell eingeben), oder im DogTracker **QR-Code fuer Jagdkarte anzeigen** wählen und beim passenden Hund in Jagdkarte **DogTracker-QR scannen**.
4. Bei einem DogTracker-QR bleibt Jagdkarte nach dem Scan im Dialog **Warten auf Bestaetigung** und nennt Hund und Revier. Auf dem anderen Smartphone im DogTracker **Kopplung bestaetigen** tippen; Fortschritt und Fehler stehen direkt beim Knopf, der Erfolg erscheint in einem eigenen Dialog. Jagdkarte zeigt **Kopplung erfolgreich** erst nach Serverbestaetigung fuer genau den gescannten QR-Code. Bis dahin bleibt der bisherige Schlüssel aktiv. Wird abgebrochen, bleibt der bisherige Schlüssel aktiv; nach einem unklaren Timeout kann die Bestätigung wiederholt werden.
5. Den QR-Schlüssel geheim halten und nur mit dem gewünschten Revier koppeln. Die Jagdberechtigungen und Relaisfreigabe im Benutzerkonto oder im DogTracker einrichten. Das Standortintervall festlegen und **Tracking starten** wählen; diese Aktion erscheint erst mit vollständigen Berechtigungen.

Der DogTracker-QR-Gegenweg benötigt die additiven RPC-Migrationen [`202611110001_register_hunting_dog_device_token.sql`](https://github.com/wierichs/wierichs.github.io/blob/main/Jagdkarte/supabase/migrations/202611110001_register_hunting_dog_device_token.sql) und [`202611110002_two_phase_dog_device_pairing.sql`](https://github.com/wierichs/wierichs.github.io/blob/main/Jagdkarte/supabase/migrations/202611110002_two_phase_dog_device_pairing.sql) im gemeinsamen Supabase-Projekt. Laut Rückmeldung wurden beide Migrationen ausgeführt und das PWA-Repository aktualisiert; Zielprojekt und Laufzeitverhalten sind hier nicht unabhängig verifiziert. Jagdkarte-QR und manuelle Schlüsseleingabe bleiben ebenfalls verfügbar.

Fuer die neue serverbestaetigte Rueckmeldung zusaetzlich
[`202611130001_dog_device_pairing_status.sql`](supabase/migrations/202611130001_dog_device_pairing_status.sql)
im SQL Editor **desselben Supabase-Projekts** ausfuehren und die Migration auch
in die Migrationen des PWA-Repositories uebernehmen, bevor dessen Datenbankschema
erneut ausgerollt wird. Die Erweiterung fuegt nur die lesende RPC
`get_hunting_dog_device_pairing_status` hinzu: Owner und Bearbeiter koennen fuer
Hund und gescannten QR-Code `pending`, `active` oder `not_found` abfragen.
Tabellen, bestehende Kopplungen und PWA-Verhalten bleiben unveraendert; Gaeste
und nicht angemeldete Benutzer erhalten keinen Zugriff. Keine Signier-
oder App-Konfigurationsaenderung ist erforderlich.

Der Wartedialog prueft alle zwei Sekunden, solange er offen und die App sichtbar
ist. Bei einem Fehler bleibt der Hinweis sichtbar und **Kopplung erneut pruefen**
erlaubt einen erneuten Versuch ohne erneuten Scan. Fehlt die SQL-Erweiterung,
wird das ausdruecklich gemeldet, niemals als Kopplungserfolg behandelt.
Nach Schliessen des Dialogs endet diese Statuspruefung; Tracking startet weiterhin
nur durch deine ausdrueckliche Auswahl im DogTracker.

Der Hundedialog bleibt beim Wechsel zum Google-Scanner erhalten, auch wenn
die Karte im Hintergrund ihre Live-Daten entfernt. Das Scanner-Ergebnis und
der anschliessende Kopplungsauftrag werden dadurch nicht mehr zusammen mit
dem Dialog verworfen.

In **Hunde im Revier** sehen Owner und Bearbeiter je Hund einen **gruenen Haken**
fuer eine aktive Kopplung oder ein **rotes X** fuer einen Hund ohne aktive
Kopplung. **Bestaetigung ausstehend** kennzeichnet ein vorgemerktes Smartphone;
bei einem Ersatz bleibt ein alter aktiver Tracker gruen und die neue Kopplung
wird getrennt als ausstehend angezeigt. **Zur Hundeliste** fuehrt aus dem
Wartedialog zurueck. Die Liste aktualisiert sich alle fuenf Sekunden, solange
sie sichtbar ist, und ueber **Kopplungsstatus aktualisieren**. Fehler zeigen
**Status unbekannt**, nicht einen erfundenen Haken oder ein falsches X.
Der Haken sagt nichts ueber laufendes Tracking, Internet oder aktuelle GPS-Daten.

Dafuer zusaetzlich
[`202611130002_dog_pairing_states.sql`](supabase/migrations/202611130002_dog_pairing_states.sql)
im selben Supabase-Projekt ausfuehren und in die gemeinsamen PWA-Migrationen
uebernehmen. Die neue lesende RPC `get_hunting_dog_pairing_states` liefert nur
Hundekennung, aktive Kopplung und ausstehende Bestaetigung; keine Schluessel oder
Token-Hashes. Bestehende Tabellenrechte und Kopplungsfunktionen bleiben unveraendert.

Die SQL-Vertraege lassen sich ohne Produktionszugriff mit
`deno test --allow-read .\supabase\tests\dog_device_pairing_status_test.ts` pruefen
(isoliertes PostgreSQL/PGlite mit Testrollen und synthetischen QR-Daten).

Die App ist für Android ab Version 12 vorgesehen. Pro Hund kann derzeit ein Smartphone gleichzeitig gekoppelt sein. Wenn ein Smartphone ersetzt wird oder der Schlüssel verloren geht, muss es in der Jagdkarte erneut gekoppelt werden.

Owner und Bearbeiter können einen registrierten Hund in **Hunde im Revier** nach einer Bestätigung dauerhaft löschen. Dabei werden auch die Serverkopplung und die gespeicherte Hundeposition entfernt; Gäste können keine Hunde löschen.

## Funktionen

- QR-Code scannen oder den 64-stelligen Geräteschlüssel manuell eingeben.
- Standortintervall zwischen 15 Sekunden und 60 Minuten wählen; bei der ersten Einrichtung sind 15 Sekunden voreingestellt. Ein geänderter Wert bleibt gespeichert.
- Standort im Hintergrund über einen Android-Standortdienst erfassen. Eine dauerhafte Benachrichtigung zeigt die aktive Sitzung an und bietet auch eine Stopp-Schaltfläche.
- Position, GPS-Genauigkeit und Messzeitpunkt über eine verschlüsselte HTTPS-Verbindung übertragen.
- Bei fehlender Internetverbindung nur die neueste GPS-Position lokal puffern; sie wird nach 24 Stunden verworfen. Bei verfügbarer Verbindung folgt der Upload automatisch.
- GPS-Nutzdaten lokal mit AES-GCM und einem Android-Keystore-Schlüssel verschlüsseln; die Zeit-/Queue-Metadaten in SQLite bleiben lesbar. `location_outbox.db` ist von Cloud-Backup und Geräteübertragung ausgeschlossen.
- Während einer gestarteten Tracking-Sitzung auch als Relais teilnehmen: verschlüsselte und signierte Positionsmeldungen werden nach einer bestätigten Nearby-Verbindung per Bluetooth/WLAN an einen anderen Tracker übergeben. Die ausdrückliche Freigabe erfolgt gemeinsam mit der Berechtigungseinrichtung; ein separater Relaisschalter entfällt. Relay benötigt eine vorherige Online-Kopplung.
- Der Status unterscheidet lokal gespeicherte, noch ausstehende Meldungen von einer vom Server bestätigten Übertragung und zeigt weitere Wartemeldungen an.
- Den Geräteschlüssel geschützt im Android Keystore speichern.

Das Tracking beginnt erst nach **Tracking starten** und lässt sich in der App oder über die Benachrichtigung beenden. Ist der Jagdmodus in der Jagdkarte ausgeschaltet, stoppt der Tracker nach Rückmeldung des Servers und löscht noch gepufferte Positionen.

## Hinweise

Für die Erfassung sind aktivierter Standort und die erforderlichen Android-Berechtigungen nötig. Die Übertragung benötigt eine Internetverbindung; ohne Verbindung bleibt nur die neueste Position höchstens 24 Stunden auf dem Smartphone gespeichert. Relay-Nachrichten verwenden weiterhin ihre eigene Ablauf- und Bestätigungssemantik. Android-Energiesparfunktionen, Empfang und GPS-Bedingungen können Aktualisierungen verzögern. Der Tracker ist keine garantierte Echtzeitortung und kein Notrufsystem.

Die experimentelle Relaisfunktion bleibt ohne ausdrückliche App-Freigabe ausgeschaltet. **Jagdfunktionen deaktivieren** beendet aktive Teilnahme beziehungsweise Tracking, ohne Android-Standortzugriff für die reine Kartenanzeige zu entziehen. Beim Verbinden muss der Sicherheitscode auf beiden Geräten verglichen und bestätigt werden. Unterstützt wird derzeit ein einzelner Relay-Hop; die Ende-zu-Ende-Strecke ist noch nicht auf mehreren realen Geräten validiert.

Die Jagdkarte zeigt Hundepositionen im gekoppelten Revierprofil an, auch außerhalb der Reviergrenze. Die [Android-Hilfe](docs/help/hilfe.md) und Fehlerbehebung öffnest du auch über das Info-Symbol in der Kartenleiste. Die [PWA-Hilfe](https://wierichs.github.io/Jagdkarte/hilfe.html#hunde) bleibt zusätzlich für die Web-App verfügbar.