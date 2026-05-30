
import com.printmanager.PDFService;
import java.io.File;

public class PDFInspector {
    public static void main(String[] args) throws Exception {
        PDFService service = new PDFService();
        String[] files = {
            "C:\\Users\\sures\\OneDrive\\Documents\\Downloads\\QPArchive\\11.05.26FN\\Done\\REG_11.05.26_10_00 AM_143630_Corporate Regulations and Governance--(Core 5)--(COM4CJ203).pdf",
            "C:\\Users\\sures\\OneDrive\\Documents\\Downloads\\QPArchive\\11.05.26FN\\Done\\REG_11.05.26_10_00 AM_143686_Intermediate Microeconomics--(Core 5)--(ECO4CJ203).pdf"
        };
        
        for (String filePath : files) {
            File f = new File(filePath);
            System.out.println("--- FILE: " + f.getName() + " ---");
            String text = service.getText(f);
            String[] lines = text.split("\\r?\\n");
            for (int i = 0; i < Math.min(lines.length, 20); i++) {
                System.out.println(lines[i]);
            }
            System.out.println("-----------------------------------");
        }
    }
}
