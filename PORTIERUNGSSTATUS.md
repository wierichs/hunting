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

JWD- und GeoJSON-Import sind umgesetzt. Das Zusammenfuehren in bestehende
Profile sowie interaktive Grenzbearbeitung bleiben offen; Live-Import und
Dateiauswahl muessen noch manuell abgenommen werden.

## Offene beziehungsweise zu pruefende Funktionen

Die folgende Ersterfassung basiert auf dem bekannten PWA-Umfang. Bei der
Umsetzung koennen wir Eintraege praezisieren, aufteilen oder weitere PWA-
Funktionen ergaenzen. Ein Statuswechsel zu **Abgenommen** soll einen knappen
Pruefbeleg enthalten; ein erfolgreicher Build allein ist keine Funktionsabnahme.

### Reviersuche und Daten

- **Offen**: Oeffentliche Regionsuche ohne Anmeldung, inklusive regionaler
  Datenquellen, Suchfeldern, Aktualisierung sowie Quellen- und Lizenzangaben.

### Profile und Zusammenarbeit

- **Offen**: Eigenes Revierprofil aus einem gefundenen Revier anlegen und das
  eigene Profil loeschen.
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
- **Umgesetzt**: JWD-, JSON- und GeoJSON-Dateien als neues eigenes Profil
  importieren. Enthalten sind Polygon/MultiPolygon, befriedete Bereiche,
  dauerhafte Einrichtungen und die Auswahl bei mehreren GeoJSON-Features.
  Vorschau, 10-MB-Grenze und Rollback bei Teilfehlern sind enthalten; JVM- und
  26 Emulator-Tests bestehen. Bestehende Profile werden noch nicht
  zusammengefuehrt; Live-Supabase-Schreibtest und Dateiauswahl-Abnahme stehen
  aus.
- **Offen**: JSON- und GeoJSON-Revierdateien importieren; bei mehreren
  GeoJSON-Flaechen die gewuenschte Grenze auswaehlen und importierte
  Einrichtungen uebernehmen.
- **Umgesetzt**: Reviergrenzen als PWA-kompatible JWD-Datei exportieren; Owner
  und Bearbeiter erhalten zusaetzlich dauerhafte Einrichtungen. Gaeste
  exportieren nur die Grenze, temporaere Marker werden ausgeschlossen.
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