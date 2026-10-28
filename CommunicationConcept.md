# Kommunikationskonzept: Tracker als Relais

## Ziel

Standortmeldungen sollen auch dann die Jagdkarte erreichen können, wenn das Smartphone eines Hundes selbst keine Internetverbindung hat. Ist ein anderer Tracker per Bluetooth oder lokalem WLAN erreichbar und dieser Tracker online, kann er Nachrichten zwischenspeichern und an den Server weiterleiten.

Jedes Gerät kann dabei beide Rollen übernehmen: Es erzeugt eigene Positionsmeldungen und kann zusätzlich als Relais für berechtigte andere Geräte dienen. Die Funkverbindung allein begründet jedoch keine Berechtigung. Weitergegeben werden nur authentifizierte Nachrichten aus einer freigegebenen Tracker-Gruppe.

## Ausgangslage

Die Android-App erfasst Positionen während einer gestarteten Tracking-Sitzung, speichert sie in einer lokalen SQLite-Warteschlange und lädt sie per HTTPS hoch, sobald das Gerät Internet hat. Die Anmeldung am bestehenden Upload-Endpunkt erfolgt mit dem Geräteschlüssel des Trackers. Eine lokale Geräte-zu-Geräte-Verbindung und serverseitige Deduplizierung sind im aktuellen Stand nicht vorhanden.

Die Relaisfunktion benötigt daher Änderungen an der Android-App **und** an der Server-Schnittstelle der Jagdkarte. Eine App-seitige Änderung allein kann die Nachrichten nicht sicher im Namen eines anderen Trackers annehmen.

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