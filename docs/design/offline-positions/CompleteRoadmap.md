# 📊 Offline-Positionssystem – Vollständige Roadmap

**Version:** 2026-11-18  
**Status:** Arbeitsentwurf; Schema, Schlüsselverteilung und Abnahmekriterien sind noch nicht freigegeben.

## Verifizierter Bestand und Leitplanken

- Maßgeblich für Supabase sind das Schema und die Migrationen im separaten
  PWA-Projekt `D:\NoScan\wierichs.github.io\Jagdkarte\`. Änderungen an
  Tabellen, RPCs, Edge Functions oder RLS werden ausschließlich dort entwickelt;
  Bereitstellung und Migrationsstand sind separat zu prüfen.
- `hunting_profile_locations` enthält höchstens einen aktuellen Stand je
  `(profile_id, user_id)`. `hunting_dog_locations` enthält höchstens einen
  aktuellen Stand je `dog_id`. Das Schema ist kein Positionsverlauf.
- Die Relay-Migration ergänzt `hunting_relay_devices` und
  `hunting_relay_messages` für Geräteberechtigung und Nachrichtendeduplizierung.
  Bestätigte Relay-Positionen aktualisieren weiterhin die vorhandenen
  Teilnehmer- und Hundestandorttabellen; sie sind dadurch nicht für Offline-
  Smartphones lesbar.
- RLS wirkt bei Serverzugriffen, nicht als Offline-Autorisierung. Das Vertrauen
  zwischen Reviermitgliedern soll die Bedienung einfach halten, ersetzt aber
  weder Geräteidentität noch die Prüfung des signierten Offline-Verzeichnisses.
  Ein gemeinsamer Revierschlüssel und seine Verteilung sind im Bestand nicht
  vorhanden; die unten skizzierte Schlüsselableitung ist daher keine
  Implementierungsgrundlage.
- Die Android-`MessageStore`-SQLite-Datenbank ist eine verschlüsselte
  Nachrichten-Outbox, kein Bestand empfangener Kartenpositionen. Für lokale
  Anzeige wäre ein eigener, reviergebundener lokaler Bestand nötig; dafür ist
  keine Supabase-Änderung erforderlich, solange die Funktion lokal bleibt.
- Die vorhandene Anzeige verwirft Teilnehmerpositionen nach 90 Sekunden als
  sichtbar und kennzeichnet Hundepositionen nach 120 Sekunden als veraltet,
  lässt sie aber sichtbar. Diese Regeln legen keine TTL für neue Funkpositionen
  fest. Auch die Grenzprüfung ist nicht pauschal auf Hunde übertragbar.
- Fachentscheidung vom 2026-11-18 für die Offline-Anzeige: Teilnehmer- und
  Hundepositionen nach 120 Sekunden als veraltet markieren, aber bis zum
  bekannten Ende des Jagdmodus anzeigen. Bei vollständig getrennten Geräten
  wird ein Jagdmodusende oder Widerruf erst bei einer späteren Verbindung
  bekannt; bis dahin bleibt die Position als veraltet sichtbar.
- Automatische Tests müssen ein manuell nachvollziehbares Nutzerszenario
  abbilden. Protokoll- und Speichertests verwenden lokale Testdaten; echte
  Funkreichweite, Hintergrundverhalten und Akku bleiben Geräteabnahmen. Tests
  mit angeschlossenen Geräten werden gezielt auf ein ausgewähltes Gerät
  beschränkt und nicht ungeprüft auf beide Telefone verteilt.

---

## 🎯 Gesamtvision

```
Phase 1-3: Offline-Positionen (MVP – 25h)
├─ Grundlage für alles Weitere
└─ Automatische Verbindung & gegenseitige Anzeige

Phase 4a: Broadcasts (Addon – 12-15h)
├─ Textmeldungen für alle
├─ Templates: "Jagd beendet", "Treffpunkt verschoben", etc.
└─ Für Jagdleiter & Bearbeiter

Phase 4b: Alerts (Addon – 18-22h)
├─ Vibration/Klingeln auf Hundegeräten
├─ Hundeauswahl-Dialog
└─ Für Jagdleiter zur Koordination
```

---

## 🗓️ Implementierungs-Timeline

### **Quartal 1-2 (Jetzt): Phase 1-3**

**Woche 1-2: Phase 1a (Datenstrukturen)**
- Task 1a-1: `OfflinePosition.kt`
- Task 1a-2: `OfflinePositionMessage.kt`
- Task 1a-3: SQLite Schema
- ✓ Code-Review & Merge

**Woche 3-4: Phase 1b (Verschlüsselung)**
- Task 1b-1: `OfflinePositionCipher.kt`
- Task 1b-2: `OfflinePositionValidator.kt`
- Task 1b-3: RSA-Signaturen
- ✓ Unit-Tests (50%)

**Woche 5-6: Phase 2 (Speicherung)**
- Task 2-1: `OfflinePositionStore.kt`
- Task 2-2: `MessageLedger.kt`
- Task 2-3: `RelayOfflineAuthorization` erweitern
- ✓ Unit-Tests (80%)

**Woche 7-9: Phase 3 (Integration)**
- Task 3-1: `NearbyRelayTransport` erweitern
- Task 3-2: `JagdkarteRepository` erweitern
- Task 3-3: `JagdkarteScreen` Marker
- Task 3-4: Merge-Logik
- ✓ Instrumented-Tests (Emulator)

**Woche 10: Phase 5 (Tests + Docs)**
- Alle Unit-Tests vollständig
- Dokumentation aktualisieren
- ✓ Release & Merge

---

### **Quartal 3+ (Später): Phase 4a + 4b**

Nach stabiler Phase 1-3 & Feldabnahmen:

**Phase 4a: Broadcasts (2-3 Wochen)**
- `OfflineBroadcast.kt` Datenstruktur
- `OfflineBroadcastStore.kt` CRUD
- UI: "Nachricht senden" Dialog
- Android Notifications
- ✓ Instrumented-Tests

**Phase 4b: Alerts (3-4 Wochen)**
- `OfflineAlert.kt` Datenstruktur
- `OfflineAlertStore.kt` CRUD
- UI: "Hunde anrufen" Dialog
- Vibration + Sound Effekte
- Wakelock-Handling
- ✓ Instrumented-Tests (Screen aus)

---

## 🏗️ Nachrichtentypen & Speicherung

```
┌─────────────────────────────────────────┐
│   OfflinePositionMessage (Base)         │
│   - version: 1                          │
│   - type: "offline.xxx"                 │
│   - sender_id, profile_id, message_seq  │
│   - encryption, signature               │
└─────────────────────────────────────────┘
         ▼         ▼           ▼
    ┌────────┐ ┌────────┐ ┌────────┐
    │Position│ │Broadcast│ │Alert   │
    ├────────┤ ├────────┤ ├────────┤
    │GPS     │ │Template │ │Vibrate │
    │Age     │ │Text     │ │Dogs    │
    │Source  │ │TTL:30m  │ │TTL:5m  │
    └────────┘ └────────┘ └────────┘
         ▼         ▼           ▼
    ┌─────────────────────────────────┐
    │  SQLite                         │
    ├─────────────────────────────────┤
    │ offline_positions (Phase 3)     │
    │ offline_broadcasts (Phase 4a)   │
    │ offline_alerts (Phase 4b)       │
    │ message_ledger (Replay-Schutz)  │
    └─────────────────────────────────┘
         ▼         ▼           ▼
    NearbyRelayTransport Handler
         ▼         ▼           ▼
    ┌────────┐ ┌────────┐ ┌────────┐
    │Karte   │ │Notif.  │ │Vibration│
    │Marker  │ │Toast   │ │Sound   │
    └────────┘ └────────┘ └────────┘
```

---

## 🔐 Sicherheits-Modell (einheitlich)

Alle Offline-Nachrichtentypen nutzen:

| Aspekt | Implementierung |
|--------|-----------------|
| **Verschlüsselung** | AES-256-GCM mit Revierposition-Schlüssel |
| **Absender-Auth** | RSA-2048 Signatur (Android Keystore) |
| **Autorisierung** | Bestehende RLS/Rollen + Online-Registrierung |
| **Replay-Schutz** | Monotone Sequenzen per Sender + Revier |
| **Revierbindung** | GPS-Validierung (Phase 1) + Message-Typ-Filter (Phase 4) |
| **Aktualität** | TTL ab Erstellung (15m Position, 30m Broadcast, 5m Alert) |
| **Zielgruppen** | Revier (Position), Revier (Broadcast), Device-Liste (Alert) |

**Wichtig:** Keine Überschneidungen mit Server-Encryption (bestehend).  
Offline-Format ist völlig lokal, PWA bleibt unverändert.

---

## 📋 Checkliste: Vor dem Start (Phase 1-3)

- [ ] **Team-Alignment**
  - [ ] Spezifikation mit Tech-Lead reviewen
  - [ ] Sicherheits-Anforderungen mit InfoSec prüfen
  - [ ] DB-Schema mit DBA absprechen
  - [ ] UX-Mockups mit PM reviewen

- [ ] **Infra-Setup**
  - [ ] Test-Backend (Testprojekt) vorbereiten
  - [ ] CI/CD Pipeline für Tests ready
  - [ ] Android Emulator Konfiguration vorbereitet
  - [ ] Git Branches geplant (feature/offline-positions, etc.)

- [ ] **Abhängigkeiten prüfen**
  - [ ] `LocationPayloadCipher` funktioniert noch? (Test)
  - [ ] `RelaySigningIdentity` Keystore-Zugriff OK? (Test)
  - [ ] `MessageStore` DB-Zugriff OK? (Test)
  - [ ] `NearbyRelayTransport` stabil? (Integrations-Test)

- [ ] **Testplan vorbereiten**
  - [ ] Unit-Test Struktur (Cipher, Validator, Store)
  - [ ] Integration-Test Struktur (Store + Repo)
  - [ ] Instrumented-Test Setup (2 Emulatoren)
  - [ ] Edge-Cases: Reviergrenzen, TTL, Replay

---

## 🎯 Erfolgs-Kriterien (Phase 1-3)

Nach Phase 3 auf 2 Emulator-Geräten:

**Automatisiert prüfbar:**
- ✓ Nachricht verschlüsselt & signiert korrekt
- ✓ Validierung: TTL, Reviergrenze, Signatur
- ✓ Replay-Schutz funktioniert (Duplikate verworfen)
- ✓ Lagerung: Alte Einträge nach 24h gelöscht
- ✓ Offline nach Neustart persistiert

**Instrumented geprüfbar:**
- ✓ Nearby-Verbindung automatisch ohne Dialog
- ✓ Nachricht ankommen im 5-30 Sekunden
- ✓ Offline-Position sichtbar auf Karte (Gerät B sieht Gerät A)
- ✓ Quelle erkennbar ("⚡ Funk" vs. "Server")
- ✓ Nach 15m Gültigkeit verfallen

**Manuell prüfbar (Feldtest später):**
- ✓ Zwei echte Handys ohne Mobilfunk verbunden
- ✓ Gegenseitige Positionen sichtbar
- ✓ Verbindung nach Unterbrechung wiederhergestellt
- ✓ GPS-Genauigkeit angemessen angezeigt

---

## 📞 Schnittstellen zu existendem Code

### Zu verwenden (kopieren/erweitern):

**RelayEnvelope.kt** (Server-Encryption)
→ Muster für `OfflinePositionMessage` (Offline-Encryption)

```kotlin
// PATTERN: Wrapper + verschlüsselte Nutzdaten
fun toJson(): JSONObject = JSONObject()
    .put("version", version)
    .put("ciphertext", ciphertext)
    .put("signature", signature)
```

**LocationPayloadCipher.kt** (AES-GCM)
→ Für `OfflinePositionCipher`

```kotlin
// PATTERN: AES-256-GCM mit HKDF
val cipher = Cipher.getInstance("AES/GCM/NoPadding")
cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, nonce))
```

**RelaySigningIdentity.kt** (RSA-Signatur)
→ Für `OfflinePositionMessage.sign()` / `.verify()`

```kotlin
// PATTERN: RSA mit Android Keystore
val signature = Signature.getInstance("SHA256withRSA")
signature.initSign(privateKey)
signature.update(data)
```

**HuntingLocation.kt** (JTS Reviergrenze)
→ Für `OfflinePositionValidator.validateLocation()`

```kotlin
// PATTERN: JTS Polygon-Validierung
val geometry = GeoJsonReader().read(boundary.toString())
geometry.covers(GeometryFactory().createPoint(Coordinate(lon, lat)))
```

**MessageStore.kt** (SQLite)
→ Tabellen erweitern + Zugriffsmethoden

```kotlin
// PATTERN: CRUD mit Coroutines
suspend fun save(entity: T)
suspend fun load(id: String): T?
suspend fun deleteExpired(before: Long)
```

**RelayOfflineAuthorization.kt** (Offline-Verzeichnis)
→ Schlüsselverteilung erweitern

```kotlin
// PATTERN: Cache Revier-AES-Schlüssel
fun deriveOfflinePositionKey(mastKey: ByteArray, profileId: String): SecretKey
```

**NearbyRelayTransport.kt** (Google Nearby)
→ Message-Payload-Handler erweitern

```kotlin
// PATTERN: Type-basierter Dispatch
when(message.type) {
    "offline.position" -> handlePosition()
    "offline.broadcast" -> handleBroadcast()
    "offline.alert" -> handleAlert()
}
```

**JagdkarteScreen.kt** (UI/Compose)
→ Offline-Marker-Layer + Buttons hinzufügen

```kotlin
// PATTERN: Flow<List<>> sammeln, Marker rendern
offlinePositions.forEach { position ->
    MarkerItem(
        name = position.deviceName,
        label = "⚡ Funk ${position.ageMinutes}m",
        icon = R.drawable.offline_marker
    )
}
```

### Nicht ändern:

- ❌ `RelayEnvelope` (Server-Encryption bleibt)
- ❌ `JagdkarteRepository` HTTPS-Pfade (`dog-location`, `dog-location-relay`)
- ❌ Backend-Schemas
- ❌ PWA-Vertrag

---

## 🚨 Kritische Risiken & Mitigations

| Risiko | Wahrscheinlichkeit | Mitigation |
|--------|-------------------|-----------|
| **Nearby-Verbindung zu langsam/unzuverlässig** | Mittel | Frühe Instrumented-Tests, Timeout-Handling, Retry-Logik |
| **Batterie-Impact** | Mittel-Hoch | Power-Profiling, Nearby-Idle-Handling, Akkuwarnung |
| **Speicher-Fragmentierung** (100+ Positionen) | Niedrig | Maximal 20 pro Gerät, 32 MB Limit, Weekly Cleanup |
| **Reviergrenze-Validierung slow** | Niedrig | JTS Caching, vorberechnete Polygone |
| **Offline-Autorisierung verfällt** | Mittel | 24h TTL + Warnung, Erneuerung bei Online |
| **Spielraum-Duplikate** | Niedrig | Message-Ledger mit Seq-Check |

---

## 📞 Kontakt & Fragen

Für Spezifikations-Fragen:
- 📄 [OfflinePositionMessageFormat](OfflinePositionMessageFormat.md)
- 📄 [ImplementationRoadmap](ImplementationRoadmap.md)
- 📄 [BroadcastAlertDesign](BroadcastAlertDesign.md)

---

**Nächster Schritt:** ▶️ **Mit Task 1a-1 starten** (`OfflinePosition.kt`)

**Status:** ✅ Bereit zur Implementierung
