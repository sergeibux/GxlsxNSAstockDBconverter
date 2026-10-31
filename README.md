# GxlsxNSAstockDB_converter

Command-line Java tool of the **NSA-app** ecosystem (equipment tracking for the NSA diving
club). It keeps the club's historical **Google Drive spreadsheets** (`.xlsx` inventories of
tanks, regulators and BCDs) in line with the changes made in the NSA database:

1. connects to Google Drive with a **service account**;
2. downloads the two inventory workbooks;
3. applies a list of cell changes read from a file (one JSON object per line);
4. uploads the modified workbooks back to Drive (same file id, new revision).

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
```

| Key | Meaning |
|-----|---------|
| `fileName.blocs` | Tank inventory workbook (sheet `BLOCS`) |
| `fileName.det_et_stabs` | Regulators / BCDs workbook (sheets `Détendeur NSA`, `Stab NSA`) |
| `apiKey` | Path of the service account JSON key file |

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

One JSON object per line, each describing one cell to change:

```json
{"sheetName":"BLOCS", "idCol":"Numéro", "idKey":"B12", "editCol":"Statut", "newVal":"à contrôler"}
{"sheetName":"Stab NSA", "idCol":"Référence", "idKey":"S-04", "editCol":"Local", "newVal":"Cabane 2"}
```

| Field | Meaning |
|-------|---------|
| `sheetName` | Sheet to edit (exact sheet name). It also selects the workbook: a name contained in `BLOCS` → tanks workbook, a name contained in `Détendeur NSA_Stab NSA` (e.g. `Détendeur NSA`, `Stab NSA`) → regulators/BCDs workbook (case-insensitive) |
| `idCol` | Header of the column identifying the equipment (case-insensitive, exact) |
| `idKey` | Value searched in `idCol` (case-insensitive, **"contains"**) – the first matching row is edited |
| `editCol` | Header of the column to change (case-insensitive, exact) |
| `newVal` | New cell value (written as a string) |

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
| `0` | Success – but also when a workbook cannot be downloaded ("nothing to do") or when an exception is caught and logged |
| `1` | Wrong number of arguments, or class loading error (`NoClassDefFoundError`) |
| `2` | Workbook not found on Drive (check `fileName.*`) |

Each processed workbook is also written to `debug.xlsx` in the working directory before
upload; as both workbooks use the same name, the file holds the last one.

### Helper script

`run.sh` expects the change file at `/tmp/changed_records.nsa`:

```bash
./run.sh            # run the existing jar
./run.sh compile    # mvn package, then run
```

It renames `/tmp/changed_records.nsa` with a timestamp during the run and puts it back
afterwards (test mode: the file is not deleted).

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
- `Spreadsheet.editSheet`:
  - an `idCol` / `editCol` header that is missing, or located in column A, falls back to
    column A;
  - an empty target cell (`row.getCell(...) == null`) raises a `NullPointerException`;
  - `idKey` is matched with "contains": `B1` also matches `B12`.
- If the Drive connection fails, `Main.prepare` returns `null` and the run fails later.
- Exit code `0` does not guarantee that the workbooks were updated (see the table above).

## Related projects

- NSA web site & API: `~/sites/projects/NSA`
- NSA-app (Android): `~/AndroidStudioProjects/NSAapp`
