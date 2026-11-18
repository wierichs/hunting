# Offline-Positionsnachrichtenformat

**Datum:** 2026-11-18  
**Status:** Design-Phase  
**Zielgruppe:** Implementierung in Jagdbruder Android-App

> **Arbeitsentwurf, nicht freigegeben.** Die vorgeschlagene gemeinsame
> Revierschlüssel-Ableitung ist keine Implementierungsgrundlage. Der aktuelle
> Protokollbaustein signiert Positionsfelder und prüft sie beim Empfang über
> Nearby gegen das authentifizierte Absendergerät und das Offline-Verzeichnis.
> Das Teilnehmer-Relais speichert verschlüsselt den jeweils neuesten Stand pro
> Konto, Revier und Absendergerät. Doppelte und ältere Meldungen überschreiben
> ihn nicht. Ein bestätigtes Jagdmodusende löscht den Stand für das Revier.
> Teilnehmer- und Hundegeräte senden ihre jeweils letzte Position direkt an
> authentifizierte Teilnehmergeräte. Das signierte Offline-Verzeichnis bindet
> die Benutzer- bzw. Hunde-ID an das Absendergerät. Dafür zuerst die neue
> Migration und danach `location-relay` im PWA-Projekt bereitstellen und das
> Offline-Verzeichnis einmal online erneuern. Mit alten Verzeichnissen bleibt
> der bisherige Server-Relay möglich, aber keine lokale Positionsübertragung.
> Die Karte führt Positionsquellen pro Teilnehmer oder Hund zusammen. Über
> Nearby empfangene Positionen werden ab 120 Sekunden als veraltet markiert
> und bleiben bis zum bekannten Jagdmodusende sichtbar. RLS autorisiert keine
> Offline-Nachrichten.

---

## 1. Designprinzipien

### Vereinfachung durch Vertrauen
- **Teilnehmer untereinander vertrauenswürdig**: Besitzer, Bearbeiter und Gäste desselben Reviers sind authentifiziert
- **Keine zusätzlichen Dialoge nötig**: Revierberechtigungen (RLS/Rollen) reichen aus
- **Automatische Erkennung & Verbindung**: Zero-Click-Erlebnis
- **Minimal-Format**: Nur notwendige Daten, keine redundanten Felder

### Anforderungen
1. **Vertraulichkeit**: Nearby-Verbindung verschlüsselt den Transport; lokale
   Speicherung muss mit dem vorhandenen `LocationPayloadCipher` abgesichert werden
2. **Authentizität**: Signaturen der Absender-Identität (RSA, Android Keystore)
3. **Aktualität**: Versionsnummern, Zeitstempel, Zielabstand (Aktualitäts-TTL)
4. **Revierbindung**: Nachricht nur für Geräte desselben Reviers dekodierbar
5. **Quellentracking**: Backend vs. Funk-Quelle erkennbar
6. **Replay-Schutz**: Monotone Sequenzen pro Sender + Revierpaar
7. **Effizient**: Klein genug für Nearby-Übertragung (Payload max. ~8 KB)

---

## 2. Nachrichtenformat (binär/JSON)

### 2.1 Wrapper-Struktur (wie RelayEnvelope, aber vereinfacht)

```json
{
  "v": 1,
  "type": "offline.position",
  "sender_id": "device-uuid",
  "sender_type": "participant" | "dog",
  "profile_id": "uuid-of-hunting-territory",
  "message_seq": 42,
  "created_at": "2026-11-18T07:18:00Z",
  "expires_at": "2026-11-18T07:33:00Z",
  "nonce": "base64(...)",
  "ciphertext": "base64(...)",
  "signature": "base64(...)"
}
```

**Erklärung:**
- `v`: Format-Version (zukünftige Kompatibilität) → aktuell `1`
- `type`: Nachrichtentyp (erweiterbar für Zukunftsfunktionen):
  - `offline.position` (aktuell)
  - `offline.broadcast` (geplant: vorgefertigte Nachrichten für alle)
  - `offline.alert` (geplant: Vibration/Klingeln für ausgewählte Hunde)
  - `offline.marker` (zukünftig: gemeinsame Markierungen)
- `sender_id`: Absender-Geräte-ID (eindeutig pro Gerät + Revierberechtigten-Konto)
- `sender_type`: Unterscheidung Teilnehmer vs. Hund (für Anzeige)
- `profile_id`: Revier-UUID (Bindung an einen Revierbereich)
- `message_seq`: Laufende Nummer (Replay-Schutz, monoton pro Sender + Revier)
- `created_at`: Nachrichtenerstellungszeit (UTC, für Aktualität)
- `expires_at`: Höchstens 15 Minuten nach Erstellung (Offline-Gültigkeit)
- `nonce`: IV für AES-GCM (zufällig, 12 Bytes base64)
- `ciphertext`: Verschlüsselte Nutzdaten (AES-256-GCM)
- `signature`: RSA-Signatur des Wrapper (außer `ciphertext`) nach PKCS#1 v1.5

### 2.2 Verschlüsselte Nutzdaten (Plaintext)

```json
{
  "latitude": 50.123456,
  "longitude": 8.654321,
  "accuracy_m": 12.5,
  "bearing_deg": 180,
  "speed_mps": 2.5,
  "timestamp_ms": 1700294280000,
  "device_name": "Mikes Handy",
  "hunting_mode": true,
  "battery_pct": 85,
  "source": "gps"
}
```

**Erklärung:**
- `latitude`, `longitude`: WGS84, für JTS-Validierung gegen Reviergrenzen
- `accuracy_m`: GPS-Genauigkeit (für Anzeige: "±12m" zur Transparenz)
- `bearing_deg`: Fahrtrichtung (0-360, `null` wenn < 1 m/s)
- `speed_mps`: Geschwindigkeit (für spätere Richtungspfeil-Animation)
- `timestamp_ms`: GPS-Zeit (für "age"-Anzeige: "vor 3 Min.")
- `device_name`: Lesbar für Benutzer ("Mikes Handy", "Bella Hund")
- `hunting_mode`: Jagdmodus aktiv? (optionale spätere Filterung)
- `battery_pct`: Akkustand (Warnung, wenn < 20%)
- `source`: "gps" (reserviert für: "relay-received" später)

**Größe:** ~300 Bytes plaintext, ~400 Bytes base64-codiert ciphertext

---

## 3. Verschlüsselung & Signatur

### 3.1 Schlüsselaustauch (vereinfacht)

**Problem:** Wie wissen zwei Offline-Geräte, welcher Schlüssel des anderen zu verwenden ist?

**Lösung (vertrauensbasiert):**

1. **Online-Registrierung** (bestehend):
   - Gerät A registriert öffentliche Revierverzeichnis mit eigenem RSA-Schlüssel
   - Revierverzeichnis ist signiert, für alle Geräte des gleichen Reviers sichtbar
   
2. **Offline-Initialisierung** (neu):
   - Beim Aktivieren von Jagdmodus ladet Gerät A das Revierverzeichnis
   - Speichert lokal die öffentlichen RSA-Schlüssel aller anderen Geräte
   - Speichert auch symmetrischen **Revierposition-Schlüssel** (AES-256):
     - Hergeleitet aus: `HKDF(master_key, "offline.position", profile_id)`
     - Master-Key: Bestehender Device-Schlüssel oder neu generiert bei Revierregistrierung
   
3. **Offline-Kommunikation**:
   - Sender verschlüsselt Nutzdaten mit Revier-AES-Schlüssel (**alle im Revier können diese lesen**)
   - Sender signiert den Wrapper mit eigenem RSA-Schlüssel
   - Empfänger authentifiziert Signatur via öffentlicher Schlüssel aus cache'd Verzeichnis

**Vorteil:** 
- ✅ Keine Handshakes nötig
- ✅ Keine privaten Schlüssel geteilt
- ✅ Vertrauensbasiert (angemeldete Teilnehmer = nicht-adversarial)
- ✅ Größe konstant (~8 KB max.)

### 3.2 Algorithmen

| Operation | Algorithmus | Parameter |
|-----------|-------------|-----------|
| **Verschlüsselung** | AES-256-GCM | NIST-Standard, 12-Byte Nonce |
| **Signatur** | RSA-2048 + SHA-256 | RSASSA-PKCS1-v1_5 (wie bestehend) |
| **Key-Derivation** | HKDF-SHA256 | RFC 5869, für Revier-AES-Schlüssel |
| **Revierschlüssel** | Gehashed Profile-ID | `master_key || profile_id` als Input |

---

## 4. Aktualitäts- & Quellentracking

### 4.1 TTL (Time-To-Live)

- **Gültigkeit**: 15 Minuten ab Erstellung (nicht länger als Offline-Autorisierung)
- **Offline-Speicherung**: Max. 20 Positionen pro Gerät, 32 MB Gesamtgröße
- **Anzeige**: "vor 2 Min. (Funk)" vs. "von heute 14:30 (Server, veraltet)"

### 4.2 Quellenmarkierung

```json
{
  "source": "gps",           // Lokal erfasst
  "relay_hop": 1,           // Kam über 1 Hop (später für Mehrhop)
  "confirmed_by_server": false  // Serverbestätigung erhalten?
}
```

Kartenanzeige:
- Pro Teilnehmer oder Hund gibt es einen Marker. Eine nutzbare Serverposition
  gewinnt bei gleicher oder neuerer Messzeit; andernfalls zeigt die Karte den
  letzten Nearby-Stand.
- Im Markerdetail steht bei Nearby-Positionen die Quelle und die Messzeit.
  Nach 120 Sekunden bleibt der Marker sichtbar, wird aber blass bzw. grau und
  im Detail als „veraltet“ gekennzeichnet.
- Der verschlüsselte Reviercache enthält Namen und Rollen, aber keine
  Positionskoordinaten von Teilnehmern oder Hunden.

---

## 5. Replay-Schutz

### 5.1 Lokale Ledger

Pro Gerät + Revier:
```
message_ledger {
  sender_id: "device-X",
  profile_id: "hunt-123",
  last_message_seq: 42,
  last_verified_at: 1700294280000,
  ledger: [
    { message_id: "msg-uuid-1", seq: 40, verified_at: 1700294100000, confirmed: true },
    { message_id: "msg-uuid-2", seq: 41, verified_at: 1700294190000, confirmed: true },
    { message_id: "msg-uuid-3", seq: 42, verified_at: 1700294280000, confirmed: false }
  ]
}
```

**Regeln:**
- Nachricht akzeptieren nur wenn `seq > last_message_seq`
- Doppelten Message-ID verwerfen
- Lücken erlaubt (Paketsverlust), aber alte nachrichten ablehnen

### 5.2 Offline-Persistenz

- Ledger in `MessageStore` SQLite (wie bestehend)
- Nach Geräteneustart: Ledger lädt, nächste Nachricht muss `seq > gespeichert`
- Alte Einträge alle 7 Tage bereinigen

---

## 6. Revierbindung & Autorisierung

### 6.1 Validierung beim Empfang

1. **Wrapper-Validierung**:
   - `profile_id` im Revier des empfangenden Geräts vorhanden?
   - `created_at` ≤ jetzt ≤ `expires_at`?
   - RSA-Signatur mit öffentlichem Schlüssel aus cache'd Verzeichnis valide?

2. **Nutzdaten-Validierung**:
   - Mit Revierposition-AES-Schlüssel dekryptieren
   - GPS-Koordinaten mit JTS gegen Reviergrenzen prüfen
   - Accuracy + Alter prüfen (zu alt = aus Anzeige)

3. **Autorisierung**:
   - Sind beide Geräte für das gleiche Revier online registriert? ✓ (aus cache'd Verzeichnis)
   - Hat der Sender Jagdmodus aktiv? ✓ (aus Revierstand)
   - → Ohne Server-Prüfung, basierend auf lokal gespeicherten Daten

### 6.2 Offline-Autorisierungsfresh ness

(Bestehende `RelayOfflineAuthorization` erweitern)

- Autorisierung bei Online-Erneuern mitnehmen
- Gültig bis: `24 Stunden` oder bis Revierwechsel
- Nach Geräteneustart: Erneuern beim nächsten Online-Besuch

---

## 7. Kompatibilität & Versionierung

### 7.1 Forward Compatibility

```
{
  "v": 1,  // Format-Version → Parsing-Regeln
  "type": "offline.position"  // Nachrichtentyp → Behandlung
}
```

**Zukünftige Versionen:**
- `v=1`: Aktuelle (2026)
- `v=2`: Könnte z.B. komprimierte Payloads, Mehrhop-Routing-Header etc.
- Alte Apps ignorieren unbekannte Versionen

### 7.2 PWA & Backend Kompatibilität

- Offline-Positionen werden **nur lokal** angezeigt
- Server sieht diese Nachrichten nicht (server-verschlüsselter Envelope ist andere Struktur)
- PWA bleibt unverändert, kennt Offline-Format nicht
- Pro Teilnehmer/Hund zählt die neuere nutzbare Position; bei gleicher Zeit hat der Serverstand Vorrang

---

## 8. Implementierungs-Roadmap

### Phase 1a: Datenstrukturen
- [ ] `OfflinePosition.kt` (Datenklasse)
- [x] Signiertes `OfflinePositionMessage`-Format und gezielte Parser-/Signaturtests
- [ ] SQLite Schema erweitern (`offline_positions` Tabelle)

### Phase 1b: Verschlüsselung & Validierung
- [ ] `OfflinePositionCipher.kt` (AES-256-GCM)
- [ ] `OfflinePositionValidator.kt` (Reviergrenze, TTL, Signatur)

### Phase 2: Speicherung & Ledger
- [x] `OfflinePositionStore.kt`: verschlüsselte Speicherung der letzten Position, Löschen bei bekanntem Jagdmodusende
- [x] Sequenz- und Messzeitprüfung beim Speichern, ohne separate Ledger-Datei

### Phase 3: Nearby-Integration
- [x] Separater Empfangspfad mit Absenderprüfung und Quittung nach erfolgreicher Speicherung
- [x] Positionsmeldungen von Teilnehmer- und Hundegeräten ohne Internet senden (aktualisiertes Offline-Verzeichnis erforderlich)

### Phase 4: Kartenintegration
- [x] Server- und Nearby-Positionen in der Karte zusammenführen
- [x] Quelle, gespeicherte Namen und Veraltung anzeigen

### Phase 5: Tests
- [ ] Unit-Tests: Verschlüsselung, Validierung, Replay-Schutz
- [ ] Integration-Tests: Speicherung, Reviergebindung
- [x] Funk- und Kartenanzeige zwischen zwei Teilnehmergeräten nachweislich ohne Internet prüfen
- [x] Frische Hundeposition vom Offline-Hundetracker per Nearby auf der Jagdkarte anzeigen
- [x] Funk- und Kartenanzeige für Hundepositionen mit beiden Geräten ohne Internet prüfen

**Feldtest am 18.11.2026 (Motorola und Xiaomi):** Das ursprünglich bestätigte
Kartenverhalten ist kein belastbarer Offline-Nachweis: Auf dem Motorola waren
noch mobile Daten aktiv. Beim anschließenden Versuch hatten beide Geräte
keine Standard-Netzverbindung; WLAN und Bluetooth waren eingeschaltet. Das
Xiaomi forderte als Hundetracker einen Standort an, hatte zum Zeitpunkt der
Diagnose aber noch keine GNSS-Position geliefert. Im späteren Lauf lieferte
das Xiaomi ohne Internet frische GPS-Messungen. Auf dem weiterhin online
verbundenen Motorola wurde eine frische Hundeposition mit der Quelle
„Per Nearby empfangen“ bestätigt. Nach Abschalten der mobilen Daten am
Motorola hatten beide Geräte laut USB-Diagnose keine Standard-Netzverbindung.
Auf dem Motorola wurde erneut „Per Nearby empfangen“ angezeigt, diesmal mit
einer Messzeit nach der Netztrennung. Im DogTracker wird die
Nearby-Revierberechtigung als eigener Status neben dem
GPS-/Upload-Status angezeigt. Eine gültige Berechtigung bestätigt noch keine
Funkverbindung und keinen empfangenen Standort.

**Teilnehmertest am 18.11.2026:** Motorola und Xiaomi liefen beide als
Teilnehmergeräte ohne Standard-Netzverbindung. Beide Karten zeigten die
Position des jeweils anderen Teilnehmers als „Per Nearby empfangen“ mit
einer Messzeit nach der Netztrennung. Auch die Eigenpositionsanzeige über
das Fadenkreuz funktionierte auf beiden Geräten offline. Auf dem Xiaomi
dauerte es länger, bis die eigene Position sichtbar wurde.

**Teilnehmermarker und Jagdende am 18.11.2026:** Nach Abschalten der
Standortfreigabe auf dem Xiaomi blieb dessen Teilnehmermarker auf dem
offline betriebenen Motorola sichtbar und wurde ab 120 Sekunden nach der
letzten Messung als „veraltet“ angezeigt. Das anschließend online auf dem
Xiaomi ausgelöste Jagdende änderte die Offline-Anzeige auf dem Motorola
zunächst nicht. Nach dessen Wiederverbindung und dem automatischen Abruf
des Revierstatus verschwand der Marker ohne App-Neustart. Nearby überträgt
derzeit keinen Jagdmodus-Status. Die Weitergabe eines serverbestätigten
Jagdendes über Nearby bleibt eine mögliche Folgeerweiterung.

**Manueller Abnahmetest:** Nach Bereitstellung des neuen Offline-Verzeichnisses
das Revier auf beiden Smartphones einmal online öffnen. Anschließend den
Internetzugang auf beiden Geräten abschalten (mobile Daten aus und keine
Verbindung zu einem WLAN mit Internet), WLAN und Bluetooth aber eingeschaltet
lassen. Möglichst im Freien zuerst sicherstellen, dass der Sender eine frische
Position erfasst. Auf dem Empfangsgerät prüfen, dass der zweite Teilnehmer
bzw. in einem zweiten Lauf der Hundetracker genau einen Marker erzeugt, dessen
Detail „Per Nearby empfangen“ und eine Messzeit nach der Netztrennung zeigt. Den
Sender stoppen und prüfen, dass die letzte Position nach zwei Minuten
veraltet aussieht, aber sichtbar bleibt. Danach den Jagdmodus auf einem
Online-Gerät beenden, während das Empfangsgerät offline bleibt. Der Marker
bleibt zunächst sichtbar und soll nach Wiederverbindung und automatischem
Abruf des Revierstatus ohne App-Neustart verschwinden.

---

## 11. Zukünftige Nachrichtentypen (Phase 4+)

### 11.1 `offline.broadcast` – Vorgefertigte Nachrichten für alle

**Anwendungsfall:**
- Jagdleiter sendet Nachricht an alle Teilnehmer
- Z.B. "Treffpunkt verschoben", "Jagd beendet", "Warnung: fremde Jäger gesichtet"
- Mit speicherbarer Template-Liste (3-5 vordefinierte Nachrichten)

**Wrapper (wie `offline.position`, aber `type: "offline.broadcast"`)**

**Verschlüsselte Nutzdaten:**
```json
{
  "template_id": "msg-1",
  "template_text": "Treffpunkt verschoben zu Coordinate [51.5, 8.2]",
  "timestamp_ms": 1700294280000,
  "broadcast_to": "all"
}
```

**Speicherung:**
- `offline_broadcast_messages` Tabelle (wie `offline_positions`)
- TTL: 30 Min. (länger als Positionen, da wichtiger)
- Benachrichtigung: Android Toast / Notification Popup

**Implementierung:**
- Phase 4 (nach Position-Anzeige stabil)
- Keine Vibration/Klingeln nötig für Text-Broadcasts
- Mittel-Komplexität

---

### 11.2 `offline.alert` – Vibration/Klingeln für Hunde

**Anwendungsfall:**
- Hundebesitzer wählt einzelne Hundegeräte aus ("Bella", "Rex")
- Sendet Alert-Signal
- Ziel-Hundegeräte vibrieren oder klingeln (auch ohne Screen-Unlock)

**Wrapper (wie `offline.position`, aber `type: "offline.alert"`)**

**Verschlüsselte Nutzdaten:**
```json
{
  "alert_type": "vibration" | "sound" | "both",
  "alert_duration_ms": 2000,
  "alert_intensity": 1..3,  // 1=light, 2=medium, 3=strong
  "triggered_by_device": "participant-device-id",
  "triggered_by_name": "Jäger Max",
  "timestamp_ms": 1700294280000,
  "target_dog_ids": ["dog-device-1", "dog-device-2"]
}
```

**Speicherung:**
- `offline_alerts` Tabelle (Ledger wie `message_ledger`)
- TTL: 5 Min. (kurz, da Echtzeit-Ereignis)
- Duplikat-Prüfung: Gleicher Alert innerhalb 2 Sek. ignorieren

**Implementierung (Komplex):**

1. **UI**: Hundeauswahl-Dialog in Jagdmodus
   - Checkboxes für registrierte Hunde
   - "Alert senden" Button
   - Lokale Benachrichtigung nach Absendung

2. **Vibration/Sound**: Android-native
   ```kotlin
   val vibrator = context.getSystemService(Vibrator::class.java)
   vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 100, 300)))
   
   val ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
   ringtone.play()
   ```

3. **Hintergrund-Ausführung**:
   - Alert-Handler auch bei Bildschirm aus
   - Foreground Service (wenn Jagdmodus aktiv)
   - Wakelock-Handling (Batterie-Impact)

4. **Zielgruppe**:
   - `target_dog_ids` enthält nur Hund-Device-IDs
   - Empfänger prüft: "Bin ich ein registrierter Hund?"
   - Nur Hundegeräte empfangen & reagieren

**Abhängigkeiten:**
- Hund-Registrierung & Pairing (bestehend)
- Lokale Hundeliste abrufen können
- Jagdmodus mit Relay aktiv

**Phase:** 5+ (nach Positions-Anzeige + Tests stabil, mind. 2 Monate später)
**Komplexität:** Hoch

---

### 11.3 Erweiterbarkeits-Strategie

**Für neue Message-Typen:**

1. **Wrapper-Versionierung beibehalten** (`v: 1`, `type: "offline.xxx"`)
2. **Neue SQLite Tabelle pro Typ** (nicht `offline_positions` überladen)
3. **Generischer Handler in `NearbyRelayTransport`**:
   ```kotlin
   fun onOfflineMessageReceived(message: OfflinePositionMessage) {
       when(message.type) {
           "offline.position" -> handlePosition(message)
           "offline.broadcast" -> handleBroadcast(message)
           "offline.alert" -> handleAlert(message)
           else -> logger.warn("Unbekannter Typ: ${message.type}")
       }
   }
   ```
4. **MessageStore** abstrahiert pro Typ
5. **Alte Apps**: Unbekannte Typen stumm ignorieren (Forward-Kompatibilität)

---



1. **Revierposition-Schlüssel-Derivation**: Fest pro Revier, oder rotieren bei jedem Login?
   → Empfehlung: Fest, aber erneuert bei Online-Erneuerung der Autorisierung
   
2. **Höchstgültigkeit der Offline-Positionen**: 15 Min. oder länger?
   → Empfehlung: 15 Min. (Offline-Autorisierung ist 24h, aber Position sollte frischer sein)
   
3. **Maximal speicherbare Positionen**: 20 pro Gerät oder mehr?
   → Empfehlung: 20 (Speicherschutz, Netzwerkeffekt bei vielen Geräten gering)
   
4. **Unterschiedliche TTL für Teilnehmer vs. Hunde?**
   → Empfehlung: Gleich (Jagdlogik ist gleich)

5. **Zustimmungsneuerung bei Freigabe-Ende oder Revierwechsel?**
   → Empfehlung: Ja, bei nächster Autorisierungserneuerung (kein zusätzlicher Dialog)

---

## 10. Sicherheits- & Datenschutz-Prüfungen (später)

- [ ] Reviergrenze-Validierung mit JTS (keine Position außerhalb)
- [ ] Alterscheck gegen GPS-Spoofing
- [ ] Akkuverbrauch bei Nearby-Dauer-Betrieb
- [ ] Offline-Speicher (verschlüsselt, `noBackupFilesDir`)
- [ ] Wiederherstellung nach Geräteneustart
- [ ] Interaction mit Flugmodus, WLAN, Bluetooth

---

**Nächster Schritt:** Spezifikation mit Team reviewen, dann mit Phase 1a beginnen (Datenstrukturen).
