package main.java.GxlsxNSAstockDBconverter.utils;

import main.java.GxlsxNSAstockDBconverter.Viewer;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class Spreadsheet {
    /** Cell text as displayed in the spreadsheet (numbers and dates included). */
    private static final DataFormatter FORMATTER = new DataFormatter();
    /** Dates sent in the changes file: JJ/MM/AAAA (leading zeros optional), impossible dates refused. */
    private static final DateTimeFormatter INPUT_DATE = DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final String DEFAULT_DATE_FORMAT = "D/M/YYYY";
    /**
     * Written in the state column of an equipment deleted in the app: the row is kept (the spreadsheet holds data
     * the app does not have) and a human removes it later. An import from the spreadsheets must skip such rows.
     */
    public static final String DELETED_MARK = "Supprimé de la NSA-app :";

    /** Kind of values held by a column, deduced from its cells (the changes file only carries text). */
    private enum ColumnKind { TEXT, NUMBER, DATE }

    /**
     * Columns written only when the row is created (reference unknown to the sheet): the identification of an
     * equipment never changes afterwards (a new reference is a new row). Replaced by the protected.columns key
     * if it exists. The characteristic is written: the import from the workbooks compares it with the database.
     */
    public static final List<String> DEFAULT_PROTECTED_COLUMNS = List.of("Identification", "Identification Bloc");

    private CellStyle defaultDateStyle;
    private final Set<String> protectedColumns = new HashSet<>();
    /** Rows added during this run (sheet + reference): their protected columns can still be written. */
    private final Set<String> createdRows = new HashSet<>();
    /** Sheets changed during this run, with the index of their header row (for stampStateDate). */
    private final Map<String, Integer> changedSheets = new LinkedHashMap<>();

    private ByteArrayOutputStream baos;
    private String fileName;
    private Workbook workbook;

    public Spreadsheet(String fileName, ByteArrayOutputStream baos) throws IOException {
        this.fileName = fileName;
        this.baos = baos;
        setProtectedColumns(DEFAULT_PROTECTED_COLUMNS);

        ByteArrayInputStream inputStream = new ByteArrayInputStream(baos.toByteArray());
        if (fileName.endsWith(".xlsx")) {

            try {
                Class<?> zipArchiveInputStreamClass = Class.forName("org.apache.commons.compress.archivers.zip.ZipArchiveInputStream");
                Logger.LOG("ZipArchiveInputStream loaded from: " +
                        zipArchiveInputStreamClass.getProtectionDomain().getCodeSource().getLocation());
            } catch (ClassNotFoundException e) {
                Logger.ERROR_printMsg("ZipArchiveInputStream not found.", e.getMessage());
            }
            workbook = new XSSFWorkbook(inputStream);
        } else if (fileName.endsWith(".xls")) {
            workbook = new HSSFWorkbook(inputStream);
        } else {
            throw new IllegalArgumentException("File format not supported: " + fileName);
        }
    }

    public void sheetToString(String sheetName) {
        Viewer viewer = new Viewer();
        viewer.displayData(workbook.getSheet(sheetName));
    }

    /**
     * Edit cell from specified sheet.
     * The row is the one whose id cell equals the key (whole value, case and spaces ignored: "DET-1" never
     * matches "DET-12"). A key found on no row is added on a new row below the last filled one;
     * a key found on several rows is not applied.
     *
     * @param sheetName - name of the sheet to be edited
     * @param idColName - content of the column title used to find the reference key value (used to find the row)
     * @param id - key value to find to identify the row in the column referenced by title as in idColName
     * @param editColName - content of the column title used to change cell value
     * @param newValue - new value of the cell identified by col==editColNameIdx, (row identified by id, idColName)
     */
    public void editSheet (String sheetName, String idColName, String id, String editColName, String newValue) {
        applyEdit(sheetName, idColName, id, editColName, newValue);
    }

    /**
     * Equipment deleted in the app: its row is kept and its id cell becomes
     * "Supprimé de la NSA-app :" + line break + the reference (wrapped text, so both lines show).
     * An id missing from the sheet is only logged (no row is added). A marked row no longer matches its
     * reference: an equipment recreated with the same reference gets a new row.
     */
    public void markDeleted(String sheetName, String idColName, String id) {
        Sheet sheet = workbook.getSheet(sheetName);
        if (sheet == null) {
            Logger.ERROR("Cannot find sheet name:" + sheetName);
            return;
        }
        RowMatch match = findRow(sheet, idColName, id);
        if (match == null)
            return;
        if (match.target == null) {
            Logger.WARN("\"" + id + "\" not found in sheet " + sheetName + ": nothing to mark");
            return;
        }
        Cell cell = match.target.getCell(match.idCol, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
        String reference = FORMATTER.formatCellValue(cell);
        CellStyle wrapped = workbook.createCellStyle();
        wrapped.cloneStyleFrom(cell.getCellStyle());
        wrapped.setWrapText(true);
        cell.setCellValue(DELETED_MARK + "\n" + reference);
        cell.setCellStyle(wrapped);
        Logger.LOG("\"" + reference + "\" marked as deleted on row " + (match.target.getRowNum() + 1));
        changedSheets.put(sheetName, match.header.getRowNum());
        rewrite();
    }

    /** Header row, id column and row of an id: null (error logged) if the column or a single row cannot be told. */
    private static final class RowMatch {
        Row header;
        int idCol;
        Row target;            // null if the id is not in the sheet
        int lastFilledRow;     // last row with an id, where a new row is appended after
    }

    private RowMatch findRow(Sheet sheet, String idColName, String id) {
        // Header row: the first one holding the id column title
        RowMatch match = new RowMatch();
        for (Row row : sheet) {
            match.idCol = findColumn(row, idColName);
            if (match.idCol >= 0) {
                match.header = row;
                break;
            }
        }
        if (match.header == null) {
            Logger.ERROR("Column \"" + idColName + "\" not found in sheet " + sheet.getSheetName() + ": change not applied");
            return null;
        }

        String key = normalize(id);
        int matches = 0;
        match.lastFilledRow = match.header.getRowNum();
        for (int r = match.header.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            String value = (row == null) ? "" : FORMATTER.formatCellValue(row.getCell(match.idCol));
            if (value.isBlank())
                continue;
            match.lastFilledRow = r;
            if (normalize(value).equals(key)) {
                match.target = row;
                matches++;
            }
        }
        if (matches > 1) {
            Logger.ERROR("\"" + id + "\" found on " + matches + " rows of sheet " + sheet.getSheetName() + ": change not applied");
            return null;
        }
        return match;
    }

    private void applyEdit(String sheetName, String idColName, String id, String editColName, String newValue) {
        Sheet sheet = workbook.getSheet(sheetName);
        if (sheet == null) {
            Logger.ERROR("Cannot find sheet name:" + sheetName);
            return;
        }
        Logger.LOG("looking for \"" + id + "\" in \"" + idColName + "\" to be replaced by: " + newValue);

        RowMatch match = findRow(sheet, idColName, id);
        if (match == null)
            return;
        Row header = match.header;
        int idCol = match.idCol;
        int editCol = findColumn(header, editColName);
        if (editCol < 0) {
            Logger.ERROR("Column \"" + editColName + "\" not found in sheet " + sheetName + ": change not applied");
            return;
        }

        String key = normalize(id);
        Row target = match.target;
        String rowKey = sheetName + "\u0000" + key;
        if (target == null) {
            target = appendRow(sheet, match.lastFilledRow);
            target.getCell(idCol, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue(id);
            createdRows.add(rowKey);
            Logger.LOG("\"" + id + "\" not found: added on row " + (target.getRowNum() + 1));
        } else if (protectedColumns.contains(normalize(editColName)) && !createdRows.contains(rowKey)) {
            Logger.WARN("Column \"" + editColName + "\" of \"" + id + "\" is only written when the row is created: change not applied");
            return;
        }

        Cell cell = target.getCell(editCol, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
        Logger.LOG("Change value " + FORMATTER.formatCellValue(cell) + " to " + newValue);
        writeValue(sheet, cell, columnKind(sheet, editCol, idCol, header.getRowNum()), newValue);
        changedSheets.put(sheetName, header.getRowNum());
        rewrite();
    }

    /** Serializes the workbook into baos, the content uploaded at the end of the run. */
    private void rewrite() {
        ByteArrayOutputStream modifiedOutputStream = new ByteArrayOutputStream();
        try {
            workbook.write(modifiedOutputStream);
            setBaos(modifiedOutputStream);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Sets the date of the state ("Etat et position au …") of every sheet changed during this run: the first cell
     * with a date format on the row right under the titles. Its display format is kept.
     */
    public void stampStateDate(LocalDate date) {
        if (changedSheets.isEmpty())
            return;
        for (Map.Entry<String, Integer> changed : changedSheets.entrySet()) {
            Row row = workbook.getSheet(changed.getKey()).getRow(changed.getValue() + 1);
            Cell dateCell = null;
            if (row != null)
                for (Cell cell : row)
                    if (cell.getCellType() == CellType.NUMERIC && hasDateFormat(cell)) {
                        dateCell = cell;
                        break;
                    }
            if (dateCell == null) {
                Logger.WARN("No state date under the titles of sheet " + changed.getKey() + ": not updated");
                continue;
            }
            Logger.LOG("State date of sheet " + changed.getKey() + ": " + FORMATTER.formatCellValue(dateCell) + " -> " + date);
            dateCell.setCellValue(date);
        }
        rewrite();
    }

    /** Replaces the protected columns (titles compared like the headers: case and whitespace ignored). */
    public void setProtectedColumns(Collection<String> titles) {
        protectedColumns.clear();
        for (String title : titles)
            protectedColumns.add(normalize(title));
    }

    /**
     * A column holding at least one real date is a date column; one holding numbers but no date, a number
     * column; a column with no value but empty cells formatted for dates is a date column; any other column
     * (serial numbers stored as text…) stays text. Only equipment rows count (id cell
     * holding text): the rows right under the titles may hold dates belonging to the header ("Etat et position au").
     */
    private static ColumnKind columnKind(Sheet sheet, int col, int idCol, int headerRow) {
        boolean numbers = false;
        boolean dateFormats = false;
        for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            Cell id = (row == null) ? null : row.getCell(idCol);
            if (id == null || id.getCellType() != CellType.STRING || id.getStringCellValue().isBlank())
                continue;
            Cell cell = row.getCell(col);
            if (cell == null)
                continue;
            if (cell.getCellType() != CellType.NUMERIC) {
                dateFormats |= cell.getCellType() == CellType.BLANK && hasDateFormat(cell);
                continue;
            }
            if (DateUtil.isCellDateFormatted(cell))
                return ColumnKind.DATE;
            numbers = true;
        }
        if (numbers)
            return ColumnKind.NUMBER;
        // column still empty but formatted for dates (e.g. "Date de prochaine révision" never filled yet)
        return dateFormats ? ColumnKind.DATE : ColumnKind.TEXT;
    }

    /**
     * Writes the value with the type of its column: a real date (JJ/MM/AAAA) or number, so that sorting,
     * filters and formulas keep working. An invalid value is written as text with a warning; an empty one
     * clears the cell.
     */
    private void writeValue(Sheet sheet, Cell cell, ColumnKind kind, String value) {
        String text = (value == null) ? "" : value.trim();
        if (text.isEmpty()) {
            cell.setBlank();
            return;
        }
        if (kind == ColumnKind.DATE) {
            try {
                LocalDate date = LocalDate.parse(text, INPUT_DATE);
                if (!hasDateFormat(cell))
                    cell.setCellStyle(dateStyleFor(sheet, cell));
                cell.setCellValue(date);
                return;
            } catch (DateTimeParseException e) {
                Logger.WARN("\"" + text + "\" is not a JJ/MM/AAAA date (column of dates): written as text");
            }
        } else if (kind == ColumnKind.NUMBER) {
            try {
                cell.setCellValue(Double.parseDouble(text.replace(',', '.').replaceAll("\\s+", "")));
                return;
            } catch (NumberFormatException e) {
                Logger.WARN("\"" + text + "\" is not a number (column of numbers): written as text");
            }
        }
        cell.setCellValue(text);
    }

    /** True if the cell's display format is a date one, whatever it holds (text cells included). */
    private static boolean hasDateFormat(Cell cell) {
        CellStyle style = cell.getCellStyle();
        return style != null && DateUtil.isADateFormat(style.getDataFormat(), style.getDataFormatString());
    }

    /** Date format of the same column on the row above, D/M/YYYY if it has none. */
    private CellStyle dateStyleFor(Sheet sheet, Cell cell) {
        Row above = sheet.getRow(cell.getRowIndex() - 1);
        Cell model = (above == null) ? null : above.getCell(cell.getColumnIndex());
        if (model != null && hasDateFormat(model))
            return model.getCellStyle();
        if (defaultDateStyle == null) {
            defaultDateStyle = workbook.createCellStyle();
            defaultDateStyle.cloneStyleFrom(cell.getCellStyle());
            defaultDateStyle.setDataFormat(workbook.createDataFormat().getFormat(DEFAULT_DATE_FORMAT));
        }
        return defaultDateStyle;
    }

    /**
     * Comparison form of an identifier or a column title: case and whitespace ignored, Unicode spaces included
     * (non-breaking spaces pasted from other software), like the API does (preg_replace('/\\s+/u', …)).
     */
    static String normalize(String value) {
        return value.replaceAll("(?U)\\s+", "").toUpperCase(Locale.ROOT);
    }

    /** @return index of the cell of this row whose text is the given title, -1 if none */
    private static int findColumn(Row row, String title) {
        String wanted = normalize(title);
        for (Cell cell : row)
            if (normalize(FORMATTER.formatCellValue(cell)).equals(wanted))
                return cell.getColumnIndex();
        return -1;
    }

    /**
     * New row just below the last filled one, formatted like it. If that row already holds something
     * (note, total…), the rows below are shifted down instead of being overwritten.
     */
    private static Row appendRow(Sheet sheet, int lastFilledRow) {
        int index = lastFilledRow + 1;
        Row existing = sheet.getRow(index);
        boolean used = false;
        if (existing != null)
            for (Cell cell : existing)
                if (!FORMATTER.formatCellValue(cell).isBlank()) {
                    used = true;
                    break;
                }
        if (used)
            sheet.shiftRows(index, sheet.getLastRowNum(), 1);
        Row row = (used || existing == null) ? sheet.createRow(index) : existing;
        Row model = sheet.getRow(lastFilledRow);
        if (model != null) {
            row.setHeight(model.getHeight());
            for (Cell source : model)
                row.getCell(source.getColumnIndex(), Row.MissingCellPolicy.CREATE_NULL_AS_BLANK)
                        .setCellStyle(source.getCellStyle());
        }
        return row;
    }

    public ByteArrayOutputStream getBaos() {
        return baos;
    }

    public void setBaos(ByteArrayOutputStream baos) {
        this.baos = baos;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Workbook getWorkbook() {
        return workbook;
    }

    public void setWorkbook(Workbook workbook) {
        this.workbook = workbook;
    }

    public void writeWorkbook(FileOutputStream fos) throws IOException {
        workbook.write(fos);
//        try {
//            ZipOutputStream zos = new ZipOutputStream(fos);
//            zos.setLevel(Deflater.BEST_COMPRESSION);
//            workbook.write(zos);
//        } catch (Exception e) {
//            Logger.ERROR_printMsg("Unable to save workbook. Ignore.", e.getMessage());
//        }
//TODO -- removed for tests !        workbook.close();
    }
}
