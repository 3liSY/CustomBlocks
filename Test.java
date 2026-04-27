import net.minecraft.server.network.ServerPlayerEntity;
import java.lang.reflect.Method;
public class Test {
    public static void main(String[] args) {
        for (Method m : ServerPlayerEntity.class.getMethods()) {
            if (m.getName().toLowerCase().contains("pack")) {
                System.out.println(m);
            }
        }
    }
}
