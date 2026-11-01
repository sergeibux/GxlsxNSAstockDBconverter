# GxlsxNSAstockDB_converter

Command-line Java tool of the **NSA-app** ecosystem (equipment tracking for the NSA diving
club). It keeps the club's historical **Google Drive spreadsheets** (`.xlsx` inventories of
tanks, regulators and BCDs) in line with the changes made in the NSA-app database. Run by cron:

1. reads the NSA-app database (`orgs/<ORG>.db` of the web site, **read-only**) and computes the values every
   equipment has in the workbooks;
2. compares them with the **snapshot** of the last successful run: only what changed in the app since then is
   written (a cell corrected by hand stays as it is until the app changes that value); no change: Drive is not
   even contacted;
3. downloads each workbook concerned, applies the changes, and uploads it back (same file id, new revision),
   unless someone changed it on Drive meanwhile: then it is downloaded and processed again, up to 10 times
   (manual changes are never overwritten);
4. advances the snapshot of each equipment whose changes were uploaded: a workbook that could not be updated
   is retried at the next run;
5. with the **import** (`api.url` set): right after, the workbooks should hold the database values; what still
   differs was changed by hand in the workbook and is written to the database **through the NSA-app API**
   (see *Import from the workbooks*).

A **test mode** applies a file of changes instead (one JSON object per line, see *Test mode*).

---

## Requirements

- **JDK 11** or newer (compiled for Java 11; runs on 17/21), on **Linux x86-64** (the only SQLite native
  library kept in the jar)
- **Maven 3.6+** (tested with 3.9.9)
- A **Google Cloud project** with the **Google Drive API** enabled and a **service account**
  with a JSON key
- The two workbooks **shared with the service account e-mail** (*Editor* role), stored as
  real `.xlsx` files on Drive, not native Google Sheets (the tool downloads the binary file
  with `files.get(…).executeMediaAndDownloadTo`)
- Network access to `www.googleapis.com`
- Import only: network access to the NSA-app API, and an NSA-app account whose member type has the rights
  `edit` and `loan` (or `all`)

## Dependencies

Declared in `pom.xml`:

| Dependency | Version | Use |
|------------|---------|-----|
| `org.apache.poi:poi` | 5.5.1 | Workbook model |
| `org.apache.poi:poi-ooxml` | 5.5.1 | `.xlsx` (XSSF) reading / writing |
| `org.apache.logging.log4j:log4j-api` / `log4j-core` | 2.20.0 | Logging backend required by POI |
| `com.google.apis:google-api-services-drive` | v3-rev20251210-2.0.0 | Drive v3 client |
| `com.google.api-client:google-api-client` | 2.8.1 | Google API client (brings `google-http-client`, `-gson`, `-apache-v2`, `google-auth-library-oauth2-http`) |
| `com.google.oauth-client:google-oauth-client` | 1.39.0 | OAuth 2 client |
| `org.xerial:sqlite-jdbc` | 3.53.4.0 | NSA-app database, read-only |

Build plugin: `maven-shade-plugin` 3.4.1 (single runnable jar with every dependency, main class
`main.java.GxlsxNSAstockDBconverter.Main`; SQLite native library for Linux x86-64 only).

## Configuration

`GxlsxNSAstockDB_converter.conf` (Java properties, UTF-8), path given with `--conf`
(`/etc/NSA/GxlsxNSAstockDB_converter.conf` for `run.sh`):

```properties
# Part of the Drive file names to look for (case-insensitive "contains")
fileName.blocs=Liste de blocs
fileName.det_et_stabs=Liste des Détendeurs et Stab du NSA
# Path to the service account JSON key (absolute, or relative to the working directory)
apiKey=/path/to/service-account-key.json
# NSA-app database, read-only (database mode)
db.file=/var/lib/nsa/orgs/NSA.db
# Values of the workbooks at the last successful run (database mode, written by the tool, mode 600)
snapshot.file=/var/lib/nsa/drive/NSA.snapshot.json
# Import from the workbooks into the database (optional: without api.url, no import)
api.url=https://smv-coding.fr/NSA/api
#api.org=NSA
api.email=account@example.org
api.password=…
import.state.file=/var/lib/nsa/drive/import.state.json
# Columns written only when a row is created, ";"-separated (optional: this is the default list)
#protected.columns=Identification;Identification Bloc
# Local tests only: copy of each workbook written before its upload (leave unset on the server)
#debug.copy=debug.xlsx
```

| Key | Meaning |
|-----|---------|
| `fileName.blocs` | Tank inventory workbook (sheet `BLOCS`) |
| `fileName.det_et_stabs` | Regulators / BCDs workbook (sheets `Détendeur NSA`, `Stab NSA`) |
| `apiKey` | Path of the service account JSON key file |
| `protected.columns` | Columns written only when the row is created (reference unknown to the sheet), `;`-separated. Absent: `Identification;Identification Bloc` (defined in the code). Present but empty: no protected column. With the import, a column it compares (state, characteristic, revision date, stock) is refused (exit code 1): an app change of it would never reach the workbook, and the import would undo it |
| `debug.copy` | **Local tests only**: file where a copy of each workbook is written before its upload. Unset (the default): no copy. Never set it on the server: the copy holds the club's data |
| `db.file` | NSA-app database of the organisation, opened **read-only** (required in database mode) |
| `snapshot.file` | Snapshot (JSON), in a directory writable by the user of the cron (required in database mode) |
| `api.url` | Base URL of the NSA-app API (`…/api`). Set: the import runs after each export (database mode only) |
| `api.org` | Organisation (default `NSA`) |
| `api.email`, `api.password` | NSA-app account used by the import (required with `api.url`): the file holds a password, keep it `640 root:www-data` |
| `import.state.file` | State of the import (JSON, mode 600, written by the tool; required with `api.url`): device id and temporary ids for the API, session token, start of the last run, Drive version of each workbook entirely imported |

The file name is matched against the **first 10 files** returned by Drive for the service
account (if several match, the last one wins): keep the shared folder small, or the
workbook may not be found.

The configuration file and the service account key are **not versioned** (see
`.gitignore`). Keep them readable only by the user running the tool (`chmod 600`).

### Google service account setup

1. Google Cloud console › *APIs & Services* › enable **Google Drive API**.
2. *IAM & Admin › Service accounts* › create an account › *Keys › Add key › JSON*.
3. Share both workbooks on Drive with the service account e-mail
   (`…@….iam.gserviceaccount.com`), role *Editor*.
4. Put the key path in `apiKey=`.

## Database mode

Values computed for each equipment (sheet chosen by the type of the app):

| App type | Sheet | Identification column | Characteristic column | Revision columns | Stock column |
|----------|-------|-----------------------|-----------------------|------------------|--------------|
| `Bloc` | `BLOCS` | `Identification Bloc` | `Type de Blocs` | `Date de dernière inspection visuelle` | – |
| `Détendeur` | `Détendeur NSA` | `Identification` | `Type` | `Date de dernière révision`, `Date de prochaine révision` | – |
| `STAB` | `Stab NSA` | `Identification` | `Type` | `Date de dernière révision`, `Date de prochaine révision` | `Affectation initiale` |

- `Etat et position au`: `Prêt en cours à : <first name> <last name>` during a loan, else `En révision`
  (status *à contrôler*), `Non disponible` (status *non disponible*), else the name of its place.
- Revision dates: last revision, and last revision + revision interval of the type (`JJ/MM/AAAA`,
  Europe/Paris). Requalification columns are never written, nor the columns typed by hand (owner,
  manufacturer, serial number, inspector, status, purchase date…).
- A value the database does not have (no place, no revision date) is never written: the cell stays as it is.

Changes since the snapshot (deletions first):

| In the app | In the workbook |
|------------|-----------------|
| New equipment | new row with every known value |
| Equipment deleted | identification cell marked `Supprimé de la NSA-app :` + reference (row kept) |
| Reference changed (case and spaces ignored) | old row marked deleted, new row with every known value (columns typed by hand are not copied) |
| Loan, return, place, status, revision, stock, member or place renamed | the columns whose value changed |
| Characteristic changed | the characteristic column |
| Type changed, or type without a sheet | nothing, warning |

Several changes between two runs give one write of the final value; an equipment deleted then created again
in the app (new id) gives a marked row then a new one.

The snapshot (`snapshot.file`) is written atomically (temporary file, then rename). First run, without a
snapshot: it is only created, nothing is written. An unreadable snapshot stops the run (exit code 1): it is
never recreated silently, as the changes not uploaded yet would be lost. Delete it by hand to start again
from the current database.

## Import from the workbooks

Run after the export, in database mode, when `api.url` is set. Once the export is done, the workbooks should
hold the database values, plus the columns the database does not have and the rows marked deleted: any
difference left is a change made by hand. The workbooks are compared with the database **by meaning**
(`Non conforme` and `Non disponible` are both the status *non disponible*: no difference), and the differences
are sent to the API (`changes.php`), as a phone would do: the server checks them (columns, foreign keys,
unique references).

Which rows:

- every equipment row of the `BLOCS`, `Détendeur NSA` and `Stab NSA` sheets, except the rows marked deleted
  (`Supprimé de la NSA-app :`), the free slots (owner `Identification Libre`) and the rows without reference;
- a reference found on several rows of a sheet, or on several equipment in the database, or on an equipment
  of another type: not imported, warning;
- references compared case and spaces ignored (non-breaking spaces included), imported trimmed.

| In the workbook | In the database |
|-----------------|-----------------|
| Reference unknown to the database | equipment created (`creationTs`: now) |
| `Etat et position au`: `En révision`, `En réparation` | status *à contrôler* (place unchanged) |
| `Non disponible`, `Non conforme`, `Villa - Non coforme`, `Retiré sur demande` | status *non disponible* (place unchanged) |
| `Prêt en cours à : …`, empty | nothing (loans are made in the app only) |
| Any other text | status *disponible*, and that place (created if no place has this name) |
| Characteristic (`Type`, `Type de Blocs`) | characteristic of the type with that text (created if none), line breaks kept; created empty: `Non renseigné` |
| Last revision / inspection date | `lastRevisionTs` (midnight, Europe/Paris); created without a date: 0, revision due (warning) |
| `Affectation initiale` (stabs) | stock of that name (unknown name: warning); created without one: `Milieu naturel` |
| Owner other than `Club NSA` (`BLOCS`) | created *non disponible* in the place `Maison` (created if missing); its state cell is never imported |
| Row removed, reference changed by hand | nothing (deleting stays in the app; a changed reference is a new equipment) |
| Next revision, requalification, manufacturer, serial number, owner, inspector, purchase date… | nothing (no such field in the database) |

Priority to the app: every row is sent with **the start of the previous run** as `lastUpdate`. The server keeps
an equipment changed in the app since then (`skipped`, logged *kept*); the workbook is compared again at the
next run, when the app change is in the workbook, and the change made by hand is then applied (if it was on
another column: on the same column, the export already wrote the app value over it). A change of place
replaces the `equipmentPlace` row, which the server does not check: it is not sent if the equipment or its
place changed in the app since the previous run (compared again next run). A change made offline on a phone
after the previous run, uploaded later, wins over the import.

Echo: the equipment written enter the snapshot with their new database values, so they are not written back
to the workbooks (unless the app changed them meanwhile).

Drive access: a workbook without change to write is downloaded only if its Drive version differs from the one
entirely imported; a run with nothing to write nor to import only reads the two versions and logs nothing.
A workbook just uploaded is compared at once, and downloaded again at the next run.

Refusals and errors: a change refused by the server (e.g. reference already used, 409) drops that equipment
(and what depends on it), logged, the rest is sent again; the workbook is then compared again at every run
until it is fixed. API unreachable or login refused: nothing more is sent, exit code 3, compared again at the
next run. The temporary ids sent to the API are never reused (`import.state.file`, saved before each upload).

First run with the import (no snapshot): every equipment of the database is written to the workbooks first,
then the import runs; it creates in the database every equipment of the workbooks it does not have.

## Test mode: changes file

`--changes <file.jsonl>`: no database nor snapshot; the lines are applied **in their order** (a value changed
twice keeps the last one; a deletion then a creation of the same reference marks the row then adds a new one).
This is also the internal format of the database mode. One JSON object per line. A cell change:

```json
{"sheetName":"Détendeur NSA", "idCol":"Identification", "idKey":"NSA DET-01", "editCol":"Etat et position au", "newVal":"Piscine"}
{"sheetName":"Stab NSA", "idCol":"Identification", "idKey":"NSA-XXS 01", "editCol":"Date de dernière révision", "newVal":"05/03/2026"}
```

An equipment deleted in the app (its row is kept and marked, never removed):

```json
{"op":"delete", "sheetName":"Détendeur NSA", "idCol":"Identification", "idKey":"NSA DET-05"}
```

| Field | Meaning |
|-------|---------|
| `op` | Optional: `delete` replaces the `idCol` cell with **Supprimé de la NSA-app :** then, on a second line of the same cell, the reference (e.g. `NSA DET-05`); otherwise a cell change. `editCol` and `newVal` are not used |
| `sheetName` | Sheet to edit (exact sheet name). It also selects the workbook: a name contained in `BLOCS` → tanks workbook, a name contained in `Détendeur NSA_Stab NSA` (e.g. `Détendeur NSA`, `Stab NSA`) → regulators/BCDs workbook (case-insensitive) |
| `idCol` | Title of the column identifying the equipment, looked up in the header row (case and whitespace ignored, whole title) |
| `idKey` | Equipment reference: the row whose `idCol` cell equals it **entirely** (case and spaces ignored: `NSA DET-1` never matches `NSA DET-12`) |
| `editCol` | Title of the column to change, in the same header row (case and whitespace ignored, whole title, e.g. `Etat et position au`) |
| `newVal` | New value, written with the type of its column (see below); empty clears the cell |

Rules:

- **Reference missing from the sheet**: a cell change adds a new row below the last filled one (formatted like it,
  following rows shifted down if that row is used); a `delete` is only logged.
- **Deleted equipment**: a marked row no longer matches its reference (the cell starts with
  `Supprimé de la NSA-app :`), so an equipment recreated with the same reference gets a new row. Marking is
  not blocked by the protected columns.
- **Reference found on several rows**, unknown column: the change is not applied and is logged.
- **State date**: in every sheet changed during the run, the first date on the row right under the titles
  (the date of "Etat et position au") is set to the current date (Europe/Paris), its display format kept.
- **Protected columns** (identification: see `protected.columns`): written only on a row added
  during the run; a change of such a column on an existing row is not applied and is logged.
- **Value types**, deduced from the equipment rows of the column: a column holding at least one real date takes
  dates sent as `JJ/MM/AAAA` (real dates, display format kept or taken from the cell above, `D/M/YYYY` by
  default); a column of numbers takes numbers (comma or dot); any other column stays text (serial numbers…).
  An invalid date or number is written as text with a warning.

Lines with missing fields are skipped with a warning showing the expected format.

## Build

```bash
mvn package
# -> target/GxlsxNSAstockDBconverter-1.0.2.jar (shaded, runnable, ~29 MB)
```

Use this jar: the IntelliJ artifact does not contain the Google and SQLite libraries (`NoClassDefFoundError`).

## Run

```bash
java -jar GxlsxNSAstockDBconverter-1.0.2.jar --conf /etc/NSA/GxlsxNSAstockDB_converter.conf     # database mode
java -jar GxlsxNSAstockDBconverter-1.0.2.jar --changes test/changes-test.jsonl --conf my.conf  # test mode
```

Options in any order. Each run logs one summary line when something was written
(`3 equipment changed, 7 cell(s); uploaded: …`) and one when something was imported
(`Import from the workbooks: 2 equipment created, 1 updated, 0 kept …, 0 refused by the server`);
a run without changes logs nothing.

| Exit code | Meaning |
|-----------|---------|
| `0` | Done: every workbook concerned was uploaded, or nothing to write, or first run (snapshot created) |
| `1` | Wrong arguments, configuration, database, snapshot or import state unreadable, import state not written, snapshot not written (the changes uploaded are applied again at the next run, which gives the same values), class loading error (`NoClassDefFoundError`) |
| `2` | Workbook not found on Drive (check `fileName.*`); the snapshot does not advance |
| `3` | Not every workbook uploaded: Drive unreachable, download/upload error, or a workbook still changed on Drive after 10 attempts. Database mode: the snapshot advanced for the others, the rest is retried at the next run. Import: API unreachable or login refused, compared again at the next run |

With `debug.copy` set (local tests only), each processed workbook is also written to that file before its
upload; as both workbooks use the same file, it holds the last one.

### Helper script

`run.sh`, for cron: runs the jar (`/opt/NSA/GxlsxNSAstockDBconverter-1.0.2.jar`) in database mode with
`/etc/NSA/GxlsxNSAstockDB_converter.conf`, from its own directory (relative paths of the configuration).

- Only one run at a time (lock `/var/lib/nsa/drive/converter.lock`): a run started while another is in
  progress logs `previous run still in progress: skipped` and stops (a line every 10 minutes reveals a stuck run).
- A non-zero exit code of the converter is logged and returned; nothing else to do, the next run catches up.

### Deployment (server)

```bash
# 1. directory of the snapshot and of the lock, for the cron user only
sudo install -d -o www-data -g www-data -m 700 /var/lib/nsa/drive
# 2. jar and script (built with mvn package)
sudo install -o root -g www-data -m 640 target/GxlsxNSAstockDBconverter-1.0.2.jar /opt/NSA/
sudo install -o root -g www-data -m 750 run.sh /opt/NSA/runGdrive.sh
# 3. configuration: db.file and snapshot.file (changes.file is no longer used); key and conf readable by www-data
C=/etc/NSA/GxlsxNSAstockDB_converter.conf
sudo sed -i '/^changes.file=/d;/^db.file=/d;/^snapshot.file=/d' $C
printf 'db.file=/var/lib/nsa/orgs/NSA.db\nsnapshot.file=/var/lib/nsa/drive/NSA.snapshot.json\n' | sudo tee -a $C
#    import (optional): api.url, api.email, api.password, import.state.file (see Configuration)
sudo chgrp www-data $C && sudo chmod 640 $C        # same for the service account key (apiKey)
# 4. first run by hand: creates the snapshot, writes nothing (with the import: writes every equipment, then imports)
sudo -u www-data /opt/NSA/runGdrive.sh; sudo -u www-data ls -l /var/lib/nsa/drive
# 5. cron of www-data
sudo crontab -u www-data -e   # */10 * * * * /opt/NSA/runGdrive.sh >> /var/log/nsa/gdrive.log 2>&1
```

The workbooks are only changed for what changes in the app **after** the first run: align them by hand
beforehand if needed.

### Build without Maven (IntelliJ)

The IntelliJ artifact (`.idea/artifacts/`) and `out.lib` (a `java -cp …` command line) use
plain jars from a local `lib/` directory. `lib/` is **not versioned**: fill it with the
dependencies above (e.g. `mvn dependency:copy-dependencies -DoutputDirectory=lib`) if you
need that workflow. Maven is the reference build.

## Project layout

```
src/main/java/GxlsxNSAstockDBconverter/
├── Main.java                 Entry point: arguments, database or test mode, download, apply, upload
├── Viewer.java               Debug display of a sheet
└── utils/
    ├── ConfigReader.java     Reads GxlsxNSAstockDB_converter.conf
    ├── StockDb.java          Reads the NSA-app database, values of each equipment in the workbooks
    ├── Snapshot.java         Snapshot file, changes since the last run, snapshot advance
    ├── WorkbookImport.java   Import: workbook rows, differences with the database, changes sent to the API
    ├── NsaApi.java           NSA-app API client (login, changes)
    ├── ImportState.java      State of the import (device, temporary ids, token, last run, Drive versions)
    ├── GglApiLayer.java      Drive access (service account, 3 retries, list/download/update)
    ├── Spreadsheet.java      POI workbook wrapper, editSheet(), markDeleted()
    └── Logger.java           Console logger (colored INFO/WARN/ERROR with file:method:line)
src/META-INF/MANIFEST.MF      Main class for the IntelliJ artifact
test/TestPOI.java             Manual POI check (not a unit test, does not compile as is)
test/changes-test.jsonl, .md  Test mode file covering every rule, with the expected results
run.sh                        Script run by cron
out.lib                       Classpath command line for the non-Maven build
```

Not versioned (`.gitignore`): configuration and keys, `tokens/`, `target/`, `out/`, `lib/`,
`debug.xlsx`, `test/debug.*`.

## Known limitations

- The package is named `main.java.GxlsxNSAstockDBconverter` instead of following the Maven
  layout (`GxlsxNSAstockDBconverter`).
- Import: a reference changed by hand in a workbook cannot be told from a new row: a new equipment is created,
  the old one stays in the database (its next change in the app adds its row back to the workbook).
- The Drive version is checked just before the upload: a manual change made in the very few milliseconds
  between this check and the upload would still be overwritten.

## Related projects

- NSA web site & API: `~/sites/projects/NSA`
- NSA-app (Android): `~/AndroidStudioProjects/NSAapp`
