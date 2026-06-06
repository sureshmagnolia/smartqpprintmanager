import com.printmanager.PrintService;
import java.util.Map;

public class TestMonitor {
    public static void main(String[] args) {
        try {
            PrintService ps = new PrintService();
            System.out.println("Available Printers (javax.print):");
            for (String p : ps.getAvailablePrinters()) {
                System.out.println(" - " + p);
            }
            System.out.println("\nDetailed Printers (JNA Winspool):");
            Map<String, Map<String, String>> detailed = ps.getPrintersDetailedStatus();
            for (Map.Entry<String, Map<String, String>> e : detailed.entrySet()) {
                System.out.println(" - " + e.getKey() + " -> " + e.getValue().get("status"));
            }
        } catch(Exception e) {
            e.printStackTrace();
        }
    }
}
