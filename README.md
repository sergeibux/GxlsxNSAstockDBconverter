# GxlsxNSAstockDB_converter

Command-line Java tool of the **NSA-app** ecosystem (equipment tracking for the NSA diving
club). It keeps the club's historical **Google Drive spreadsheets** (`.xlsx` inventories of
tanks, regulators and BCDs) in line with the changes made in the NSA database:

1. connects to Google Drive with a **service account**;
2. downloads each inventory workbook targeted by at least one change;
3. applies the changes read from a file (one JSON object per line);
4. uploads the modified workbook back to Drive (same file id, new revision), unless someone changed it on
   Drive meanwhile: then it is downloaded and processed again, up to 10 times (manual changes are never
   overwritten); after 10 attempts the changes are left for the next run.

---

## Requirements

- **JDK 11** or newer (compiled for Java 11; runs on 17/21)
- **Maven 3.6+** (tested with 3.9.9)
- A **Google Cloud project** with the **Google Drive API** enabled and a **service account**
  with a JSON key
- The two workbooks **shared with the service account e-mail** (*Editor* role), stored as
  real `.xlsx` files on Drive, not native Google Sheets (the tool downloads the binary file
  with `files.get(…).executeMediaAndDownloadTo`)
- Network access to `www.googleapis.com`

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

Build plugin: `maven-shade-plugin` 3.4.1 (fat jar, main class
`main.java.GxlsxNSAstockDBconverter.Main`).

## Configuration

`GxlsxNSAstockDB_converter.conf` (Java properties, UTF-8), read from the **working directory**:

```properties
# Part of the Drive file names to look for (case-insensitive "contains")
fileName.blocs=Liste de blocs
fileName.det_et_stabs=Liste des Détendeurs et Stab du NSA
# Path to the service account JSON key (relative to the working directory or absolute)
apiKey=/path/to/service-account-key.json
# Changes file processed by run.sh (optional, read by run.sh only)
changes.file=/opt/NSA/changes.jsonl
# Columns written only when a row is created, ";"-separated (optional: this is the default list)
#protected.columns=Identification;Identification Bloc;Type;Type de Blocs
# Local tests only: copy of each workbook written before its upload (leave unset on the server)
#debug.copy=debug.xlsx
```

| Key | Meaning |
|-----|---------|
| `fileName.blocs` | Tank inventory workbook (sheet `BLOCS`) |
| `fileName.det_et_stabs` | Regulators / BCDs workbook (sheets `Détendeur NSA`, `Stab NSA`) |
| `apiKey` | Path of the service account JSON key file |
| `protected.columns` | Columns written only when the row is created (reference unknown to the sheet), `;`-separated. Absent: `Identification;Identification Bloc;Type;Type de Blocs` (defined in the code). Present but empty: no protected column |
| `debug.copy` | **Local tests only**: file where a copy of each workbook is written before its upload. Unset (the default): no copy. Never set it on the server: the copy holds the club's data |
| `changes.file` | Changes file processed by `run.sh` (default `/opt/NSA/changes.jsonl`); `run.sh` reads it from `/etc/NSA/GxlsxNSAstockDB_converter.conf` |

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

## Input file

One JSON object per line. A cell change:

```json
{"sheetName":"Détendeur NSA", "idCol":"Identification", "idKey":"NSA DET-01", "editCol":"Etat et position au", "newVal":"Piscine"}
{"sheetName":"Stab NSA", "idCol":"Identification", "idKey":"NSA-XXS 01", "editCol":"Date de dernière révision", "newVal":"05/03/2026"}
```

An equipment deleted in the app (its row is kept and marked, never removed):

```json
{"op":"delete", "sheetName":"Détendeur NSA", "idCol":"Identification", "idKey":"NSA DET-05", "editCol":"Etat et position au"}
```

| Field | Meaning |
|-------|---------|
| `op` | Optional: `delete` marks the row with **Supprimé de la NSA-app** in `editCol`; otherwise a cell change |
| `sheetName` | Sheet to edit (exact sheet name). It also selects the workbook: a name contained in `BLOCS` → tanks workbook, a name contained in `Détendeur NSA_Stab NSA` (e.g. `Détendeur NSA`, `Stab NSA`) → regulators/BCDs workbook (case-insensitive) |
| `idCol` | Title of the column identifying the equipment, looked up in the header row (case and whitespace ignored, whole title) |
| `idKey` | Equipment reference: the row whose `idCol` cell equals it **entirely** (case and spaces ignored: `NSA DET-1` never matches `NSA DET-12`) |
| `editCol` | Title of the column to change, in the same header row (case and whitespace ignored, whole title, e.g. `Etat et position au`) |
| `newVal` | New value, written with the type of its column (see below); empty clears the cell |

Rules:

- **Reference missing from the sheet**: a cell change adds a new row below the last filled one (formatted like it,
  following rows shifted down if that row is used); a `delete` is only logged.
- **Reference found on several rows**, unknown column: the change is not applied and is logged.
- **State date**: in every sheet changed during the run, the first date on the row right under the titles
  (the date of "Etat et position au") is set to the current date (Europe/Paris), its display format kept.
- **Protected columns** (identification, characteristic: see `protected.columns`): written only on a row added
  during the run; a change of such a column on an existing row is not applied and is logged.
- **Value types**, deduced from the equipment rows of the column: a column holding at least one real date takes
  dates sent as `JJ/MM/AAAA` (real dates, display format kept or taken from the cell above, `D/M/YYYY` by
  default); a column of numbers takes numbers (comma or dot); any other column stays text (serial numbers…).
  An invalid date or number is written as text with a warning.

Lines with missing fields are skipped with a warning showing the expected format.

## Build

```bash
mvn package
# -> target/GxlsxNSAstockDBconverter-1.0-SNAPSHOT.jar (shaded, runnable)
```

## Run

From the directory that holds `GxlsxNSAstockDB_converter.conf`:

```bash
java -jar target/GxlsxNSAstockDBconverter-1.0-SNAPSHOT.jar /path/to/changes.jsonl
```

Exactly one argument is required (the change file).

| Exit code | Meaning |
|-----------|---------|
| `0` | Every workbook concerned by the changes was uploaded (or no change concerned it) |
| `1` | Wrong number of arguments, or class loading error (`NoClassDefFoundError`) |
| `2` | Workbook not found on Drive (check `fileName.*`) |
| `3` | Changes not (all) uploaded: Drive unreachable, download/upload error, or a workbook still changed on Drive after 10 attempts. Run again later with the same file (applying a change twice gives the same result) |

With `debug.copy` set (local tests only), each processed workbook is also written to that file before its
upload; as both workbooks use the same file, it holds the last one.

### Helper script

`run.sh` processes the changes file (`changes.file`, default `/opt/NSA/changes.jsonl`), for cron:

```bash
./run.sh            # run the existing jar
./run.sh compile    # mvn package, then run
```

- The file is renamed before being read: changes written by the server during the run go to a new file.
  A writer must hold an exclusive lock on `<changes file>.lock` while appending (`flock`).
- Exit code `0`: the processed changes are deleted. Any other code: they are kept in
  `<changes file>.processing` and replayed first at the next run, before the newer ones.
- Only one run at a time (lock `<changes file>.run.lock`): a run started while another is in progress
  stops immediately.

### Build without Maven (IntelliJ)

The IntelliJ artifact (`.idea/artifacts/`) and `out.lib` (a `java -cp …` command line) use
plain jars from a local `lib/` directory. `lib/` is **not versioned**: fill it with the
dependencies above (e.g. `mvn dependency:copy-dependencies -DoutputDirectory=lib`) if you
need that workflow. Maven is the reference build.

## Project layout

```
src/main/java/GxlsxNSAstockDBconverter/
├── Main.java                 Entry point: config, download, apply changes, upload
├── Viewer.java               Debug display of a sheet
└── utils/
    ├── ConfigReader.java     Reads GxlsxNSAstockDB_converter.conf
    ├── GglApiLayer.java      Drive access (service account, 3 retries, list/download/update)
    ├── Spreadsheet.java      POI workbook wrapper, editSheet()
    └── Logger.java           Console logger (colored INFO/WARN/ERROR with file:method:line)
src/META-INF/MANIFEST.MF      Main class for the IntelliJ artifact
test/TestPOI.java             Manual POI check (not a unit test, does not compile as is)
run.sh                        Helper script
out.lib                       Classpath command line for the non-Maven build
```

Not versioned (`.gitignore`): configuration and keys, `tokens/`, `target/`, `out/`, `lib/`,
`debug.xlsx`, `test/debug.*`.

## Known limitations

- `pom.xml` declares `maven-shade-plugin` twice (only the second one sets the main class).
- The package is named `main.java.GxlsxNSAstockDBconverter` instead of following the Maven
  layout (`GxlsxNSAstockDBconverter`).
- The Drive version is checked just before the upload: a manual change made in the very few milliseconds
  between this check and the upload would still be overwritten.

## Related projects

- NSA web site & API: `~/sites/projects/NSA`
- NSA-app (Android): `~/AndroidStudioProjects/NSAapp`
