import com.sun.jna.platform.win32.Winspool;
import com.sun.jna.platform.win32.WinspoolUtil;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class TestJNAAsync {
    public static void main(String[] args) {
        try {
            CompletableFuture<Winspool.PRINTER_INFO_2[]> future = CompletableFuture.supplyAsync(() -> {
                return WinspoolUtil.getPrinterInfo2();
            });
            Winspool.PRINTER_INFO_2[] printers = future.get(5, TimeUnit.SECONDS);
            System.out.println("Got printers: " + printers.length);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
}
