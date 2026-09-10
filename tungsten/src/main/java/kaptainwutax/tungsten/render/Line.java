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
        //#if MC < 12111
        //$$ Vec3d camPos = TungstenModDataContainer.gameRenderer.getCamera().getPos();
        //$$ this.putVertex(builder, camPos, this.start);
        //$$ this.putVertex(builder, camPos, this.end);
        //#else
        // Two passes so the cue reads against real terrain (user 2026-09-10): a SOLID,
        // depth-tested line where it is actually visible, plus the same line at 50% alpha drawn
        // THROUGH walls where it is occluded. A single ignoreOcclusion pass (what this was) made
        // every route/break/place overlay a flat full-alpha smear that ignored the world; a single
        // depth-tested pass would vanish behind terrain. Every box (Cuboid = 12 Lines) and the
        // route line inherit this, so break outlines (red) and place outlines (green) get it too.
        net.minecraft.world.debug.gizmo.GizmoDrawing.line(this.start, this.end, this.color.toARGB(255));
        net.minecraft.world.debug.gizmo.GizmoDrawing.line(this.start, this.end, this.color.toARGB(128)).ignoreOcclusion();
        //#endif
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
