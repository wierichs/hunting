# Android-Portierungsstatus

Stand: 2026-11-09

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

Der Import von JWD-, JSON- und GeoJSON-Revierdaten kann neue Profile anlegen
oder Daten in ein bestehendes, editierbares Profil zusammenfuehren. Grenzen
werden geometrisch vereinigt; vorhandene Marker bleiben erhalten und identische
Einrichtungen werden nicht doppelt angelegt. JVM- und Emulator-UI-Tests
bestehen. Der Nutzer hat die manuelle Dateiauswahl und den Dialogpfad fuer
Neuimport beziehungsweise Merge bereits bestaetigt; diese UI-Schritte sind
abgenommen und sollen nicht wiederholt werden. Weitere manuelle Tests werden
am Ende der Portierung gesammelt ausgefuehrt; die bestaetigten UI-Schritte
bleiben erledigt. Die Checkliste am Dokumentende enthaelt die noch offenen
Supabase-Schreib-/RLS- und Datenintegritaetspruefungen.

## Offene beziehungsweise zu pruefende Funktionen

Die folgende Ersterfassung basiert auf dem bekannten PWA-Umfang. Bei der
Umsetzung koennen wir Eintraege praezisieren, aufteilen oder weitere PWA-
Funktionen ergaenzen. Ein Statuswechsel zu **Abgenommen** soll einen knappen
Pruefbeleg enthalten; ein erfolgreicher Build allein ist keine Funktionsabnahme.

### Reviersuche und Daten

- **Offen**: Oeffentliche Regionsuche ohne Anmeldung, inklusive regionaler
  Datenquellen, Suchfeldern, Aktualisierung sowie Quellen- und Lizenzangaben.

### Profile und Zusammenarbeit

- **Offen**: Eigenes Revierprofil aus einem gefundenen Revier anlegen.
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

- **Offen**: Reviergrenzen bearbeiten, einschliesslich Geometriepunkten und
  Teilflaechen.
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

- **Offen**: Eigenen Standort auf der Karte anzeigen, manuelles erneutes
  Einpassen des Reviers, Grenzanzeige ein-/ausschalten und Karten-Vollbild.
- **Offen**: Optionale Satellitenansicht.
- **Offen**: PMTiles-Offlinekarten importieren, verwenden und verwalten sowie
  den PWA-Ablauf zum Anfordern eines Kartenausschnitts abbilden oder fuer
  Android passend ersetzen.
- **Offen**: Oeffentliche Reviergrenzen und benoetigte App-Inhalte fuer
  Offline-Nutzung verfuegbar machen. Private Revierdaten duerfen dadurch nicht
  ungewollt als dauerhafte Offline-Kopie gespeichert werden.

### Konto, Hunde und Hilfe

- **Offen**: Anmeldung ueber E-Mail-Link als Alternative zum E-Mail-Code
  unterstuetzen oder eine Android-gerechte Alternative dokumentieren.
- **Offen**: Registrierte Hunde loeschen koennen.
- **Offen**: Vollstaendige Hilfe und Fehlerbehebung der PWA fuer die native App
  bereitstellen.

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
- **Offen**: Marker verschieben, temporaere Marker loeschen und Owner-/Editor-/
  Gastberechtigungen mit tatsaechlichem GPS und RLS pruefen.

### Karte, Standort und Geraete

- **Offen**: Reale Marker und Reviergrenze bei mehreren Zoomstufen mit der
  Kartenbasis vergleichen; insbesondere Marker nahe der Grenze kontrollieren.
- **Offen**: Eigenen Standort, GPS-Berechtigung, manuelles Einpassen,
  Grenzanzeige und Vollbild auf einem realen Geraet pruefen.
- **Offen**: Satelliten- und Offlinekarten nach Festlegung der Datenquelle und
  des Importablaufs testen.
- **Offen**: DogTracker-Pairing sowie Relay-Pairing, Sicherheitscode,
  Berechtigungen, Funktransfer, Offline-Warteschlange und Serverbestaetigung mit
  mindestens zwei physischen Geraeten pruefen.