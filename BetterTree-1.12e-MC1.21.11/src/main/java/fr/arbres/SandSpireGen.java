package fr.arbres;

import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Pointes de grès dans les déserts et les mesas : des pics effilés qui sortent du sable, parfois en petit groupe.
 * Sous certains pics, une cache enterrée : os (blocs d'os) et sable suspect à brosser.
 * Tout reste dans les colonnes du chunk (aucun problème de bord de chunk).
 */
final class SandSpireGen {
    private SandSpireGen() {}

    private static final int NONE = Integer.MIN_VALUE;

    /** Enregistre un bloc de sable suspect : son butin est ajouté plus tard, quand le chunk est chargé. */
    interface BrushSink {
        void add(String world, int x, int y, int z);
    }

    private static double hash(long seed, int x, int y, int z) {
        long h = seed;
        h ^= x * 0x9E3779B97F4A7C15L;
        h = Long.rotateLeft(h, 31) * 0xBF58476D1CE4E5B9L;
        h ^= y * 0x94D049BB133111EBL;
        h = Long.rotateLeft(h, 27) * 0x9E3779B97F4A7C15L;
        h ^= z * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h >>> 11) * (1.0 / (1L << 53));
    }

    private static boolean natural(Material m) {
        return m == Material.SAND || m == Material.RED_SAND || m == Material.SANDSTONE || m == Material.RED_SANDSTONE;
    }

    private static boolean passable(Material m) {
        return m.isAir() || (Blocks.replaceable(m) && !Blocks.leaf(m)) || m == Material.CACTUS;
    }

    private static int surface(Canvas cv, int x, int z) {
        int y = cv.topY(x, z);
        while (y > cv.minY()) {
            Material m = cv.type(x, y, z);
            if (m == null) return NONE;
            if (passable(m)) {
                y--;
                continue;
            }
            return y;
        }
        return NONE;
    }

    static int spires(Canvas cv, String world, int chunkX, int chunkZ, long seed, double chance, double hiddenChance,
                      BrushSink sink) {
        Random r = new Random(TreeGen.mix(seed * 53L + chunkX * 341873128712L + chunkZ * 132897987541L + 6161L));
        if (r.nextDouble() >= chance) return 0;
        int x0 = chunkX * 16, z0 = chunkZ * 16, x1 = x0 + 15, z1 = z0 + 15;
        int mx = x0 + 5 + r.nextInt(6), mz = z0 + 5 + r.nextInt(6);
        int my = surface(cv, mx, mz);
        if (my == NONE) return 0;
        String biome = cv.biome(mx, my, mz);
        boolean red = biome.contains("badlands");
        if (!red && !biome.contains("desert")) return 0;

        // paramètres tirés d'abord, puis validés (le résultat ne dépend pas de l'ordre des tirages)
        int n = 1 + 2 + r.nextInt(3); // 1 pic principal + 2 à 4 petits
        double[][] sp = new double[n][];
        sp[0] = new double[] {mx, mz, 12 + r.nextInt(12), 3.0 + r.nextDouble() * 1.0, 0.18, r.nextLong(), r.nextDouble()};
        for (int i = 1; i < n; i++) {
            double a = r.nextDouble() * Math.PI * 2;
            double d = 4.5 + r.nextDouble() * 3.0;
            int sx = (int) Math.max(x0 + 3, Math.min(x1 - 3, mx + Math.round(Math.cos(a) * d)));
            int sz = (int) Math.max(z0 + 3, Math.min(z1 - 3, mz + Math.round(Math.sin(a) * d)));
            sp[i] = new double[] {sx, sz, 5 + r.nextInt(8), 1.5 + r.nextDouble() * 0.9, 0.2, r.nextLong(), r.nextDouble()};
        }
        List<double[]> kept = new ArrayList<>();
        for (double[] s : sp) {
            boolean clash = false;
            for (double[] o : kept) {
                if (Math.hypot(o[0] - s[0], o[1] - s[1]) < o[3] + s[3] + 1.5) {
                    clash = true;
                    break;
                }
            }
            if (!clash) kept.add(s);
        }
        int made = 0;
        for (double[] s : kept) {
            int x = (int) s[0], z = (int) s[1];
            int gy = surface(cv, x, z);
            if (gy == NONE) continue;
            Material g = cv.type(x, gy, z);
            if (g == null || !natural(g)) continue;
            String b = cv.biome(x, gy, z);
            if (!b.contains("desert") && !b.contains("badlands")) continue;
            Random sr = new Random(TreeGen.mix((long) s[5]));
            spire(cv, sr, (long) s[5], x, gy, z, (int) s[2], s[3], s[4], red, x0, z0, x1, z1);
            if (s[6] < hiddenChance) cache(cv, sr, world, x, gy, z, sink);
            made++;
        }
        return made;
    }

    private static void spire(Canvas cv, Random r, long seed, int x, int gy, int z, int h, double baseR, double leanMax,
                              boolean red, int clipX0, int clipZ0, int clipX1, int clipZ1) {
        BlockData core = (red ? Material.RED_SANDSTONE : Material.SANDSTONE).createBlockData();
        BlockData smooth = (red ? Material.SMOOTH_RED_SANDSTONE : Material.SMOOTH_SANDSTONE).createBlockData();
        BlockData cut = (red ? Material.CUT_RED_SANDSTONE : Material.CUT_SANDSTONE).createBlockData();
        double ang = r.nextDouble() * Math.PI * 2;
        double lean = r.nextDouble() * leanMax;
        double lx = Math.cos(ang) * lean, lz = Math.sin(ang) * lean;
        for (int k = -2; k <= h; k++) {
            double t = Math.max(0, k) / (double) h;
            double rad = baseR * Math.pow(1.0 - t, 1.25) + 0.45;
            double ccx = x + lx * Math.max(0, k), ccz = z + lz * Math.max(0, k);
            int ir = (int) Math.ceil(rad) + 1;
            int cxi = (int) Math.round(ccx), czi = (int) Math.round(ccz);
            for (int dx = -ir; dx <= ir; dx++) {
                for (int dz = -ir; dz <= ir; dz++) {
                    int px = cxi + dx, pz = czi + dz;
                    if (px < clipX0 || px > clipX1 || pz < clipZ0 || pz > clipZ1) continue;
                    double ddx = px - ccx, ddz = pz - ccz;
                    double d2 = (ddx * ddx + ddz * ddz) / (rad * rad);
                    if (d2 + (hash(seed, px, gy + k, pz) - 0.5) * 0.45 > 1.0) continue;
                    Material cur = cv.type(px, gy + k, pz);
                    if (cur == null) continue;
                    boolean ok = passable(cur) || (k <= 0 && natural(cur));
                    if (!ok) continue;
                    double n = hash(seed + 1, px, gy + k, pz);
                    // bandes horizontales de grès : strates lisses et taillées, comme une falaise érodée
                    int band = Math.floorMod(k / 2, 4);
                    BlockData d = core;
                    if (band == 1 && n < 0.75) d = smooth;
                    else if (band == 3 && n < 0.45) d = cut;
                    else if (n < 0.08) d = smooth;
                    cv.set(px, gy + k, pz, d);
                }
            }
        }
        // petit tas de sable au pied (posé sur le sol, donc stable)
        BlockData sand = (red ? Material.RED_SAND : Material.SAND).createBlockData();
        int ring = (int) Math.ceil(baseR) + 2;
        for (int dx = -ring; dx <= ring; dx++) {
            for (int dz = -ring; dz <= ring; dz++) {
                double dd = Math.sqrt(dx * dx + dz * dz);
                if (dd < baseR || dd > baseR + 1.8) continue;
                int px = x + dx, pz = z + dz;
                if (px < clipX0 || px > clipX1 || pz < clipZ0 || pz > clipZ1) continue;
                if (hash(seed + 2, px, gy, pz) > 0.4) continue;
                Material up = cv.type(px, gy + 1, pz);
                if (up == null || !passable(up)) continue;
                int sy = surface(cv, px, pz);
                if (sy != gy) continue;
                Material gr = cv.type(px, gy, pz);
                if (gr == null || !natural(gr)) continue;
                cv.set(px, gy + 1, pz, sand);
            }
        }
    }

    /** Cache enterrée sous le pic : blocs d'os et sable suspect (à brosser pour trouver des objets). */
    private static void cache(Canvas cv, Random r, String world, int x, int gy, int z, BrushSink sink) {
        BlockData susp = Material.SUSPICIOUS_SAND.createBlockData();
        int y = gy - 5;
        int[][] cells = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        int suspCount = 0;
        for (int i = 0; i < cells.length; i++) {
            int px = x + cells[i][0], pz = z + cells[i][1];
            Material cur = cv.type(px, y, pz);
            if (cur == null || !natural(cur)) continue;
            double roll = r.nextDouble();
            if (i == 0 || roll < 0.45) {
                BlockData bone = Material.BONE_BLOCK.createBlockData();
                if (bone instanceof Orientable o) o.setAxis(Axis.values()[r.nextInt(3)]);
                cv.set(px, y, pz, bone);
            } else if (roll < 0.75 && suspCount < 3) {
                cv.set(px, y, pz, susp);
                sink.add(world, px, y, pz);
                suspCount++;
            }
        }
        // une couche de sable suspect juste au-dessus : la première chose qu'on trouve en creusant
        int ux = x + r.nextInt(3) - 1, uz = z + r.nextInt(3) - 1;
        Material up = cv.type(ux, y + 1, uz);
        if (up != null && natural(up)) {
            cv.set(ux, y + 1, uz, susp);
            sink.add(world, ux, y + 1, uz);
        } else if (suspCount == 0) {
            // au minimum un bloc de sable suspect à côté des os
            Material c0 = cv.type(x + 1, y, z);
            if (c0 != null && natural(c0)) {
                cv.set(x + 1, y, z, susp);
                sink.add(world, x + 1, y, z);
            }
        }
    }
}
