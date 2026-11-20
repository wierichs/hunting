# 📢 Offline-Broadcast & Alert-System

**Für Phase 4+** (nach stabiler Positions-Anzeige)

> **Historischer Arbeitsentwurf, nicht der implementierte Vertrag.** Direkte
> Nearby-Rundnachrichten und Signale für eigene Hunde sind inzwischen in
> begrenztem Umfang implementiert. Die folgenden Architektur- und UI-Beispiele
> sind unverbindliche Vorschläge und nicht als aktuelle Aufgabenliste oder
> Sicherheitsnachweis zu verstehen. Der tatsächliche Stand und offene
> Abnahmen stehen im [Kommunikationskonzept](../../../CommunicationConcept.md)
> und in den [Testaufgaben](../../testing/Testaufgaben.md).

---

## 🎯 Funktionen

### 1. Broadcast (Mittlere Priorität)

**Was?**
- Vorgefertigte Textmeldungen für alle Revier-Teilnehmer
- Z.B. "Treffpunkt verschoben", "Jagd beendet", "Erste Hilfe nötig"
- Mit optionalen GPS-Koordinaten

**Wer?**
- Jeder mit "Bearbeiter"-Rolle oder höher
- Im aktiven Jagdmodus

**Wie?**
1. Button "Nachricht senden" in Jagdmodus-UI
2. Dialog mit 3-5 vordefinierten Templates
3. Optional: Position anhängen (Treffpunkt)
4. Send → alle Offline-Geräte empfangen

**Empfänger-Erlebnis:**
- Notification: "Jagdleiter Max: Treffpunkt verschoben"
- 30 Min. in Offline-Nachrichtenliste speichern
- Keine Vibration/Ton nötig

---

### 2. Alert / Ruf (Hohe Priorität, Komplex)

**Was?**
- Vibration oder Klingeln auf Hundegeräten
- Ohne Screen-Unlock
- Mit Absender-Info ("Jäger Max ruft dich")

**Wer darf?**
- Jeder mit Jagdmodus aktiv
- Nur für seine eigenen, gepaarten Hunde
- Oder alle Hunde wenn "Bearbeiter" (Jagdleiter)

**Wer empfängt?**
- Nur die ausgewählten Hundegeräte
- Muss Jagdmodus aktiv haben
- Muss die ausreichend frische Autorisierung haben

**Wie?**

**Schritt 1: Hundeauswahl-Dialog (Absender-Seite)**
```
┌─────────────────────────┐
│ Hunde anrufen           │
├─────────────────────────┤
│ ☑ Bella (online)        │
│ ☐ Rex (offline)         │
│ ☑ Luna (online)         │
├─────────────────────────┤
│ Intensität: Light ⚪    │
│            Medium ●    │
│            Strong ◉    │
├─────────────────────────┤
│ [Abbrechen] [Rufen]     │
└─────────────────────────┘
```

**Schritt 2: Alert-Nachricht versenden**
- Nachrichtentyp: `offline.alert`
- Target: `["dog-device-1", "dog-device-2"]`
- Payload: Intensität, Absender-Name, Triggersignal

**Schritt 3: Hundegerät empfängt & reagiert**
- Prüfung: "Bin ich ein Hund?" + "In meiner Target-Liste?"
- Vibration: 300ms (Light) bis 1000ms (Strong)
- Sound: Optional (konfigurierbar)
- UI-Benachrichtigung: Toast + Notification
- Logging: "Rufer: Max um 14:35"

**Schritt 4: Absender-Bestätigung**
- Benachrichtigung: "Alert an Bella & Luna erfolgreich übertragen (Offline)"
- Server-Sync später möglich (optional)

---

## 🏗️ Architektur

### Datenstrukturen

**Broadcast-Vorlage (statisch):**
```kotlin
data class BroadcastTemplate(
    val id: String,
    val text: String,
    val minRoleId: Int = 2  // 1=Guest, 2=Editor, 3=Owner
)

val DEFAULT_TEMPLATES = listOf(
    BroadcastTemplate("b1", "Jagd beendet"),
    BroadcastTemplate("b2", "Treffpunkt verschoben"),
    BroadcastTemplate("b3", "Erste Hilfe nötig"),
    BroadcastTemplate("b4", "Fremde Jäger gesichtet"),
    BroadcastTemplate("b5", "Waldsperre / Gebiet gesperrt")
)
```

**SQLite: `offline_broadcasts`**
```sql
CREATE TABLE offline_broadcasts (
    id TEXT PRIMARY KEY,
    sender_device_id TEXT,
    sender_name TEXT,
    profile_id TEXT,
    template_id TEXT,
    custom_text TEXT,
    latitude REAL,
    longitude REAL,
    created_at INTEGER,
    expires_at INTEGER,
    received_at INTEGER,
    read_at INTEGER,
    FOREIGN KEY(profile_id) REFERENCES profiles(id)
);
```

**SQLite: `offline_alerts`**
```sql
CREATE TABLE offline_alerts (
    id TEXT PRIMARY KEY,
    sender_device_id TEXT,
    sender_name TEXT,
    sender_type TEXT,  -- "participant" | "gateway"
    profile_id TEXT,
    target_dog_ids TEXT,  -- JSON array serialized
    alert_type TEXT,  -- "vibration" | "sound" | "both"
    alert_intensity INTEGER,  -- 1-3
    created_at INTEGER,
    expires_at INTEGER,
    received_at INTEGER,
    acknowledged_at INTEGER,
    FOREIGN KEY(profile_id) REFERENCES profiles(id)
);
```

---

### UI-Komponenten (Phase 4+)

**In JagdkarteScreen.kt erweitern:**

```kotlin
// Neuer Button in Jagdmodus-Toolbar
IconButton(
    onClick = { showBroadcastDialog = true },
    modifier = Modifier.align(Alignment.TopCenter)
) {
    Icon(Icons.Default.Announcement, contentDescription = "Nachricht senden")
}

// Dialog 1: Broadcast-Template auswählen
if (showBroadcastDialog) {
    AlertDialog(
        title = { Text("Nachricht senden") },
        text = {
            Column {
                DEFAULT_TEMPLATES.forEach { template ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                            .clickable {
                                selectedTemplate = template
                                showBroadcastDialog = false
                                sendBroadcast(template)
                            }
                    ) {
                        Text(template.text)
                    }
                }
            }
        }
    )
}

// Dialog 2: Hunde auswählen & Alert senden
IconButton(
    onClick = { showDogAlertDialog = true },
    modifier = Modifier.align(Alignment.TopEnd)
) {
    Icon(Icons.Default.Phone, contentDescription = "Hunde anrufen")
}

if (showDogAlertDialog) {
    AlertDialog(
        title = { Text("Hunde anrufen") },
        text = {
            Column {
                // Liste der gepaarten Hunde mit Online-Status
                registeredDogs.forEach { dog ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    ) {
                        Checkbox(
                            checked = selectedDogIds.contains(dog.id),
                            onCheckedChange = { 
                                selectedDogIds = if (it) 
                                    selectedDogIds + dog.id 
                                else 
                                    selectedDogIds - dog.id 
                            }
                        )
                        Text("${dog.name} (${if (dog.isOnline) "online" else "offline"})")
                    }
                }
                
                // Intensitäts-Slider
                Text("Intensität:")
                Slider(
                    value = alertIntensity.toFloat(),
                    onValueChange = { alertIntensity = it.toInt() },
                    valueRange = 1f..3f,
                    steps = 1
                )
            }
        },
        onDismissRequest = { showDogAlertDialog = false },
        confirmButton = {
            Button(onClick = {
                sendAlert(selectedDogIds, alertIntensity)
                showDogAlertDialog = false
            }) {
                Text("Rufen")
            }
        }
    )
}
```

---

### Message-Handler (Phase 4+)

**In NearbyRelayTransport.kt:**

```kotlin
private fun deliver(endpointId: String, session: RelayPeerSession, bytes: ByteArray) {
    val message = try {
        OfflinePositionMessage.fromByteArray(bytes)
    } catch (e: Exception) {
        logger.warn("Fehler bei Message-Deserialisierung", e)
        return
    }
    
    when (message.type) {
        "offline.position" -> handlePosition(message)
        "offline.broadcast" -> handleBroadcast(message)
        "offline.alert" -> handleAlert(message)
        else -> {
            logger.info("Unbekannter Message-Typ: ${message.type}, ignoriert")
        }
    }
}

private suspend fun handleBroadcast(message: OfflinePositionMessage) {
    val plaintext = decryptPayload(message)
    val broadcast = OfflineBroadcast.fromJson(plaintext)
    
    // Speichern
    broadcastStore.save(broadcast, message)
    
    // Android Notification anzeigen
    val notification = NotificationCompat.Builder(appContext, "broadcasts")
        .setSmallIcon(R.drawable.ic_broadcast)
        .setContentTitle(broadcast.senderName)
        .setContentText(broadcast.text)
        .setAutoCancel(true)
        .build()
    
    NotificationManagerCompat.from(appContext)
        .notify(broadcast.id.hashCode(), notification)
}

private suspend fun handleAlert(message: OfflinePositionMessage) {
    val plaintext = decryptPayload(message)
    val alert = OfflineAlert.fromJson(plaintext)
    
    // Bin ich ein Ziel?
    if (!alert.targetDogIds.contains(localDeviceId)) {
        return  // Nicht für mich
    }
    
    // Bin ich ein Hundegerät?
    val isDog = localDeviceType == "dog"
    if (!isDog) {
        return  // Nur Hundegeräte reagieren
    }
    
    // Speichern
    alertStore.save(alert, message)
    
    // Vibration & Sound
    vibrate(alert.alertIntensity)
    if (alert.alertType in listOf("sound", "both")) {
        playSound()
    }
    
    // Notification
    val notification = NotificationCompat.Builder(appContext, "alerts")
        .setSmallIcon(R.drawable.ic_alert)
        .setContentTitle("${alert.senderName} ruft dich")
        .setContentText("Jagd Ruf")
        .setVibrate(longArrayOf(0, 300, 100, 300))
        .setAutoCancel(true)
        .build()
    
    NotificationManagerCompat.from(appContext)
        .notify(alert.id.hashCode(), notification)
    
    // Logging
    logger.info("Alert erhalten von ${alert.senderName} um ${System.currentTimeMillis()}")
}

private fun vibrate(intensity: Int) {
    val vibrator = appContext.getSystemService(Vibrator::class.java)
    if (vibrator?.hasVibrator() == true) {
        val pattern = when(intensity) {
            1 -> longArrayOf(0, 300)  // Light: 300ms
            2 -> longArrayOf(0, 500, 100, 500)  // Medium: 500+500ms
            else -> longArrayOf(0, 1000, 100, 1000)  // Strong: 1000+1000ms
        }
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }
}

private fun playSound() {
    val ringtone = RingtoneManager.getRingtone(
        appContext,
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    )
    ringtone.play()
}
```

---

## 📊 Phasen

| Phase | Features | Voraussetzung |
|-------|----------|---------------|
| **Phase 3** | Offline-Positionen anzeigen | Bestehend |
| **Phase 4a** | Broadcasts (Text-Nachrichten) | Positions-Phase stabil + Tests ✓ |
| **Phase 4b** | Alerts (Vibration/Klingeln) | Broadcasts funktionierend + Hund-Registrierung |

---

## Implementierter Umfang und mögliche Erweiterungen

- Rundnachrichten mit festen Vorlagen werden direkt an verbundene Teilnehmer
  gesendet; sie haben eine Empfangsbestätigung, aber keine Nachlieferung.
- Ein registrierendes Konto kann seinem erreichbaren eigenen Hundegerät direkt
  ein Klingel- oder Vibrationssignal senden. Die Rückmeldung unterscheidet
  bestätigte, nicht erreichbare, nicht bestätigte und unterdrückte Signale.
- Mehrfachauswahl, Intensitätsregelung, Nachrichtenhistorie und nachgelagerte
  Zustellung sind Ideen aus diesem Entwurf, keine zugesagten Funktionen.
  Ihre Berechtigung und ihr Aufwand wären vor einer Umsetzung neu zu prüfen.

---

## 🚨 Kritische Punkte

### Sicherheit
Der tatsächliche Transport und die Berechtigungen stehen im
[Kommunikationskonzept](../../../CommunicationConcept.md). Eine bloße lokale
Rollenprüfung oder Vertrauensannahme ersetzt keine Geräteberechtigung.

### Batterie-Impact (Alert-Nachricht)
- ⚠️ Vibration: ~20-50 mA je Vibration, OK
- ⚠️ Sound: Abhängig von Länge, kurz halten
- ⚠️ Nearby bleibt während Alert aktiv (zieht Strom)
- Die Ablauf- und Wiederholungsregeln wären für eine Erweiterung festzulegen.

### Zielgruppen-Validierung
- Bei einer Mehrfachauswahl wären Zielzuordnung und Berechtigung erneut zu
  spezifizieren; die obigen Datenstrukturen sind nur Entwürfe.

---

## 🔮 Noch später: Message-Queueing & Resend

Wenn 100+ Geräte Alerts senden → Nearby-Kapazität?

Mögliche Erweiterung (Phase 5+):
- Alert-Queue in SQLite
- Batch-Versand pro Nearby-Sitzung (max. 10 pro Zyklus)
- Prioritäts-Queueing (Alerts > Broadcasts > Positionen)
- Server-Relay bei verfügbarem Internet

---

Die ursprünglichen Aufwandsangaben und Checklisten sind durch den inzwischen
abweichenden Implementierungsstand überholt.
