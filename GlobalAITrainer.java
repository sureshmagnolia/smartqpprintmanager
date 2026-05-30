
import com.printmanager.ai.*;
import com.printmanager.model.*;
import com.fasterxml.jackson.databind.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;

public class GlobalAITrainer {
    public static void main(String[] args) throws Exception {
        PDFDecrypter decrypter = new PDFDecrypter();
        AIRoutingAgent agent = new AIRoutingAgent();
        ObjectMapper mapper = new ObjectMapper();

        File archiveRoot = new File("C:/Users/sures/OneDrive/Documents/Downloads/QPArchive");
        int totalAttempted = 0;
        int totalMatched = 0;
        int totalFailed = 0;

        System.out.println("--- AI TRAINING START: SCANNING ARCHIVE ---");

        for (File sessionFolder : archiveRoot.listFiles(File::isDirectory)) {
            // Find the original JSON
            File[] jsons = sessionFolder.listFiles(f -> f.getName().startsWith("Room_") && f.getName().endsWith(".json"));
            if (jsons == null || jsons.length == 0) continue;

            System.out.println("\n>>> Processing Session: " + sessionFolder.getName());

            JsonNode root = mapper.readTree(jsons[0]);
            List<RoomItem> rooms = new ArrayList<>();
            for (JsonNode node : root) {
                RoomItem ri = new RoomItem(node.path("roomSerial").asText(), node.path("qpCode").asText(), "", 0, -1);
                ri.setCourseName(node.path("courseName").asText());
                ri.setStream(node.path("stream").asText("Regular"));
                rooms.add(ri);
            }

            List<FileItem> files = new ArrayList<>();
            for (File pdf : sessionFolder.listFiles(f -> f.getName().toLowerCase().endsWith(".pdf"))) {
                files.add(new FileItem(pdf, 0, "", "", false, false, "", 0, "", ""));
            }

            for (RoomItem room : rooms) {
                totalAttempted++;
                List<MatchResult> aiResults = agent.findAllMatchesForRoom(room, files);
                if (!aiResults.isEmpty()) {
                    totalMatched++;
                    for (MatchResult res : aiResults) {
                        System.out.println("  [OK] " + room.getCourseName() + " -> " + res.getMatchedFile().getFileName() + " (" + (int)(res.getConfidenceScore()*100) + "%)");
                    }
                } else {
                    totalFailed++;
                    System.out.println("  [FAIL] No Match for: " + room.getCourseName() + " (JSON QP: " + room.getQpCode() + ")");
                    // Run a deep analysis for failures
                    for (FileItem f : files) {
                         MatchResult tempResult = new MatchResult(false, room, 0.0);
                         // Accessing private evaluateMatch is not possible here without reflection, but we can look at the logs if we capture them.
                    }
                }
            }
        }

        System.out.println("\n--- TRAINING SUMMARY ---");
        System.out.println("Total Rooms Attempted: " + totalAttempted);
        System.out.println("Successful AI Matches: " + totalMatched);
        System.out.println("Failed AI Matches:     " + totalFailed);
        System.out.println("Accuracy:              " + String.format("%.2f", (totalMatched * 100.0 / totalAttempted)) + "%");
    }
}
