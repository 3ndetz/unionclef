package kaptainwutax.tungsten.render;

import net.minecraft.client.render.BufferBuilder;
import net.minecraft.util.math.*;

public class Cuboid extends Renderer {

    public Vec3d start;
    public Vec3d size;

    private Line[] edges = new Line[12];

    public Cuboid() {
        this(Vec3d.ZERO, Vec3d.ZERO, Color.WHITE);
    }

    public Cuboid(Vec3d pos) {
        this(pos, new Vec3d(1, 1, 1), Color.WHITE);
    }

    public Cuboid(Box box, Color color) {
        this(new Vec3d(box.minX, box.minY, box.minZ), new Vec3d(box.maxX, box.maxY, box.maxZ), color);
    }

    public Cuboid(Vec3d start, Vec3d size, Color color) {
        this.start = start;
        this.size = size;
        this.edges[0] = new Line(this.start, this.start.add(this.size.getX(), 0, 0), color);
        this.edges[1] = new Line(this.start, this.start.add(0, this.size.getY(), 0), color);
        this.edges[2] = new Line(this.start, this.start.add(0, 0, this.size.getZ()), color);
        this.edges[3] = new Line(this.start.add(this.size.getX(), 0, this.size.getZ()), this.start.add(this.size.getX(), 0, 0), color);
        this.edges[4] = new Line(this.start.add(this.size.getX(), 0, this.size.getZ()), this.start.add(this.size.getX(), this.size.getY(), this.size.getZ()), color);
        this.edges[5] = new Line(this.start.add(this.size.getX(), 0, this.size.getZ()), this.start.add(0, 0, this.size.getZ()), color);
        this.edges[6] = new Line(this.start.add(this.size.getX(), this.size.getY(), 0), this.start.add(this.size.getX(), 0, 0), color);
        this.edges[7] = new Line(this.start.add(this.size.getX(), this.size.getY(), 0), this.start.add(0, this.size.getY(), 0), color);
        this.edges[8] = new Line(this.start.add(this.size.getX(), this.size.getY(), 0), this.start.add(this.size.getX(), this.size.getY(), this.size.getZ()), color);
        this.edges[9] = new Line(this.start.add(0, this.size.getY(), this.size.getZ()), this.start.add(0, 0, this.size.getZ()), color);
        this.edges[10] = new Line(this.start.add(0, this.size.getY(), this.size.getZ()), this.start.add(0, this.size.getY(), 0), color);
        this.edges[11] = new Line(this.start.add(0, this.size.getY(), this.size.getZ()), this.start.add(this.size.getX(), this.size.getY(), this.size.getZ()), color);
    }

    @Override
    public void render(BufferBuilder builder) {
        if(this.start == null || this.size == null || this.edges == null)return;

        for(Line edge: this.edges) {
            if(edge == null)continue;
            edge.render(builder);
        }
    }

    @Override
    public BlockPos getPos() {
    	Vec3d vec = this.start.add(this.size.getX() / 2, this.size.getY() / 2, this.size.getZ() / 2);
        return new BlockPos((int) vec.x, (int) vec.y, (int) vec.z);
    }

}
