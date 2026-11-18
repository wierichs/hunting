# Hilfe

Jagdbruder vereint die Bereiche **Jagdkarte** und **DogTracker**. Mit der
Jagdkarte kannst du Reviere ansehen und verwalten. Der DogTracker überträgt
den Standort eines Smartphones, das ein Hund mitführt.

Die Karte ist eine Orientierungshilfe. Sie ersetzt keine verbindliche
Revierkarte und erteilt keine Jagderlaubnis. Standorte sind keine sichere
Echtzeitortung. Im Notfall 112 wählen; eine App-Markierung ist kein Notruf.

## Bedienung und erster Start

Beim ersten Start öffnet sich die Jagdkarte mit einer Erklärung der
Jagdberechtigungen. Mit **Später** kannst du Karten ansehen und Reviere
verwalten, ohne die Jagdfunktionen einzurichten. Danach startet die App im
zuletzt verwendeten Bereich und merkt sich unter anderem das letzte Revier,
die Kartenposition und den Zoom.

- Die **Lupe** öffnet die öffentliche Reviersuche.
- Der **Aktualisieren-Pfeil** lädt Serverdaten neu.
- Die **drei Punkte** öffnen Revier und Verwaltung, das **Personensymbol**
  das Benutzerkonto und das **Informationssymbol** diese Hilfe.
- Die **Zielscheibe** zeigt deinen eigenen Standort auf deinem Gerät. Sie
  startet keine Standortfreigabe.
- Über **Kartendarstellung** wählst du OpenStreetMap, Luftbild oder Offline.

## Ein Revier finden

Öffne über die Lupe die öffentliche Reviersuche, wähle eine Datenquelle und
suche nach Reviername, Nummer oder Gemeinde. Die Suche benötigt Internet.
Prüfe Quellenangabe, Lizenz und Datenstand. Eigene HTTPS-GeoJSON- und
WFS-Quellen kannst du unter **Suchquellen verwalten** hinzufügen.

Ein Treffer lässt sich zunächst auf der Karte ansehen, ohne ein Profil
anzulegen. Für ein eigenes Revierprofil musst du angemeldet sein. Einzelne
Suchtreffer kannst du ausdrücklich für die spätere Offlineansicht speichern.

## Anmelden und registrieren

Öffne **Benutzerkonto** und wähle **Anmelden** oder **Neues Konto**. Gib deine
E-Mail-Adresse ein und fordere die Anmelde-Mail an. Öffne den Link in der
neuesten E-Mail auf demselben Smartphone oder gib den Zahlencode aus der Mail
unter **Zahlencode (alternativ)** ein. Ein Passwort wird nicht benötigt.

Links und Codes sind vertraulich und nur einmal verwendbar. Die
Anmeldeanforderung bleibt beim Schließen der App erhalten und gilt auf diesem
Gerät höchstens eine Stunde. Melde dich auf gemeinsam genutzten Geräten nach
der Nutzung ab.

## Gespeicherte Reviere und Rollen

Nach der Anmeldung wählst du über die Revierauswahl ein eigenes oder geteiltes
Profil. Owner (Verwalter) und Bearbeiter können Revierdaten verwalten. Gäste
sehen gemeinsame Markierungen und können eigene zeitlich begrenzte Meldungen
setzen. Nur der Owner kann das gesamte Profil löschen.

Ein geteiltes Revier kannst du aus der Auswahl ausblenden und später wieder
einblenden; die Mitgliedschaft bleibt bestehen. Das Löschen eines eigenen
Profils entfernt es samt Markierungen und Freigaben dauerhaft.

## Grenzen und Markierungen bearbeiten

Owner und Bearbeiter können eine vorhandene Reviergrenze bearbeiten:
Grenzpunkte verschieben, hinzufügen oder entfernen sowie Teilflächen und
befriedete Bereiche anlegen. Änderungen lassen sich rückgängig machen und
müssen ausdrücklich gespeichert werden. Vorhandene Markierungen bleiben beim
Ändern der Grenze erhalten.

Wähle ein Revier und tippe auf **Markierung setzen**. Verschiebe die Karte
unter der Positionsnadel und bestätige mit **Hier setzen**; alternativ kannst
du einen Kartenpunkt lange berühren. Wähle einen Objekttyp und gib bei Bedarf
Titel und Notiz ein. Die Markierung muss innerhalb der Reviergrenze liegen.

Owner und Bearbeiter können dauerhafte Einrichtungen und zeitlich begrenzte
Standplätze setzen. Gäste können Anschuss und Wildsichtung setzen, wenn ihr
aktueller GPS-Standort innerhalb des Reviers liegt. Anschuss, Wildsichtung und
Standplatz laufen nach 48 Stunden ab.

Tippe auf ein Kartensymbol, um Details zu sehen. Berechtigte Personen können
Markierungen bearbeiten oder löschen; Owner und Bearbeiter können sie mit
der Zielscheibe im Markierungsfenster an den eigenen Standort verschieben.
Gäste können ihre eigenen zeitlich begrenzten Meldungen löschen.

## Andere einladen

Owner und Bearbeiter können im ausgewählten Revier Personen per E-Mail als
Gast oder Bearbeiter einladen. Die eingeladene Person muss mit genau dieser
E-Mail-Adresse angemeldet sein. Ausstehende Einladungen werden beim Öffnen
oder Wiederaufnehmen der Jagdkarte und beim Aktualisieren angenommen; das
gilt auch für Einladungen aus der PWA.

Positionsbezogene Gastlinks laufen nach sieben Tagen ab und können
weitergegeben werden. Teile sie nur mit Personen, denen du den Revierzugang
anvertrauen willst. Nach dem Annehmen öffnet sich das Zielrevier mit der
zugehörigen Markierung. Je nach Android-App-Link-Einstellung kann auch die
Jagdkarte-Startseite statt im Browser in der App geöffnet werden.

## Jagdberechtigungen und Standortfreigabe

Unter **Benutzerkonto → Berechtigungen und Freigaben einrichten** kannst du
die Einrichtung auch ohne Anmeldung jederzeit ändern. Genauer Standort wird
für die eigene Position und Grenzprüfung benötigt; Bluetooth und je nach
Android-Version WLAN beziehungsweise lokales Netzwerk ermöglichen
Relaisverbindungen. Benachrichtigungen machen aktive Dienste sichtbar.

**Freigeben und einrichten** erlaubt die automatische eigene
Standortfreigabe und den Relaisbetrieb im Jagdmodus des ausgewählten Reviers
sowie Relais während einer ausdrücklich gestarteten DogTracker-Sitzung.
Android-Berechtigungen allein teilen deinen Standort nicht.
Jagdmodus-Steuerung, Livepositionen und **Tracking starten** erscheinen erst
bei vollständiger Einrichtung. Deine Revierrolle bleibt unverändert.

Berechtigte Geräte desselben Reviers verbinden sich für das Relais
automatisch; ein Bestätigungsdialog für jede Verbindung ist nicht nötig.
Dafür müssen beide Geräte zuvor online eingerichtet worden sein und aktuelle
Revierberechtigungen besitzen, die das jeweils andere Gerät enthalten. Die
Offline-Berechtigung gilt höchstens 24 Stunden. Nach einem vollständigen
Neustart des Smartphones muss sie online erneuert werden. Sind beide Geräte
für dasselbe Revier eingerichtet und in Funkreichweite, kann die Jagdkarte
Teilnehmer- und Hundepositionen auch ohne Internet über Nearby empfangen.
Für die Kartenbasis muss eine geeignete Offlinekarte verfügbar sein.

**Jagdfunktionen deaktivieren** beendet Jagdteilnahme und DogTracker. Die
reine Anzeige des eigenen Standorts auf der Karte bleibt davon unabhängig.

## Jagdmodus und Livepositionen

Owner und Bearbeiter können den Jagdmodus unter **Revier und Verwaltung**
mit Internetverbindung steuern. Bei aktivem Jagdmodus, vollständiger Einrichtung
und gültiger Berechtigung werden verschlüsselte Reviermeldungen automatisch
weitergeleitet.
Deine Teilnehmerposition wird nur bei aktiver Karte und innerhalb der
Reviergrenze übermittelt. Das Fadenkreuz zeigt unabhängig davon deinen
eigenen Standort an, sobald das Gerät ihn ermittelt hat.

Im Markerdetail erkennst du eine direkt empfangene Position an
**Per Nearby empfangen** und ihrer Messzeit. Ohne neue Position wird der
Marker ab zwei Minuten als veraltet angezeigt, bleibt aber sichtbar.
Das Ausschalten der eigenen Standortfreigabe beendet nicht den Jagdmodus
für das Revier. Erfährt deine Karte beim Online-Abruf vom Jagdende,
verschwinden die gespeicherten Revierpositionen. Wenn das Internet
zurückkehrt, wird die geöffnete Jagdkarte automatisch aktualisiert.

Bei Revierwechsel, Abmeldung oder ausgeschaltetem Jagdmodus endet die
bisherige Teilnahme. Wird die Karte pausiert, endet die eigene
Standortübertragung; das Relais kann sichtbar im Hintergrund weiterarbeiten.
Startest du den DogTracker, übernimmt er den Relaisbetrieb; deine
Teilnehmerposition wird dabei nicht parallel geteilt. Beim Ausschalten des
Jagdmodus werden gespeicherte Gruppen- und Hundepositionen serverseitig
entfernt.

Bei frischen, ausreichend genauen Positionen zeigt eine blaue Pfeilspitze
direkt am Personen- oder Hundemarker die aus zwei GPS-Positionen abgeleitete
Bewegungsrichtung. Sie zeigt nicht die Blickrichtung oder Ausrichtung des
Smartphones. Hundepositionen können auch außerhalb der Reviergrenze angezeigt
werden.

## Nearby-Rundnachrichten

Im aktiven Jagdmodus öffnet der **Briefumschlag** unten rechts in der Karte
die Rundnachrichten. Er sitzt über dem Plus für Markierungen. Owner und
Bearbeiter senden eine Vorlage direkt, indem sie auf ihren Text tippen:
„Das Treiben beginnt!“, „Hahn in Ruh!“ oder „Zurück zum Sammelplatz.“.
Gäste können empfangen, aber nicht senden. Beide Seiten benötigen zuvor online
erneuerte Revierberechtigungen und eine direkte Nearby-Verbindung. Eine
Internetverbindung ist für den Versand nicht erforderlich.

Die Liste zeigt zu jeder eigenen Nachricht den Stand der Teilnehmer aus
dem Revierverzeichnis: **Angekommen** (mindestens eines ihrer Geräte hat
den Empfang bestätigt), **Nicht bestätigt** oder **Nicht verbunden**.
Es gibt keine Nachlieferung und keine Lesebestätigung. Die Übersicht
bleibt nur während der laufenden Relais-Sitzung erhalten. Verwende die
Nachrichten nicht als einziges Sicherheitssignal.

Bei Empfang zeigt Android eine Benachrichtigung. Unter **Android-Einstellungen
→ Apps → Jagdbruder → Benachrichtigungen → Nearby-Rundnachrichten** kannst du
den Benachrichtigungston und, je nach Gerät, Vibration einstellen oder die
Kategorie stummschalten. Sie wird beim ersten Start des Revier-Relais
angelegt. Auch bei ausgeschalteten Benachrichtigungen bleibt die Nachricht
in der App sichtbar und der Empfang wird bestätigt.

## Hunde und DogTracker

Öffne **Revier und Verwaltung → Hunde**. Jedes angemeldete Reviermitglied,
auch ein Gast, kann einen eigenen Hund registrieren und ein Smartphone für
ihn koppeln. Dafür gibt es zwei Wege:

1. In Jagdkarte **Smartphone koppeln** wählen und den angezeigten QR-Code im
   DogTracker mit **Tracker-Kamera** scannen oder den Schlüssel dort manuell
   eingeben.
2. Im DogTracker **QR-Code fuer Jagdkarte anzeigen** wählen. In Jagdkarte beim
   passenden Hund **DogTracker-QR scannen** wählen und den Code scannen. Den
   Wartedialog geöffnet lassen und im DogTracker **Kopplung bestaetigen**
   wählen. Jagdkarte zeigt den Erfolg nach der Serverbestätigung.

Der QR-Scanner verwendet die Google Play-Dienste und benötigt keine eigene
Kamerafreigabe für Jagdbruder. Der Geräteschlüssel wird nur einmal angezeigt
und muss geheim bleiben. Pro Hund kann ein Smartphone aktiv gekoppelt sein;
bei einer neuen Kopplung bleibt der bisherige Schlüssel bis zur Bestätigung
aktiv.

In **Hunde im Revier** kennzeichnen ein grüner Haken beziehungsweise ein
rotes X den Kopplungsstatus. **Bestaetigung ausstehend** bezeichnet eine
vorgemerkte neue Kopplung. Eine aktive Kopplung bedeutet nicht, dass Tracking
läuft oder eine aktuelle Position vorliegt.

Richte die Jagdberechtigungen ein, wähle im DogTracker ein Standortintervall
zwischen 15 Sekunden und 60 Minuten und tippe auf **Tracking starten**. Eine
dauerhafte Benachrichtigung zeigt die Sitzung und bietet eine Stoppaktion.
**Tracking stoppen** beendet auch den Relaisbetrieb des DogTrackers. Ohne
Internet bleibt für den direkten Upload nur die neueste GPS-Position
höchstens 24 Stunden auf dem Smartphone gespeichert. Sobald wieder eine
Verbindung verfügbar ist, versucht der laufende DogTracker den Upload
automatisch. Erst **Position vom Server bestätigt** zeigt an, dass der
Server die Position angenommen hat.

Mit **Löschen** entfernen Registrierende ihre eigenen Hunde. Owner und
Bearbeiter behalten ihre Verwaltungsrechte. Beim Löschen entfallen auch
Kopplung und gespeicherte Hundeposition.

Im Jagdmodus erscheinen eigene Hunde **türkis**, andere Hunde **orange**,
solange ihre Position aktuell ist. Tippe auf deinen Hund und wähle
**Klingeln** oder **Vibrieren**. Das Signal geht direkt per Nearby an das
gekoppelte Gerät mit laufendem DogTracker. Ist es nicht erreichbar,
wird das Signal nicht später nachgeliefert. **Vom Hundegerät bestätigt**
bedeutet, dass der DogTracker die Benachrichtigung ausgelöst hat, nicht
zwingend, dass das Gerät geklingelt oder vibriert hat. Auf dem Hundegerät
gelten die Android-Benachrichtigungseinstellungen für **Hundegerät:
Klingeln** und **Hundegerät: Vibrieren**. Falls das Xiaomi-Hundegerät im
normalen Tonmodus nicht vibriert, aktiviere unter **Einstellungen → Ton &
Vibration** die Option **Bei Anruf vibrieren**. Auf dem getesteten Xiaomi
hat diese Einstellung das Problem behoben.

## Revierdateien

Im Revierbereich kannst du eine Reviergrenze als JWD-Datei exportieren oder
eine JWD-, JSON- oder GeoJSON-Datei importieren. Wähle die Datei über den
Android-Dateiauswahldialog und prüfe die Zusammenfassung vor dem Import.

Du kannst ein neues eigenes Profil anlegen oder Daten mit einem bestehenden
bearbeitbaren Profil zusammenführen. Beim Zusammenführen werden die Grenzen
vereinigt, vorhandene Markierungen bleiben erhalten und neue dauerhafte
Einrichtungen werden ergänzt. Behandle Revierdateien mit vertraulichen
Markierungen wie private Unterlagen.

## Karten und Offline-Nutzung

OpenStreetMap und Luftbilder benötigen Internet. Die Luftbildabdeckung ist
derzeit auf NRW begrenzt; an Landes- und Diensträndern können Lücken auftreten.

Für Offlinekarten lade eine passende Mapsforge-`.map`-Datei und die
zugehörige `.poly`-Extraktgrenze, beispielsweise von den
[Mapsforge-Downloads](https://download.mapsforge.org/maps/v5/) und den
[Extraktgrenzen](https://download.mapsforge.org/maps/poly/). Wähle unter
**Revier und Verwaltung → Offlinekarten verwalten** die Aktion
**MAP / Extraktgrenze importieren** und beide Dateien aus. Alternativ wird
gleichnamiges Polygon-GeoJSON als Abdeckungsdatei unterstützt. Offline wird
angeboten, wenn die lokalen Karten das gesamte Revier abdecken; ein
ausgewählter Offline-Modus wechselt nicht automatisch auf Onlinekarten.

Öffentliche Reviergrenzen werden nicht automatisch geladen oder aktualisiert.
Speichere einzelne Suchtreffer ausdrücklich über **Offline speichern**. Bei
unklarer Lizenz musst du bestätigen, dass du die Nutzungsbedingungen geprüft
hast. Gespeicherte Grenzen findest und entfernst du unter **Offline-Grenzen**.

Der zuletzt online geladene Revierstand mit Grenze und Markierungen kann für
höchstens sieben Tage offline angesehen werden. Er ist als veraltet markiert,
nur lesbar und enthält selbst keine Teilnehmer- oder Hundepositionen.
Nearby-Positionen können separat auf der Karte erscheinen. Änderungen an
Revierdaten und Einladungen benötigen weiterhin Internet. Das zuvor
eingerichtete Relais kann Meldungen ohne Internet annehmen und später über ein Gerät mit
Internetverbindung weiterleiten. Der Revierstand, öffentliche Grenzen und
Kartenpakete werden nicht mit der PWA oder einem Konto synchronisiert.

## Name, Darstellung und Installation

Im Benutzerkonto kannst du einen Anzeigenamen für Reviermitglieder speichern
oder entfernen. Ohne Anzeigenamen kann deine E-Mail-Adresse in der
Teilnehmerliste erscheinen. Markergröße, Kartenmodus und lokale Offlinekarten
gelten nur auf diesem Gerät.

Jagdbruder ist eine native Android-App. Installation und Aktualisierung
erfolgen über den Android-Bezugsweg, über den du die App erhalten hast.
Installiere APK-Dateien nur aus einer vertrauenswürdigen Quelle.
