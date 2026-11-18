# Jagdbruder für Android

Jagdbruder ist eine native Android-App mit den Bereichen **Jagdkarte** und
**DogTracker**. Sie ergänzt die [Jagdkarte-PWA](https://wierichs.github.io/Jagdkarte/),
ersetzt sie aber nicht. Beide Clients nutzen das gemeinsame Supabase-Backend.
Backend-Erweiterungen müssen mit der PWA und den bestehenden
Hundetracker-Schnittstellen kompatibel bleiben.

Die App dient der Orientierung und ist weder eine verbindliche Revierkarte noch
ein Notrufsystem. Standort- und Relaisübertragung garantieren keine Echtzeit
oder Zustellung.

## Dokumentation

- [Hilfe](docs/help/hilfe.md): Bedienung aus Nutzersicht; dieselbe Datei wird
  offline im Hilfe-Dialog der App angezeigt.
- [Portierungsstatus](PORTIERUNGSSTATUS.md): Umsetzungsstand, bekannte
  Einschränkungen und Entwicklungsoptionen gegenüber der PWA.
- [Testaufgaben](docs/testing/Testaufgaben.md): separat erfasste offene
  Prüfungen für eine spätere Abnahme.
- [Kommunikationskonzept](CommunicationConcept.md): direkte Übertragung,
  Relay-Architektur, Sicherheitsgrenzen und bestätigte Wiederverbindung.
- [Offline-Positionsnachrichten](docs/design/offline-positions/OfflinePositionMessageFormat.md):
  implementierter Protokollstand und bestätigte Feldtests.
- [Rundnachrichten und Hunde-Alerts](docs/design/offline-positions/BroadcastAlertDesign.md):
  Arbeitsentwurf für spätere Erweiterungen.
- [Relay-Serverstufe](https://github.com/wierichs/wierichs.github.io/blob/main/Jagdkarte/supabase/location-relay.md):
  Serververtrag, Bereitstellung und Betrieb im Web-Repository.

Die Git-Historie enthält frühere Entwicklungs- und Fehlerbehebungsstände.

## Funktionsüberblick

- Öffentliche Reviersuche mit auswählbaren Quellen und lokalen eigenen
  HTTPS-GeoJSON- und WFS-Quellen; Treffer können ohne Anmeldung auf der Karte
  angesehen werden.
- Anmeldung per E-Mail-Link oder Zahlencode; eigene und geteilte Revierprofile,
  Rollen, Einladungen und positionsbezogene Gastlinks.
- Reviergrenzen und Markierungen anzeigen und, abhängig von der Rolle,
  bearbeiten; Import von JWD, JSON und GeoJSON sowie JWD-Export.
- OpenStreetMap, NRW-Luftbilder und importierte Mapsforge-Offlinekarten.
  Einzelne öffentliche Grenzen können separat offline gespeichert werden.
  Ein verschlüsselter, höchstens sieben Tage alter Revier-Snapshot ermöglicht
  eine als veraltet gekennzeichnete Leseansicht. Nearby-Positionen werden
  getrennt vom Snapshot gespeichert und angezeigt.
- Jagdmodus mit Teilnehmer- und Hundepositionen sowie gerätebezogener
  Berechtigung und Zustimmung zur Standortfreigabe und zum Relaisbetrieb.
- DogTracker mit QR- oder manueller Kopplung, Vordergrunddienst,
  verschlüsselter lokaler Positionswarteschlange und experimentellem
  Nearby-Ein-Hop-Relais. Details und Grenzen stehen im
  [Kommunikationskonzept](CommunicationConcept.md).

## Technik und Aufbau

Android ab **12 / API 31**, App-ID `org.wierichs.jagdkarte`, Kotlin und Jetpack
Compose; die Karte verwendet osmdroid, Offlinekarten Mapsforge, Geometrie JTS.
Die App verwendet keine WebView. Der QR-Scanner nutzt Google Play-Dienste und
benötigt keine eigene Kameraberechtigung. Relay verwendet Google Nearby
Connections; die bestehende Dienstkennung ist `org.wierichs.dogtracker`.

| Pfad | Inhalt |
| --- | --- |
| `app/src/main/java/org/wierichs/dogtracker/` | Einstieg, Tracker, Berechtigungen und Hintergrunddienste |
| `app/src/main/java/org/wierichs/dogtracker/jagdkarte/` | Kartenoberfläche, Backendzugriff, Grenzen, Marker und Offline-Daten |
| `app/src/main/java/org/wierichs/dogtracker/communication/` | Outbox, HTTPS, Verschlüsselung, Signaturen und Nearby-Relay |
| `app/src/main/assets/` | Marker, Symbole und öffentliche Suchquellen |
| `app/src/test/`, `app/src/androidTest/` | JVM- beziehungsweise Android-Instrumentierungstests |
| `docs/help/` | Markdown-Quelle der in die APK eingebundenen Hilfe |

Der Gradle-Namespace `org.wierichs.dogtracker` unterscheidet sich von der
App-ID. Die App kann neben der alten App mit ID `org.wierichs.dogtracker`
installiert werden; deren Einstellungen und Kopplung werden nicht übernommen.

## Architektur und technische Verträge

### Anmeldung und Autorisierung

Die App spricht Supabase Auth und die vorhandenen REST-/RPC-Schnittstellen
direkt an. Sitzung und Tracker-Schlüssel werden getrennt im privaten
App-Speicher mit einem Android-Keystore-Schlüssel geschützt. Die lokale
Anmeldeanforderung enthält E-Mail-Adresse und PKCE-Verifier und bleibt
verschlüsselt über einen Neustart erhalten. Beim Rücksprung muss die vom
Auth-Server bestätigte E-Mail zur Anfrage passen. Nur der PKCE-Codeaustausch
prüft den Verifier serverseitig; die direkte `token_hash`-Verifikation tut
dies nicht. Zugangstoken aus einer URL werden nicht als Sitzung übernommen.
Der App-Link-Filter umfasst den exakten Pfad `/Jagdkarte/` und damit auch die
PWA-Startseite; andere Unterseiten bleiben Browser-Links.

Die Oberfläche prüft Rollen und Reviergrenzen vor Schreibaktionen; die
maßgebliche Autorisierung bleibt beim Backend und seinen RLS-Regeln und RPCs.
Beispiele für den Vertrag stehen in
[EmailAuthentication.kt](app/src/main/java/org/wierichs/dogtracker/jagdkarte/EmailAuthentication.kt)
und [JagdkarteRepository.kt](app/src/main/java/org/wierichs/dogtracker/jagdkarte/JagdkarteRepository.kt).

### Revierdaten, Suche und Schreiben

Die öffentliche Suche liest GeoJSON, WFS, OGC API Features und ArcGIS direkt
vom Gerät; eigene Quellen unterstützen GeoJSON und WFS. Die App validiert
Polygon-/MultiPolygon-Geometrien mit JTS. Suchvorschauen schreiben keine
Profile; erst die bestätigte Profilanlage verwendet die bestehenden RPCs.
Eigene Quellen bleiben lokal und verwenden bei der Profilanlage die
Serverquelle `user-imports`.

Quellenzugriffe erlauben ausschließlich HTTPS ohne Zugangsdaten in der URL.
Nicht global routbare Adressen werden bei der DNS-Auflösung ausgeschlossen;
Weiterleitungen und OGC-Seitenlinks bleiben auf denselben Ursprung beschränkt.
Limits von 40 Seiten, 20.000 Features, 12 MiB je Antwort, 48 MiB insgesamt
und 90 Sekunden begrenzen eine Suche. Implementierung und Prüfungen stehen in
[PublicBoundaryClient.kt](app/src/main/java/org/wierichs/dogtracker/jagdkarte/PublicBoundaryClient.kt)
und [PublicBoundarySource.kt](app/src/main/java/org/wierichs/dogtracker/jagdkarte/PublicBoundarySource.kt).

Temporäre Marker werden über `create_temporary_hunting_marker` angelegt;
ihre 48-Stunden-Frist bestimmt der Server. Dauerhafte Marker nutzen den
Tabellen-Insert. Beim Grenzeditor werden Rolle und Ausgangsgrenze vor dem
Schreiben erneut geladen, es gibt aber keine atomare serverseitige
Versionsprüfung. Import und Merge bestehen aus mehreren Serveranfragen:
Aufräumversuche bei Fehlern sind nicht mit einer atomaren Transaktion
gleichzusetzen. Der Merge vereinigt Grenzen und vermeidet identische
dauerhafte Einrichtungen.

### Karten und Offline-Daten

Die Offline-Kartenbasis besteht aus importierten Mapsforge-`.map`-Paketen und
zugehörigen `.poly`- oder Polygon-GeoJSON-Abdeckungen. JTS prüft, ob die
vereinigte Abdeckung das gesamte Revier umfasst; das Dateirechteck allein
genügt nicht. Der Offline-Modus wechselt nicht still auf Onlinekarten. Die
Luftbildquelle ist Geobasis NRW und keine weltweite Abdeckung.

Die lokalen Bestände erfüllen unterschiedliche Zwecke:

| Bestand | Inhalt und Grenzen |
| --- | --- |
| Offlinekarten | Kartenpakete und Abdeckungen ohne private Revierdaten; kein automatischer Karten-Download. |
| Öffentliche Offline-Grenzen | Ausdrücklich gespeicherte Suchtreffer; höchstens 4 MiB je Grenze, 64 MiB und 100 Einträge insgesamt. |
| Revier-Snapshot | Konto- und revierbezogen, verschlüsselt, höchstens sieben Tage, 4 MiB je Revier, 32 MiB und 20 Reviere insgesamt. Nur lesbar und als veraltet markiert; keine Teilnehmer- oder Hund-Livepositionen. |
| Nearby-Positionen | Verschlüsselter letzter Stand je Konto, Revier und Absendergerät; ab 120 Sekunden als veraltet sichtbar, bis das Jagdmodusende bekannt wird. |

Die ersten drei Bestände liegen im `noBackupFilesDir`. Die separate
Positionsdatenbank ist von Backup und Gerätetransfer ausgeschlossen.
Die lokalen Bestände werden nicht mit Konto oder PWA synchronisiert. Die
Kartenwahl betrifft nur die Kartenbasis und deaktiviert weder Serverzugriffe
noch Relay. Details:
[OfflineMapCoverage.kt](app/src/main/java/org/wierichs/dogtracker/jagdkarte/OfflineMapCoverage.kt),
[OfflinePublicBoundaryStore.kt](app/src/main/java/org/wierichs/dogtracker/jagdkarte/OfflinePublicBoundaryStore.kt)
und [JagdkarteOfflineCache.kt](app/src/main/java/org/wierichs/dogtracker/jagdkarte/JagdkarteOfflineCache.kt).

### Standort und Relay

Standortfreigabe und Relay benötigen ausdrückliche gerätebezogene Zustimmung
zusätzlich zu Android-Berechtigungen. Teilnehmerpositionen werden nur bei
aktiver Karte erfasst. Ihre Server-Relay-Umschläge gelten höchstens
90 Sekunden ab Messung; das Relay kann sichtbar im Hintergrund weiterlaufen.
Der DogTracker startet
ausdrücklich und hält für den direkten Upload höchstens den neuesten GPS-Fix
für 24 Stunden vor. Seine lokal verschlüsselten Nutzdaten und Relay-Umschläge
haben unterschiedliche Ablauf- und Bestätigungsregeln.

Nearby überträgt signierte, für den Server verschlüsselte Umschläge über
höchstens einen Relay-Hop. Eine Funkübergabe ist keine Serverbestätigung;
Benutzer- und Hundegeräte bleiben getrennte Identitäten und geben ihre
Zugangstoken nicht an Relais weiter. Serverpfade und Bestätigungssemantik
unterscheiden sich: siehe [Kommunikationskonzept](CommunicationConcept.md).

Die Relaisverbindung zwischen zuvor online eingerichteten Geräten desselben
Reviers erfolgt automatisch und ohne Bestätigungsdialog, sofern ihre aktuellen
signierten Verzeichnisse einander enthalten. Diese
Offline-Verzeichnisse gelten höchstens 24 Stunden; ihre Laufzeit wird zusätzlich
über die monotone Gerätezeit begrenzt. Nach einem vollständigen Geräteneustart
ist vor erneutem Relaisbetrieb eine Online-Erneuerung erforderlich. Ein
Gateway kann nach einem Prozessende innerhalb desselben Gerätebetriebs mit
gespeicherter Berechtigung offline wieder anlaufen. Neue eigene
Teilnehmer- und Hundepositionen können für dasselbe zuvor online eingerichtete
Revier auch ohne Internet direkt an erreichbare Teilnehmergeräte gesendet
werden. Dafür gibt es eigene signierte Positionsnachrichten, getrennt von
den nur für den Server lesbaren Relay-Umschlägen. Das serverbestätigte
Jagdende wird derzeit erst beim Online-Abruf des Revierstatus erkannt,
nicht über Nearby. Sobald Android wieder ein geeignetes Standardnetz meldet,
stößt die App Kartenabruf bzw. Upload automatisch an. Die 120-Sekunden-Grenze
kennzeichnet das Alter von Nearby-Positionen und ist keine Wartezeit für die
Wiederverbindung.

## Entwicklung und Tests

Android Studio mit Android SDK für **compileSdk/targetSdk 37** einrichten.
Der Gradle-Wrapper verwendet Gradle 9.6 und die in
`gradle/gradle-daemon-jvm.properties` festgelegte JVM 25. Die Projektdateien
enthalten die konkreten Plugin- und Bibliotheksversionen.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

Für die Relay-Protokolltests kann gezielt
`.\gradlew.bat :app:testDebugUnitTest --tests org.wierichs.dogtracker.communication.UnifiedRelayProtocolTest`
ausgeführt werden. Unter Windows kann bei einer Standardinstallation die
mit Android Studio gelieferte JVM über
`$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'` gewählt
werden; der tatsächliche Installationspfad ist gegebenenfalls anzupassen.

Für Instrumentierungstests ist ein laufender Emulator oder ein angeschlossenes
Gerät erforderlich. Die Debug-APK liegt unter
`app/build/outputs/apk/debug/Jagdbruder-debug.apk`. Ihre Debug-Signatur kann
eine anders signierte Release-Installation nicht aktualisieren. Einige
Karten-Instrumentierungstests benötigen zusätzlich Mapsforge-Testdateien;
der optionale Luftbild-Livetest wird mit
`-Pandroid.testInstrumentationRunnerArguments.liveAerial=true` aktiviert.
Der Gateway-Offline-Wiederanlauftest
`RelayOfflineAuthorizationInstrumentedTest` benötigt für seinen
Gateway-Teil eine isolierte Test-App-ID, zum Beispiel über
`-PsupabaseUrl=https://example.invalid`. Instrumentierung nicht ungeprüft auf
allen angeschlossenen Geräten ausführen; das Testgerät gezielt auswählen.

Für die Offline-Renderertests `liechtenstein.map` und `liechtenstein.poly` aus
den [Mapsforge-Karten](https://download.mapsforge.org/maps/v5/) beziehungsweise
[Extraktgrenzen](https://download.mapsforge.org/maps/poly/) in das externe
App-Dateiverzeichnis des Testgeräts unter
`/sdcard/Android/data/org.wierichs.jagdkarte/files/` kopieren. Ohne diese
Dateien werden die entsprechenden Tests übersprungen; bei einer Test-App-ID
ändert sich der Verzeichnispfad entsprechend.

Die Tests verwenden überwiegend lokale Testdaten. Reale Geräte-, Funk-, GPS-,
Mail- und Backend-/RLS-Abnahmen sind gesondert durchzuführen; siehe
[Portierungsstatus](PORTIERUNGSSTATUS.md). Keine produktiven oder fremden
Reviere für Schreibtests verwenden.

### Backend konfigurieren

Notwendige Änderungen am gemeinsamen Supabase-Backend, etwa Migrationen,
Edge Functions und deren Konfiguration, werden während der Entwicklung
**ausschließlich im korrespondierenden PWA-Projekt**
`..\..\wierichs.github.io\Jagdkarte\` vorgenommen und gepflegt, nicht in diesem
Android-Repository. Die Bereitstellung im jeweiligen Supabase-Projekt ist davon
getrennt; den bestehenden Migrations- und Deploymentstand vorher prüfen.

Standardmäßig verwendet der Build die öffentliche Projektkonfiguration der
PWA. Für ein isoliertes Testprojekt können die Gradle-Properties `supabaseUrl`
und `supabasePublishableKey` gesetzt werden. Eine abweichende URL erzeugt eine
eigene Test-App-ID; sie ist kein Update der produktiven Installation. Ein
Publishable Key ist kein administrativer Schlüssel: Zugriffsschutz muss auf
dem Server durch Authentifizierung und RLS erfolgen. Service-Role-Schlüssel
und andere Geheimnisse gehören nicht in die App oder das Repository.

Benötigt werden das kompatible Jagdkarte-Schema und die Auth-E-Mail-Vorlagen
des gemeinsamen Backends sowie die Funktionen `dog-location` und
`dog-location-relay`. Für Teilnehmer-Relay ist zusätzlich `location-relay`
erforderlich. Die Offline-Erweiterung wurde laut Nutzer im gemeinsam genutzten
Entwicklungsprojekt bereitgestellt und dort getestet. Das belegt keine
Bereitstellung in anderen Zielprojekten oder in Produktion. Ohne die passende
Serverstufe kann die neue Relay-Teilnahme nicht erfolgreich eingerichtet werden.
Bereitstellung und Schlüsselverwaltung stehen in der
[Serveranleitung](https://github.com/wierichs/wierichs.github.io/blob/main/Jagdkarte/supabase/location-relay.md).

Für den DogTracker-QR-Gegenweg benötigt das Ziel-Backend die Migrationen
`202611110001_register_hunting_dog_device_token` und
`202611110002_two_phase_dog_device_pairing`; die Statusanzeigen benötigen
zusätzlich `202611130001_dog_device_pairing_status` und
`202611130002_dog_pairing_states`. Den tatsächlichen Migrationsstand des
Zielprojekts vor Änderungen prüfen; lokale, nicht versionierte Dateien sind
kein Nachweis eines Deployments.

Die Auth-Rückkehradresse `https://wierichs.github.io/Jagdkarte/` muss im
Supabase-Projekt zugelassen sein. Die Vorlagen **Confirm sign up** und
**Magic link or OTP** können neben dem bestehenden PWA-kompatiblen Link
`{{ .Token }}` als Zahlencode anbieten. Die App unterstützt `token_hash` mit
`type=email` und PKCE-Code-Rückgaben. Ein Link setzt eine vorher auf demselben
Gerät angeforderte, höchstens eine Stunde alte Anmeldung voraus.

## Release

Für Updates muss derselbe Signaturschlüssel wie für die installierte APK
verwendet werden. Den Keystore außerhalb des Repositorys halten und die
Gradle-Properties `jagdkarteReleaseStoreFile`,
`jagdkarteReleaseStorePassword`, `jagdkarteReleaseKeyAlias` und
`jagdkarteReleaseKeyPassword` ausschließlich lokal, zum Beispiel in der
geschützten Gradle-Benutzerkonfiguration, setzen. Keine Zugangsdaten einchecken
oder weitergeben; Backslashes in Properties-Werten müssen escaped werden.

```powershell
.\gradlew.bat :app:assembleRelease -PreleaseSigning=true --no-configuration-cache --no-daemon --console=plain
```

Die APK liegt unter `app/build/outputs/apk/release/Jagdbruder.apk`. Bei
angeforderter Signierung bricht der Build bei fehlenden Angaben ab; ohne
`-PreleaseSigning=true` wird nicht mit dieser Konfiguration signiert. Vor
Verteilung die APK mit `apksigner` prüfen und den
SHA-256-Zertifikatsfingerabdruck mit der bisherigen Release-APK vergleichen.
