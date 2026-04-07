// 
// Decompiled by Procyon v0.6.0
// 

package com.customblocks.client;

import net.minecraft.class_481;
import net.minecraft.class_437;
import com.customblocks.client.gui.CustomBlocksScreen;
import java.util.Iterator;
import com.customblocks.client.texture.TextureCache;
import net.minecraft.class_2248;
import net.minecraft.class_2680;
import net.minecraft.class_239;
import com.customblocks.block.SlotBlock;
import net.minecraft.class_3965;
import net.minecraft.class_9779;
import net.minecraft.class_332;
import com.customblocks.SlotManager;
import net.minecraft.class_310;
import java.lang.reflect.Field;
import net.minecraft.class_1799;
import net.minecraft.class_5321;
import com.customblocks.CustomBlocksMod;
import net.minecraft.class_7923;
import net.minecraft.class_1761;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import com.customblocks.network.SlotUpdatePayload;
import net.minecraft.class_8710;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import com.customblocks.network.FullSyncPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.class_3675;
import net.minecraft.class_304;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.api.ClientModInitializer;

@Environment(EnvType.CLIENT)
public class CustomBlocksClient implements ClientModInitializer
{
    private static final String PACK_ENTRY = "file/customblocks_generated";
    private static final AtomicBoolean reloadScheduled;
    private static final AtomicBoolean generateRunning;
    private static final AtomicLong lastPacketTime;
    public static volatile boolean pendingCreativeRefresh;
    private static class_304 openGuiKey;
    
    public void onInitializeClient() {
        CustomBlocksClient.openGuiKey = KeyBindingHelper.registerKeyBinding(new class_304("key.customblocks.open_gui", class_3675.class_307.field_1668, 66, "category.customblocks"));
        ClientLifecycleEvents.CLIENT_STARTED.register((Object)(client -> {
            SlotManager.loadFromClientDir(client.field_1697);
            ResourcePackGenerator.generate(client);
            injectPackIfNeeded(client);
        }));
        ClientTickEvents.END_CLIENT_TICK.register((Object)(client -> {
            while (CustomBlocksClient.openGuiKey.method_1436()) {
                if (client.field_1755 == null) {
                    client.method_1507((class_437)new CustomBlocksScreen());
                }
            }
            if (CustomBlocksClient.pendingCreativeRefresh && client.field_1724 != null) {
                CustomBlocksClient.pendingCreativeRefresh = false;
                bustItemGroupIconCache();
                if (client.field_1755 instanceof class_481) {
                    client.method_1507((class_437)new class_481(client.field_1724, client.field_1724.field_3944.method_45735(), false));
                }
            }
        }));
        ClientPlayNetworking.registerGlobalReceiver((class_8710.class_9154)FullSyncPayload.ID, (payload, context) -> {
            final class_310 client2 = context.client();
            client2.execute(() -> {
                SlotManager.clearAll();
                TextureCache.invalidateAll();
                payload.entries().iterator();
                final Iterator iterator;
                while (iterator.hasNext()) {
                    final FullSyncPayload.SlotEntry e = iterator.next();
                    SlotManager.assignAtIndex(e.index(), e.customId(), e.displayName(), null);
                    SlotManager.setProperties(e.customId(), e.lightLevel(), e.hardness(), e.soundType());
                }
                if (payload.tabIconTexture() != null) {
                    SlotManager.setTabIconTexture(payload.tabIconTexture());
                }
                scheduleGenerateAndReload(client);
            });
        });
        ClientPlayNetworking.registerGlobalReceiver((class_8710.class_9154)SlotUpdatePayload.ID, (payload, context) -> {
            // 
            // This method could not be decompiled.
            // 
            // Original Bytecode:
            // 
            //     1: invokeinterface net/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking$Context.client:()Lnet/minecraft/class_310;
            //     6: astore_2        /* client */
            //     7: aload_2         /* client */
            //     8: aload_0         /* payload */
            //     9: aload_2         /* client */
            //    10: invokedynamic   BootstrapMethod #9, run:(Lcom/customblocks/network/SlotUpdatePayload;Lnet/minecraft/class_310;)Ljava/lang/Runnable;
            //    15: invokevirtual   net/minecraft/class_310.execute:(Ljava/lang/Runnable;)V
            //    18: return         
            //    MethodParameters:
            //  Name     Flags  
            //  -------  -----
            //  payload  
            //  context  
            // 
            // The error that occurred was:
            // 
            // java.lang.NullPointerException: Cannot invoke "com.strobel.assembler.metadata.TypeReference.getSimpleType()" because the return value of "com.strobel.decompiler.ast.Variable.getType()" is null
            //     at com.strobel.decompiler.languages.java.ast.NameVariables.generateNameForVariable(NameVariables.java:252)
            //     at com.strobel.decompiler.languages.java.ast.NameVariables.assignNamesToVariables(NameVariables.java:185)
            //     at com.strobel.decompiler.languages.java.ast.AstMethodBodyBuilder.nameVariables(AstMethodBodyBuilder.java:1482)
            //     at com.strobel.decompiler.languages.java.ast.AstMethodBodyBuilder.populateVariables(AstMethodBodyBuilder.java:1411)
            //     at com.strobel.decompiler.languages.java.ast.AstMethodBodyBuilder.createMethodBody(AstMethodBodyBuilder.java:210)
            //     at com.strobel.decompiler.languages.java.ast.AstMethodBodyBuilder.createMethodBody(AstMethodBodyBuilder.java:93)
            //     at com.strobel.decompiler.languages.java.ast.AstBuilder.createMethodBody(AstBuilder.java:868)
            //     at com.strobel.decompiler.languages.java.ast.AstBuilder.createMethod(AstBuilder.java:761)
            //     at com.strobel.decompiler.languages.java.ast.AstBuilder.addTypeMembers(AstBuilder.java:638)
            //     at com.strobel.decompiler.languages.java.ast.AstBuilder.createTypeCore(AstBuilder.java:605)
            //     at com.strobel.decompiler.languages.java.ast.AstBuilder.createTypeNoCache(AstBuilder.java:195)
            //     at com.strobel.decompiler.languages.java.ast.AstBuilder.createType(AstBuilder.java:162)
            //     at com.strobel.decompiler.languages.java.ast.AstBuilder.addType(AstBuilder.java:137)
            //     at com.strobel.decompiler.languages.java.JavaLanguage.buildAst(JavaLanguage.java:71)
            //     at com.strobel.decompiler.languages.java.JavaLanguage.decompileType(JavaLanguage.java:59)
            //     at com.strobel.decompiler.DecompilerDriver.decompileType(DecompilerDriver.java:334)
            //     at com.strobel.decompiler.DecompilerDriver.main(DecompilerDriver.java:148)
            // 
            throw new IllegalStateException("An error occurred while decompiling this method.");
        });
        HudRenderCallback.EVENT.register((Object)((ctx, tickCounter) -> {
            final class_310 client = class_310.method_1551();
            if (client.field_1687 == null || client.field_1724 == null) {
                return;
            }
            final class_239 patt0$temp = client.field_1765;
            if (!(patt0$temp instanceof class_3965)) {
                return;
            }
            final class_3965 bhr = (class_3965)patt0$temp;
            final class_2680 state = client.field_1687.method_8320(bhr.method_17777());
            final class_2248 patt1$temp = state.method_26204();
            if (!(patt1$temp instanceof SlotBlock)) {
                return;
            }
            final SlotBlock sb = (SlotBlock)patt1$temp;
            final SlotManager.SlotData data = SlotManager.getBySlot(sb.getSlotKey());
            if (data == null) {
                return;
            }
            final String name = data.displayName;
            final int cx = ctx.method_51421() / 2;
            final int w = client.field_1772.method_1727(name);
            ctx.method_25294(cx - w / 2 - 5, 38, cx + w / 2 + 5, 52, -2013265920);
            ctx.method_25300(client.field_1772, name, cx, 42, -1);
        }));
    }
    
    private static void bustItemGroupIconCache() {
        try {
            final class_1761 group = (class_1761)class_7923.field_44687.method_29107((class_5321)CustomBlocksMod.CUSTOM_BLOCKS_TAB);
            if (group == null) {
                return;
            }
            final String[] array;
            final String[] candidates = array = new String[] { "icon", "field_24603", "iconStack" };
            for (final String name : array) {
                try {
                    final Field f = class_1761.class.getDeclaredField(name);
                    f.setAccessible(true);
                    if (f.get(group) instanceof class_1799) {
                        f.set(group, class_1799.field_8037);
                        CustomBlocksMod.LOGGER.info("[CustomBlocks] Tab icon cache cleared via field '{}'.", (Object)name);
                        return;
                    }
                }
                catch (final NoSuchFieldException ex) {}
            }
            for (final Field f2 : class_1761.class.getDeclaredFields()) {
                if (f2.getType() == class_1799.class) {
                    f2.setAccessible(true);
                    f2.set(group, class_1799.field_8037);
                    CustomBlocksMod.LOGGER.info("[CustomBlocks] Tab icon cache cleared via type scan.");
                    return;
                }
            }
            CustomBlocksMod.LOGGER.warn("[CustomBlocks] Could not find ItemGroup icon field \u2014 tab icon may not update.");
        }
        catch (final Exception e) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] bustItemGroupIconCache failed: {}", (Object)e.getMessage());
        }
    }
    
    private static void scheduleGenerateAndReload(final class_310 client) {
        CustomBlocksClient.lastPacketTime.set(System.currentTimeMillis());
        if (CustomBlocksClient.generateRunning.compareAndSet(false, true)) {
            final Thread t = new Thread(() -> {
                while (true) {
                    final long remaining = 2000L - (System.currentTimeMillis() - CustomBlocksClient.lastPacketTime.get());
                    if (remaining <= 0L) {
                        break;
                    }
                    else {
                        try {
                            Thread.sleep(Math.max(50L, remaining));
                        }
                        catch (final InterruptedException ignored) {
                            break;
                        }
                    }
                }
                SlotManager.saveToClientDir(client.field_1697);
                ResourcePackGenerator.generate(client);
                client.execute(() -> {
                    injectPackIfNeeded(client);
                    CustomBlocksClient.generateRunning.set(false);
                    if (CustomBlocksClient.reloadScheduled.compareAndSet(false, true)) {
                        client.method_1521().thenRun(() -> client.execute(() -> {
                            CustomBlocksClient.reloadScheduled.set(false);
                            CustomBlocksMod.LOGGER.info("[CustomBlocks] Resources reloaded.");
                            CustomBlocksClient.pendingCreativeRefresh = true;
                        }));
                    }
                    else {
                        CustomBlocksClient.pendingCreativeRefresh = true;
                    }
                });
                return;
            }, "CustomBlocks-GenerateReload");
            t.setDaemon(true);
            t.start();
        }
    }
    
    private static void injectPackIfNeeded(final class_310 client) {
        if (!client.field_1690.field_1887.contains("file/customblocks_generated")) {
            client.field_1690.field_1887.add("file/customblocks_generated");
            client.field_1690.method_1640();
        }
    }
    
    static {
        reloadScheduled = new AtomicBoolean(false);
        generateRunning = new AtomicBoolean(false);
        lastPacketTime = new AtomicLong(0L);
        CustomBlocksClient.pendingCreativeRefresh = false;
    }
}
