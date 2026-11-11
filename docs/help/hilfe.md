# Hilfe und Fehlerbehebung

Die Karte ist eine Orientierungshilfe. Sie ersetzt keine verbindliche Revierkarte und erteilt keine Jagderlaubnis. Im Notfall 112 waehlen; eine App-Markierung ist kein Notruf.

## Ein Revier finden

Oeffne die oeffentliche Reviersuche ueber das Lupensymbol. Waehle eine Datenquelle und suche nach Reviername, Nummer oder Gemeinde. Die Suche braucht eine Internetverbindung.

Die App bevorzugt keine Quelle automatisch. Pruefe Quellenangabe, Lizenz und Datenstand. Eigene HTTPS-GeoJSON- und WFS-Quellen lassen sich unter Suchquellen verwalten hinzufuegen.

Ein Treffer kann zuerst als Vorschau auf der Karte angezeigt werden. Das legt kein Profil an. Fuer ein eigenes Revierprofil musst du angemeldet sein; dabei werden die bestehenden Serverfunktionen verwendet.

## Bildsymbole und Bedienung

- Die Lupe oeffnet die oeffentliche Reviersuche, der Aktualisieren-Pfeil laedt Serverdaten neu, die drei Punkte oeffnen Revier und Verwaltung, und das Personensymbol oeffnet das Benutzerkonto.
- Die Zielscheibe zeigt den eigenen Standort. Der Kartenmodus-Schalter waehlt OpenStreetMap, Luftbild oder Offline. Das Informationssymbol oeffnet diese Hilfe.
- Im Markierungs-Popup verschiebt die Zielscheibe eine Markierung an den eigenen Standort; der Papierkorb loescht sie, wenn deine Rolle dazu berechtigt ist. Ein Kreuz schliesst einen Dialog oder entfernt eine aktuelle Auswahl, loescht aber kein gespeichertes Revier.

## Anmelden und registrieren

Oeffne Benutzerkonto und waehle Anmelden oder Neues Konto. Gib deine E-Mail-Adresse ein und fordere einen Code an. Trage den Zahlencode aus der neuesten E-Mail in der App ein und bestaetige.

Die native Android-App bestaetigt die Anmeldung mit einem E-Mail-Code. Ein Anmeldelink aus der E-Mail kann Browser oder PWA oeffnen und meldet nicht zuverlaessig in dieser Android-App an. Verwende hier den Code; ein Passwort brauchst du nicht.

Pruefe bei ausbleibender Nachricht die Adresse und den Spam-Ordner und fordere nach kurzer Wartezeit einen neuen Code an. Codes sind vertraulich und nur einmal verwendbar. Auf gemeinsam genutzten Geraeten nach der Nutzung abmelden.

## Gespeicherte Reviere und Rollen

Melde dich an und waehle ein Profil ueber die Revierauswahl. Eigene Profile verwaltest du selbst; geteilte Profile gehoeren einem anderen Verwalter.

Owner (Verwalter) und Bearbeiter koennen Revierdaten verwalten. Gaeste koennen gemeinsame Markierungen sehen und eigene zeitlich begrenzte Meldungen setzen. Nur der Owner kann das gesamte Profil loeschen.

Ein geteiltes Revier auszublenden entfernt nicht deine Mitgliedschaft. Du kannst es spaeter wieder einblenden. Ein eigenes Revierprofil zu loeschen entfernt das Profil mit seinen Markierungen und Freigaben dauerhaft.

## Markierungen setzen und bearbeiten

Waehle ein Revier und tippe auf Markierung setzen. Waehle eine Stelle innerhalb der Reviergrenze, einen Objekttyp und bei Bedarf Titel und Notiz; speichere dann die Markierung.

Owner und Bearbeiter koennen dauerhafte Einrichtungen und Standplaetze setzen. Gaeste koennen Anschuss und Wildsichtung setzen, wenn ihr aktueller GPS-Standort innerhalb des Reviers liegt. Zeitlich begrenzte Meldungen laufen nach 48 Stunden ab.

Tippe auf ein Kartensymbol, um Details zu sehen. Berechtigte Personen koennen Markierungen bearbeiten oder loeschen. Owner und Bearbeiter koennen Markierungen innerhalb der Reviergrenze verschieben.

## Andere einladen

Owner und Bearbeiter koennen im ausgewaehlten Revier Personen per E-Mail als Gast oder Bearbeiter einladen. Die eingeladene Person muss sich mit genau dieser E-Mail-Adresse anmelden und das Revier danach neu laden.

Ein Gast darf gemeinsame Markierungen sehen sowie eigene Anschuss- und Wildsichtungsmeldungen setzen. Bearbeiter duerfen zusaetzlich dauerhafte Markierungen, Standplaetze und Einladungen verwalten. Das Profil loeschen darf nur der Owner.

Positionsbezogene Gastlinks laufen nach sieben Tagen ab und koennen weitergegeben werden. Teile sie nur mit Personen, denen du den Revierzugang anvertrauen willst.

## Hunde und Tracker

Oeffne Revier und Verwaltung, aktiviere bei Bedarf den Jagdmodus und waehle Hunde. Owner und Bearbeiter koennen einen Hund registrieren und ein Smartphone koppeln. Mit Tracker-Kamera scannst du den von Jagdkarte angezeigten QR-Code im DogTracker oder gibst den Schluessel manuell ein.

Hat das Tracker-Smartphone keine Kamera, waehle dort **QR-Code fuer Jagdkarte anzeigen**. In Jagdkarte beim passenden Hund **DogTracker-QR scannen** waehlen; nach der Erfolgsmeldung zum Tracker zurueckkehren und **Jagdkarte hat gekoppelt** bestaetigen. Dieser Gegenweg ist erst nach Ausrollen der `register_hunting_dog_device_token`-Migration im gemeinsamen Backend verfuegbar. Bis dahin den QR-Code aus Jagdkarte oder die manuelle Schluesseleingabe verwenden.

Der Geraeteschluessel wird nur einmal angezeigt. Halte ihn geheim. Pro Hund kann ein Smartphone aktiv gekoppelt sein. Die Tracker-App braucht Standortberechtigung und fuer verlaessliches Tracking meist auch Benachrichtigungs- und Hintergrundberechtigung.

Mit Hund loeschen entfernst du den Hund dauerhaft aus dem Revier. Die Serverkopplung und die gespeicherte Hundeposition werden ebenfalls geloescht. Der Tracker kann danach keine neuen Positionen mehr mit diesem Schluessel uebermitteln.

## Standort und Jagdmodus

Die Zielscheibe zeigt nur deinen eigenen Standort auf deinem Geraet; sie teilt ihn nicht automatisch mit der Gruppe. Fuer das Teilen brauchst du die Standortberechtigung und musst die Freigabe fuer das ausgewaehlte Revier einschalten.

Der Jagdmodus steuert, ob Gruppen- und Hundestandorte fuer das Revier uebermittelt werden duerfen. Owner und Bearbeiter koennen ihn verwalten. Beim Ausschalten werden gespeicherte Gruppen- und Hundepositionen serverseitig entfernt.

Standorte sind keine sichere Echtzeitortung. Ohne Verbindung kann eine letzte Position noch kurz sichtbar sein. Hundepositionen koennen auch ausserhalb der Reviergrenze angezeigt werden; die eigene Standortfreigabe und bestimmte Markierungen sind dagegen an die Reviergrenze gebunden.

## Revierdateien

Im Revierbereich kannst du eine Reviergrenze als JWD-Datei exportieren oder eine JWD-, JSON- oder GeoJSON-Datei importieren. Waehle Dateien ueber den Android-Dateiauswahldialog aus und pruefe die angezeigte Zusammenfassung vor dem Import.

Ein neues Profil wird als eigenes Revier angelegt. Beim Zusammenfuehren bleiben die bestehende Grenze und vorhandene Markierungen erhalten; neue dauerhafte Einrichtungen werden ergaenzt. GeoJSON-Dateien koennen mehrere passende Features enthalten.

Behandle Revierdateien mit vertraulichen Markierungen wie private Unterlagen. Teile sie nur mit berechtigten Personen. Ein Importfehler sollte keine Teilimporte hinterlassen; lies die Fehlermeldung und pruefe die Datei.

## Name und Darstellung

Im Benutzerkonto kannst du einen Anzeigenamen fuer Reviermitglieder speichern oder entfernen. Ohne Anzeigenamen kann deine E-Mail-Adresse in der Teilnehmerliste erscheinen.

Die Groesse der Reviermarker und weitere Darstellungseinstellungen gelten nur auf diesem Geraet. Kartenmodus und lokale Offlinekarten bleiben ebenfalls geraetebezogen.

## App installieren und aktualisieren

Dies ist eine native Android-App. Browseraktionen wie Zum Startbildschirm hinzufuegen und PWA-Service-Worker-Updates gelten hier nicht. Installation und Aktualisierung erfolgen ueber den Android-Bezugsweg, ueber den diese App bereitgestellt wurde.

Wenn du eine APK direkt erhaeltst, installiere nur Dateien aus einer vertrauenswuerdigen Quelle. Bei einer Aktualisierung vorher laufende Eingaben speichern.

## Karten und Offline-Nutzung

OpenStreetMap und Luftbilder benoetigen eine Internetverbindung. Die Luftbildabdeckung ist derzeit auf NRW begrenzt; an Landes- und Dienstraendern koennen Luecken auftreten.

Fuer Offline-Karten importierst du ein Mapsforge-.map-Paket und die passende .poly- oder Polygon-GeoJSON-Abdeckung. Offline wird nur angeboten, wenn die lokale Abdeckung das ganze Revier einschliesst. Im Offline-Modus gibt es keinen stillen Wechsel auf Onlinekarten.

Oeffentliche Reviergrenzen werden nicht automatisch geladen oder aktualisiert. Speichere einzelne Suchtreffer ausdruecklich ueber Offline speichern. Bei unklarer Lizenz musst du bestaetigen, dass du die Nutzungsbedingungen geprueft hast. Gespeicherte Grenzen findest und entfernst du unter Offline-Grenzen.

Beim naechsten Start oeffnet sich der zuletzt verwendete Bereich. In der Jagdkarte werden letztes Revier, Kartenposition und Zoom wiederhergestellt.

Der zuletzt online geladene Revierstand mit Grenze und Markierungen kann danach verschluesselt fuer hoechstens sieben Tage offline angesehen werden. Er liegt getrennt von Kartenpaketen im privaten, vom Android-Backup ausgeschlossenen Speicher; maximal 20 Reviere, 4 MiB je Revier und 32 MiB insgesamt. Die Offlineansicht ist als veraltet markiert und nur lesbar. Teilnehmer- und Hund-Livepositionen sind nicht enthalten. Aenderungen, Einladungen und Standortfreigaben brauchen weiterhin eine Verbindung. Der Cache synchronisiert nicht mit PWA oder Konto. Die oeffentliche Suche braucht Internet.

## Wenn etwas nicht klappt

### Keine Anmelde-E-Mail

Adresse kontrollieren, Spam-Ordner pruefen, kurz warten und einen neuen Code anfordern. Verwende bei der Bestaetigung den neuesten Code.

### Revier oder Einladung fehlt

Das richtige Profil und die eingeladene E-Mail-Adresse pruefen und Serverdaten aktualisieren. Nicht jede Region oder Datenquelle ist immer erreichbar.

### Standort fehlt oder ist ungenau

GPS einschalten, genaue Standortberechtigung erteilen und das Geraet nach Moeglichkeit ins Freie bringen. Fuer das Teilen muss die Freigabe aktiv sein; einige Aktionen erfordern einen Standort innerhalb der Grenze.

### Markierung kann nicht gespeichert werden

Profil und Rolle pruefen. Anschuss und Wildsichtung brauchen einen aktuellen Standort im Revier; Owner- und Bearbeiteraktionen stehen Gaesten nicht zur Verfuegung.

### Hundestandort fehlt

Jagdmodus, aktive Kopplung, Tracker-Berechtigungen und Internetverbindung pruefen. Bei neuem oder verlorenem Schluessel das Smartphone erneut koppeln.

### Karte oder Suche bleibt leer

Internetverbindung und gewaehlte Quelle pruefen und erneut laden. Im Offline-Modus passende Mapsforge-Abdeckung installieren; unvollstaendige Pakete werden nicht automatisch durch Onlinekarten ergaenzt.

### Datei wird abgelehnt

Dateityp und Groesse pruefen, Importzusammenfassung lesen und sicherstellen, dass die Datei gueltiges GeoJSON oder eine unterstuetzte Revierdatei enthaelt.