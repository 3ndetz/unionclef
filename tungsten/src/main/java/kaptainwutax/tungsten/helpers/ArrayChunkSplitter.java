package kaptainwutax.tungsten.helpers;

import java.lang.reflect.Array;
import java.util.Arrays;

public class ArrayChunkSplitter {

	 public static <T> T[][] splitArrayIntoChunksOfX(T[] array, int chunkSize) {
        int numChunks = (int) Math.ceil((double) array.length / chunkSize);
        // ⛔ FIXED 2026-09-05: this used `Array.newInstance(componentType, numChunks, chunkSize)`,
        // which pre-allocates every row at the FULL chunkSize -- then immediately threw each row
        // away by reassigning `chunks[i] = Arrays.copyOfRange(...)` (the only way to get a
        // correctly-*short* last chunk), and finally re-copied the identical range into that
        // already-correct array via a redundant System.arraycopy. Not a correctness bug (the
        // final values were always right), but every call allocated one full-size throwaway row
        // per chunk and did the copy twice. Live in PathFinder.java's per-node parallel-chunking
        // (called for every search node expansion), so this wasted allocation/copy work runs hot.
        // Fixed idiom: allocate only the OUTER array (`array.getClass()` = T[].class, so this
        // call makes a T[][] with unset rows) and let copyOfRange size each row exactly once.
        @SuppressWarnings("unchecked")
		T[][] chunks = (T[][]) Array.newInstance(array.getClass(), numChunks);

        for (int i = 0; i < numChunks; i++) {
            int currentChunkSize = Math.min(chunkSize, array.length - i * chunkSize);
            chunks[i] = Arrays.copyOfRange(array, i * chunkSize, i * chunkSize + currentChunkSize);
        }

        return chunks;
    }
}
