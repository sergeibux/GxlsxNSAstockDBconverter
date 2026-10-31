package main.java.GxlsxNSAstockDBconverter.utils;

import main.java.GxlsxNSAstockDBconverter.Viewer;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

public class Spreadsheet {
    /** Cell text as displayed in the spreadsheet (numbers and dates included). */
    private static final DataFormatter FORMATTER = new DataFormatter();

    private ByteArrayOutputStream baos;
    private String fileName;
    private Workbook workbook;

    public Spreadsheet(String fileName, ByteArrayOutputStream baos) throws IOException {
        this.fileName = fileName;
        this.baos = baos;

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
        ByteArrayOutputStream modifiedOutputStream;
        Sheet sheet = workbook.getSheet(sheetName);
        if (sheet == null) {
            Logger.ERROR("Cannot find sheet name:" + sheetName);
            return;
        }
        Logger.LOG("looking for \"" + id + "\" in \"" + idColName + "\" to be replaced by: " + newValue);

        // Header row: the first one holding the id column title; the edited column is looked up in the same row
        Row header = null;
        int idCol = -1;
        int editCol = -1;
        for (Row row : sheet) {
            idCol = findColumn(row, idColName);
            if (idCol >= 0) {
                header = row;
                editCol = findColumn(row, editColName);
                break;
            }
        }
        if (header == null) {
            Logger.ERROR("Column \"" + idColName + "\" not found in sheet " + sheetName + ": change not applied");
            return;
        }
        if (editCol < 0) {
            Logger.ERROR("Column \"" + editColName + "\" not found in sheet " + sheetName + ": change not applied");
            return;
        }

        String key = normalize(id);
        Row target = null;
        int matches = 0;
        int lastFilledRow = header.getRowNum();
        for (int r = header.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            String value = (row == null) ? "" : FORMATTER.formatCellValue(row.getCell(idCol));
            if (value.isBlank())
                continue;
            lastFilledRow = r;
            if (normalize(value).equals(key)) {
                target = row;
                matches++;
            }
        }
        if (matches > 1) {
            Logger.ERROR("\"" + id + "\" found on " + matches + " rows of sheet " + sheetName + ": change not applied");
            return;
        }
        if (target == null) {
            target = appendRow(sheet, lastFilledRow);
            target.getCell(idCol, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue(id);
            Logger.LOG("\"" + id + "\" not found: added on row " + (target.getRowNum() + 1));
        }

        Cell cell = target.getCell(editCol, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
        Logger.LOG("Change value " + FORMATTER.formatCellValue(cell) + " to " + newValue);
        cell.setCellValue(newValue);

        modifiedOutputStream = new ByteArrayOutputStream();
        try {
            workbook.write(modifiedOutputStream);
            setBaos(modifiedOutputStream);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
//
//            Sheet sheet = workbook.getSheet(sheetName);


//        try {
//            baos.flush();
//            workbook.write(baos);
//        } catch (IOException e) {
//            throw new RuntimeException(e);
//        }
    }

    /** Comparison form of an identifier or a column title: case and whitespace (spaces, line breaks) ignored. */
    static String normalize(String value) {
        return value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
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
