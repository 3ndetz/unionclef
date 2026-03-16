package adris.altoclef.util.helpers;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.Vec3d;

public class ArrowTrajectoryRenderer {
    private static final double GRAVITY = 0.05D;
    private static final float ARROW_VELOCITY = 3.0F;

    public void renderTrajectory(MatrixStack matrixStack, float partialTicks, PlayerEntity player) {
        if (!player.isUsingItem() || player.getActiveItem().getItem() != Items.BOW) {
            return;
        }

        // Calculate bow charge
        int useDuration = player.getItemUseTime();
        float power = (float) (useDuration) / 20.0F;
        power = (power * power + power * 2.0F) / 3.0F;
        power = Math.min(1.0F, power);

        if (power < 0.1F) {
            return;
        }
        matrixStack.push();

        // Get camera position for proper transformation
        Camera camera = MinecraftClient.getInstance().gameRenderer.getCamera();
        matrixStack.translate(
                -camera.getPos().x,
                -camera.getPos().y,
                -camera.getPos().z
        );

        // Start position
        Vec3d startPos = player.getEyePos().subtract(0, 0.1, 0)
                .add(player.getRotationVector().multiply(0.5));
        Vec3d lookVec = player.getRotationVector();

        // Initial velocity
        double velocityX = lookVec.x * ARROW_VELOCITY * power;
        double velocityY = lookVec.y * ARROW_VELOCITY * power;
        double velocityZ = lookVec.z * ARROW_VELOCITY * power;

        // Trajectory rendering currently disabled (rendering API changes between MC versions).
        // Uncomment and adapt for your Minecraft version if needed.

        matrixStack.pop();
    }
}
