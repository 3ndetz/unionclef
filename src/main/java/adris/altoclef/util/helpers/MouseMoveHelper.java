package adris.altoclef.util.helpers;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public abstract class MouseMoveHelper {
    public static boolean RotationEnabled = true;
    public static double sqrt3 = Math.sqrt(3);
    public static double sqrt5 = Math.sqrt(5);
    public static List<Integer> PointsListX = new ArrayList<>();
    public static List<Integer> PointsListY = new ArrayList<>();

    public static void PointListsReset() {
        PointsListX.clear();
        PointsListY.clear();
    }

    public static void windMouse(int start_x, int start_y, int dest_x, int dest_y,
                                  double G_0, double W_0, double M_0, double D_0) {
        int current_x = start_x;
        int current_y = start_y;
        double v_x = 0;
        double v_y = 0;
        double W_x = 0;
        double W_y = 0;
        double dist = Math.hypot(dest_x - start_x, dest_y - start_y);
        Random random = new Random();

        while (dist >= 1) {
            double W_mag = Math.min(W_0, dist);
            if (dist >= D_0) {
                W_x = W_x / sqrt3 + (2 * random.nextDouble() - 1) * W_mag / sqrt5;
                W_y = W_y / sqrt3 + (2 * random.nextDouble() - 1) * W_mag / sqrt5;
            } else {
                W_x /= sqrt3;
                W_y /= sqrt3;
                if (M_0 < 3) {
                    M_0 = random.nextDouble() * 3 + 3;
                } else {
                    M_0 /= sqrt5;
                }
            }
            v_x += W_x + G_0 * (dest_x - start_x) / dist;
            v_y += W_y + G_0 * (dest_y - start_y) / dist;
            double v_mag = Math.hypot(v_x, v_y);
            if (v_mag > M_0) {
                double v_clip = M_0 / 2 + random.nextDouble() * M_0 / 2;
                v_x = (v_x / v_mag) * v_clip;
                v_y = (v_y / v_mag) * v_clip;
            }
            start_x += v_x;
            start_y += v_y;
            int move_x = (int) Math.round(start_x);
            int move_y = (int) Math.round(start_y);
            if (current_x != move_x || current_y != move_y) {
                PointsListX.add(move_x);
                PointsListY.add(move_y);
            }
            dist = Math.hypot(dest_x - start_x, dest_y - start_y);
        }
    }
}

interface MoveMouse {
    void moveMouse(int x, int y);
}
