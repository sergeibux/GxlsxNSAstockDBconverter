package main.java.GxlsxNSAstockDBconverter.utils;

import main.java.GxlsxNSAstockDBconverter.Viewer;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class Spreadsheet {

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
     * Edit cell from specified sheet
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
        if (sheet != null) {
            int colCellToEdit = 0;
            int colCellToSearch = 0;
            Logger.LOG("looking for \"" + id + "\" in \"" + idColName + "\" to be replaced by: " + newValue);
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (cell == null)
                        break;

                    if (cell.toString().equalsIgnoreCase(idColName))
                        Logger.LOG("Found \"" + idColName + "\" at row,col idx: " + cell.getRowIndex() + "," + cell.getColumnIndex());
                    else if (cell.toString().equalsIgnoreCase(editColName))
                        Logger.LOG("Found \"" + editColName + "\" at row,col idx: " + cell.getRowIndex() + "," + cell.getColumnIndex());

                    if (cell.toString().equalsIgnoreCase(idColName))
                        colCellToSearch = cell.getColumnIndex();
                    else if (cell.toString().equalsIgnoreCase(editColName))
                        colCellToEdit = cell.getColumnIndex();

                    if (colCellToEdit > 0 && colCellToSearch > 0)
                        break;
                }

                if (row.getCell(colCellToSearch) != null /*&& cell.getCellType() == CellType.STRING*/) {
                    if (row.getCell(colCellToSearch).toString().toUpperCase().contains(id.toUpperCase())) {
                        Cell cell = row.getCell(colCellToEdit);
                        Logger.LOG("Change value " + cell.toString() + " to " + newValue);
                        cell.setCellValue(newValue);
                        break;
                    }
                }
            }
        } else {
            Logger.ERROR("Cannot find sheet name:" + sheetName);
        }

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
