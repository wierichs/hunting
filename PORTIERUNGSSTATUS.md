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
  und 22 Emulator-Tests bestehen. Live-RPC-Abnahme steht aus. Die HTTPS-
  App-Link-Zuordnung ist nicht verifiziert; ohne Website-Association bleibt
  das Oeffnen in der PWA/im Browser als Fallback erhalten.
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
  Eintraege gesamt; kein Sync und keine automatische Aktualisierung. Private
  Profile, Marker und Live-Positionen werden nicht durch diese Funktion
  dauerhaft lokal gespeichert. JVM- und Offline-Store-/UI-Tests bestehen.
  Noch offen: reale Offline-Abnahme im Flugmodus einschliesslich Lizenzpruefung,
  Neustart, Entfernen, knappen Speicherplatzes sowie bestaetigen, dass private
  Serverdaten nicht persistiert werden.

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
  Online-Fallback; private Revierdaten duerfen nicht neu dauerhaft gespeichert
  werden. Der Kartenmodus allein deaktiviert keine bestehende Kommunikation.
- **Offen**: Grosse Kartenpakete (mehrere GB), knappen Speicher, abgebrochenen
  oder fehlerhaften Import, doppelte Datei sowie Laden/Loeschen pruefen.
  Gleichnamige, zur Karte passende Extraktgrenze sicherstellen; bei
  Mehrdateifehlern bereits importierte Pakete in der Verwaltung kontrollieren.
- **Offen**: DogTracker-Pairing sowie Relay-Pairing, Sicherheitscode,
  Berechtigungen, Funktransfer, Offline-Warteschlange und Serverbestaetigung mit
  mindestens zwei physischen Geraeten pruefen.