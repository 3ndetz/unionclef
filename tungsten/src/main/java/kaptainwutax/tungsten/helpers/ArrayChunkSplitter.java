package kaptainwutax.tungsten.helpers;

import java.lang.reflect.Array;
import java.util.Arrays;

public class ArrayChunkSplitter {

	 public static <T> T[][] splitArrayIntoChunksOfX(T[] array, int chunkSize) {
        int numChunks = (int) Math.ceil((double) array.length / chunkSize);
        @SuppressWarnings("unchecked")
		T[][] chunks = (T[][]) Array.newInstance(array.getClass().getComponentType(), numChunks, chunkSize);

        for (int i = 0; i < numChunks; i++) {
            int currentChunkSize = Math.min(chunkSize, array.length - i * chunkSize);
            chunks[i] = Arrays.copyOfRange(array, i * chunkSize, i * chunkSize + currentChunkSize);
            System.arraycopy(array, i * chunkSize, chunks[i], 0, currentChunkSize);
        }

        return chunks;
    }
}
