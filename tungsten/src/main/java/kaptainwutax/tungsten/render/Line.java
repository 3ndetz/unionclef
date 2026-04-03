package kaptainwutax.tungsten.render;

import kaptainwutax.tungsten.TungstenModDataContainer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class Line extends Renderer {

    public Vec3d start;
    public Vec3d end;
    public Color color;

    public Line() {
        this(Vec3d.ZERO, Vec3d.ZERO, Color.WHITE);
    }

    public Line(Vec3d start, Vec3d end) {
        this(start, end, Color.WHITE);
    }

    public Line(Vec3d start, Vec3d end, Color color) {
        this.start = start;
        this.end = end;
        this.color = color;
    }

    @Override
    public void render(BufferBuilder builder) {
        if(TungstenModDataContainer.gameRenderer == null || this.start == null || this.end == null || this.color == null)return;
        Vec3d camPos = TungstenModDataContainer.gameRenderer.getCamera().getPos();
        this.putVertex(builder, camPos, this.start);
        this.putVertex(builder, camPos, this.end);
    }

    protected void putVertex(BufferBuilder buffer, Vec3d camPos, Vec3d pos) {
        buffer.vertex(
                (float) (pos.getX() - camPos.x),
                (float) (pos.getY() - camPos.y),
                (float) (pos.getZ() - camPos.z)
        ).color(
                this.color.getFRed(),
                this.color.getFGreen(),
                this.color.getFBlue(),
                1.0F
        );
    }

    @Override
    public BlockPos getPos() {
        double x = (this.end.getX() - this.start.getX()) / 2 + this.start.getX();
        double y = (this.end.getY() - this.start.getY()) / 2 + this.start.getY();
        double z = (this.end.getZ() - this.start.getZ()) / 2 + this.start.getZ();
        return new BlockPos((int) x, (int) y, (int) z);
    }

}
