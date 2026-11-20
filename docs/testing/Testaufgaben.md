# Offene Testaufgaben

Stand: 18.11.2026. Diese Liste sammelt Prüfungen für eine spätere Abnahme,
keine Implementierungsaufgaben. Backend-Tests nur mit einem isolierten
Supabase-Testprojekt, Testkonten und Testdaten durchführen. Getestete Rollen,
App-Versionen, Geräte, Netzbedingungen und Ergebnis festhalten.

## Anmeldung, Rollen und Hundekopplung

- [ ] Echte Test-E-Mail: Link und Zahlencode, Einladungen für bereits
  angemeldete Konten sowie Gastlinks in Android und PWA.
- [ ] Erlaubte und abgelehnte Aktionen für Owner, Bearbeiter und Gast
  einschließlich Datenbankregeln (RLS) und RPCs: Profile, Markierungen,
  Grenzen, Einladungen und Hundelöschung samt abhängigen Daten.
- [ ] Beide Hundekopplungswege mit zwei Geräten: Vormerken, Bestätigen,
  Abbruch, ausstehender Ersatz, Wiederholung und Statusanzeige. Zuvor den
  Migrationsstand des Testprojekts feststellen.

## Reviere, Dateien und Karten

- [ ] **Revier um Standort erstellen**: mit frischem GPS-Fix ein benanntes
  Profil anlegen, 100-m-Kreis und nachträgliche Grenzbearbeitung prüfen.
  Verweigerte genaue Standortberechtigung, fehlenden Fix und fehlendes
  Internet prüfen; Fehler müssen sichtbar bleiben. Hunde koppeln und im
  Jagdmodus auch außerhalb des Kreises anzeigen.
- [ ] Live-Suche je Quellprotokoll und eigene Quellen prüfen: Geometrie,
  Quellenangaben, Grenzen und Anlage eines Profils.
- [ ] JWD-/JSON-/GeoJSON-Neuimport und Merge: Löcher, Mehrfach-Features,
  Duplikate, Fehler und Aufräumen nach Teilfehlern. JWD-Export und
  Reimport in der PWA oder anderer unterstützter Software prüfen.
- [ ] Erststart, Zustimmung und Berechtigungsentzug, Konto- und
  Revierwechsel, Standortanzeige, Grenzeditor und Marker auf realen Geräten.
- [ ] NRW-Luftbilder an den Abdeckungsrändern und Quellenangabe prüfen.
- [ ] Mapsforge-Pakete und Abdeckungen bei mehreren Paketen, großen
  Dateien, knappem Speicher und fehlerhaften Eingaben prüfen.
- [ ] Flugmodus, Neustart und Revierwechsel mit Offlinekarte, öffentlicher
  Offline-Grenze und Revier-Snapshot prüfen: kein stiller Online-Fallback,
  keine irreführend aktuellen Marker aus dem Snapshot.

## Kommunikation und Gerätebetrieb

- [ ] Eigene Hunde als Gast registrieren und koppeln; türkise Marker online
  und offline prüfen.
  Fremden Hund, getrenntes Gerät ohne Nachlieferung, Stummschaltung und
  Empfangsbestätigung auf echten Geräten prüfen.
- [x] Eigenes Hundegerät direkt anstupsen: Am 18.11.2026 funktionierte
  auf dem Xiaomi **Klingeln** im normalen Tonmodus und **Vibrieren**
  zunächst nur im Lautlos-Modus. Das Aktivieren von **Bei Anruf vibrieren**
  unter **Einstellungen → Ton & Vibration** behob das Problem im normalen
  Tonmodus laut Gerätetest des Nutzers.
- [ ] Vibrationsverhalten auf einem zweiten Hundegerät prüfen.
- [ ] Nearby-Rundnachrichten mit aktivem Jagdmodus auf physischen Geräten
  abnehmen: Owner und Bearbeiter senden, Gast empfängt und bestätigt,
  kann aber nicht senden. Offline-Versand und „Angekommen“ sind bereits
  bestätigt. Ein nicht verbundenes
  Gerät darf keine Nachricht nachträglich erhalten. Eine alte App darf
  nicht als bestätigter Empfänger erscheinen.
- [ ] Nearby-Benachrichtigungen auf dem Empfängergerät prüfen: einmal pro
  neuer Nachricht, eigener Ton und Vibration, bei stummem oder gesperrtem
  Kanal weiterhin Nachricht in der App und Empfangsbestätigung.
- [ ] Wiederverbindung mit ausgeschaltetem Display für Jagdkarte und
  DogTracker (**verschoben**). Automatischen Wiederanlauf vor dem Entsperren
  von Wiederverbindung erst nach dem Entsperren unterscheiden.
- [ ] Hintergrundbetrieb und automatischen Android-Prozesswiederanlauf
  prüfen. Der manuelle Gateway-Wiederanlauf im selben Gerätebetrieb und die
  Sperre nach Offline-Geräteneustart wurden bereits geprüft.
- [ ] Funkabbrüche, Wiederholungen, DogTracker-Outbox und Serverquittungen
  prüfen. Funkempfang ist keine Serverbestätigung. Bei verzögerter Rückkehr
  getrennt erfassen, wann „Offline“ und „Serverbestätigung ausstehend“ enden.
- [ ] Fremdes Revier, alte APK und alte Verzeichnisse, Berechtigungsablauf,
  Schlüsselwechsel und verzögerten Widerruf prüfen.
- [ ] Die Bewegungsrichtung bei echter GPS-Bewegung prüfen. Der Zeichentest
  mit vorgegebenen Richtungen für Teilnehmer- und Hundemarker wurde auf dem
  Motorola bereits bestanden.
- [ ] DogTracker-Statuszeile auf echten Geräten prüfen; vorhandene
  Android-Instrumentierungstests zur Speicherung, zum Cache und zur
  Statusanzeige tatsächlich ausführen (bisher nur kompiliert).
- [ ] Verlässlichkeit, Entdeckungszeit, Verzögerung und Akkuverbrauch bei
  Sichtkontakt und im Wald messen, unter anderem bei 100, 200 und 300 Metern.
  Akzeptanzwerte vor der Feldabnahme festlegen. Mindestens zwei Offline-Geräte
  einsetzen; für Serverweitergabe zusätzlich ein Online-Gateway vorsehen.
- [ ] Früh gestartete GPS-Anforderung abnehmen: Zeit bis zum ersten eigenen
  GNSS-Fix online und offline messen, jeweils nach kürzlicher Nutzung und
  längerer Pause. Für die Jagdkarte Relay-Start und Fix-Zeit getrennt
  erfassen; prüfen, dass ein über 30 Sekunden alter Fix nicht gesendet wird.

## Zielumgebung vor einer Produktionsfreigabe

- [ ] Serverbestätigtes Jagdende mit drei Jagdkarte-Geräten abnehmen:
  A ist online und beendet den Jagdmodus, B und C sind offline. Zuerst
  verbindet sich nur B mit A, danach trennt sich B von A und verbindet sich
  mit C. Auf B und C müssen Teilnehmer- und Hundepositionen verschwinden,
  die eigene lokale Position muss verfügbar bleiben. B darf nach dem Ende
  nur noch den Nachweis weitergeben. Duplikate, ein abgelaufener Nachweis
  und ein Nachweis zum früheren Durchlauf dürfen keine neue Jagd beenden.
  Direkter Zweigerätetest Motorola → offline Xiaomi am 20.11.2026 erfolgreich;
  nach erneuter Online-Verbindung und manueller Aktualisierung zeigte das
  Xiaomi Positionen des neuen Durchlaufs. Mehrhop, gezielte Wiederholung eines
  alten Endnachweises stehen noch aus.
- [x] Auf dem online verbundenen Xiaomi im Testrevier nach Jagdende warten
  und den Jagdmodus auf dem Motorola wieder einschalten. Ohne manuelles
  Aktualisieren muss der neue Stand nach etwa 30 Sekunden zuzüglich
  Netzwerkzeit erscheinen. Laut Gerätetest wechselte das Xiaomi nach etwa
  16 Sekunden auf „An“ und zeigte wieder Positionen.
- [ ] Beim automatischen Wiederanlauf die Übertragung frischer Positionen
  ausdrücklich über Nearby prüfen.
- [ ] Jagdende und erneuten Jagdstart bei durchgehend offline verbleibendem
  Teilnehmer unterstützen: Beim wiederholten Zweigerätetest übernahm das
  offline bleibende Xiaomi das Jagdende und blieb nach dem erneuten Start auf
  „Aus“; Positionen wurden nicht angezeigt. Eine zuvor erinnerte „An“-Anzeige
  bestätigte sich nicht. Zuvor wurde auch eine ungültige Relais-Authentifizierung
  oder Revierberechtigung gemeldet; nach Wiederverbindung funktionierte es
  automatisch wieder. Die genaue Ursache der Fehlermeldung bleibt offen; ein
  serverbestätigter Offline-Nachweis für einen neuen Start ist nicht umgesetzt.
- [ ] Migrationen und `location-relay` im tatsächlichen Zielprojekt sowie
  Kompatibilität mit PWA und bestehenden Hundetracker-Schnittstellen prüfen.
  Bereitstellung und Geräteinstallationen separat abstimmen.

Bereits bestätigte Abläufe:
[Offline-Anzeige und Jagdende](../design/offline-positions/OfflinePositionMessageFormat.md#bestätigte-feldtests)
und [Online-Wiederverbindung](../../CommunicationConcept.md#rückkehr-der-internetverbindung).
