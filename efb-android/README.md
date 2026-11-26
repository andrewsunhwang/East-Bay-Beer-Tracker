# SimpleEFB (Android)

A simple electronic flight bag for Android that uses official FAA data and keeps it current on its own.

> **Not FAA-approved or certified.** Use it as a supplement to current official sources, and check chart currency and NOTAMs before every flight.

## What it does

| Feature | Details |
|---|---|
| **Moving map** | FAA **VFR Sectional**, **TAC** (drawn over the sectional), **IFR Enroute Low** and **IFR Enroute High** charts. Shows ownship, ADS-B traffic, follow mode and track-up/north-up. |
| **Terminal procedures** | Every airport in the FAA d-TPP: **airport diagrams & hot spots**, **takeoff minimums / ODPs / alternate minimums**, **SIDs & graphic ODPs**, **STARs**, and **approach plates**. A procedure's continuation pages open with it. |
| **Chart Supplement** | The airport's d-CS page(s) plus its notices page, for any airport ID, including airports with no instrument procedures. |
| **PDF viewer** | Pinch zoom, double-tap zoom, multi-page, night mode (inverted colors), and an **EXPIRED** banner when shown data is out of date. |
| **Position sources** | Phone/tablet GPS · ADS-B receivers over Wi-Fi (GDL 90 on UDP 4000: Stratux, Sentry, Stratus, SkyEcho…) · Bluetooth serial devices (GDL 90 or NMEA, detected automatically), with a raw-data recorder for troubleshooting. |
| **Automatic updates** | Runs twice a day in the background, on Wi-Fi only by default. See below. |

### How automatic updates work

- **d-TPP (28-day cycle):** the app downloads the procedure index for the current cycle. When the FAA posts the next cycle early (about 3 weeks ahead), the app downloads that too. It switches over at 0901Z on the effective date, with no gap.
- **Favorites (★):** every chart and the Chart Supplement entry for each favorite airport is kept downloaded for offline use, for both the current and next cycle.
- **Map charts (56-day cycle):** each tile expires one day after the next edition starts. Saved offline areas are re-downloaded into a fresh cache after each new edition. The map switches only once that download is complete, so you never lose charts while offline.
- **Cleanup:** expired cycles and editions are deleted automatically.
- **Manual update:** *Settings → Update now* runs an update right away.

### Data sources (all public FAA)

- Procedures index: `https://aeronav.faa.gov/d-tpp/<cycle>/xml_data/d-tpp_Metafile.xml`, with plates at `https://aeronav.faa.gov/d-tpp/<cycle>/<PDF>`
- Chart Supplement: per-page PDFs under `https://aeronav.faa.gov/afd/<date>/`, found through the FAA d-CS search. The d-CS XML index is a fallback.
- Map charts: FAA Aeronautical Information Services tile services (`VFR_Sectional`, `VFR_Terminal`, `IFR_AreaLow`, `IFR_High`).

The CI job **"Live FAA data check"** runs against these endpoints on every push. If the FAA changes a URL or file format, that job is where it will show up.

## Garmin GTX 345: please read

The GTX 345 sends its data over Bluetooth using Garmin's **Connext** protocol. Garmin has not published this protocol, and only licensed partners such as Garmin Pilot, ForeFlight and FltPlan Go have it. According to Garmin support, as reported by owners, the GTX 345 does not output standard GDL 90 over Bluetooth.

What SimpleEFB does today:
1. You pair the GTX 345 in Android's Bluetooth settings, then choose it under *Settings → Position source → Bluetooth device*.
2. SimpleEFB connects over RFCOMM using SPP, or another advertised service you pick. If that fails, it falls back to an insecure connection and then to channel 1.
3. If the stream is GDL 90 or NMEA, it works right away and shows position and traffic.
4. If the stream is in a different format, the status card shows *"unrecognized format"* along with live byte counts and a hex view. Turn on **Record raw data**, fly or run the transponder for a few minutes, then use **Share latest capture**. With that capture, a decoder can be added if the format turns out to be workable.

Options that work for sure today:
- An **ADS-B receiver over Wi-Fi** (Stratux, uAvionix SkyEcho, ForeFlight Sentry, Appareo Stratus in open mode). It feeds GDL 90 with GPS position and traffic.
- The GTX 345 can also send GDL 90 *traffic* on a wired RS-232 port, which an avionics shop would have to configure. That needs extra hardware to reach a phone.
- The phone or tablet's own GPS.

## Installing

1. Open the repository's **Actions** tab, then the latest **EFB Android** run, and download the **SimpleEFB-apk** artifact.
2. Unzip it, copy `app-release.apk` to your Android device, and open it. You may need to allow installing from unknown sources for your file manager or browser.
3. Later builds install over earlier ones. Every build is signed with the same key in `keystore/`. That key exists only for sideloading this personal app, so don't use it for anything else.

On first launch the app downloads the procedures index, about 16 MB. Then:
- **Airports:** search for an airport and tap ★ to keep it updated offline.
- **Map:** pan and zoom to your flying area and tap ⇩ to save it for offline use.
- **Settings:** choose your position source.

Requires Android 8.0 or newer.

## Building

```bash
cd efb-android
./gradlew :app:assembleRelease                 # needs the Android SDK (API 35)
./gradlew -PcoreOnly=true :core:test           # core logic only; no Android SDK needed
./gradlew -PcoreOnly=true :core:liveCheck      # checks the live FAA endpoints
```

### Layout

- `core/` is plain Kotlin and unit-tested. It contains cycle math (28-day AIRAC and 56-day chart editions), the d-TPP metafile parser, Chart Supplement link handling, GDL 90 framing/CRC/decoding (tested against the spec's examples), NMEA parsing, and stream auto-detection.
- `app/` is the Android app (Jetpack Compose and osmdroid):
  - `store/` holds the SQLite index, downloads and settings.
  - `update/` is the WorkManager auto-update.
  - `position/` contains the GPS, Wi-Fi and Bluetooth sources.
  - `map/` holds the FAA tile sources, the offline cache and the ownship/traffic overlay.
  - `ui/` contains the screens.

## Known limitations

- There is no flight planning, weather, NOTAMs, or geo-referenced ownship on plates. Keeping the app "simple" was intentional.
- Map charts come at the FAA tile services' resolution, which tops out at zoom level 12. The IFR High chart stops at level 9.
- The app itself does not update automatically. Install a newer APK from Actions to update it. Chart data does update automatically.
