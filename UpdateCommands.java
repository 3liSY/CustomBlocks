import java.nio.file.*;
import java.util.regex.*;
import java.io.IOException;

public class UpdateCommands {
    public static void main(String[] args) throws IOException {
        Path path = Paths.get("src/main/java/com/customblocks/command/CustomBlockCommand.java");
        String content = Files.readString(path);
        
        Pattern pattern = Pattern.compile("(\\s*)\\.then\\(CommandManager\\.literal\\(\"([^\"]+)\"\\)");
        Matcher matcher = pattern.matcher(content);
        StringBuffer sb = new StringBuffer();
        
        while (matcher.find()) {
            String whitespace = matcher.group(1);
            String commandName = matcher.group(2);
            String replacement = whitespace + ".then(CommandManager.literal(\"" + commandName + "\")\n" + 
                                 whitespace + "    .requires(src -> PermissionHelper.canUseCommand(src, \"" + commandName + "\"))";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        
        String newContent = sb.toString();
        
        newContent = newContent.replaceAll(
            "\\.requires\\(src -> PermissionHelper\\.canUse\\(src\\)\\)",
            ".requires(src -> PermissionHelper.canUseCommand(src, \"main\"))"
        );
        
        Files.writeString(path, newContent);
        System.out.println("Done!");
    }
}
