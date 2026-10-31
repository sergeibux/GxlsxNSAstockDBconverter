import org.apache.poi.xssf.usermodel.HSSFWorkbook;
import java.io.FileInputStream;

public class TestPOI {
    public static void main(String[] args) {
        try {
            FileInputStream fis = new FileInputStream("test/debug.xlsx");
            HSSFWorkbook workbook = new HSSFWorkbook(fis);
            System.out.println("Fichier Excel ouvert avec succès !");
            workbook.close();
            fis.close();
        } catch (Exception e) {
            ERR
            System.err.println("Erreur lors de l'ouverture du fichier Excel : " + e.getMessage());
            e.printStackTrace();
        }
    }
}
