package main.java.GxlsxNSAstockDBconverter;

import main.java.GxlsxNSAstockDBconverter.utils.Logger;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

public class Viewer {
    public void displayData(Sheet sheet) {
        for (Row row : sheet) {
            for (Cell cell : row) {
                if (cell.getCellType() != CellType.BLANK) {
                    String content = cell.toString().replaceAll("\n", " ");
                    Logger.LOG(content + "\t|\t");
                }
            }
        }
    }
}
