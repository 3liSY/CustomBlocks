import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class FixMojibake {
    public static void main(String[] args) throws Exception {
        System.out.println("Starting fixes...");
        
        // 1. Fix Mojibake in JSON files
        File langDir = new File("src/main/resources/assets/customblocks/lang");
        if (langDir.exists() && langDir.isDirectory()) {
            for (File f : langDir.listFiles()) {
                if (f.getName().endsWith(".json")) {
                    String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                    
                    // The files were double-encoded. Convert string back to raw bytes (ISO-8859-1 preserves bytes 0-255),
                    // then decode those bytes properly as UTF-8.
                    // Wait, sometimes things are mixed. Let's just do exact string replacements for safety.
                    String fixed = content.replace("Â§", "§")
                                          .replace("â€”", "—")
                                          .replace("âœ”", "✔")
                                          .replace("â†’", "→")
                                          .replace("âœ˜", "✘")
                                          .replace("â€¦", "…")
                                          .replace("â€œ", "“")
                                          .replace("â€", "”");
                    
                    if (!content.equals(fixed)) {
                        Files.writeString(f.toPath(), fixed, StandardCharsets.UTF_8);
                        System.out.println("Fixed mojibake in " + f.getName());
                    }
                }
            }
        }

        // 2. Fix playSound missing .value()
        File javaDir = new File("src/main/java");
        fixPlaySound(javaDir);
        
        System.out.println("Done.");
    }
    
    private static void fixPlaySound(File dir) throws Exception {
        for (File f : dir.listFiles()) {
            if (f.isDirectory()) {
                fixPlaySound(f);
            } else if (f.getName().endsWith(".java")) {
                String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                // Regex to find playSound(..., SoundEvents.SOMETHING, ...) where .value() is missing.
                // We'll look for SoundEvents.[A-Z_]+(?!\.value\(\))
                Pattern p = Pattern.compile("(SoundEvents\\.[A-Z0-9_]+)(?!\\.value\\(\\))");
                Matcher m = p.matcher(content);
                boolean changed = false;
                StringBuffer sb = new StringBuffer();
                while (m.find()) {
                    changed = true;
                    m.appendReplacement(sb, m.group(1) + ".value()");
                }
                if (changed) {
                    m.appendTail(sb);
                    Files.writeString(f.toPath(), sb.toString(), StandardCharsets.UTF_8);
                    System.out.println("Fixed playSound in " + f.getName());
                }
            }
        }
    }
}
