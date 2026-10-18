package com.nickrocks.pixelflowbot.candy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class CandySolver {
    public static final int DOWN = 0;
    private static final int EMPTY = -1;
    private static final int FOG = -2;
    public static final String[] GRAVITY_NAMES = {"down", "up", "left", "right"};
    public static final int LEFT = 2;
    static final int MAX_CASCADES = 10;
    public static final int RIGHT = 3;
    public static final int UP = 1;
    static final double W_ACTIVATION = 2.0d;
    static final double W_BLOCKER = 3.0d;
    static final double W_BOMB = 22.0d;
    static final double W_CANDY = 1.0d;
    static final double W_CASCADE = 1.5d;
    static final double W_JELLY = 2.5d;
    static final double W_STRIPED = 9.0d;
    static final double W_WRAPPED = 11.0d;
    private int gravity = 0;

    public static final class Move {
        public int blockerHits;
        public final int c1;
        public final int c2;
        public int cascades;
        public int cleared;
        public int jellyHits;
        public final int r1;
        public final int r2;
        public double score;
        public String why = "";

        Move(int i, int i2, int i3, int i4) {
            this.r1 = i;
            this.c1 = i2;
            this.r2 = i3;
            this.c2 = i4;
        }

        public String key() {
            return (this.r1 < this.r2 || (this.r1 == this.r2 && this.c1 < this.c2)) ? this.r1 + "," + this.c1 + ">" + this.r2 + "," + this.c2 : this.r2 + "," + this.c2 + ">" + this.r1 + "," + this.c1;
        }

        public String toString() {
            return "(" + (this.r1 + 1) + "," + (this.c1 + 1) + ")→(" + (this.r2 + 1) + "," + (this.c2 + 1) + ")";
        }
    }

    private static final class S {
        int activations;
        int blockerHits;
        int bombs;
        int cascades;
        int cleared;
        final int[] color;
        final int cols;
        boolean[] hit;
        final boolean[] jelly;
        int jellyHits;
        final int[] kind;
        final int rows;
        double score;
        final int[] special;
        int striped;
        final StringBuilder why = new StringBuilder();
        int wrapped;

        S(Board board) {
            this.rows = board.rows;
            this.cols = board.cols;
            this.kind = (int[]) board.kind.clone();
            this.color = (int[]) board.color.clone();
            this.special = (int[]) board.special.clone();
            this.jelly = (boolean[]) board.jelly.clone();
            this.hit = new boolean[this.kind.length];
        }

        int i(int i, int i2) {
            return (this.cols * i) + i2;
        }

        boolean in(int i, int i2) {
            return i >= 0 && i2 >= 0 && i < this.rows && i2 < this.cols;
        }

        boolean candy(int i) {
            return this.kind[i] == 2;
        }

        int mcolor(int i) {
            if (this.kind[i] != 2 || this.special[i] == 4) {
                return (-100) - i;
            }
            int i2 = this.color[i];
            return i2 < 0 ? (-100) - i : i2;
        }
    }

    public void setGravity(int i) {
        this.gravity = i;
    }

    public int getGravity() {
        return this.gravity;
    }

    public List<Move> rank(Board board, Set<String> set) {
        ArrayList arrayList = new ArrayList();
        for (int i = 0; i < board.rows; i++) {
            for (int i2 = 0; i2 < board.cols; i2++) {
                if (i2 + 1 < board.cols) {
                    consider(board, i, i2, i, i2 + 1, set, arrayList);
                }
                if (i + 1 < board.rows) {
                    consider(board, i, i2, i + 1, i2, set, arrayList);
                }
            }
        }
        Collections.sort(arrayList, new Comparator<Move>() { // from class: com.nickrocks.pixelflowbot.candy.CandySolver.1
            @Override // java.util.Comparator
            public int compare(Move move, Move move2) {
                return Double.compare(move2.score, move.score);
            }
        });
        return arrayList;
    }

    public Move best(Board board, Set<String> set) {
        List<Move> rank = rank(board, set);
        if (rank.isEmpty()) {
            return null;
        }
        return rank.get(0);
    }

    private void consider(Board board, int i, int i2, int i3, int i4, Set<String> set, List<Move> list) {
        int idx = board.idx(i, i2);
        int idx2 = board.idx(i3, i4);
        if (board.kind[idx] == 2 && board.kind[idx2] == 2) {
            Move simulate = simulate(board, i, i2, i3, i4);
            Move simulate2 = simulate(board, i3, i4, i, i2);
            if (simulate != null) {
                if (simulate2 == null) {
                    simulate2 = simulate;
                } else if (simulate2.score <= simulate.score) {
                    simulate2 = simulate;
                }
            }
            if (simulate2 != null) {
                if (set == null || !set.contains(simulate2.key())) {
                    list.add(simulate2);
                }
            }
        }
    }

    public Move simulate(Board board, int i, int i2, int i3, int i4) {
        boolean z;
        double max;
        S s = new S(board);
        int i5 = s.i(i, i2);
        int i6 = s.i(i3, i4);
        int i7 = s.special[i5];
        int i8 = s.special[i6];
        boolean z2 = i == i3;
        boolean[] zArr = new boolean[s.kind.length];
        if (i7 == 4 || i8 == 4) {
            z = true;
            comboBomb(s, i5, i6, zArr);
        } else if (i7 != 0 && i8 != 0) {
            z = true;
            comboSpecials(s, i5, i6, zArr);
        } else {
            swap(s, i5, i6);
            if (!resolveMatches(s, zArr, i6, i5, z2, true)) {
                return null;
            }
            z = false;
        }
        if (z) {
            s.special[i5] = 0;
            s.special[i6] = 0;
            zArr[i5] = true;
            zArr[i6] = true;
        }
        activateAndRemove(s, zArr);
        int i9 = 0;
        while (true) {
            int i10 = i9;
            if (i10 >= MAX_CASCADES) {
                break;
            }
            applyGravity(s);
            boolean[] zArr2 = new boolean[s.kind.length];
            if (!resolveMatches(s, zArr2, -1, -1, false, false)) {
                break;
            }
            s.cascades++;
            activateAndRemove(s, zArr2);
            i9 = i10 + 1;
        }
        Move move = new Move(i, i2, i3, i4);
        double d = s.score + (s.cleared * W_CANDY) + (s.striped * W_STRIPED) + (s.wrapped * W_WRAPPED) + (s.bombs * W_BOMB) + (s.activations * W_ACTIVATION) + (s.blockerHits * W_BLOCKER) + (s.jellyHits * W_JELLY) + (s.cascades * W_CASCADE);
        switch (this.gravity) {
            case 1:
                max = ((board.rows - 1) - Math.min(i, i3)) / board.rows;
                break;
            case 2:
                max = ((board.cols - 1) - Math.min(i2, i4)) / board.cols;
                break;
            case 3:
                max = Math.max(i2, i4) / board.cols;
                break;
            default:
                max = Math.max(i, i3) / board.rows;
                break;
        }
        move.score = (max * 0.3d) + d;
        move.cleared = s.cleared;
        move.cascades = s.cascades;
        move.blockerHits = s.blockerHits;
        move.jellyHits = s.jellyHits;
        StringBuilder sb = new StringBuilder();
        if (s.why.length() > 0) {
            sb.append((CharSequence) s.why);
        }
        if (s.bombs > 0) {
            sep(sb).append("makes colour bomb");
        }
        if (s.wrapped > 0) {
            sep(sb).append("makes wrapped");
        }
        if (s.striped > 0) {
            sep(sb).append("makes striped");
        }
        sep(sb).append(s.cleared).append(" candies");
        if (s.cascades > 0) {
            sb.append(", ").append(s.cascades).append(" cascade").append(s.cascades > 1 ? "s" : "");
        }
        if (s.blockerHits > 0) {
            sb.append(", hits ").append(s.blockerHits).append(" blocker").append(s.blockerHits > 1 ? "s" : "");
        }
        if (s.jellyHits > 0) {
            sb.append(", ").append(s.jellyHits).append(" jelly");
        }
        move.why = sb.toString();
        return move;
    }

    private static StringBuilder sep(StringBuilder sb) {
        if (sb.length() > 0) {
            sb.append(", ");
        }
        return sb;
    }

    private static void swap(S s, int i, int i2) {
        int i3 = s.kind[i];
        s.kind[i] = s.kind[i2];
        s.kind[i2] = i3;
        int i4 = s.color[i];
        s.color[i] = s.color[i2];
        s.color[i2] = i4;
        int i5 = s.special[i];
        s.special[i] = s.special[i2];
        s.special[i2] = i5;
    }

    private void comboBomb(S s, int i, int i2, boolean[] zArr) {
        if (s.special[i] != 4) {
            i2 = i;
        }
        int i3 = s.special[i2];
        if (i3 == 4) {
            s.why.append("colour bomb + colour bomb: clears the board");
            for (int i4 = 0; i4 < s.kind.length; i4++) {
                if (s.candy(i4)) {
                    zArr[i4] = true;
                } else {
                    hitBlocker(s, i4);
                }
            }
            s.activations += 2;
            s.score += 10.0d;
            return;
        }
        int i5 = s.color[i2];
        int mostCommonColor = i5 < 0 ? mostCommonColor(s, -1) : i5;
        if (mostCommonColor >= 0) {
            if (i3 == 1 || i3 == 2) {
                s.why.append("colour bomb + striped: stripes every ").append(colorName(mostCommonColor));
                int i6 = 0;
                for (int i7 = 0; i7 < s.kind.length; i7++) {
                    if (s.candy(i7) && s.color[i7] == mostCommonColor && s.special[i7] != 4) {
                        int i8 = i7 / s.cols;
                        int i9 = i7 % s.cols;
                        if (((i8 + i9 + i6) & 1) == 0) {
                            line(s, i8, -1, zArr);
                        } else {
                            line(s, -1, i9, zArr);
                        }
                        zArr[i7] = true;
                        i6++;
                        s.activations++;
                    }
                }
                s.score += 8.0d;
            } else if (i3 == 3) {
                s.why.append("colour bomb + wrapped: blasts every ").append(colorName(mostCommonColor));
                for (int i10 = 0; i10 < s.kind.length; i10++) {
                    if (s.candy(i10) && s.color[i10] == mostCommonColor && s.special[i10] != 4) {
                        area(s, i10 / s.cols, i10 % s.cols, 1, zArr);
                        s.activations++;
                    }
                }
                int mostCommonColor2 = mostCommonColor(s, mostCommonColor);
                if (mostCommonColor2 >= 0) {
                    for (int i11 = 0; i11 < s.kind.length; i11++) {
                        if (s.candy(i11) && s.color[i11] == mostCommonColor2) {
                            zArr[i11] = true;
                        }
                    }
                }
                s.score += 8.0d;
            } else {
                s.why.append("colour bomb clears ").append(colorName(mostCommonColor));
                for (int i12 = 0; i12 < s.kind.length; i12++) {
                    if (s.candy(i12) && s.color[i12] == mostCommonColor && s.special[i12] != 4) {
                        zArr[i12] = true;
                    }
                }
            }
            s.activations++;
        }
    }

    private void comboSpecials(S s, int i, int i2, boolean[] zArr) {
        int i3 = s.special[i];
        int i4 = s.special[i2];
        int i5 = i2 / s.cols;
        int i6 = i2 % s.cols;
        boolean z = i3 == 1 || i3 == 2;
        boolean z2 = i4 == 1 || i4 == 2;
        if (z && z2) {
            s.why.append("striped + striped: row and column");
            line(s, i5, -1, zArr);
            line(s, -1, i6, zArr);
            s.score += W_BLOCKER;
        } else if (z || z2) {
            s.why.append("striped + wrapped: 3 rows and 3 columns");
            for (int i7 = -1; i7 <= 1; i7++) {
                if (i5 + i7 >= 0 && i5 + i7 < s.rows) {
                    line(s, i5 + i7, -1, zArr);
                }
                if (i6 + i7 >= 0 && i6 + i7 < s.cols) {
                    line(s, -1, i6 + i7, zArr);
                }
            }
            s.score += 6.0d;
        } else {
            s.why.append("wrapped + wrapped: big double blast");
            area(s, i5, i6, 2, zArr);
            s.score += 10.0d;
        }
        s.activations += 2;
    }

    private boolean resolveMatches(S s, boolean[] zArr, int i, int i2, boolean z, boolean z2) {
        int i3;
        int i4;
        int i5;
        int i6;
        int length = s.kind.length;
        int[] iArr = new int[length];
        int[] iArr2 = new int[length];
        for (int i7 = 0; i7 < s.rows; i7++) {
            for (int i8 = 0; i8 < s.cols; i8 = i6) {
                int mcolor = s.mcolor(s.i(i7, i8));
                i6 = i8 + 1;
                while (i6 < s.cols && s.mcolor(s.i(i7, i6)) == mcolor) {
                    i6++;
                }
                if (mcolor >= 0 && i6 - i8 >= 3) {
                    for (int i9 = i8; i9 < i6; i9++) {
                        iArr[s.i(i7, i9)] = i6 - i8;
                    }
                }
            }
        }
        for (int i10 = 0; i10 < s.cols; i10++) {
            for (int i11 = 0; i11 < s.rows; i11 = i5) {
                int mcolor2 = s.mcolor(s.i(i11, i10));
                i5 = i11 + 1;
                while (i5 < s.rows && s.mcolor(s.i(i5, i10)) == mcolor2) {
                    i5++;
                }
                if (mcolor2 >= 0 && i5 - i11 >= 3) {
                    for (int i12 = i11; i12 < i5; i12++) {
                        iArr2[s.i(i12, i10)] = i5 - i11;
                    }
                }
            }
        }
        boolean z3 = false;
        for (int i13 = 0; i13 < length; i13++) {
            if (iArr[i13] > 0 || iArr2[i13] > 0) {
                z3 = true;
                break;
            }
        }
        if (!z3) {
            return false;
        }
        int[] iArr3 = new int[length];
        Arrays.fill(iArr3, -1);
        int i14 = 0;
        int[] iArr4 = new int[length];
        ArrayList<int[]> arrayList = new ArrayList();
        int i15 = 0;
        while (i15 < length) {
            if (iArr[i15] == 0 && iArr2[i15] == 0) {
                i4 = i14;
            } else if (iArr3[i15] >= 0) {
                i4 = i14;
            } else {
                int mcolor3 = s.mcolor(i15);
                int i16 = 1;
                iArr4[0] = i15;
                iArr3[i15] = i14;
                ArrayList arrayList2 = new ArrayList();
                while (i16 > 0) {
                    i16--;
                    int i17 = iArr4[i16];
                    arrayList2.add(Integer.valueOf(i17));
                    int i18 = i17 / s.cols;
                    int i19 = i17 % s.cols;
                    int[][] iArr5 = {new int[]{0, 1}, new int[]{0, -1}, new int[]{1, 0}, new int[]{-1, 0}};
                    int length2 = iArr5.length;
                    // (Was a decompiled while(true) with no exit: it never left after the 4th direction.)
                    for (int i21 = 0; i21 < length2; i21++) {
                        {
                            int[] iArr6 = iArr5[i21];
                            int i22 = iArr6[0] + i18;
                            int i23 = iArr6[1] + i19;
                            if (s.in(i22, i23)) {
                                int i24 = s.i(i22, i23);
                                if (iArr3[i24] < 0 && ((iArr[i24] != 0 || iArr2[i24] != 0) && s.mcolor(i24) == mcolor3)) {
                                    if ((iArr6[0] == 0 && iArr[i17] > 0 && iArr[i24] > 0) || (iArr6[1] == 0 && iArr2[i17] > 0 && iArr2[i24] > 0)) {
                                        iArr3[i24] = i14;
                                        iArr4[i16] = i24;
                                        i16++;
                                    }
                                }
                            }
                        }
                    }
                }
                int[] iArr7 = new int[arrayList2.size()];
                int i25 = 0;
                while (true) {
                    int i26 = i25;
                    if (i26 >= iArr7.length) {
                        break;
                    }
                    iArr7[i26] = ((Integer) arrayList2.get(i26)).intValue();
                    i25 = i26 + 1;
                }
                arrayList.add(iArr7);
                i4 = i14 + 1;
            }
            i15++;
            i14 = i4;
        }
        for (int[] iArr8 : arrayList) {
            int i27 = 0;
            int i28 = 0;
            int i29 = -1;
            boolean z4 = false;
            for (int i30 : iArr8) {
                i27 = Math.max(i27, iArr[i30]);
                i28 = Math.max(i28, iArr2[i30]);
                if (iArr[i30] > 0 && iArr2[i30] > 0) {
                    i29 = i30;
                }
                if (i30 == i || i30 == i2) {
                    z4 = true;
                }
            }
            if (i27 >= 5 || i28 >= 5) {
                i3 = 4;
            } else if (i27 >= 3 && i28 >= 3) {
                i3 = 3;
            } else if (i27 != 4 && i28 != 4) {
                i3 = 0;
            } else if (z2 && z4) {
                i3 = z ? 1 : 2;
            } else {
                i3 = i27 == 4 ? 2 : 1;
            }
            int i31 = -1;
            if (i3 != 0) {
                int length3 = iArr8.length;
                int i32 = 0;
                int i33 = -1;
                while (i32 < length3) {
                    int i34 = iArr8[i32];
                    if (i34 != i) {
                        i34 = i33;
                    }
                    i32++;
                    i33 = i34;
                }
                if (i33 < 0) {
                    int length4 = iArr8.length;
                    int i35 = 0;
                    while (i35 < length4) {
                        int i36 = iArr8[i35];
                        if (i36 != i2) {
                            i36 = i33;
                        }
                        i35++;
                        i33 = i36;
                    }
                }
                i31 = i33;
                if (i31 < 0 && i29 >= 0 && i3 == 3) {
                    i31 = i29;
                }
                if (i31 < 0) {
                    i31 = iArr8[iArr8.length / 2];
                }
            }
            int i37 = s.color[iArr8[0]];
            for (int i38 : iArr8) {
                if (i38 != i31) {
                    zArr[i38] = true;
                }
            }
            for (int i39 : iArr8) {
                hitNeighbours(s, i39);
            }
            if (i31 >= 0) {
                if (s.special[i31] != 0) {
                    effect(s, i31, s.special[i31], zArr);
                    zArr[i31] = false;
                }
                s.special[i31] = i3;
                s.color[i31] = i3 == 4 ? -1 : i37;
                if (i3 == 4) {
                    s.bombs++;
                } else if (i3 == 3) {
                    s.wrapped++;
                } else {
                    s.striped++;
                }
                if (s.jelly[i31]) {
                    s.jelly[i31] = false;
                    s.jellyHits++;
                }
                s.cleared++;
            }
        }
        return true;
    }

    private static void hitNeighbours(S s, int i) {
        int i2 = i / s.cols;
        int i3 = i % s.cols;
        for (int[] iArr : new int[][]{new int[]{0, 1}, new int[]{0, -1}, new int[]{1, 0}, new int[]{-1, 0}}) {
            int i4 = iArr[0] + i2;
            int i5 = iArr[1] + i3;
            if (s.in(i4, i5)) {
                hitBlocker(s, s.i(i4, i5));
            }
        }
    }

    private void activateAndRemove(S s, boolean[] zArr) {
        try {
            activateAndRemove0(s, zArr);
        } finally {
            Arrays.fill(s.hit, false);
        }
    }

    private void activateAndRemove0(S s, boolean[] zArr) {
        boolean[] zArr2 = new boolean[zArr.length];
        boolean z = true;
        while (z) {
            z = false;
            for (int i = 0; i < zArr.length; i++) {
                if (zArr[i] && !zArr2[i] && s.kind[i] == 2 && s.special[i] != 0) {
                    zArr2[i] = true;
                    effect(s, i, s.special[i], zArr);
                    z = true;
                }
            }
        }
        for (int i2 = 0; i2 < zArr.length; i2++) {
            if (zArr[i2] && s.kind[i2] == 2) {
                s.cleared++;
                if (s.jelly[i2]) {
                    s.jelly[i2] = false;
                    s.jellyHits++;
                }
                s.kind[i2] = -1;
                s.color[i2] = -1;
                s.special[i2] = 0;
            }
        }
    }

    private void effect(S s, int i, int i2, boolean[] zArr) {
        int i3 = i / s.cols;
        int i4 = i % s.cols;
        s.activations++;
        switch (i2) {
            case 1:
                line(s, i3, -1, zArr);
                break;
            case 2:
                line(s, -1, i4, zArr);
                break;
            case 3:
                area(s, i3, i4, 1, zArr);
                s.score += 4.0d;
                break;
            case 4:
                int mostCommonColor = mostCommonColor(s, -1);
                if (mostCommonColor >= 0) {
                    for (int i5 = 0; i5 < s.kind.length; i5++) {
                        if (s.candy(i5) && s.color[i5] == mostCommonColor) {
                            zArr[i5] = true;
                        }
                    }
                    break;
                }
                break;
        }
    }

    private static void line(S s, int i, int i2, boolean[] zArr) {
        if (i >= 0) {
            for (int i3 = 0; i3 < s.cols; i3++) {
                mark(s, s.i(i, i3), zArr);
            }
        }
        if (i2 >= 0) {
            for (int i4 = 0; i4 < s.rows; i4++) {
                mark(s, s.i(i4, i2), zArr);
            }
        }
    }

    private static void area(S s, int i, int i2, int i3, boolean[] zArr) {
        for (int i4 = -i3; i4 <= i3; i4++) {
            for (int i5 = -i3; i5 <= i3; i5++) {
                if (s.in(i + i4, i2 + i5)) {
                    mark(s, s.i(i + i4, i2 + i5), zArr);
                }
            }
        }
    }

    private static void mark(S s, int i, boolean[] zArr) {
        if (s.kind[i] != 2) {
            hitBlocker(s, i);
        } else {
            zArr[i] = true;
        }
    }

    private static void hitBlocker(S s, int i) {
        if (s.kind[i] != 1 || s.hit[i]) {
            return;
        }
        s.hit[i] = true;
        s.blockerHits++;
    }

    private static int mostCommonColor(S s, int i) {
        int[] iArr = new int[6];
        for (int i2 = 0; i2 < s.kind.length; i2++) {
            if (s.candy(i2) && s.color[i2] >= 0 && s.color[i2] != i) {
                int i3 = s.color[i2];
                iArr[i3] = iArr[i3] + 1;
            }
        }
        int i4 = -1;
        for (int i5 = 0; i5 < 6; i5++) {
            if (iArr[i5] > 0 && (i4 < 0 || iArr[i5] > iArr[i4])) {
                i4 = i5;
            }
        }
        return i4;
    }

    static String colorName(int i) {
        switch (i) {
            case 0:
                return "red";
            case 1:
                return "orange";
            case 2:
                return "yellow";
            case 3:
                return "green";
            case 4:
                return "blue";
            case Board.PURPLE /* 5 */:
                return "purple";
            default:
                return "?";
        }
    }

    private void applyGravity(S s) {
        int i;
        int i2;
        int i3;
        boolean z = this.gravity == 0 || this.gravity == 1;
        int i4 = z ? s.cols : s.rows;
        int i5 = z ? s.rows : s.cols;
        int[] iArr = new int[i5];
        for (int i6 = 0; i6 < i4; i6++) {
            for (int i7 = 0; i7 < i5; i7++) {
                switch (this.gravity) {
                    case 1:
                        i2 = i6;
                        i3 = i7;
                        break;
                    case 2:
                        i2 = i7;
                        i3 = i6;
                        break;
                    case 3:
                        i2 = (i5 - 1) - i7;
                        i3 = i6;
                        break;
                    default:
                        i3 = (i5 - 1) - i7;
                        i2 = i6;
                        break;
                }
                iArr[i7] = s.i(i3, i2);
            }
            int i8 = 0;
            while (i8 < i5) {
                if (fluid(s, iArr[i8])) {
                    int i9 = i8;
                    while (i9 < i5 && fluid(s, iArr[i9])) {
                        i9++;
                    }
                    int i10 = i8;
                    int i11 = i8;
                    while (i10 < i9) {
                        int i12 = iArr[i10];
                        if (s.kind[i12] == 2) {
                            i = i11 + 1;
                            int i13 = iArr[i11];
                            if (i13 != i12) {
                                s.kind[i13] = 2;
                                s.color[i13] = s.color[i12];
                                s.special[i13] = s.special[i12];
                                s.kind[i12] = -1;
                                s.color[i12] = -1;
                                s.special[i12] = 0;
                            }
                        } else {
                            i = i11;
                        }
                        i10++;
                        i11 = i;
                    }
                    while (i11 < i9) {
                        int i14 = iArr[i11];
                        s.kind[i14] = 2;
                        s.color[i14] = FOG;
                        s.special[i14] = 0;
                        i11++;
                    }
                    i8 = i9;
                } else {
                    i8++;
                }
            }
        }
    }

    private static boolean fluid(S s, int i) {
        return s.kind[i] == 2 || s.kind[i] == -1;
    }
}
