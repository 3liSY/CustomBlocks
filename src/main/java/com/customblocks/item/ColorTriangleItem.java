package com.customblocks.item;

import com.customblocks.CustomBlocksMod;
import com.customblocks.SlotManager;
import com.customblocks.block.SlotBlock;
import com.customblocks.network.SlotUpdatePayload;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Right-click any CustomBlock to recolour its texture's background to this triangle's colour.
 * Background is detected as the colour of the top-left corner pixel, then every pixel within
 * a tolerance of 40 per channel is replaced with the triangle colour.
 * Works on any block — no naming convention required.
 */
public class ColorTriangleItem extends Item {

    private final int targetR, targetG, targetB;
    private final String colorName;

    /** Colour tolerance: pixels within this distance per channel are treated as background. */
    private static final int TOLERANCE = 40;

    public ColorTriangleItem(int r, int g, int b, String colorName, Settings settings) {
        super(settings);
        this.targetR   = r;
        this.targetG   = g;
        this.targetB   = b;
        this.colorName = colorName;
    }

    @Override public Text getName()                 { return Text.literal(colorName + " Triangle"); }
    @Override public Text getName(ItemStack stack)  { return getName(); }

    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        World        world  = ctx.getWorld();
        BlockPos     pos    = ctx.getBlockPos();
        PlayerEntity player = ctx.getPlayer();

        if (world.isClient) return ActionResult.PASS;

        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof SlotBlock sb)) return ActionResult.PASS;

        if (player != null && !player.hasPermissionLevel(2)) {
            player.sendMessage(
                Text.literal("§c[CustomBlocks] You need OP (level 2) to use colour triangles."), true);
            return ActionResult.FAIL;
        }

        SlotManager.SlotData d = SlotManager.getBySlot(sb.getSlotKey());
        if (d == null) return ActionResult.PASS;

        if (d.texture == null || d.texture.length == 0) {
            if (player != null)
                player.sendMessage(
                    Text.literal("§c[CustomBlocks] This block has no texture to recolour."), true);
            return ActionResult.FAIL;
        }

        MinecraftServer        server  = world.getServer();
        SlotManager.SlotData   finalD  = d;
        PlayerEntity           fp      = player;
        int                    fR = targetR, fG = targetG, fB = targetB;

        Thread t = new Thread(() -> {
            try {
                System.setProperty("java.awt.headless", "true");
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(finalD.texture));
                if (img == null) throw new Exception("Could not decode image");

                // Detect background from top-left pixel
                int bgArgb = img.getRGB(0, 0);
                int bgR    = (bgArgb >> 16) & 0xFF;
                int bgG    = (bgArgb >> 8)  & 0xFF;
                int bgB    =  bgArgb        & 0xFF;

                int newArgb = (0xFF << 24) | (fR << 16) | (fG << 8) | fB;

                for (int y = 0; y < img.getHeight(); y++) {
                    for (int x = 0; x < img.getWidth(); x++) {
                        int px = img.getRGB(x, y);
                        int pr = (px >> 16) & 0xFF;
                        int pg = (px >> 8)  & 0xFF;
                        int pb =  px        & 0xFF;
                        if (Math.abs(pr - bgR) <= TOLERANCE &&
                            Math.abs(pg - bgG) <= TOLERANCE &&
                            Math.abs(pb - bgB) <= TOLERANCE) {
                            img.setRGB(x, y, newArgb);
                        }
                    }
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(img, "PNG", baos);
                byte[] newBytes = baos.toByteArray();

                server.execute(() -> {
                    SlotManager.updateTexture(finalD.customId, newBytes);
                    SlotManager.saveAll();
                    CustomBlocksMod.broadcastUpdate(server,
                        new SlotUpdatePayload("retexture", finalD.index, finalD.customId, null,
                            newBytes, finalD.lightLevel, finalD.hardness, finalD.soundType));
                    if (fp != null)
                        fp.sendMessage(
                            Text.literal("§a[CustomBlocks] Background recoloured to §f"
                                + colorName + "§a!"), true);
                });

            } catch (Exception e) {
                server.execute(() -> {
                    if (fp != null)
                        fp.sendMessage(
                            Text.literal("§c[CustomBlocks] Recolour failed: " + e.getMessage()), true);
                });
            }
        }, "CB-Recolour");
        t.setDaemon(true);
        t.start();

        return ActionResult.SUCCESS;
    }
}
