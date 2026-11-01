package main.java.GxlsxNSAstockDBconverter;

import smv_logger.src.Logger.Logger;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

public class Viewer {
    private static final Logger LOGGER = Logger.getInstance();
    public void displayData(Sheet sheet) {
        for (Row row : sheet) {
            for (Cell cell : row) {
                if (cell.getCellType() != CellType.BLANK) {
                    String content = cell.toString().replaceAll("\n", " ");
                    LOGGER.LOG(content + "\t|\t", Logger.LEVEL.INFO);
                }
            }
        }
    }
}
