# 🗺️ Offline-Positionsanzeige - Handlungskarte

**Erstellt:** 2026-11-18  
**Status:** Arbeitsentwurf; technische Freigabe und Aufwandsschätzung offen  
**Komplexität:** Medium  
**Geschätzter Aufwand:** 30-35 Stunden

---

## 🎯 Leitgedanke

**Einfache Bedienbarkeit durch Vertrauen:**
- Keine manuellen Dialoge oder Zustimmungsanfragen
- Bestehende Revierberechtigungen (RLS) sind ausreichend
- Automatische, Zero-Click-Verbindung zwischen registrierten Geräten
- Maximal 15 Min. Offline-Gültigkeit für Positionen

---

## 📋 Implementierungs-Phasen

### **Phase 1: Datenstrukturen & Verschlüsselung (8h)**

### **Phase 1: Datenstrukturen & Verschlüsselung (8h)**

#### 1a: Datenstrukturen (3.5h)
- **Task 1a-1**: `OfflinePosition.kt` (Kotlin Data Class)
  ```kotlin
  data class OfflinePosition(
      val latitude: Double,
      val longitude: Double,
      val accuracyM: Float,
      val bearingDeg: Int?,
      val speedMps: Float,
      val timestampMs: Long,
      val deviceName: String,
      val huntingMode: Boolean,
      val batteryPct: Int,
      val source: String = "gps"
  )
  ```

- **Task 1a-2**: `OfflinePositionMessage.kt` (Wrapper mit Header)
  ```kotlin
  data class OfflinePositionMessage(
      val version: Int = 1,
      val type: String = "offline.position",
      val senderId: String,
      val senderType: String,  // "participant" | "dog"
      val profileId: String,
      val messageSeq: Long,
      val createdAt: String,
      val expiresAt: String,
      val nonce: String,
      val ciphertext: String,
      val signature: String
  )
  ```

- **Task 1a-3**: SQLite Schema erweitern
  ```sql
  CREATE TABLE offline_positions (
    id TEXT PRIMARY KEY,
    sender_id TEXT,
    sender_type TEXT,
    profile_id TEXT,
    latitude REAL,
    longitude REAL,
    accuracy_m REAL,
    created_at INTEGER,  -- epoch millis
    expires_at INTEGER,
    received_at INTEGER,
    source TEXT,
    ciphertext TEXT,  -- für Neu-Deserialisierung
    -- profile_id is scoped locally; this database has no profiles table.
  );
  
  CREATE TABLE message_ledger (
    id TEXT PRIMARY KEY,
    sender_id TEXT,
    profile_id TEXT,
    last_message_seq INTEGER,
    last_verified_at INTEGER
  );
  ```

#### 1b: Verschlüsselung & Validierung (4.5h)
- **Task 1b-1**: `OfflinePositionCipher.kt`
  - AES-256-GCM Verschlüsselung (12-Byte Nonce)
  - Revierposition-Schlüssel ableiten: `HKDF(master_key, "offline.position", profile_id)`
  - Abhängig: `LocationPayloadCipher` (bestehend)

- **Task 1b-2**: `OfflinePositionValidator.kt`
  - TTL-Prüfung (`created_at ≤ now ≤ expires_at`)
  - JTS Reviergrenze-Validierung (wie `HuntingLocation.locationInsideBoundary`)
  - RSA-Signatur mit öffentlichem Schlüssel aus cache'd Verzeichnis
  - Accuracy & GPS-Plausibilität

- **Task 1b-3**: RSA-Signatur in `OfflinePositionMessage.kt`
  - Wrapper-Signieren (alles außer `ciphertext`) mit Android Keystore
  - Abhängig: `RelaySigningIdentity` (bestehend)

---

### **Phase 2: Speicherung & Replay-Schutz (6.5h)**

- **Task 2-1**: `OfflinePositionStore.kt` (Repository-Pattern)
  ```kotlin
  suspend fun save(position: OfflinePosition, message: OfflinePositionMessage)
  suspend fun load(profileId: String): List<OfflinePosition>
  suspend fun deleteExpired(before: Long)
  suspend fun deleteByProfile(profileId: String)
  ```

- **Task 2-2**: `MessageLedger.kt` (Replay-Schutz)
  ```kotlin
  fun shouldAccept(senderId: String, profileId: String, messageSeq: Long): Boolean
  fun record(senderId: String, profileId: String, messageSeq: Long)
  ```
  - Prüfe `messageSeq > last_recorded_seq`
  - Speichere in SQLite
  - Cleanup alle 7 Tage

- **Task 2-3**: `RelayOfflineAuthorization.kt` erweitern
  - Cache Revierposition-AES-Schlüssel beim Online-Login
  - Erneuern bei Autorisierungs-Refresh (24h)
  - Nach Geräteneustart: Laden aus `DeviceTokenStorage`

---

### **Phase 3: Kartenintegration & Nearby (10h)**

- **Task 3-1**: `NearbyRelayTransport.kt` um Offline-Handler erweitern
  - `onIncomingOfflinePosition: (OfflinePositionMessage) -> Unit` Callback
  - Automatische Validierung & Speicherung

- **Task 3-2**: `JagdkarteRepository.kt` erweitern
  - `getOfflinePositions(profileId): Flow<List<OfflinePosition>>`
  - Merge mit Backend-Positionen nach Aktualität

- **Task 3-3**: `JagdkarteScreen.kt` Marker-Layer
  - Offline-Positionen als separate Marker mit "⚡ Funk"-Label
  - Unterschiedliche Farbe/Icon vs. Backend-Positionen
  - Alter anzeigen: "vor 2 Min." vs. "von 14:30"
  - Genauigkeit: "±12m"

- **Task 3-4**: `LocationTrackingService.kt` Merge-Logik
  - Für jeden Sender: Backend-Position vs. Offline-Position
  - Jüngste gewinnt (nach Message-ID + Timestamp)
  - Quellenangabe deutlich machen

---

## 🔐 Vertrauensmodell (Vereinfacht)

| Aspekt | Lösung |
|--------|--------|
| **Authentifizierung** | RSA-Signatur mit öffentlichem Schlüssel aus cache'd Revierverzeichnis |
| **Verschlüsselung** | Gemeinsamer Revierposition-Schlüssel (AES-256-GCM) ⇒ alle im Revier können lesen |
| **Autorisierung** | Bestehende Revierberechtigungen (RLS/Rollen) + Online-Registrierung |
| **Replay-Schutz** | Monotone Sequenzen per Sender + Revier in SQLite Ledger |
| **Revierbindung** | GPS-Koordinaten gegen Reviergrenzen validiert (JTS) |
| **Aktualität** | 15-Min. TTL ab Erstellung, Quelle & Alter gekennzeichnet |

---

## 📦 Abhängigkeiten & Bestehender Code

### Zu verwenden (bestehend):
- ✅ `RelayEnvelope` → Struktur kopieren (vereinfacht)
- ✅ `LocationPayloadCipher` → AES-GCM Muster verwenden
- ✅ `RelaySigningIdentity` → RSA Signatur mit Keystore
- ✅ `HuntingLocation.locationInsideBoundary` → JTS Validierung
- ✅ `MessageStore` → SQLite erweitern
- ✅ `RelayOfflineAuthorization` → Schlüsselverwaltung erweitern
- ✅ `NearbyRelayTransport` → Payload-Handler erweitern

### Nicht ändern (PWA-Kompatibilität):
- ❌ Backend-Server-Strukturen
- ❌ RelayEnvelope (Server-Encryption bleibt separat)
- ❌ Bestehende HTTPS-Pfade (`dog-location`, `dog-location-relay`)

---

## ✅ Implementierungs-Checkliste

### Schritt 1: Setup & Design Review
- [ ] Spezifikation mit Team reviewen
- [ ] Datenbankschema mit DBA absprechen
- [ ] Testplan aufstellen (Unit + Instrumented)

### Schritt 2a: Datenstrukturen (Phase 1a)
- [ ] Task 1a-1: `OfflinePosition.kt` ✓
- [ ] Task 1a-2: `OfflinePositionMessage.kt` ✓
- [ ] Task 1a-3: SQLite Schema ✓
- [ ] Code-Review & Merge

### Schritt 2b: Verschlüsselung (Phase 1b)
- [ ] Task 1b-1: `OfflinePositionCipher.kt` ✓
- [ ] Task 1b-2: `OfflinePositionValidator.kt` ✓
- [ ] Task 1b-3: RSA-Signatur ✓
- [ ] Unit-Tests: 50% Coverage
- [ ] Code-Review & Merge

### Schritt 3: Speicherung (Phase 2)
- [ ] Task 2-1: `OfflinePositionStore.kt` ✓
- [ ] Task 2-2: `MessageLedger.kt` ✓
- [ ] Task 2-3: `RelayOfflineAuthorization` erweitern ✓
- [ ] Unit-Tests: 80% Coverage
- [ ] Code-Review & Merge

### Schritt 4: Integration (Phase 3)
- [ ] Task 3-1: `NearbyRelayTransport` ✓
- [ ] Task 3-2: `JagdkarteRepository` ✓
- [ ] Task 3-3: `JagdkarteScreen` Marker ✓
- [ ] Task 3-4: Merge-Logik ✓
- [ ] Instrumented-Tests (Emulator 2 Geräte)
- [ ] Code-Review & Merge

### Schritt 5: Tests & Docs
- [ ] Unit-Tests alle Cipher-/Validierungs-Cases
- [ ] Replay-Schutz geprüft
- [ ] Reviergrenzen-Validierung geprüft
- [ ] Offline-Persistenz nach Neustart ✓
- [ ] Dokumentation aktualisieren (README, PORTIERUNGSSTATUS.md)

---

## 📊 Erfolgs-Kriterien

Nach Phase 3 sollte **auf 2 Emulator-Geräten** folgendes funktionieren:

1. **Automatische Verbindung**: Nach "Jagdmodus aktivieren" verbinden sich Geräte ohne Dialog
2. **Offline-Positionen sichtbar**: Gerät A sieht Gerät B's Position (auch ohne Internet)
3. **Quellenangabe**: "⚡ Funk vor 2 Min. ±12m" vs. "Server 14:30"
4. **Aktualität**: Nach 15 Min. verschwindet die Offline-Position aus der Anzeige
5. **Replay-Schutz**: Duplikate & alte Nachrichten werden verworfen
6. **Revierbindung**: Position außerhalb der Grenze wird nicht angezeigt

---

## 🔮 Zukunftsphasen (nach Phase 3)

Nach stabiler Offline-Positions-Anzeige:

### **Phase 4a: Broadcasts (12-15h)**
- Vorgefertigte Textmeldungen für alle Revier-Teilnehmer
- Z.B. "Treffpunkt verschoben", "Jagd beendet"
- Android Notifications, 30 Min. TTL
- Mittlere Komplexität

### **Phase 4b: Alerts (18-22h)** 
- Vibration/Klingeln auf ausgewählten Hundegeräten
- Hundeauswahl-Dialog für Jagdleiter
- Absender-Authentifizierung & Zielgruppen-Validierung
- Hohe Komplexität, Batterie-Impact

**→ Details in: [BroadcastAlertDesign](BroadcastAlertDesign.md)**

---

## 🚀 Start: Nächster Schritt?

**Empfehlung:**
1. Diese Spezifikation mit Team durchsprechen
2. ✅ Spezifikation freigeben
3. ▶️ **Mit Task 1a-1 starten** (`OfflinePosition.kt`)

**Fragen vor dem Start?**
- Sollte `OfflinePosition` auch `deviceId` als Feld haben? (Ja, für Quellentracking)
- Passen die SQLite Spalten zu euren bestehenden Schemen?
- Welches Test-Backend für Instrumented Tests verwenden? (Testprojekt oder Public?)

---

**Zuletzt aktualisiert:** 2026-11-18 07:24 UTC
