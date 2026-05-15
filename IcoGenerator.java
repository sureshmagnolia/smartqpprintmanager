import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.imageio.ImageIO;

public class IcoGenerator {
    public static void main(String[] args) throws Exception {
        int[] sizes = {16, 32, 48, 256};
        BufferedImage baseImage = ImageIO.read(new File("src/main/resources/icon.png"));
        
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        
        // ICO Header
        dos.writeShort(Short.reverseBytes((short) 0)); // Reserved
        dos.writeShort(Short.reverseBytes((short) 1)); // Type 1 = ICO
        dos.writeShort(Short.reverseBytes((short) sizes.length)); // Number of images
        
        int offset = 6 + (16 * sizes.length);
        byte[][] imageData = new byte[sizes.length][];
        
        for (int i = 0; i < sizes.length; i++) {
            int size = sizes[i];
            BufferedImage resized = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(baseImage, 0, 0, size, size, null);
            g.dispose();
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(resized, "png", baos);
            imageData[i] = baos.toByteArray();
            
            // Directory Entry
            dos.writeByte(size >= 256 ? 0 : size); // Width
            dos.writeByte(size >= 256 ? 0 : size); // Height
            dos.writeByte(0); // Color palette
            dos.writeByte(0); // Reserved
            dos.writeShort(Short.reverseBytes((short) 1)); // Color planes
            dos.writeShort(Short.reverseBytes((short) 32)); // Bits per pixel
            dos.writeInt(Integer.reverseBytes(imageData[i].length)); // Size of data
            dos.writeInt(Integer.reverseBytes(offset)); // Offset
            
            offset += imageData[i].length;
        }
        
        for (byte[] data : imageData) {
            dos.write(data);
        }
        
        dos.close();
        
        try (FileOutputStream fos = new FileOutputStream("src/main/resources/icon.ico")) {
            fos.write(bos.toByteArray());
        }
        
        System.out.println("True multi-resolution ICO generated.");
    }
}
