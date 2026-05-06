import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;

public class FixEncoding {
    public static void main(String[] args) throws IOException {
        File dir = new File("src/main/java");
        fixDir(dir);
    }

    private static void fixDir(File dir) throws IOException {
        for (File f : dir.listFiles()) {
            if (f.isDirectory()) {
                fixDir(f);
            } else if (f.getName().endsWith(".java")) {
                String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                String orig = content;
                
                content = content.replace("Ã‚Â§", "§");
                content = content.replace("Ãƒâ€šÃ‚Â§", "§");
                content = content.replace("Â§", "§");
                content = content.replace("Ã¢Å“Â¦", "✦");
                content = content.replace("Ã¢Â¬Â¡", "⬡");
                content = content.replace("ÃƒÂ¢Ã‚Â¬Ã‚Â¡", "⬡");
                content = content.replace("Ã¢â€\u009Dâ‚¬", "─");
                content = content.replace("Ã¢â€\u009DÅ“", "├");
                content = content.replace("Ã¢â€\u009Dâ€”", "└");
                content = content.replace("Ã¢â€\u009Dâ€š", "│");
                content = content.replace("Ã¢â€“Â¶", "▶");
                content = content.replace("Ã¢Å“Å½", "✎");
                content = content.replace("Ã°Å¸â€\u009DÂ\u008D", "🔍");
                content = content.replace("Ã¢â‚¬â€\u009D", "—");
                content = content.replace("Ã¢Å“â€\u009D", "✔");
                content = content.replace("Ã¢Å“â€“", "✖");
                content = content.replace("Ã¢Â\u008FÂ¸", "⏸");
                content = content.replace("Ã¢â€”â‚¬", "◀");
                content = content.replace("Ã¢Å¡â„¢", "⚙");
                content = content.replace("Ã¢â„¢Â«", "♫");
                content = content.replace("Ã¢â‚¬Â¦", "…");
                content = content.replace("Ã¢â€ â€™", "→");

                if (!content.equals(orig)) {
                    Files.writeString(f.toPath(), content, StandardCharsets.UTF_8);
                    System.out.println("Fixed: " + f.getName());
                }
            }
        }
    }
}
