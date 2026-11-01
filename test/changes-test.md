# Test changes file

`changes-test.jsonl` exercises every rule of the converter on the **copies** of the workbooks
("Copie de Liste de blocs V6", "Copie de Liste des Détendeurs et Stab du NSA"). Never run it against the
real workbooks: it changes their content.

```bash
java -jar target/GxlsxNSAstockDBconverter-*.jar test/changes-test.jsonl
# or through run.sh: copy it to the changes file first
cp test/changes-test.jsonl /opt/NSA/changes.jsonl && ./run.sh
```

Expected result (exit code 0):

| # | Line | Expected |
|---|------|----------|
| 1 | NSA DET-02 › Etat et position au = Piscine | written |
| 2-3 | NSA DET-03 › révision dates | real dates 15/10/2026 and 15/10/2027, date format kept |
| 4 | NSA DET-04 › Etat = Prêt en cours à : Jean Test | written |
| 5 | `nsa det-44` (sheet: `NSA DET- 44`) › Etat = En révision | row found (case and spaces ignored) |
| 6 | NSA DET-06 › 31/02/2026 | written **as text**, warning in the log |
| 7 | NSA DET-02 › Type | **not written** (protected column on an existing row), warning |
| 8 | NSA DET-02 › Colonne inconnue | **not written**, error in the log |
| 9-12 | NSA DET-TEST-01 (unknown) | **new row** below the last one: state, type (allowed on a new row), two real dates |
| 13 | delete NSA DET-05 | Identification = **Supprimé de la NSA-app :** / `NSA DET-05` (two lines, wrapped), other cells and row kept |
| 14 | delete NSA DET-99 (unknown) | nothing written, warning, no row added |
| 15 | NSA-XXS 01 › Affectation initiale = Milieu naturel | written |
| 16-17 | NSA-XXS 02 › révision dates | real dates 20/09/2026 and 20/09/2027 |
| 18-19 | NSA B12-01 › Etat = Non disponible, Date de dernière inspection visuelle | written, real date |
| 20 | NSA B12-02 › Type de Blocs | **not written** (protected column), warning |
| 21 | not JSON | skipped, the expected format is printed |

Also: the date under "Etat et position au" of the three sheets (Détendeur NSA, Stab NSA, BLOCS) is set to the
day of the run; the other sheets are untouched.

To undo: restore the previous revision of each copy from the Drive history (File › Version history).
