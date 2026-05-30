
import com.printmanager.ai.*;
import com.printmanager.model.*;
import java.io.File;
import java.util.Collections;

public class AISimulator {
    public static void main(String[] args) {
        PDFDecrypter decrypter = new PDFDecrypter();
        AIRoutingAgent agent = new AIRoutingAgent();
        
        // 1. Target a problematic file
        File pdfFile = new File("C:/Users/sures/OneDrive/Documents/Downloads/QPArchive/18.05.26FN/REG_18.05.26_10_00 AM_140767_GOODS AND SERVICES TAX--(4.4 BCH).pdf");
        
        System.out.println("SIMULATION: Testing File [" + pdfFile.getName() + "]");
        
        // 2. Extract Metadata
        PDFDecrypter.PDFMetadata metadata = decrypter.extractMetadata(pdfFile);
        System.out.println("STEP 1: Extracted Metadata -> " + metadata.toString());
        
        // 3. Mock a Room Item (from the No_QP_Codes JSON)
        RoomItem room = new RoomItem("1", "", "", 10, -1);
        room.setCourseName("GOODS AND SERVICES TAX (4.4 BCH) [2017 SYLLABUS]");
        room.setStream("Regular");
        
        System.out.println("STEP 2: Matching against Room -> " + room.getCourseName());
        
        // 4. Run AI Agent
        FileItem fileItem = new FileItem(pdfFile, 0, "", "", false, false, "", 0, "", "");
        MatchResult result = agent.findBestFileForRoom(room, Collections.singletonList(fileItem));
        
        System.out.println("STEP 3: Result -> Match=" + result.isMatch() + " Confidence=" + result.getConfidenceScore());
        System.out.println("STEP 4: Working Logs:");
        for(String log : result.getWorkingLogs()) {
            System.out.println("  " + log);
        }
    }
}
