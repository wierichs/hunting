# Hundetracker

Der Hundetracker übermittelt den GPS-Standort eines Smartphones an ein Hund-Profil in der [Jagdkarte](https://wierichs.github.io/Jagdkarte/). So kann das Smartphone beim Hund mitgeführt werden und dessen Position im zugehörigen Revierprofil sichtbar machen.

## Einrichtung

1. In der Jagdkarte anmelden, ein Revierprofil öffnen und den Jagdmodus einschalten.
2. Unter **Hunde im Revier** einen Hund registrieren und **Smartphone koppeln** auswählen.
3. Den angezeigten QR-Code mit dem Hundetracker scannen oder den Geräteschlüssel manuell einfügen. Der Schlüssel wird in der Jagdkarte nur einmal angezeigt und sollte geheim bleiben.
4. Das Standortintervall festlegen und **Tracking starten** wählen. Beim ersten Start benötigt die App Zugriff auf den genauen Standort und, ab Android 13, auf Benachrichtigungen.

Die App ist für Android ab Version 12 vorgesehen. Pro Hund kann derzeit ein Smartphone gleichzeitig gekoppelt sein. Wenn ein Smartphone ersetzt wird oder der Schlüssel verloren geht, muss es in der Jagdkarte erneut gekoppelt werden.

## Funktionen

- QR-Code scannen oder den 64-stelligen Geräteschlüssel manuell eingeben.
- Standortintervall zwischen 15 Sekunden und 60 Minuten wählen; voreingestellt sind 60 Sekunden.
- Standort im Hintergrund über einen Android-Standortdienst erfassen. Eine dauerhafte Benachrichtigung zeigt die aktive Sitzung an und bietet auch eine Stopp-Schaltfläche.
- Position, GPS-Genauigkeit und Messzeitpunkt über eine verschlüsselte HTTPS-Verbindung übertragen.
- Bei fehlender Internetverbindung Messungen lokal puffern und den Upload bei verfügbarer Verbindung erneut versuchen.
- Optional als Tracker-Relais teilnehmen: verschlüsselte und signierte Positionsmeldungen werden nach einer bestätigten Nearby-Verbindung per Bluetooth/WLAN an einen anderen Tracker übergeben. Relay läuft nur während des aktiven Trackings und benötigt eine vorherige Online-Kopplung.
- Der Status unterscheidet lokal gespeicherte, noch ausstehende Meldungen von einer vom Server bestätigten Übertragung und zeigt weitere Wartemeldungen an.
- Den Geräteschlüssel geschützt im Android Keystore speichern.

Das Tracking beginnt erst nach **Tracking starten** und lässt sich in der App oder über die Benachrichtigung beenden. Ist der Jagdmodus in der Jagdkarte ausgeschaltet, stoppt der Tracker nach Rückmeldung des Servers und löscht noch gepufferte Positionen.

## Hinweise

Für die Erfassung sind aktivierter Standort und die erforderlichen Android-Berechtigungen nötig. Die Übertragung benötigt eine Internetverbindung; ohne Verbindung werden Positionen zunächst auf dem Smartphone gespeichert. Android-Energiesparfunktionen, Empfang und GPS-Bedingungen können Aktualisierungen verzögern. Der Tracker ist keine garantierte Echtzeitortung und kein Notrufsystem.

Die experimentelle Relaisfunktion ist standardmäßig ausgeschaltet. Beim Verbinden muss der Sicherheitscode auf beiden Geräten verglichen und bestätigt werden. Unterstützt wird derzeit ein einzelner Relay-Hop; die Ende-zu-Ende-Strecke ist noch nicht auf mehreren realen Geräten validiert.

Die Jagdkarte zeigt Hundepositionen im gekoppelten Revierprofil an, auch außerhalb der Reviergrenze. Weitere Informationen zur Registrierung und Kopplung stehen in der [Hilfe zur Jagdkarte](https://wierichs.github.io/Jagdkarte/hilfe.html#hunde).