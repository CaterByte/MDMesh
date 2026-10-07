# MeinConnect MDM – der Fork

Dies ist der MeinConnect-Fork von [MDMesh](https://github.com/MDMesh-app/MDMesh). Er bleibt so nah wie möglich am Original, damit Updates aus MDMesh leicht zu übernehmen sind, und ändert genau drei Dinge:

1. **Eigene Agent-Identität.** Der Agent heißt `de.meinconnect.mdm` und wird mit dem eigenen Release-Schlüssel signiert. Die Konsole, der Einrichtungs-QR und der Updater sind darauf eingestellt.
2. **Kiosk und Launcher im MeinConnect-CI.** Der Home-Screen ist fürs Smartphone im Hochformat gebaut: Signaturverlauf oben, Datum und große Uhr, Standort, die Haupt-App als große Karte mit Verlaufs-Knopf „Öffnen“, weitere Apps als Zeilen und unten die freigegebenen Schnelleinstellungen (WLAN, Helligkeit, Lautstärke). Schrift ist Poppins. Alle Texte gibt es auf Deutsch und Englisch, je nach Gerätesprache. Jede Konfiguration kann das Design überschreiben, etwa mit Titel, Logo, Farben, Hintergrundbild oder Balken. So geht auch White-Label pro Kunde.
3. **Eigene Release-Pipeline.** Tags in diesem Repo bauen den signierten Agent, die Docker-Images unter `ghcr.io/caterbyte/…` und das per minisign signierte Manifest. Der Supervisor auf dem Server nimmt nur Releases an, die mit unserem Schlüssel signiert sind.

---

## Schlüssel und Secrets

Die Schlüssel liegen **außerhalb** des Repos unter `~/DEV/meinconnect-mdm-keys/`, dort steht auch eine eigene README. Die sechs GitHub-Secrets sind im Repo unter *Settings → Secrets and variables → Actions* hinterlegt:

`MDM_RELEASE_STORE_B64`, `MDM_RELEASE_STORE_PASSWORD`, `MDM_RELEASE_KEY_ALIAS`, `MDM_RELEASE_KEY_PASSWORD`, `MINISIGN_SECRET_KEY`, `MINISIGN_PASSWORD`

- **Den APK-Keystore nie wechseln.** Geräte sind als Device Owner an dieses Zertifikat gebunden. Ist der Schlüssel verloren oder ein anderer im Einsatz, gibt es keine Updates mehr, und das Gerät muss auf Werkseinstellungen zurückgesetzt werden.
- Der öffentliche minisign-Schlüssel liegt in `release/minisign.pub` und wird in das Supervisor-Image eingebaut.
- Die Signatur-Prüfsumme für den Einrichtungs-QR wird bei jedem Release automatisch aus dem APK berechnet. Zur Kontrolle steht sie in `meinconnect-mdm-keys/signatur-checksum.txt`. Derselbe Wert muss im Release-Manifest unter `components.apk.signatureChecksum` stehen.

---

## Ein Release bauen

```bash
git tag -a v1.0.0 -m "MeinConnect MDM 1.0.0"
git push origin v1.0.0
```

Den Rest erledigt GitHub Actions über `.github/workflows/release.yml`. Tags müssen streng `vX.Y.Z` heißen. Aus dem Tag entsteht der Versionscode des Agents (`X*10000 + Y*100 + Z`), er muss also immer steigen.

**Beim allerersten Release** bricht der Workflow im Schritt *„Verify images are publicly pullable“* ab. Neue GHCR-Pakete einer Organisation sind nämlich zunächst privat. Dann so vorgehen:

1. In GitHub unter **CaterByte → Packages** die drei Pakete `mdmesh-server`, `mdmesh-web` und `mdmesh-supervisor` öffnen und jeweils über **Package settings → Change visibility** auf **Public** stellen.
2. Im fehlgeschlagenen Workflow-Lauf **„Re-run failed jobs“** klicken.

Das ist nur einmal nötig.

---

## Den Hetzner-Server auf den Fork umstellen (einmalig)

Ausgangslage: Die Quick-Start-Installation mit Docker läuft und nutzt die Original-Images von `mdmesh-app`.

> **Warum nicht über „Update“ in der Konsole?** Im laufenden Supervisor steckt noch der öffentliche Schlüssel des Originals. Unsere Releases würde er deshalb ablehnen. Dieses eine Mal werden die Images also von Hand gezogen. Danach laufen Updates wieder ganz normal mit einem Klick oder automatisch.

Im Installationsordner, also dort, wo `docker-compose.yml` und `.env` liegen:

```bash
# 1. Datenbank sichern
docker compose exec -T postgres pg_dump -U mdmesh mdmesh > backup-vor-meinconnect-$(date +%F).sql

# 2. .env anpassen (vorher eine Kopie anlegen)
cp .env .env.vor-meinconnect
#    IMAGE_OWNER=caterbyte
#    GITHUB_REPO=CaterByte/MDMesh
#    SERVER_VERSION=1.0.0
#    WEB_VERSION=1.0.0
#    SUPERVISOR_VERSION=1.0.0      (oder latest)
#    CURRENT_VERSION=1.0.0
#    GITHUB_TOKEN wird nur gebraucht, wenn das Repo privat ist

# 3. Neue Images ziehen und starten (Liquibase legt dabei die neuen Spalten an)
docker compose pull && docker compose up -d
docker compose logs -f server      # warten, bis der Server healthy ist

# 4. Prüfen, ob das Agent-APK für die Einrichtung jetzt unseres ist
curl -s https://<deine-mdm-domain>/files/agent.apk -o /tmp/agent.apk && sha256sum /tmp/agent.apk
#    Den Wert mit components.apk.sha256 in manifest.json des GitHub-Releases vergleichen
```

**Zurück zum alten Stand:** `.env.vor-meinconnect` zurückkopieren, `docker compose pull && docker compose up -d` ausführen und bei Bedarf das Backup einspielen.

> Geräte, die noch mit dem **offiziellen** MDMesh-Agent laufen, sprechen weiter mit dem Server. Sie können aber kein Update auf unseren Agent bekommen, weil die Signatur eine andere ist. **Starte deshalb keinen Agent-Rollout auf diese Geräte.** Sie werden ohnehin neu eingerichtet.

---

## Geräte neu einrichten

1. **Zuerst ein einzelnes Testgerät** auf Werkseinstellungen zurücksetzen und im Willkommensbildschirm sechsmal tippen, damit der QR-Scanner erscheint.
2. In der Konsole unter **Enroll** den QR-Code für die gewünschte Konfiguration anzeigen und scannen. Der QR-Code enthält bereits `de.meinconnect.mdm` und unsere Signatur-Prüfsumme.
3. Auf dem Testgerät prüfen: Lässt sich das Gerät einrichten? Wird der Kiosk angezeigt? Werden Apps installiert? Erscheint das Branding?
4. Erst danach die übrigen Geräte zurücksetzen und einrichten.

---

## Kiosk-Branding einstellen

Das Branding stellst du in der Konsole ein: **Configurations → Konfiguration**, Gruppe **Display**. Die Felder sind als *Enforced* markiert, weil der Agent sie anwendet. Lässt du ein Feld leer, gilt der MeinConnect-Standard. Die Farbfelder haben dafür einen **Reset**-Knopf.

| Feld | Wirkung | MeinConnect-Standard |
|---|---|---|
| Kiosk title | Überschrift unter dem Logo, z. B. der Standortname | kein Titel |
| Kiosk logo URL | Logo im Kopf und im Startbildschirm (https, PNG/JPEG/WebP, maximal 5 MB, wird auf dem Gerät zwischengespeichert) | MeinConnect-Wortmarke |
| Kiosk accent color | Buttons, Fortschrittsanzeige, Tipp-Feedback | `#0957c3` |
| Kiosk brand bar | Balken oben als durchgehender Verlauf durch die Farben `#RRGGBB:ENDE%,…`, oder `none` | MeinConnect-Verlauf `#0957c3:40,#593c90:64,#aa205d:84,#fa052a:100` |
| Background color | Hintergrundfarbe der Seite. Ist sie dunkel, wird der Text automatisch hell und das Logo kommt auf einen weißen Chip. | `#f4f5f7` |
| Text color | Textfarbe | `#111418` (auf dunklem Hintergrund weiß) |
| Background image URL | Hintergrundbild, vollflächig zugeschnitten | keines |
| Icon size | wird vom neuen Home-Screen nicht mehr genutzt (feste Größen) | – |

**Die zwei typischen Konfigurationen:**

- **Single-App:** *Kiosk mode* an und als *Main app* die MeinConnect- oder die Waage-App. Weitere Apps nicht zum Installieren eintragen. Das Gerät startet direkt in die App. Vom Agent sieht man nur kurz den Startbildschirm mit Logo und „Wird gestartet …“.
- **Launcher:** *Kiosk mode* an und mehrere Apps installieren, zum Beispiel MeinConnect und die Waage. Das Gerät zeigt den MeinConnect-Home-Screen: Die *Main app* erscheint als große Karte, alle weiteren Apps als Zeilen darunter.

**Schnelleinstellungen** (Gruppe Kiosk): *Quick setting: Wi-Fi*, *Quick setting: brightness* und *Quick setting: volume*. Was eingeschaltet ist, erscheint unten auf dem Home-Screen als Kachel und öffnet ein Fenster: WLAN wählen und Passwort eingeben, Helligkeit oder Lautstärke (Medien + Benachrichtigungen) per Regler. Die Android-Einstellungen selbst bleiben gesperrt. Für die WLAN-Liste muss die Standortfunktion des Geräts an sein. Firmen-WLAN (802.1X/EAP) lässt sich dort nicht einrichten.

**Benachrichtigungen im Kiosk:** Ab v1.1.3 sind sie bei *Notifications = Default* an; nur *Off* schaltet sie ab. Der Agent schaltet *Home* automatisch mit ein (Android verlangt das, sonst schlägt der ganze Kiosk fehl) und erteilt den freigegebenen Apps und sich selbst ab Android 13 die Benachrichtigungs-Berechtigung. Meldungen erscheinen als Banner, und die Benachrichtigungsleiste lässt sich herunterziehen. Androids Schnelleinstellungen bleiben dabei gesperrt. Welche Kiosk-Funktionen auf dem Gerät wirklich aktiv sind, meldet der Agent in der Telemetrie (`system.lockTaskFeatures`).

Wichtig für die MeinConnect-App: Android zeigt Firebase-Pushes **nicht** von selbst an, solange die App im Vordergrund ist — und im Kiosk ist sie das praktisch immer. Die App muss Nachrichten im Vordergrund selbst als lokale Benachrichtigung ausgeben (`FirebaseMessaging.onMessage` → `flutter_local_notifications`, Kanal mit hoher Wichtigkeit).

**MDM-Nachrichten** (`device.alert`) erscheinen ab v1.1.3 zusätzlich als Karte über dem aktuellen Bildschirm und auf dem Sperrbildschirm, nicht nur als Benachrichtigung.

**Sperrbildschirm:** Ab v1.1.3 ist *Lock screen* bei *Default* an. Nach „Sperren“ oder der Ein/Aus-Taste erscheint dann der normale Sperrbildschirm (ohne PIN zum Wischen, mit PIN entsprechend). Bisher hat Android im Kiosk den Sperrbildschirm komplett übersprungen. *Off* stellt das alte Verhalten wieder her. Ab v1.1.4 schaltet der Agent dabei eine Displaysperre „Keine“ auf „Wischen“ um (`setKeyguardDisabled(false)`), denn sonst gibt es gar keinen Sperrbildschirm, den der Kiosk durchlassen könnte. Eine PIN oder ein Muster bleibt unverändert.

**Hintergrund:** Das Feld *Background image URL* wird ab v1.1.3 zusätzlich als System-Hintergrund für Startbildschirm **und** Sperrbildschirm gesetzt (einmal pro Bild). Ein fertiger MeinConnect-Hintergrund liegt in MeinConnect unter `public/logo/mdm-wallpaper.jpg` (`https://meinconnect.app/logo/mdm-wallpaper.jpg`) (1080 × 2400, hell — passt zum Standard-Theme mit dunkler Schrift). Bei dunklen Hintergründen *Background color* dunkel setzen, sonst ist die Uhr schlecht lesbar.

**Systemupdates:** Gruppe *Updates* → *System updates*. *Immediately* installiert Hersteller-Updates, sobald sie da sind (das Gerät startet dann von selbst neu), *Scheduled* nur im täglichen Zeitfenster *System update from/to* (z. B. 02:00–04:00, darf über Mitternacht gehen), *Postponed* hält Updates 30 Tage zurück (Android-Grenze; Sicherheitsupdates können trotzdem kommen), *Default* lässt alles beim Nutzer. Das MDM steuert **wann** installiert wird, nicht **welche** Version — die kommt vom Hersteller. Ein wartendes Update meldet der Agent in der Telemetrie (`system.pendingUpdateSince`), sofern der Updater des Herstellers es an Android meldet.

**Geräteinfo:** Das (i) rechts neben der Uhr öffnet die Geräteinfo: Name aus der Konsole/MeinConnect, Kunde, Konfiguration, Geräte-ID, Modell, Android/Patch, Seriennummer, IMEI, Verbindung, Agent-Version, letzter Abgleich, Update-Status. Werte gedrückt halten kopiert sie.

**Branding pro Gerät (MeinConnect):** Ab v1.1.2 kann jedes Gerät Titel und Logo der Konfiguration überschreiben (Tabelle `mcDeviceBranding`). MeinConnect setzt das automatisch aus dem White-Label des zugeordneten Kunden, so teilen sich viele Kunden eine Konfiguration wie „Waage Kiosk“. Ein überschriebenes Gerät hat dadurch eine eigene Revision; die Sync-Übersicht der Konsole rechnet das mit ein. API: `GET`/`PUT /rest/private/agent/v1/devices/{nummer}/branding` mit `{"title": …, "logoUrl": "https://…"}`, beide leer = zurück zur Konfiguration.

**Den Kiosk verlassen:**

- *Kiosk exit button* an: Es erscheint der Knopf „Kiosk verlassen“.
- *Kiosk exit button* aus: Sieben schnelle Tipps in die obere rechte Ecke.

In beiden Fällen wird das *Admin password* der Konfiguration abgefragt.

---

## Upstream-Updates übernehmen

1. Auf GitHub im Fork **„Sync fork“** klicken. Gibt es Konflikte, lokal arbeiten: `git fetch upstream && git merge upstream/main`. Den Remote `upstream` legst du einmalig mit `git remote add upstream https://github.com/MDMesh-app/MDMesh.git` an.
2. In diesen Dateien sind Konflikte zu erwarten, weil der Fork sie verändert:
   - `agent-android/app/src/main/kotlin/com/mdmesh/agent/KioskLauncherActivity.kt`: Der View-Teil ist durch `brand/KioskScreens` ersetzt. Die Logik (`applyState`, Exit, Absturzschutz) entspricht dem Original.
   - `agent-android/app/src/main/kotlin/com/mdmesh/agent/MainActivity.kt`: Kopfbereich und Farben.
   - `server/src/main/resources/liquibase/db.changelog.xml`: Unser Changeset `mc-26.10.01-kiosk-branding` steht am Ende. Beim Mergen die Upstream-Changesets davor oder danach einfügen. Die IDs mit `mc-` kollidieren nie.
   - `common/…/ConfigurationMapper.java` und `ConfigurationMapper.xml`: die `kiosk*`-Spalten (Branding + Schnelleinstellungen).
   - `AgentAdminResource.java`, `AgentResource.java` (enroll), `ConfigReconciler.java`, `AgentEnrollmentToken(Mapper).java`, `AgentDeviceMapper.java` (`listDevicesForSync` mit `deviceId`), `DeviceSyncRow.java`, `UnsecureDAO.java`: Geräte-Branding und Token-Metadaten (alles mit „MeinConnect fork“ kommentiert).
   - `web/src/data/configFields.ts`: die Branding- und Schnelleinstellungs-Felder und `KIOSK_AFFECTING_KEYS`.
   - `.github/workflows/release.yml`, `setup.sh`, `install/install-native.sh`, `quickstart.sh`, `docker-compose.release.yml`: Paketname und Repo-Besitzer.
   - `release/minisign.pub`: **Hier immer unseren Schlüssel behalten.**
3. Warten, bis die CI auf `main` grün ist (`t0-fast`). Dann den nächsten Tag setzen, siehe oben. Der Supervisor bietet das Update dann in der Konsole an.

---

## Was der Fork geändert hat (Überblick)

| Bereich | Dateien |
|---|---|
| Agent-Identität | `agent-android/app/build.gradle.kts` (`applicationId`), `web/src/enroll/provisioning.ts`, `web/src/components/RolloutPanel.tsx` |
| Pipeline und Installer | `.github/workflows/release.yml`, `setup.sh`, `install/install-native.sh`, `quickstart.sh`, `docker-compose.release.yml`, `release/minisign.pub` |
| Theme-Vertrag | `proto/payloads/config-apply.schema.json`, `agent-android/proto/…/KioskCommand.kt`, `common/…/DesiredKioskTheme.java`, `common/…/DesiredConfigBuilder.java` (+ Test) |
| Datenbank | `Configuration.java`, `ConfigurationMapper.java/.xml`, Liquibase-Changesets `mc-26.10.01-kiosk-branding`, `mc-26.10.04-kiosk-quick-settings` und `mc-26.10.05-device-branding-enroll-meta` |
| MeinConnect-Anbindung (v1.1.2) | `McDeviceBranding*.java` (Domain, Mapper, DAO), `DesiredConfigBuilder.build(cfg, apps, branding)` (+ Test), `ConfigReconciler`, `AgentAdminResource` (`/devices/{n}/branding`, `/token/{id}`, `mcDescription` beim Token), `AgentResource.enroll` (Gerätename aus dem Token, `mcDeviceNumber`) |
| Konsole | `web/src/data/configFields.ts`, `web/src/pages/ConfigurationsPage.tsx` (Reset für Farbfelder) |
| Agent-Oberfläche | `agent-android/kiosk/…/brand/KioskBrand.kt`, `BrandGradient.kt` (+ Test), `agent-android/app/…/brand/*` (HomeScreen, QuickSheet, QuickSlider, WifiControl, LightAndSound, BrandParts …), `KioskLauncherActivity.kt`, `MainActivity.kt`, `core/…/AlertNotifier.kt`, `AndroidManifest.xml` (CHANGE_WIFI_STATE) |
| Kiosk-Verhalten | `agent-android/kiosk/…/KioskFeatures.kt` (+ Test): Notifications/Recents ziehen Home mit; Liquibase `mc-26.10.04-kiosk-quick-settings`; `DesiredKiosk.quickSettings` |
| v1.1.3 | `DesiredConfigBuilder` (Notifications/Lock screen standardmäßig an, `systemUpdate`, `configurationName`; Golden-Revision neu), `DesiredSystemUpdate.java`, `AgentCheckInResponse.deviceName`; Agent: `ConfigApplier` + `DpmSystemUpdatePolicy`, `SystemStatusCollector` (Telemetrie `system`), `DeviceProfileStore`, `brand/WifiPanel`/`WifiListView`/`WifiPasswordForm`/`SheetFrame`/`InfoSheet`/`DeviceInfoReader`/`WallpaperSync`, `AlertActivity` |
| Ressourcen | `res/font/poppins_*.ttf` (OFL, siehe `agent-android/third_party/poppins/OFL.txt`), `res/drawable-nodpi/mc_*.png`, `res/mipmap-*/ic_launcher_foreground.png`, `res/values(-de)/strings.xml`, `core/src/main/res/values(-de)/strings.xml` |

## Noch offen

- Den Kiosk auf einem echten Gerät ansehen. Die Oberfläche ist programmatisch gebaut und lokal nur gegen die Android-Schnittstellen typgeprüft. Den vollständigen Build und Lint übernimmt die CI.
- Das App-Icon ist eine Ableitung aus der Wortmarke mit dem Signaturbalken. Eine eigene Bildmarke fehlt noch.
- MeinConnect-Anbindung: umgesetzt im MeinConnect-Repo (`Modules/Mdm`, `docs/konzept-mdm-anbindung.md`). Ab v1.1.2 mit Geräte-Branding und Vorab-Zuordnung beim Einrichten.
