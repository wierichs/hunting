# Android-Portierungsstatus

Stand: 2026-11-11

## Ziel und Geltungsbereich

Die native Android-App soll die Funktionalitaet der PWA ergaenzen und die
PWA nicht ersetzen. Die PWA bleibt das unabhaengige, kanonische Angebot.
DogTracker und die bestehenden Endpunkte `dog-location` und
`dog-location-relay` bleiben erhalten. Die neue Funktion `location-relay` ist
additiv und wurde nur im Supabase-Testprojekt getestet, nicht in Produktion.

Diese Liste ist eine Statusdokumentation, keine Roadmap. Die Eintraege sind
nicht priorisiert und ihre Reihenfolge ist unverbindlich. Der jeweils aktuelle
Arbeitsgegenstand wird nur nach gemeinsamer Festlegung eingetragen.

## Statuslegende

- **Offen**: noch nicht umgesetzt.
- **In Arbeit**: Umsetzung laeuft.
- **Umgesetzt**: implementiert, Abnahme steht noch aus.
- **Abgenommen**: Umsetzung und vereinbarte Pruefung erfolgreich.
- **Angepasst**: fuer Android bewusst anders umgesetzt; Begruendung und
  Abnahme werden beim Eintrag dokumentiert.
- **Blockiert**: kann wegen einer dokumentierten Abhaengigkeit nicht fortgesetzt
  werden.

## Aktueller Fokus

Die zuvor offenen Punkte unter Konto, Hunde und Hilfe sind umgesetzt. Die
Android-Anmeldung bleibt bewusst beim E-Mail-Code; ein Hinweis im Anmeldedialog
und in der nativen Hilfe erklaert, dass Mail-Links zur Web-App fuehren koennen.
Owner und Bearbeiter koennen Hunde nach Bestaetigung ueber die vorhandene,
serverseitig berechtigungspruefende RPC loeschen. Die native Hilfe deckt die
PWA-Themen und Android-Abweichungen einschliesslich Fehlerbehebung ab. Drei
dedizierte Emulatorpruefungen fuer Hundeloeschung, Gaesterechte und Hilfe
bestehen; Live-RLS-/Schreibabnahmen bleiben Teil der manuellen Pruefung.

Start und Wiederaufnahme: Beim ersten Start ist DogTracker vorausgewaehlt;
danach werden letzter Tab und letztes Revier pro Konto, Kartenkamera pro
Revier und Vollbildansicht wiederhergestellt. Die Standortfreigabe bleibt als
explizite Zustimmung pro Konto/Revier gemerkt und wird nur nach frischer
Onlinepruefung, aktivem Jagdmodus und vorhandener Android-Berechtigung
reaktiviert.

Die Kartenbasis und Offlinekarten bleiben wie zuvor umgesetzt. Auf Wunsch
nach moeglichst kostenloser Nutzung wurde
MapTiler in Android durch frei zugaengliche amtliche Luftbilder ersetzt.
Android bietet OpenStreetMap, Luftbild (zunaechst Geobasis NRW, ohne Konto oder
Schluessel) und bei vollstaendiger lokaler Revierabdeckung Offline an.
Fuer Android wurde Mapsforge `.map` gewaehlt; Formatvergleich, freie Downloads,
Extraktgrenzen und Import sind in der README dokumentiert. Die PWA behaelt
PMTiles und bleibt unveraendert. Zehn Emulatortests einschliesslich
echter Offlinekarte, Rendering-/Pixelpruefung und Netz-Fallback-Schutz bestehen;
der echte NRW-Luftbildabruf und der native Kartenablauf wurden bei 320 dp
geprueft. Die 24 bestehenden
Karten-/Marker- und Grenzeditor-Emulatortests bestehen ebenfalls.
Weitere regionale Luftbildanbieter und reale Geraetepruefungen stehen aus.
Die NRW-Rechteckausdehnung ist nur eine Vorpruefung; Bildluecken an Landes-
und Dienstraendern sind moeglich. Grenzbearbeitung bleibt umgesetzt,
Live-Schreib-/RLS-Abnahme offen.

Manuelle Tests werden weiter am Ende der Portierung gesammelt ausgefuehrt.
Die bereits bestaetigte Dateiauswahl und der Neuimport-/Merge-Dialog bleiben
abgenommen und sollen nicht wiederholt werden. Die Checkliste am Dokumentende
enthaelt die offenen Supabase-Schreib-/RLS- und Datenintegritaetspruefungen.

## Offene beziehungsweise zu pruefende Funktionen

Die folgende Ersterfassung basiert auf dem bekannten PWA-Umfang. Bei der
Umsetzung koennen wir Eintraege praezisieren, aufteilen oder weitere PWA-
Funktionen ergaenzen. Ein Statuswechsel zu **Abgenommen** soll einen knappen
Pruefbeleg enthalten; ein erfolgreicher Build allein ist keine Funktionsabnahme.

### Reviersuche und Daten

- **Angepasst**: Oeffentliche Reviersuche ohne Anmeldung, bewusst ohne die
  restriktive PWA-Regel, offizielle Quellen vorzuziehen. Die 15 bereits in der
  PWA geprueften Datenquellen werden mit ihren URLs, Protokollparametern,
  Namens-/ID-Feldern und Quellenmetadaten uebernommen: 3 WFS, 6 ArcGIS REST,
  5 OGC API Features und ein datierter GeoJSON-Snapshot. Anwender waehlen die
  Quelle explizit und koennen eigene HTTPS-GeoJSON-/WFS-Quellen lokal ergaenzen,
  aendern und entfernen. WPS ist nicht Teil der PWA-Registry und bleibt ohne
  konkreten GeoJSON-Prozessvertrag unimplementiert. Treffer zeigen die
  uebernommenen Quellen-/Lizenzangaben; GeoJSON-Polygon-/MultiPolygon-Features
  werden mit JTS validiert. Begrenzte Paginierung, Antwort-/Feature-/Zeitlimits,
  HTTPS-only, private Zieladressen- und unsichere Redirect-Sperren sind enthalten.
  Treffer koennen anonym auf der Karte vorgeschaut werden; eigenes Profil wird
  erst nach Anmeldung ueber bestehende RPCs angelegt. Eigene Quellenprofile
  verwenden `user-imports`; keine Backendmigration. Zehn JVM-Vertragstests,
  sechs Such-Emulatorpruefungen und die vollstaendige Suite mit 61 Android-Tests
  bestehen. Offen bleiben Android-Live-Lesepruefungen und Profilanlage/RLS im
  isolierten Supabase-Testprojekt. Die PWA-Quellenbewertung wird nicht erneut
  vorgenommen; Abnahme prueft Android-Abruf, Geometrie und Anzeige.

### Profile und Zusammenarbeit

- **Umgesetzt**: Ein gepruefter GeoJSON-/WFS-/OGC-API-/ArcGIS-Fund kann nach
  Anmeldung als eigenes Profil angelegt werden. Bekannte PWA-Quellen behalten
  ihre `source_id` und Feature-ID; eigene Quellen verwenden den bestehenden
  `user-imports`-Eintrag ohne Backendmigration. Die Geometrie wird vor dem
  Schreiben validiert; bei Grenz-RPC-Fehler wird das neue Profil best-effort
  entfernt. JVM- und Emulator-Vertragstests bestehen; Live-RLS- und
  Profilanlage-Abnahme im isolierten Supabase-Testprojekt steht aus.
- **Umgesetzt**: Eigenes Revierprofil loeschen. Die Aktion ist nur fuer Owner
  sichtbar und wird vor dem Cascade-Delete bestaetigt; Editor und Gast koennen
  kein Profil loeschen. Die Owner-Regel ist per JVM-Test geprueft; Live-RLS-
  Abnahme steht noch aus.
- **Abgenommen**: Gaeste und Bearbeiter koennen ein geteiltes Revier lokal aus
  der Auswahl ausblenden und spaeter wieder einblenden. Die Mitgliedschaft auf
  dem Server bleibt bestehen; beim Ausblenden des aktiven Reviers werden die
  eigene Standortfreigabe und ein zugehoeriger Relaisdienst beendet. Die
  Ausblendung ist pro Benutzerkonto auf dem Geraet gespeichert. Manuelle
  Abnahme durch den Nutzer bestaetigt (2026-11-10).
- **Umgesetzt**: Gaeste und Bearbeiter per E-Mail einladen; Android speichert
  die Rolle per RLS-pruefendem PWA-RPC, fordert danach den Auth-OTP an und
  nimmt Einladungen nach verifizierter Anmeldung an. JVM- und Emulator-Tests
  bestehen; Live-Mail/RLS-Abnahme steht aus.
- **Umgesetzt**: Positionsbezogene Gastlinks erstellen und verwenden. Android
  erzeugt PWA-kompatible 32-Byte-URL-safe-Token, teilt den sieben Tage gueltigen
  Link und nimmt ihn nach verifizierter Anmeldung an; das Zielprofil wird
  geoeffnet und die Karte auf die verknuepfte Markierung fokussiert. JVM-Tests
  und 22 Emulator-Tests bestehen. Der Nutzer bestaetigt, dass ein per PWA
  geteilter Gastlink auf einem Android-15-Geraet das richtige Revier und die
  Zielmarkierung oeffnet. Die breitere Live-RPC-/Rollenabnahme steht aus.
  Die HTTPS-App-Link-Domain wurde auf dem Geraet als `verified` bestaetigt;
  die Browser-Weiterleitung einer Unterseite wurde geprueft. Der exakte Pfad
  `/Jagdkarte/` umfasst auch die PWA-Startseite
  ohne Gastlink; andere Unterseiten bleiben ausserhalb des App-Link-Filters.
- **Umgesetzt**: Eigenen Anzeigenamen im Benutzerkonto laden, bearbeiten,
  speichern und entfernen. Der Eintrag ist auf 60 Zeichen begrenzt und nutzt
  die bestehende RLS-geschuetzte Tabelle; Live-Schreib-/RLS-Abnahme steht aus.
- **Umgesetzt**: Bei geteilten Profilen die Verwalter-E-Mail ueber den
  RLS-pruefenden `get_hunting_profile_owner_emails`-RPC in der Profilauswahl
  anzeigen; bei nicht verfuegbarer Adresse wird die Owner-ID gekuerzt gezeigt.
  JVM-Vertragstest und Emulator-Regression sind erfolgreich; Live-RPC-Abnahme
  steht aus.

### Grenzen und Dateien

- **Umgesetzt**: Reviergrenzen im nativen Karteneditor bearbeiten. Owner und
  Bearbeiter koennen Punkte per Griff oder Kartenmitte verschieben, Punkte
  einfuegen/loeschen und Teilflaechen sowie befriedete Bereiche anlegen oder
  entfernen. Die letzte Teilflaeche und das Drei-Punkte-Minimum sind geschuetzt.
  Entwurf, Rueckgaengig/Wiederholen, Abbruchbestaetigung, JTS-Geometriepruefung
  und bestaetigtes Speichern ueber `set_hunting_profile_boundary` sind enthalten;
  vorhandene Marker bleiben unveraendert. Vor dem Schreiben werden Rolle und
  Ausgangsgrenze neu geladen; dies erkennt bereits gespeicherte fremde
  Aenderungen, ersetzt aber keine atomare serverseitige Konfliktpruefung.
  JVM- und sieben Editor-Emulatortests sowie 17 Karten-/Marker-Regressionstests
  bestehen; Editor-Screenshots bei normaler und 320-dp-Breite wurden geprueft.
  Live-Schreib-/RLS- und reale Geraeteabnahme stehen aus.
- **Umgesetzt**: JWD-, JSON- und GeoJSON-Dateien als neues eigenes Profil oder
  in ein bestehendes, editierbares Profil importieren. Die Merge-Variante
  vereinigt Polygon-/MultiPolygon-Grenzen einschliesslich befriedeter Bereiche,
  behaelt bestehende Marker bei und ueberspringt identische dauerhafte
  Einrichtungen. Auswahl mehrerer GeoJSON-Features, Vorschau, 10-MB-Grenze
  und Rollback bei Fehlern sind enthalten. JVM- und Emulator-UI-Tests bestehen.
  Dateiauswahl und Neu-/Merge-Dialog sind manuell vom Nutzer bestaetigt;
  offen bleibt die separate Live-Supabase-Schreib-/RLS-Pruefung.

- **Umgesetzt**: Reviergrenzen als PWA-kompatible JWD-Datei exportieren; Owner
  und Bearbeiter erhalten zusaetzlich dauerhafte Einrichtungen. Gaeste
  exportieren nur die Grenze, temporaere Marker werden ausgeschlossen. Nicht
  unterstuetzte Markertypen haben ein lokal konfigurierbares Mapping auf die
  sechs JWD-Typen; Standard ist die bisherige Hochsitz-Zuordnung. Abweichende
  Originaltypen werden als versionierter `[jagdkarte:v1:<type-id>]`-Suffix in
  der Titelzeile exportiert und beim Android-Import wiederhergestellt; das
  fruehere `jagdkarte`-Property wird beim Import weiterhin erkannt.
  Polygon, MultiPolygon, befriedete Bereiche, unbekannte Marker und Dateinamen
  sind durch JVM-Tests abgedeckt; 24 Emulator-Regressionstests bestehen.

### Markierungen

- **Umgesetzt**: Markierungen mit dem aktuellen Standort verschieben. Der
  JVM-Vertragstest ist erfolgreich; echte GPS-Erfassung und serverseitige
  RLS-Abnahme stehen noch aus.
- **Umgesetzt**: Gaeste koennen eigene temporaere Anschuss-, Wildsichtungs-
  und Standplatzmarkierungen loeschen. Android vergleicht `created_by` und
  Markertyp fuer die UI; die bestehende Supabase-DELETE-Policy bleibt die
  verbindliche Autorisierung. JVM- und 24 Emulator-Tests bestehen; Live-RLS-
  Abnahme steht aus.
- **Abgenommen**: PWA-Markertypen, Bezeichnungen und individuelle Symbole
  portiert. Alle 15 Assets rendern im Emulator-Instrumentierungstest in der
  vorgesehenen Groesse von 40 x 48 dp; der JVM-Katalogtest besteht.
- **Abgenommen**: Lokale Groessenauswahl fuer Reviermarker mit den PWA-Skalen
  0,8 / 1,0 / 1,25. Standard ist voreingestellt; die Auswahl wird auf dem
  Geraet gespeichert und per Emulator-Instrumentierungstest geprueft.
- **Abgenommen**: Teilnehmerrollen werden als Mitglied, Bearbeiter und Chef
  mit PWA-Farben und Lucide-Symbolen dargestellt; Hundepositionen haben eigene
  orange Symbole und werden bei veraltetem Stand grau. Die ein-/ausklappbare
  Legende zeigt Reviergrenze, Rollen und Hund. JVM- und 23 Emulator-Tests
  bestehen.

### Karte und Offline-Nutzung

- **Umgesetzt**: Eigenen Standort auf der Karte anzeigen, manuelles erneutes
  Einpassen des Reviers, Grenzanzeige ein-/ausschalten und Karten-Vollbild.
  Karten-/Marker-Emulatortests bestehen; reale Geraeteabnahme bleibt offen.
- **Umgesetzt**: Bewegungsrichtung von Teilnehmern und Hunden lokal aus zwei
  frischen GPS-Fixes derselben Person/des selben Hundes ableiten. Richtung wird
  nur bei Bewegung oberhalb der Genauigkeitsschwelle und hoechstens 90 Sekunden
  lang als rotierender Pfeil und Himmelsrichtung angezeigt; keine zusaetzlichen
  Netzabrufe und keine Kompass-/Blickrichtung. Geometrie- und Rauschfiltertests
  bestehen; GPS-/Pfeilverhalten bei realem Empfang bleibt manuell zu pruefen.
- **Umgesetzt**: Auswahl OpenStreetMap / Luftbild / Offline. Luftbilder nutzen
  den frei zugaenglichen Geobasis-NRW-WMTS unter dl-de/zero-2-0, ohne Konto,
  API-Schluessel oder Abrechnungsvertrag. MapTiler wurde aus Android entfernt;
  die PWA bleibt unveraendert. Web-Mercator-Matrix und Zoomversatz sowie
  Menuezustaende sind getestet; ein echter Bildabruf und die native Anzeige
  bei 320 dp mit Quellen-/Lizenzangabe bestehen. Zunaechst nur NRW, keine
  deutschlandweite Abdeckung. Die Dienstausdehnung dient als Vorpruefung;
  Landesraender koennen Luecken haben. Weitere Regionen und reale
  Geraeteabnahme sind offen; kein stiller kostenpflichtiger Fallback.
- **Umgesetzt**: Native Offlinekarten mit dem offenen OSM-Vektorformat
  Mapsforge `.map` statt PWA-PMTiles. Frei verfuegbare Laender-/Regionalpakete,
  Links zu Downloads und Extraktgrenzen, gestreamter Import, Deduplizierung,
  lokale Verwaltung und bestaetigtes Loeschen sind enthalten. `.poly` oder
  gleichnamiges Polygon-GeoJSON liefert die zugehoerige Extraktgrenze; allein
  die Dateirechtecke gelten nicht als Abdeckungsbeleg. Offline wird nur bei
  geometrisch vollstaendiger Revierabdeckung angeboten, auch durch mehrere
  Pakete gemeinsam. Ein gewaehlter Offline-Modus wechselt bei fehlender Karte
  oder Renderfehler nicht still auf Onlinekarten. Auswahl wird lokal gemerkt;
  Kartenpakete enthalten keine privaten Revierdaten. JVM-Abdeckungstests und
  Emulatorpruefungen mit echter Mapsforge-Datei bestehen. Reale
  Geraete-/Grosspaketabnahme bleibt offen; PWA-Offlineablauf bleibt unabhaengig.
- **Angepasst**: Statische App-Inhalte (Suchquellenregistry, Markerkatalog und
  Symbole) sind bereits in der APK enthalten. Oeffentliche Suchgrenzen werden
  nicht automatisch geladen oder gecacht; der Anwender kann einzelne Treffer
  ausdruecklich fuer Offline speichern. Bei unklarer Lizenz ist eine
  Bestaetigung der geprueften Nutzungsbedingungen erforderlich. Anzeige in der
  lokalen Offline-Grenzen-Liste mit Quelle, Lizenz und Speicherzeit sowie
  Oeffnen/Entfernen sind umgesetzt. Speicherung erfolgt getrennt von
  Mapsforge in `noBackupFilesDir`, maximal 4 MiB je Grenze, 64 MiB und 100
  Eintraege gesamt; kein Sync und keine automatische Aktualisierung. Der
  oeffentliche Grenzcache enthaelt keine privaten Profile, Marker oder
  Live-Positionen. JVM- und Offline-Store-/UI-Tests bestehen.
  Noch offen: reale Offline-Abnahme im Flugmodus einschliesslich Lizenzpruefung,
  Neustart, Entfernen und knappen Speicherplatzes sowie bestaetigen, dass dieser
  oeffentliche Cache keine privaten Serverdaten enthaelt.

- **Umgesetzt**: Letzte Reviergrenze und Markierungen nach erfolgreichem
  Onlineabruf lokal verschluesselt fuer den Offline-Read-only-Fallback halten.
  Speicherung in `noBackupFilesDir`, maximal sieben Tage, 4 MiB pro Revier,
  32 MiB und 20 Reviere insgesamt. Der Cache ist konto-/revierisoliert,
  unveraenderbar offline und schliesst Teilnehmer-/Hund-Livepositionen aus.
  Statische Profile pollen alle fuenf Minuten, aktiver Jagdmodus weiter alle
  30 Sekunden; wiederholte Netzwerkfehler verwenden exponentiellen Backoff.
  Cache-Verschluesselung, Limits und Ausschluss von Livepositionen sind getestet.

### Konto, Hunde und Hilfe

- **Angepasst**: Die native Android-App meldet sich bewusst mit E-Mail-Code an.
  Ein E-Mail-Link kann Browser oder PWA oeffnen und authentifiziert nicht
  zuverlaessig die native Sitzung. Der Unterschied wird im Anmeldedialog und in
  der Hilfe erklaert; die PWA unterstuetzt weiterhin ihren eigenen Link-Flow.
- **Umgesetzt**: Owner und Bearbeiter koennen registrierte Hunde nach
  ausdruecklicher Bestaetigung ueber `delete_hunting_dog` loeschen. Der
  bestehende Serververtrag prueft die Rolle und entfernt zugehoerige
  Tracker-Kopplung sowie Hundeposition per Cascade. Gaeste sehen keine
  Loeschaktion. Emulator-UI-Tests verwenden lokale injizierte Aktionen; eine
  Live-RLS-/Cascade-Abnahme bleibt offen.
- **Angepasst**: Hundekopplung funktioniert weiter rueckwaertskompatibel mit
  dem Jagdkarte-QR oder manuell. Zusaetzlich ist der Gegenweg vorbereitet:
  DogTracker zeigt einen zufaelligen einmaligen QR, Jagdkarte scannt ihn und
  bindet nur dessen SHA-256-Hash ueber eine Owner-/Editor-pruefende RPC.
  Die additive Migration `Jagdkarte/supabase/migrations/202611110001_register_hunting_dog_device_token.sql`
  liegt im PWA-Repository. Der Nutzer meldet, sie im SQL Editor ausgefuehrt und
  das PWA-Repository aktualisiert zu haben; Zielprojekt und Laufzeitverhalten
  wurden hier nicht unabhaengig verifiziert. Der bisherige Jagdkarte-QR- und
  Manuellweg bleibt erhalten. Tokenformat-, Parser- und injizierte UI-Tests
  bestehen.
- **Umgesetzt**: Native Hilfe und Fehlerbehebung ueber das Info-Symbol in der
  Kartenleiste. `docs/help/hilfe.md` ist die einzige redaktionelle Quelle; die
  README verlinkt darauf und die Android-App rendert dieselbe Datei offline als
  Markdown. Sie deckt die PWA-Hilfethemen ab und erklaert native Bedienung,
  Code-Anmeldung, Hundeloeschung und Android-Offlineverhalten. Emulatorpruefung
  der zentralen Themen besteht.

### Plattformbedingte Abweichungen

- **Zu bewerten**: PWA-Installation und Service-Worker-Updates haben keine
  direkte Android-Entsprechung. Der native Installations- und Updateweg muss
  stattdessen klar sein; eine Abweichung wird bei Bedarf als **Angepasst** mit
  Pruefbeleg festgehalten.

## Bereits vorhanden, nicht gleichbedeutend mit vollstaendiger Abnahme

- Anmeldung und Registrierung per E-Mail-Code sowie Auswahl vorhandener eigener
  und geteilter Revierprofile.
- Native Karte mit Reviergrenze, Markeranlage und Bearbeitung/Loeschung durch
  Owner und Bearbeiter, Jagdmodus und expliziter Teilnehmer-Standortfreigabe.
- DogTracker einschliesslich Hunderegistrierung und Smartphone-Kopplung sowie
  die bestehende Hundekommunikation.
- Neue additive `location-relay`-Funktion: im Supabase-Testprojekt manuell
  deployed und getestet; keine Produktionsaenderung.

Diese Zusammenfassung ersetzt keine Abnahme realer Rollen, RLS-Rechte,
Standorterfassung oder Geraete-zu-Geraete-Kommunikation. Fuer den aktuellen
validierten Projektstand gelten die manuellen Abnahmehinweise des Projektlogs.

## Manuelle Abnahmetests (am Ende der Portierung)

Diese Liste wird waehrend der Entwicklung ergaenzt, aber erst in der spaeten
Abnahmephase systematisch ausgefuehrt. Nur Debug-APK, isoliertes Supabase-
Testprojekt und Wegwerf-Testdaten verwenden; keine produktiven oder fremden
Profile veraendern. Ergebnisse, Testkonto/Rolle, Profil-ID und gegebenenfalls
Screenshot pro Punkt dokumentieren. Bereits bestaetigte Schritte nicht
wiederholen.

### Revierimport und JWD-Mapping

- **Abgenommen**: Dateiauswahl sowie Dialogpfad fuer neues Profil oder Merge
  manuell vom Nutzer geprueft.
- **Offen**: JWD-, JSON- und GeoJSON-Neuimport Ende-zu-Ende pruefen: Name,
  Polygon/MultiPolygon, Loecher, befriedete Bereiche, dauerhafte Einrichtungen
  und resultierendes Profil nach erneutem Laden vergleichen.
- **Offen**: Merge angrenzender und getrennter Flaechen mit Loechern testen;
  bestehende Grenzen und Marker muessen erhalten bleiben.
- **Offen**: Dieselbe Datei zweimal mergen und pruefen, dass dauerhafte
  Einrichtungen nicht dupliziert werden.
- **Offen**: Merge als Owner und Editor pruefen; Gast darf kein Merge-Ziel
  erhalten und keinen Merge schreiben koennen.
- **Offen**: GeoJSON-FeatureCollection mit mehreren Polygonen und
  Nicht-Polygon-Features pruefen; nur das ausgewaehlte Polygon darf geschrieben
  werden.
- **Offen**: Mapping zum Beispiel **Falle -> Bock** exportieren und reimportieren.
  Android muss den Originaltyp aus `[jagdkarte:v1:falle]` wiederherstellen;
  zusaetzlich die Datei in der PWA und, falls verfuegbar, der Jagdwetter-App
  oeffnen und die lesbare Titelzeile sowie den Standard-Markertyp kontrollieren.
- **Offen**: Abbruch, ungueltiges JSON, nicht unterstuetzte Dateiendung und Datei
  ueber 10 MB pruefen; es duerfen keine unbeabsichtigten Schreibungen entstehen.
- **Offen**: Teilfehler beim Merge im Testprojekt ausloesen und Rollback neu
  eingefuegter Marker sowie Meldung moeglicher Restdaten pruefen.

### Oeffentliche Reviersuche

- **Offen**: Die bereits in der PWA geprueften Quellen nicht erneut fachlich
  zertifizieren. Android-seitig schreibfrei mindestens einen Live-Treffer je
  Protokoll (Snapshot, WFS, OGC API Features, ArcGIS REST) laden und Adapter,
  Feature-Name, unveraenderte Koordinaten/Geometrie sowie die Anzeige der
  uebernommenen Quellen- und Lizenzangaben kontrollieren. Fehler, veralteten
  Snapshot und nicht verfuegbare Dienste pruefen.
- **Offen**: Benutzerdefinierte GeoJSON-FeatureCollection und WFS-2.0-Quelle
  am Testdienst hinzufuegen, bearbeiten, auswaehlen und loeschen. Lokal
  gespeicherte Quellen duerfen weder in der PWA erscheinen noch deren
  Konfiguration aendern. HTTP, Credentials, Loopback/private Ziele,
  Cross-Origin-Seitenlinks, ungueltiges GeoJSON, Antworten ueber 12 MB pro
  Seite/48 MB insgesamt, Suchdauer ueber 90 Sekunden und Datensaetze ueber
  20.000 Features muessen abgelehnt werden.
- **Offen**: Suche ohne Anmeldung ausfuehren, Treffer auf der Karte ansehen,
  Vorschau schliessen und bestaetigen, dass dabei weder Serverprofile noch
  andere Daten geschrieben werden. Erst die Aktion zum eigenen Profil darf
  Anmeldung und danach den bestehenden Profil-/Grenz-RPC verwenden.
- **Offen**: Mit Testkonto ein Revier aus jeder Quellenart als eigenes Profil
  anlegen; Rolle, Boundary und `source_id`/`feature_id` nach erneutem Laden
  pruefen. Eigene Quellen muessen unter `user-imports` landen. Gast darf nicht
  anlegen. RPC-Fehler/Abbruch darf kein leeres Profil zuruecklassen.
- **Offen**: Bestaetigen, dass Quellen ohne automatische offizielle
  Bevorzugung waehlbar sind, eigene Quellen lokal bleiben und WPS ohne
  vereinbarten GeoJSON-Prozessvertrag nicht angeboten wird. Keine erneute
  generelle PWA-Lizenz-/Quellenbewertung; nur die korrekte Uebernahme und
  Darstellung vorhandener Anbieterhinweise kontrollieren.

### Oeffentliche Offline-Grenzen

- **Automatisiert geprueft**: Speichern mit klarer Lizenz sowie erforderliche
  Bestaetigung bei unklarer Lizenz; gesperrtes Speichern vor Bestaetigung,
  Anzeige von Quelle/Lizenz/Speicherzeit und Entfernen. Storetests pruefen
  `noBackupFilesDir`, Aktualisierung ohne Duplikate und Ablehnung eines
  ueberschrittenen Einzelgrenzenlimits ohne Teil-Datei.
- **Offen (manuell)**: Auf einem Geraet nach App-Neustart gespeicherte Grenzen
  wiederfinden und Quelle/Lizenz/Speicherzeit kontrollieren; eine Grenze auf
  der Karte oeffnen und wieder entfernen. Lizenzbedingungen der tatsaechlich
  gewaehlten Datenquelle fachlich bestaetigen.
- **Offen**: Mit gespeicherter Grenze und Mapsforge-Paket im Flugmodus
  Kartenvorschau pruefen. Keine Online-Suche oder stillen Online-Fallback
  voraussetzen; private Profil-/Marker-/Live-Daten duerfen nicht neu dauerhaft
  gespeichert oder in den Grenzcache aufgenommen werden.
- **Offen**: Gesamtspeicher ueber 64 MiB und mehr als 100 Eintraege kontrolliert
  ablehnen; Entfernen muss den lokalen Platz freigeben. Einzelgrenzen ueber
  4 MiB und Update derselben Quelle/Feature-ID ohne Duplikat sind automatisiert
  geprueft.
- **Offen**: App-Backup-/Wiederherstellungsverhalten des `noBackupFilesDir`
  bestaetigen; die Offlinekopien werden nicht mit PWA oder Konto synchronisiert.

### Grenzbearbeitung

- **Offen**: Im isolierten Supabase-Testprojekt als Owner und Editor Punkte
  verschieben/einfuegen/loeschen, Teilflaechen und befriedete Bereiche anlegen
  oder entfernen und die Grenze nach erneutem Laden vergleichen. Ein Gast
  darf den Editor nicht erhalten und auch per RPC keine Grenze schreiben.
- **Offen**: Polygon/MultiPolygon mit mehreren Loechern testen; unberuehrte
  Teilflaechen und Loecher muessen erhalten bleiben. Die letzte Teilflaeche und
  ein Ring mit drei Punkten duerfen nicht weiter reduziert werden.
- **Offen**: Selbstkreuzung, ueberlappende Teilflaechen und Loecher ausserhalb
  der zugehoerigen Flaeche pruefen; ungueltige Entwuerfe duerfen nicht
  geschrieben werden. Rueckgaengig/Wiederholen und Verwerfen kontrollieren.
- **Offen**: Grenze verkleinern; Warnung fuer danach ausserhalb liegende
  Markierungen pruefen und unveraenderte Marker bestaetigen. Standortfreigabe
  und neue Markierungen muessen danach die neue Grenze verwenden.
- **Offen**: Schreibfehler und Berechtigungsentzug waehrend der Bearbeitung
  pruefen; der Entwurf muss nach einem Speicherfehler weiter bearbeitbar sein.
  Eine zwischenzeitlich gespeicherte fremde Grenzaenderung muss zum erneuten
  Laden auffordern. Gleichzeitige Schreibzugriffe bleiben ohne atomare
  Versionspruefung im bestehenden RPC eine bekannte Einschraenkung.
- **Offen**: Griffauswahl, Verschieben per Drag und Kartenmitte, neue Flaechen,
  Tooltips, Zoom und Zurueck-/Abbruchverhalten auf einem realen Geraet pruefen.

### Profile, Rollen und Rechte

- **Offen**: Beide Kopplungswege mit echten Rollen/RPC-Rechten im isolierten
  Supabase-Testprojekt pruefen. Der Nutzer meldet, Migration 002 nach Migration
  001 ausgefuehrt und das PWA-Repository aktualisiert zu haben; Zielprojekt und
  Laufzeitverhalten wurden hier nicht unabhaengig verifiziert. Owner/Bearbeiter
  duerfen vormerken, Gaeste nicht; Bestaetigung, Abbruch, Doppelscan und
  Wiederholung nach Timeout muessen den erwarteten Schluesselzustand erhalten.
  Bestehende Jagdkarte-QR- und Manuellwege duerfen nicht regressieren.
- **Offen**: Owner-Profil loeschen und RLS-/Cascade-Verhalten fuer Marker,
  Einladungen und Mitgliedschaften im Testprojekt bestaetigen.
- **Offen**: Einladungen mit realem Test-Mailversand, Owner-/Editor-/Gastrollen
  und RLS pruefen.
- **Offen**: Gastlink ueber RPC annehmen, Ablauf und Zielmarker-Fokus pruefen;
  Android-App-Link-Zuordnung und Browser/PWA-Fallback auf einem installierten
  Geraet bestaetigen.
- **Offen**: Anzeigename schreiben/loeschen und Owner-E-Mail-RPC mit echten
  Testkonten und RLS pruefen.
- **Offen**: Hund als Owner/Bearbeiter loeschen und als Gast verweigern;
  RLS-Cascade fuer Tracker-Kopplung und gespeicherte Hundeposition im
  isolierten Testprojekt bestaetigen.
- **Offen**: Marker verschieben, temporaere Marker loeschen und Owner-/Editor-/
  Gastberechtigungen mit tatsaechlichem GPS und RLS pruefen.

### Karte, Standort und Geraete

- **Offen**: Auf einem Geraet Erststart mit DogTracker, Tabwechsel und
  Kaltstart-Wiederherstellung pruefen. Letztes Konto/Revier, Kartenmitte, Zoom,
  Vollbild und die pro Revier gespeicherte Standortzustimmung kontrollieren.
  Widerrufene Android-Berechtigungen duerfen die Freigabe nicht starten; nach
  erneuter Freigabe darf die vorhandene App-Zustimmung ohne erneuten Dialog
  weitergelten.
- **Offen**: Nach Onlineabruf Revier wechseln und App neu starten; im Flugmodus
  muss der letzte Snapshot als veraltet und read-only erscheinen. Keine
  Teilnehmer-/Hund-Livepositionen anzeigen oder puffern; nach sieben Tagen muss
  der Cache ablaufen. Netzwerkzugriffe fuer statische Profile duerfen im
  Normalbetrieb nicht haeufiger als vorgesehen stattfinden.
- **Offen**: Reale Marker und Reviergrenze bei mehreren Zoomstufen mit der
  Kartenbasis vergleichen; insbesondere Marker nahe der Grenze kontrollieren.
- **Offen**: Eigenen Standort, GPS-Berechtigung, manuelles Einpassen,
  Grenzanzeige und Vollbild auf einem realen Geraet pruefen.
- **Offen**: Freie NRW-Luftbilder auf realem Geraet pruefen: Abdeckung und
  Luecken an Landes-/Dienstraendern, Quellen-/Lizenzangabe, Zoom 5 bis 20,
  Positionsgenauigkeit, Netz-/Anbieterfehler und Hinweis ausserhalb der
  Dienstausdehnung. Kein API-Schluessel, Konto oder kostenpflichtiger Fallback.
- **Offen**: Weitere Bundeslaender/Laender anhand tatsaechlich erreichbarer
  amtlicher Dienste, passender Projektion und freier Lizenz anbinden und
  pruefen. Keine pauschale deutschlandweite Luftbildabdeckung annehmen.
- **Offen**: Mapsforge-Regionalpaket mit passender `.poly`-Extraktgrenze auf
  realem Geraet importieren; vollstaendige, partielle und fehlende Abdeckung
  einschliesslich Loechern und mehreren gemeinsam abdeckenden Paketen pruefen.
  Offline darf nur bei vollstaendiger Abdeckung neu angeboten werden.
- **Offen**: Flugmodus, App-Neustart, Revierwechsel, geaenderte Grenze und
  geloeschtes Paket im gewaehlten Offline-Modus pruefen. Kein stiller
  Online-Fallback; der verschluesselte Snapshot muss als veraltet und read-only
  erscheinen, Live-Positionen muessen fehlen und abgelaufene Kopien duerfen
  nicht angezeigt werden. Der Kartenmodus allein deaktiviert keine bestehende
  Kommunikation.
- **Offen**: Grosse Kartenpakete (mehrere GB), knappen Speicher, abgebrochenen
  oder fehlerhaften Import, doppelte Datei sowie Laden/Loeschen pruefen.
  Gleichnamige, zur Karte passende Extraktgrenze sicherstellen; bei
  Mehrdateifehlern bereits importierte Pakete in der Verwaltung kontrollieren.
- **Offen**: DogTracker-Pairing sowie Relay-Pairing, Sicherheitscode,
  Berechtigungen, Funktransfer, Offline-Warteschlange und Serverbestaetigung mit
  mindestens zwei physischen Geraeten pruefen.

### Sicherheits- und Robustheits-TODOs

Die Punkte stammen aus einem read-only Code-Review vom 2026-11-11. In der
naechsten Arbeitsphase einzeln abarbeiten, jeweils mit gezielten Tests und ohne
Produktionsdeployment.

- [x] **P1 – SSRF bei eigenen Datendienstquellen (Umgesetzt)**: Nicht global
  routbare IPv4-/IPv6-Sonderbereiche einschliesslich RFC6598 `100.64.0.0/10`
  werden blockiert. `PublicBoundaryClient` verwendet OkHttp mit validierter
  DNS-Aufloesung fuer die tatsaechliche Verbindung; die TLS-Hostnamepruefung
  bleibt aktiv. Redirects sind auf denselben HTTPS-Ursprung begrenzt.
  JVM-Tests decken Sonderbereiche, mehrere DNS-Antworten, DNS-Aenderungen und
  Redirects ab. Kein Produktionsdeployment erforderlich.
- [x] **P2 – DogTracker-QR-Pairing atomar und wiederholbar machen (Code umgesetzt,
  Live-Abnahme offen)**: Die neue `stage_hunting_dog_device_token`-RPC laesst
  den aktiven Schluessel bis zur Bestaetigung unangetastet; Wiederholung desselben
  QR ist fuer denselben Hund idempotent. `complete_hunting_dog_device_pairing`
  behandelt Bestaetigung, Abbruch und Wiederholung nach unklarem Timeout; der
  Jagdkarte-erzeugte QR bleibt unmittelbar nutzbar und entfernt veraltete
  Vormerkungen. Die alte `register_hunting_dog_device_token`-RPC behaelt ihren
  bisherigen Vertrag; die aktuelle Android-App ruft nur die neue Vormerk-RPC
  auf. Android-JVM- und PGlite-Tests bestehen (25 Deno-Tests insgesamt).
  Der Nutzer meldet Migration 002 als ausgefuehrt und das PWA-Repository als
  aktualisiert; dies wurde nicht unabhaengig verifiziert. Reale Supabase-RLS/
  RPC- und Geraeteabnahme bleibt offen.
- [x] **P2 – GPS-Outbox begrenzen und kompaktieren (Code umgesetzt,
  Geraeteabnahme offen)**: Die Outbox behaelt pro DogTracker-Datenbank nur den
  neuesten `location.fix` nach Messzeitpunkt und verwirft ihn nach 24 Stunden.
  Bereits vorhandene GPS-Rueckstaende werden beim Aufraeumen auf den neuesten
  Fix reduziert. Relay-Nachrichten bleiben von der Kompaktierung unberuehrt und
  folgen weiter ihrer eigenen Ablauf-/Bestaetigungssemantik. Instrumentierte
  Tests fuer Ersetzung, verspaetete aeltere Fixes, TTL und vorhandene
  Rueckstaende sind ergaenzt und kompilieren; mangels verbundenem Geraet wurden
  sie nicht ausgefuehrt. Offline-Rueckkehr und Bandbreite sind auf realen
  Geraeten noch zu pruefen.
- [x] **P2 – GPS-Outbox at-rest schuetzen (Code umgesetzt, Geraeteabnahme offen)**:
  `location.fix`-Payloads werden vor Persistenz mit AES-GCM und einem separaten
  Android-Keystore-Schluessel verschluesselt; die Messzeit-/Queue-Metadaten
  bleiben lesbar. Datenbankversion 3 verschluesselt bestehende GPS-Payloads
  innerhalb der Upgrade-Transaktion. Bei fehlendem/ungueltigem Schluessel wird
  der unlesbare Fix verworfen und ein Statushinweis angezeigt. SQLite
  `secure_delete` ist aktiviert; die Datenbank ist von Cloud-Backup und
  Geraeteuebertragung ausgeschlossen. Instrumentierte Tests fuer Verschluesselung,
  Upgrade und Schluesselverlust sind ergaenzt und kompilieren, aber mangels
  verbundenem Geraet nicht ausgefuehrt.
- [x] **P2 – Transiente HTTP-Fehler nicht verwerfen (Code umgesetzt, Serverabnahme offen)**:
  408/425/429 und Serverfehler werden erneut versucht; 401/409 behalten ihr
  Sonderverhalten, andere 4xx werden als permanente Ablehnung behandelt.
  `Retry-After` wird als Delta-Sekundenwert oder HTTP-Datum gelesen und auf
  30 Sekunden bis 24 Stunden begrenzt. Die persistierte globale Wartefrist
  verhindert, dass neue GPS-Fixes sie umgehen. JVM-Tests sind ergaenzt;
  Verhalten an der realen Edge Function und bei WorkManager-Restore noch pruefen.
- [x] **P2 – Gastlink-App-Link absichern (Geraet getestet)**: Die Android-App akzeptiert
  Gastlink-Tokens nur noch von `https://wierichs.github.io/Jagdkarte/` ohne
  fremden Port oder Userinfo; Tests decken fremde Hosts und Schemes ab. Die
  Domain-Association ist auf einem Android-15-Geraet als `verified` bestaetigt:
  Die veroeffentlichte
  `https://wierichs.github.io/.well-known/assetlinks.json` lieferte bei der
  Pruefung am 2026-11-11 HTTP 200 mit passender App-ID und passendem
  Release-Zertifikat. Der Nutzer hat einen
  Release-Keystore erstellt und gesichert sowie die APK ueber Android Studio
  signiert. `apksigner verify` bestaetigte am 2026-11-11 die APK-v2-Signatur
  (RSA, 3072 Bit). Zertifikat-SHA-256:
  `04:66:52:77:23:EA:06:7B:35:DD:BA:87:2E:6E:DE:A7:FD:90:09:7A:AC:91:6F:F0:E8:F7:62:6B:4E:D2:1D:4D`.
  Die Signierung erfolgt bislang ueber den Android-Studio-Assistenten, nicht
  automatisch bei einem Gradle-Release-Build. Der Nutzer hat entschieden,
  `android:autoVerify` fuer den exakten Pfad `/Jagdkarte/` zu aktivieren:
  Android kann das Fragment `#gast=...` nicht filtern, daher wird auch die
  normale PWA-Startseite von diesem App-Link erfasst. Andere Unterseiten sind
  nicht mehr Teil des Intent-Filters. Die neu signierte Release-APK wurde am
  2026-11-11 auf App-ID, Zertifikat und enthaltenen App-Link-Filter geprueft.
  Die signierte APK stuerzte beim Start wegen einer R8-Klassen-Zugriffsverletzung ab. Die
  eingeschraenkte Optimierungs-Package-Scope-Konfiguration wurde entfernt.
  Der regulaere Release-Build und die JVM-Tests bestanden; die korrigierte
  APK wurde mit demselben Zertifikat signiert, als Update installiert und
  startete auf dem Geraet. Die Domain blieb danach `verified`. Auf dem Geraet
  oeffnete `/Jagdkarte/` die Android-App und `/Jagdkarte/hilfe.html` den
  Browser. Der Nutzer bestaetigte, dass ein echter PWA-Gastlink das richtige
  Revier und die Zielmarkierung oeffnet. Auf seinen Hinweis wird nun
  zusaetzlich das Infofenster der Zielmarkierung geoeffnet. Im ersten
  Praxistest erschien das Fenster nicht. Eine weitere Korrektur beruecksichtigt
  das kurzzeitige Nachladen des Revierstands; der erweiterte
  Instrumentierungstest auf demselben Geraet besteht. Die korrigierte APK
  wurde signiert und als Update installiert; der erneute Praxistest des
  Infofensters steht auf Wunsch des Nutzers noch aus.
- [ ] **P3 – Lokale Kartenkamera-Praefenz bewerten**: Zentrum/Zoom werden als
  Klartext-JSON in `SharedPreferences` gespeichert. Entscheiden, ob diese
  Revier-/Suchposition als sensibel gilt; bei Bedarf verschluesseln oder
  Speicherumfang reduzieren.
- [ ] **P3 – Lint-/Wartungsschulden abbauen**: Der letzte Android-Lintlauf
  meldete 30 Warnungen und 9 Hinweise, darunter Compose-State-Autoboxing,
  veraltete Abhaengigkeiten und nicht verifizierte App-Links. Zuerst konkrete
  Laufzeit-/Sicherheitsfolgen bewerten, dann risikoarm bereinigen.

### Übergabe für den nächsten Kontext

- Einstieg: **P1 – SSRF**, **P2 – DogTracker-QR-Pairing**, die GPS-Outbox-Punkte,
  die Behandlung transienter HTTP-Fehler und die Gastlink-App-Link-Zuordnung
  sind im Code umgesetzt. Die neue Gastlink-Markeranzeige noch in einer
  signierten Release-APK mit einem echten Gastlink pruefen.
- Der Nutzer meldet Pairing-Migration 002 als ausgefuehrt und das
  PWA-Repository als aktualisiert. Nicht erneut ausfuehren; Zielprojekt und
  echte RPC-/RLS-Rechte bei autorisierter Abnahme verifizieren.
- Letzte Verifikation vor dieser Übergabe: `:app:testDebugUnitTest`, gezielte
  Android-Tests fuer Start/Resume, Cache, Karten, Marker und Pairing sowie
  `:app:assembleDebug :app:assembleRelease` bestanden. Reale Geraete-/GPS-
  Abnahmen sind offen. In der aktuellen Sitzung bestanden erneut
  `:app:testDebugUnitTest` und die 25 Deno-Tests; die Instrumentierungstests
  kompilierten, konnten aber mangels per ADB verbundenem Android-Geraet/Emulator
  nicht ausgefuehrt werden.