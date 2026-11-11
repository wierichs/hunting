# Kommunikationskonzept: Tracker als Relais

## Ziel

Standortmeldungen von Hunden **und Revierteilnehmern** sollen die berechtigten
Reviermitglieder auch bei eingeschränkter Internetverbindung möglichst gut
erreichen. Ist ein berechtigtes Gerät per Bluetooth oder lokalem WLAN erreichbar
und online, kann es Nachrichten zwischenspeichern und an den Server weiterleiten.
Die Darstellung unterstützt die Lageeinschätzung; sie garantiert weder
Echtzeitdaten noch eine lückenlose Erkennung von Gefahren.

Geräte können eigene Positionsmeldungen erzeugen und, sofern unterstützt und
freigegeben, als Relais für berechtigte andere Geräte dienen. Die Funkverbindung
allein begründet keine Berechtigung. Teilnehmer- und Hundepositionen bleiben
unterschiedlichen Identitäten zugeordnet; eine Hundekopplung ist für die
Standortfreigabe eines Teilnehmers nicht erforderlich.

## Ausgangslage

Ausgangspunkt des ursprünglichen Hunde-Relay-Ausbaus war die Erfassung während
einer gestarteten DogTracker-Sitzung mit lokaler SQLite-Warteschlange und
HTTPS-Upload per Hundegeräteschlüssel. Der heutige Implementierungsstand für
Hunde und Teilnehmer ist in den folgenden Abschnitten beschrieben.

Die Relaisfunktion benötigt daher Änderungen an der Android-App **und** an der Server-Schnittstelle der Jagdkarte. Eine App-seitige Änderung allein kann die Nachrichten nicht sicher im Namen eines anderen Trackers annehmen.

### App-seitiger Implementierungsstand

Der vorherige Abschnitt beschreibt den bestehenden Hundetracker-Vertrag im
separaten Web-Repository. Er bleibt unveraendert und ist nicht die neue native
`location-relay`-Serverstufe, die in diesem Dokument weiter unten beschrieben wird.

Die Android-App hat nun eine transportneutrale Nachrichtenhülle, eine SQLite-Outbox, einen HTTPS-Transport, eine Android-Keystore-Signaturidentität und eine Relay-Envelope-Implementierung. Die App registriert den öffentlichen RSA-Schlüssel mit dem bestehenden Tracker-Token, lädt den öffentlichen Supabase-Verschlüsselungsschlüssel und verpackt Positionsmeldungen mit AES-256-GCM sowie RSA-OAEP/SHA-256. Der Ursprung signiert die unveränderlichen Relay-Metadaten mit RSASSA-PKCS1-v1_5/SHA-256.

Die App nutzt Google Nearby Connections mit P2P-Cluster-Strategie. Relay ist opt-in und nur während des aktiven Trackings verfügbar. Vor jeder lokalen Verbindung müssen Nutzer den Nearby-Sicherheitscode auf beiden Geräten vergleichen. Der Ursprung sendet eigene Relay-Umschläge; der Empfänger legt sie lokal ab und lädt sie mit seinem eigenen Geräteschlüssel hoch. Mehrhop-Weitergabe ist nicht aktiviert.

Im korrespondierenden `wierichs.github.io/Jagdkarte`-Repository liegt die separate Supabase-Funktion `dog-location-relay`; die bestehende `dog-location`-Funktion bleibt unverändert. Der Server prüft Ursprungssignatur, Revierzugehörigkeit, Ablauf und Positionswerte und übergibt die Position an die vorhandene RPC. Die RPC speichert nur den letzten Stand und verhindert, dass gleiche oder ältere Zeitstempel ihn überschreiben; ein separates Nachrichten-ID-Ledger gibt es nicht. Deploy- und API-Details stehen dort in `hundestandort-api.md` und [supabase/README.md](https://github.com/wierichs/wierichs.github.io/blob/main/Jagdkarte/supabase/README.md).

Der Kotlin-Build, die Envelope-Krypto-Unit-Tests und die Instrumentierungstest-Kompilierung sind erfolgreich. Es war kein Android-Gerät oder Emulator angeschlossen, daher sind Nearby-Pairing, Berechtigungsdialoge und realer Funktransfer noch nicht auf Hardware validiert.

## Native Jagdkarte im Branch AndroidPort

Die native Android-App ist ein zusaetzliches Angebot neben der PWA Jagdkarte,
keine Abloesung. Die PWA bleibt in ihrem jetzigen Funktionsumfang bestehen.
Gemeinsame Kommunikations- und Servererweiterungen muessen ihre vorhandenen
Funktionen und Schnittstellen rueckwaertskompatibel erhalten.

Die native Jagdkarte verwendet den vorhandenen Hundetracker innerhalb derselben
App weiter. Die neue App-ID lautet `org.wierichs.jagdkarte`; die Nearby-
Dienstkennung bleibt fuer den Austausch mit dem bestehenden DogTracker
`org.wierichs.dogtracker`. Der Sicherheitscode wird unabhaengig von der gerade
geoeffneten Ansicht angezeigt. Anmeldung, Revierdarstellung und
Teilnehmer-Standortfreigabe sind native Implementierungen, keine WebView.

Der bisherige DogTracker-Serververtrag bleibt unveraendert. Eine neue, separate
Stufe `location-relay` liegt nun im Supabase-Verzeichnis des PWA-Repositories:
Benutzergeraete
werden mit verifiziertem Benutzerkonto pro Revier registriert und widerrufen.
Ein Benutzerkonto wird nicht als Hundegeraet ausgegeben. Die Registrierung und
Erneuerung benoetigen Internet; signierte Verzeichnisse erlauben anschliessend
eine auf zwei Minuten begrenzte Offline-Pruefung der zugelassenen Urspruenge.

Die Android-App bietet eine ausdrueckliche Relaisfreigabe mit sichtbarem
Foreground-Service, getrennt von der eigenen Standortfreigabe. Eigene
Teilnehmerpositionen werden nur bei aktiver Karte erfasst, als
`participant.location` verschluesselt eingereiht und nach spaetestens
90 Sekunden ab Messzeit verworfen. Ein aktiviertes Gateway kann auch ohne
eigene Positionsfreigabe im Hintergrund weiterarbeiten. Hintergrund-GPS fuer
Teilnehmer und automatische Wiederaufnahme nach Prozessende sind noch nicht
implementiert.

Die additive Migration umfasst ein Nachrichten-ID-Ledger, monotone Sequenzen,
Mitgliedschafts-/Widerrufspruefung und Freigabekennungen. Standortentscheidung
und Annahme der Nachrichten-ID werden atomar gespeichert. Signierte
Serverbestaetigungen binden Revier, Ursprung, ID und Umschlag-Digest; die lokale
Outbox ersetzt Nachrichten erst nach deren Pruefung atomar durch die
Bestaetigung. Funkuebergabe allein wird nicht als Zustellung dargestellt.
Abschalten von Standortfreigabe oder Jagdmodus entwertet die alte Kennung, damit
verspaetete Teilnehmermeldungen nach einer erneuten Freigabe nicht wieder
erscheinen.

Die vorhandene Verschluesselung und das DogTracker-Wire-Format werden
weiterverwendet. Alte DogTracker-APKs verstehen neue Rueckbestaetigungen noch
nicht. Neue Signaturschluessel fuer Verzeichnisse/Bestaetigungen sind getrennt
von den bestehenden Verschluesselungsschluesseln zu verwalten. Bereitstellung,
Warteschlangenlimits und offene Abnahmen stehen in der
[Anleitung zur Relay-Serverstufe](https://github.com/wierichs/wierichs.github.io/blob/main/Jagdkarte/supabase/location-relay.md).

Die Serverstufe ist gegen Kryptografie-, HTTP- und PostgreSQL-Fixtures
getestet und wurde laut Projektstatus im Supabase-Testprojekt bereitgestellt
und abgenommen, aber nicht in Produktion deployed. Die PWA-Web-App und die
produktive Supabase-Installation wurden nicht veraendert. Die unten genannten
Abnahmekriterien sind insbesondere fuer reale Funkabbrueche, mehrere physische
Geraete und echte Supabase-/PostGIS-Rechte noch nicht vollstaendig abgenommen.

## Grundprinzip

Die App erhält eine allgemeine Kommunikationsschicht, die nicht an GPS oder die Jagdkarte gebunden ist. Fachmodule übergeben ihr Nachrichten; die Schicht speichert, transportiert und bestätigt diese. Für den Hundetracker ist die Nachricht zunächst eine Positionsmeldung.

```text
Standortmessung
    -> Kommunikationskern: dauerhaft speichern
    -> direkter HTTPS-Versand, falls online
    -> sonst geeigneten Tracker in Funkreichweite suchen
    -> Relais speichert Nachricht dauerhaft
    -> Relais lädt Nachricht per HTTPS hoch, sobald es online ist
    -> Server bestätigt Annahme bis zum Ursprungsgerät zurück
```

Das ist **Store-and-forward**: Geräte müssen nicht gleichzeitig online sein. Für den ersten Ausbau genügt ein einzelner Relais-Hop. Mehrhop-Routing kann später auf derselben Nachrichten- und Speicherbasis ergänzt werden.

## Bausteine

### Wiederverwendbarer Kommunikationskern

Als eigenständige Kotlin-Bibliothek oder klar abgegrenztes Modul bietet der Kern eine stabile API für andere Apps. Er enthält keine Revier-, Karten- oder GPS-Logik.

- `MessageStore`: persistente Warteschlange für ausgehende und weitergeleitete Nachrichten.
- `RelayCore`: Deduplizierung, Ablauf, Weiterleitungsregeln, Wiederholungen und Zustände.
- `MessageTransport`: gemeinsamer Vertrag für lokale Funktransporte.
- `InternetGateway`: Übergabe an einen HTTPS-Server und Verarbeitung von Serverbestätigungen.
- `RelayPolicy`: Einwilligung, erlaubte Absendergruppen, Speicherlimit und Gültigkeitsdauer.

Fach-Apps liefern einen Nachrichtentyp und dessen Nutzdaten. Bluetooth-, WLAN- und Servertransporte implementieren getrennte Adapter. So kann später ein weiterer Transport ergänzt werden, ohne Standorterfassung oder Jagdkarte umzubauen.

### Funktransporte

**Erster Schritt: Bluetooth Low Energy (BLE).** Tracker können einander in der Nähe entdecken und kleine Nachrichten oder Nachrichtenbündel austauschen. BLE eignet sich für Positionsmeldungen mit geringem Datenvolumen und benötigt keinen gemeinsamen WLAN-Router. Die Übertragung ist langsamer als WLAN; größere Dateien oder umfangreiche Warteschlangen sind nicht das Ziel des ersten Schritts.

**Später: WLAN-Transport.** Wi-Fi Direct, Wi-Fi Aware oder ein gemeinsames lokales WLAN können für größere Datenmengen oder schnellere Synchronisierung ergänzt werden. Die konkrete Variante sollte erst nach einem Geräte- und Android-Kompatibilitätstest festgelegt werden; Verfügbarkeit und Verhalten unterscheiden sich je nach Android-Version und Hersteller.

Direkter Internetversand hat immer Vorrang. Ein Tracker mit Internetzugang wird als Gateway verwendet; ein Tracker ohne Internet speichert weiter, bis ein Gateway erreichbar ist. Ein lokales WLAN mit Internet ermöglicht weiterhin den normalen direkten HTTPS-Versand und braucht kein Relais.

### Serverseitiger Relay-Eingang

Der Server benötigt einen eigenen Eingang für weitergeleitete Nachrichten. Er muss Ursprung und Relais unterscheiden können: Das Relais transportiert eine Nachricht, erhält aber nicht die Identität des Ursprungsgeräts.

Der Server prüft insbesondere, ob der Ursprung noch registriert und freigegeben ist, die Nachricht gültig und nicht abgelaufen ist und ihre ID noch nicht angenommen wurde. Eine erfolgreiche Antwort bedeutet, dass die Nachricht dauerhaft angenommen wurde. Erst dann darf die letzte lokale Kopie gelöscht werden.

## Nachrichtenformat und Zustellung

Jede Nachricht erhält mindestens:

- eine eindeutige `message_id` zur Erkennung von Wiederholungen,
- eine pseudonyme Kennung des Ursprungsgeräts und eine fortlaufende Sequenznummer,
- Nachrichtentyp, Erstellungszeit und Ablaufzeit,
- verschlüsselte Nutzdaten,
- eine Signatur des Ursprungsgeräts.

Die Positionsdaten bleiben während lokaler Weitergabe für Relais unlesbar. Die Signatur schützt sie vor unbemerkter Veränderung. Funk- und HTTPS-Verbindungen werden zusätzlich verschlüsselt. Relaisinformationen wie Hop-Anzahl oder nächster Versuch werden getrennt von den signierten Ursprungsdaten geführt.

Eine Nachricht wird erst gelöscht, wenn der nächste Knoten ihre dauerhafte Speicherung bestätigt hat. Ein Gateway löscht sie erst nach Serverbestätigung. Geht eine Bestätigung verloren, darf erneut gesendet werden: Der Server verarbeitet jede `message_id` höchstens einmal. So entsteht eine robuste **At-least-once-Übertragung mit serverseitiger Deduplizierung**, keine Behauptung von exakt einmaliger Funkübertragung.

Bei verspäteten Meldungen darf eine alte Position keine neuere Position auf der Karte überschreiben. Der Server vergleicht Messzeitpunkt und Sequenznummer. Abgelaufene Nachrichten werden abgewiesen; bereits angenommene Meldungen bleiben anhand ihrer ID idempotent.

## Identität und Sicherheit

Der aktuell verwendete Geräteschlüssel ist ein Bearer-Token für den direkten HTTPS-Upload. Er darf **niemals** an ein Relais übertragen oder in einer lokalen Funknachricht offengelegt werden. Eine Weiterleitung mit dem Token des Ursprungs wäre ein Zugriffsschlüssel-Leak und würde dem Relais erlauben, sich als dieses Gerät auszugeben.

Für Relaisbetrieb wird deshalb eine gerätegebundene Identität benötigt:

1. Beim Koppeln erzeugt das Gerät ein Schlüsselpaar; der private Schlüssel bleibt im Android Keystore.
2. Die Jagdkarte registriert den öffentlichen Schlüssel und ordnet ihn dem Hund beziehungsweise der erlaubten Tracker-Gruppe zu.
3. Das Gerät signiert Nachrichten am Ursprung. Ein Relais kann sie weder ändern noch neue Meldungen für diesen Ursprung erzeugen.
4. Das Relais meldet sich beim Server mit seiner **eigenen** Berechtigung an und darf nur unveränderte, gültige Nachrichten einreichen.
5. Widerruf, erneutes Koppeln oder ein deaktivierter Jagdmodus werden serverseitig geprüft. Für Verschlüsselung und Signaturen wird eine etablierte, geprüfte Bibliothek und ein festgelegtes Protokoll verwendet; keine selbst entworfene Kryptografie.

Die lokale Funkentdeckung überträgt keine GPS-Koordinaten und möglichst keine dauerhafte Gerätekennung. Vor dem Austausch authentifizieren sich die Geräte gegenseitig und handeln eine verschlüsselte Sitzung aus. Im Zweifel wird eine Nachricht nicht weitergeleitet.

## Einwilligung und Datenschutz

- Relaisbetrieb ist standardmäßig ausgeschaltet und muss auf jedem Gerät ausdrücklich aktiviert werden.
- Nutzer sehen, ob das Gerät eigene Meldungen sendet, Nachrichten anderer Geräte weiterleitet oder als Internet-Gateway arbeitet.
- Weitergeleitet werden nur Nachrichten der in der Jagdkarte freigegebenen Gruppe; fremde, unbekannte oder nicht prüfbare Daten werden verworfen.
- Lokale Nachrichten erhalten eine begrenzte Gültigkeitsdauer und ein Speicherlimit. Der Nutzer kann die Warteschlange löschen und Relaisbetrieb jederzeit beenden.
- Ein deaktivierter Jagdmodus wird beim nächsten Serverkontakt berücksichtigt. Ein vollständig getrenntes Gerät kann eine serverseitige Änderung nicht sofort erfahren; kurze Gültigkeitszeiten und erneute Prüfung beim Upload begrenzen dieses unvermeidbare Zeitfenster.
- Die Oberfläche zeigt „gespeichert“, „an Relais übergeben“ und „vom Server bestätigt“ als verschiedene Zustände. Eine lokal weitergereichte Meldung wird nicht als bereits zugestellt dargestellt.

## Android-Betrieb und Grenzen

BLE-Scanning und -Verbindungen benötigen je nach Android-Version zusätzliche Berechtigungen für Geräte in der Nähe. Dauerhafte Suche im Hintergrund ist durch Android und herstellerspezifische Energiesparfunktionen eingeschränkt und kann Akku verbrauchen. Die App muss Relaisbetrieb sichtbar machen, möglichst gebündelt synchronisieren und eine Pause bei niedrigem Akkustand anbieten.

Ein Relais funktioniert nur, wenn beide Geräte gleichzeitig in Funkreichweite sind, Bluetooth beziehungsweise der gewählte Transport verfügbar ist und das Relais laufen darf. Ein einzelner Tracker als Gateway deckt nur seine lokale Funkreichweite ab. Funklöcher, abgeschaltete Geräte, Berechtigungsentzug und leere Akkus bleiben mögliche Unterbrechungen. Das System verbessert die Zustellchance, garantiert aber weder Echtzeit noch eine bestimmte Reichweite oder Zustellung.

## Einführung in Stufen

1. **Nachrichtenbasis:** Bestehende Outbox um Nachrichten-ID, Ablauf, Sequenz und explizite Zustellzustände erweitern. Serverseitige Deduplizierung und Bestätigung für den direkten Upload einführen.
2. **Identität und Server-API:** Geräteschlüssel nicht mehr als Relais-Credential verwenden. Gerätesignaturen, öffentliche Schlüssel, Relay-Berechtigung, Widerruf und einen deduplizierenden Relay-Eingang einführen.
3. **BLE-Ein-Hop-Relais:** Opt-in, Geräteentdeckung, authentifizierte Sitzung, dauerhafte Übergabe und Gateway-Upload implementieren. Direkter HTTPS-Versand bleibt unverändert verfügbar.
4. **WLAN-Adapter und Optimierung:** Erst nach Messungen auf realen Geräten ergänzen; Bündelung, Akkuverbrauch und größere Warteschlangen berücksichtigen.
5. **Mehrhop optional:** Nur bei nachgewiesenem Bedarf ergänzen. Hop-Limit, Ablaufzeit, Deduplizierung und Weiterleitungsbudget verhindern Schleifen und unkontrolliertes Nachrichtenwachstum.

## Abnahmekriterien für den ersten Relais-Ausbau

- Eine Position erreicht den Server ohne Mobilfunk am Ursprungsgerät, wenn ein zugelassenes Online-Relais per BLE erreichbar ist.
- Prozessabbruch oder verlorene Bestätigungen führen weder zu stillen Datenverlusten noch zu mehrfach sichtbaren Positionen.
- Relais können den Geräte-Bearer-Token und die Position im Klartext nicht auslesen.
- Widerrufene, manipulierte, doppelte und abgelaufene Nachrichten werden serverseitig verworfen.
- Eine ältere verspätete Meldung überschreibt keine neuere Position.
- Nutzer können Relaisbetrieb erkennen und beenden; bei fehlender Verbindung wird der Zustand als ausstehend und nicht als zugestellt angezeigt.
- Tests decken Verbindungsabbrüche während Entdeckung, Übergabe und Serverantwort sowie Android-Hintergrund- und Energiesparverhalten ab.

## Offene Produktentscheidungen

- Gilt die Relaisfreigabe für alle Tracker eines Reviers oder nur für einzelne, explizit gekoppelte Geräte?
- Wie lange dürfen offline gepufferte Positionen nach der Erfassung noch zugestellt werden?
- Soll der Tracker ältere Punkte als Verlauf übertragen oder nur die jeweils neueste Position priorisieren?
- Welche Android-Geräte und Betriebssystemversionen müssen BLE-Relaisbetrieb zuverlässig unterstützen?
- Welche Status- und Datenschutzinformationen sollen Reviermitglieder in der Jagdkarte sehen?