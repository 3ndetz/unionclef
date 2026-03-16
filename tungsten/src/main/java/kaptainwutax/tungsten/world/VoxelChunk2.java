package kaptainwutax.tungsten.world;

public class VoxelChunk2 {

    protected int[] data;

    public void setId(int index, int id) {
        int wordSize = this.data[0] & 15;
        int bits = 4 + (wordSize << 15) + 16 + (id * 16);
    }

    public int getId(int index) {
        int wordSize = this.data[0] & 15;
        int bits = 4 + wordSize * index; int a = bits >>> 5; int b = bits & 31;
        int id = (this.data[a] >>> b) & ((1 << wordSize) - 1);
        bits = 4 + (wordSize << 15) + 16 + (id * 16); a = bits >>> 5; b = bits & 31;
        return (this.data[a] >>> b) & 65535;
    }

}
