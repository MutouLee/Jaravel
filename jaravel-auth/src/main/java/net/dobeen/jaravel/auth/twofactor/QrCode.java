package net.dobeen.jaravel.auth.twofactor;

import java.nio.charset.StandardCharsets;

/**
 * 二维码编码器 —— 只为 {@code otpauth://} URI 而写(docs/08 §6.4)。
 *
 * <pre>{@code
 * String svg = QrCode.svg(setup.otpauthUri());     // 直接塞进模板
 * }</pre>
 *
 * <h2>★ 为什么自己写,而不是引一个二维码库</h2>
 * <p>这件事此前登记着「不做」,理由是「不引入一整个 QR 实现」——
 * 那个理由只对了一半:不该引的是**一个覆盖二十多种条码格式的库**
 * (zxing 的 core 一百多个类),而这里需要的是它的一个子集:
 * 字节模式、一种纠错级别、十个版本以内。那是一个有完整规范
 * (ISO/IEC 18004)、输入固定、可被机器验证的东西。
 *
 * <p>「可被机器验证」是做这个决定的前提:手写的编码器如果**错得很细**
 * (某个掩码罚分算错、交织顺序差一位),生成的图看起来完全正常,
 * 而用户扫不出来 —— 那种缺陷不会有人报告,只会被当成「这个二维码不好扫」。
 * 所以测试里用 zxing **把生成的码解回来**比对原文,而 zxing 只在
 * {@code test} 作用域 —— 运行时依然零依赖。
 *
 * <h2>范围</h2>
 * <table border="1">
 *   <caption>刻意的限制</caption>
 *   <tr><td>模式</td><td>字节(8-bit)—— {@code otpauth} URI 是 ASCII</td></tr>
 *   <tr><td>纠错</td><td>M(约 15%)—— 验证器扫屏幕,不需要 Q/H 那种抗污损</td></tr>
 *   <tr><td>版本</td><td>1–10(最多 213 字节)。超了**报错**并说清</td></tr>
 * </table>
 *
 * <p>不做「自动降级到更高纠错」之类的智能:输入是我们自己生成的 URI,
 * 长度可预期。超出范围时报错比悄悄换一种编码好 —— 后者会让
 * 「同一个账号两次生成的二维码不一样」。
 */
public final class QrCode {

    private QrCode() {}

    /** 支持的最大版本。再大就要版本信息之外的更多表,而 otpauth URI 用不到 */
    private static final int MAX_VERSION = 10;

    /** 每个版本的总码字数(数据 + 纠错) */
    private static final int[] TOTAL_CODEWORDS = {
            0, 26, 44, 70, 100, 134, 172, 196, 242, 292, 346};

    /** 纠错级别 M:每块的纠错码字数 */
    private static final int[] EC_PER_BLOCK = {
            0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};

    /** 纠错级别 M:块数 */
    private static final int[] BLOCKS = {
            0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};

    /** 对齐图案的中心坐标(版本 1 没有) */
    private static final int[][] ALIGNMENT = {
            {}, {}, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34},
            {6, 22, 38}, {6, 24, 42}, {6, 26, 46}, {6, 28, 50}};

    // ================= 公开入口 =================

    /** 默认:每模块 4 像素、4 模块的静默区(规范要求的最小值) */
    public static String svg(String data) {
        return svg(data, 4, 4);
    }

    /**
     * @param moduleSize 每个小方块多少像素
     * @param quietZone  四周留几个模块的空白。**规范要求至少 4** ——
     *                   少了会让一部分扫描器找不到定位图案,
     *                   而那表现为「有的手机扫得出,有的扫不出」
     */
    public static String svg(String data, int moduleSize, int quietZone) {
        boolean[][] modules = matrix(data);
        int size = modules.length;
        int side = (size + quietZone * 2) * moduleSize;

        var svg = new StringBuilder(1024);
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(side)
           .append("\" height=\"").append(side)
           .append("\" viewBox=\"0 0 ").append(side).append(' ').append(side)
           .append("\" shape-rendering=\"crispEdges\">");
        svg.append("<rect width=\"100%\" height=\"100%\" fill=\"#fff\"/>");

        // 一个 path 画完所有黑模块 —— 每个模块一个 <rect> 会让 SVG 大十倍,
        // 而它要内联进 HTML 页面
        svg.append("<path fill=\"#000\" d=\"");
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (!modules[y][x]) continue;
                svg.append('M').append((x + quietZone) * moduleSize).append(' ')
                   .append((y + quietZone) * moduleSize)
                   .append('h').append(moduleSize)
                   .append('v').append(moduleSize)
                   .append('h').append(-moduleSize).append('z');
            }
        }
        svg.append("\"/></svg>");
        return svg.toString();
    }

    /** 模块矩阵:{@code true} = 黑。包可见 —— 测试要拿它去解码 */
    static boolean[][] matrix(String data) {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        int version = versionFor(bytes.length);

        byte[] codewords = codewords(bytes, version);
        return place(codewords, version);
    }

    private static int versionFor(int byteCount) {
        for (int version = 1; version <= MAX_VERSION; version++) {
            if (byteCount <= dataCapacityBytes(version)) return version;
        }
        throw new IllegalArgumentException("""
                二维码内容太长:%d 字节,本实现最多 %d(版本 %d,纠错级别 M)。
                  这个编码器只为 otpauth:// URI 而写(docs/08 §6.4)——
                  那种 URI 在 150 字节以内。要编码更长的内容请用一个
                  完整的二维码库,框架不打算把自己变成那个库。"""
                .formatted(byteCount, dataCapacityBytes(MAX_VERSION), MAX_VERSION));
    }

    /** 这个版本能装多少字节(扣掉模式指示符与长度字段) */
    private static int dataCapacityBytes(int version) {
        int dataCodewords = TOTAL_CODEWORDS[version] - EC_PER_BLOCK[version] * BLOCKS[version];
        int headerBits = 4 + lengthBits(version);
        return dataCodewords - (headerBits + 7) / 8;
    }

    /** 字节模式的长度字段位数:版本 1–9 是 8 位,10 以上是 16 位 */
    private static int lengthBits(int version) {
        return version <= 9 ? 8 : 16;
    }

    // ================= 数据 → 码字 =================

    private static byte[] codewords(byte[] data, int version) {
        int totalCodewords = TOTAL_CODEWORDS[version];
        int blocks = BLOCKS[version];
        int ecPerBlock = EC_PER_BLOCK[version];
        int dataCodewords = totalCodewords - ecPerBlock * blocks;

        var bits = new BitBuffer();
        bits.append(0b0100, 4);                       // 模式:字节
        bits.append(data.length, lengthBits(version));
        for (byte b : data) bits.append(b & 0xFF, 8);

        // 终止符 + 补到字节边界
        int capacityBits = dataCodewords * 8;
        bits.append(0, Math.min(4, capacityBits - bits.size()));
        while (bits.size() % 8 != 0) bits.append(0, 1);

        // 填充字节 11101100 / 00010001 交替 —— 规范定死的两个值
        for (int pad = 0xEC; bits.size() < capacityBits; pad ^= 0xEC ^ 0x11) {
            bits.append(pad, 8);
        }

        byte[] dataBytes = bits.toBytes();

        // 分块:前面的块短一个码字(规范的分法),纠错码字数每块相同
        int shortBlocks = blocks - dataCodewords % blocks;
        int shortLength = dataCodewords / blocks;

        var dataBlocks = new byte[blocks][];
        var ecBlocks = new byte[blocks][];
        int offset = 0;
        for (int i = 0; i < blocks; i++) {
            int length = shortLength + (i < shortBlocks ? 0 : 1);
            dataBlocks[i] = java.util.Arrays.copyOfRange(dataBytes, offset, offset + length);
            ecBlocks[i] = ReedSolomon.encode(dataBlocks[i], ecPerBlock);
            offset += length;
        }

        // 交织:先按列取数据码字,再按列取纠错码字。
        // 顺序错了的后果是「图像没问题但解不出来」—— 测试里的解码比对就是为了它
        var out = new byte[totalCodewords];
        int at = 0;
        for (int i = 0; i < shortLength + 1; i++) {
            for (int b = 0; b < blocks; b++) {
                if (i < dataBlocks[b].length) out[at++] = dataBlocks[b][i];
            }
        }
        for (int i = 0; i < ecPerBlock; i++) {
            for (int b = 0; b < blocks; b++) out[at++] = ecBlocks[b][i];
        }
        return out;
    }

    // ================= 码字 → 模块 =================

    private static boolean[][] place(byte[] codewords, int version) {
        int size = version * 4 + 17;
        var modules = new boolean[size][size];
        var reserved = new boolean[size][size];

        drawFinders(modules, reserved, size);
        drawTiming(modules, reserved, size);
        drawAlignment(modules, reserved, version);
        reserveFormatAreas(reserved, size, version);

        drawData(modules, reserved, codewords, size);

        int mask = bestMask(modules, reserved, size);
        applyMask(modules, reserved, size, mask);
        drawFormat(modules, size, mask);
        if (version >= 7) drawVersion(modules, size, version);
        return modules;
    }

    private static void drawFinders(boolean[][] m, boolean[][] reserved, int size) {
        int[][] origins = {{0, 0}, {size - 7, 0}, {0, size - 7}};
        for (int[] origin : origins) {
            for (int dy = -1; dy <= 7; dy++) {
                for (int dx = -1; dx <= 7; dx++) {
                    int x = origin[0] + dx;
                    int y = origin[1] + dy;
                    if (x < 0 || y < 0 || x >= size || y >= size) continue;

                    boolean dark = inRing(dx, dy);
                    m[y][x] = dark;
                    reserved[y][x] = true;      // 含分隔符那一圈白
                }
            }
        }
    }

    /** 定位图案:7×7 的外框 + 3×3 的实心,中间一圈白 */
    private static boolean inRing(int dx, int dy) {
        int ring = Math.max(Math.abs(dx - 3), Math.abs(dy - 3));
        return ring != 2 && ring <= 3;
    }

    private static void drawTiming(boolean[][] m, boolean[][] reserved, int size) {
        for (int i = 8; i < size - 8; i++) {
            boolean dark = i % 2 == 0;
            m[6][i] = dark;
            reserved[6][i] = true;
            m[i][6] = dark;
            reserved[i][6] = true;
        }
    }

    /**
     * 对齐图案。
     *
     * <h2>★ 跳过哪三个,必须按规范的「索引」判,不能按「这格被占了」判</h2>
     * <p>规范跳的是与三个定位图案重合的那三个组合:(首,首)、(首,末)、(末,首)。
     * 第一版用「{@code reserved[cy][cx]} 为真就跳」做近似 —— 在只有两个中心的
     * 版本(1–6)上它恰好等价,于是短内容全对;而从版本 7 起中心有三个,
     * {@code (6,22)} 与 {@code (22,6)} 这两个**该画**的图案落在 timing 线上,
     * 于是被误跳,结果是长内容扫出来 ChecksumException。
     *
     * <p>这正是「解回来比对」那组测试存在的理由:图看起来完全正常。
     *
     * <p>落在 timing 线上的对齐图案**覆盖** timing —— 规范如此,
     * 所以这里照常写并把那些格标成已占用。
     */
    private static void drawAlignment(boolean[][] m, boolean[][] reserved, int version) {
        int[] centers = ALIGNMENT[version];
        int last = centers.length - 1;

        for (int i = 0; i <= last; i++) {
            for (int j = 0; j <= last; j++) {
                boolean atFinder = (i == 0 && j == 0)
                        || (i == 0 && j == last)
                        || (i == last && j == 0);
                if (atFinder) continue;

                int cy = centers[i];
                int cx = centers[j];
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int ring = Math.max(Math.abs(dx), Math.abs(dy));
                        m[cy + dy][cx + dx] = ring != 1;
                        reserved[cy + dy][cx + dx] = true;
                    }
                }
            }
        }
    }

    private static void reserveFormatAreas(boolean[][] reserved, int size, int version) {
        for (int i = 0; i <= 8; i++) {
            reserved[8][i] = true;
            reserved[i][8] = true;
        }
        for (int i = 0; i < 8; i++) {
            reserved[8][size - 1 - i] = true;
            reserved[size - 1 - i][8] = true;
        }
        if (version >= 7) {
            for (int i = 0; i < 6; i++) {
                for (int j = 0; j < 3; j++) {
                    reserved[size - 11 + j][i] = true;
                    reserved[i][size - 11 + j] = true;
                }
            }
        }
    }

    /**
     * 数据按**两列一组、自右向左、蛇形**填入。
     *
     * <p>第 6 列是竖的定位图案,跳过它 —— 不跳的话整条数据会错位一列,
     * 而图像看起来照样像个二维码。
     */
    private static void drawData(boolean[][] m, boolean[][] reserved,
                                byte[] codewords, int size) {
        int bit = 0;
        int total = codewords.length * 8;

        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;              // 跳过竖向 timing

            for (int step = 0; step < size; step++) {
                // 奇数组自下向上
                boolean upward = ((right + 1) & 2) == 0;
                int y = upward ? size - 1 - step : step;

                for (int col = 0; col < 2; col++) {
                    int x = right - col;
                    if (reserved[y][x]) continue;
                    if (bit >= total) continue;

                    int value = codewords[bit >>> 3] & 0xFF;
                    m[y][x] = ((value >>> (7 - (bit & 7))) & 1) == 1;
                    bit++;
                }
            }
        }
    }

    // ================= 掩码 =================

    private static boolean maskBit(int mask, int x, int y) {
        return switch (mask) {
            case 0 -> (x + y) % 2 == 0;
            case 1 -> y % 2 == 0;
            case 2 -> x % 3 == 0;
            case 3 -> (x + y) % 3 == 0;
            case 4 -> (y / 2 + x / 3) % 2 == 0;
            case 5 -> (x * y) % 2 + (x * y) % 3 == 0;
            case 6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0;
            case 7 -> ((x + y) % 2 + (x * y) % 3) % 2 == 0;
            default -> throw new IllegalArgumentException("掩码只有 0–7:" + mask);
        };
    }

    private static void applyMask(boolean[][] m, boolean[][] reserved, int size, int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (reserved[y][x]) continue;
                if (maskBit(mask, x, y)) m[y][x] = !m[y][x];
            }
        }
    }

    /**
     * 八个掩码各算一次罚分,取最低的。
     *
     * <p>罚分规则是规范定死的(N1=3 / N2=3 / N3=40 / N4=10),目的是让图案
     * 「不要有大片同色、不要出现像定位图案的序列」。随便挑一个掩码也能
     * 生成合法的码,但在某些内容上会得到扫不出来的图 ——
     * 而那是最难归因的一类失败:同一段代码,换个账号名就不行了。
     */
    private static int bestMask(boolean[][] modules, boolean[][] reserved, int size) {
        int best = 0;
        int bestScore = Integer.MAX_VALUE;

        for (int mask = 0; mask < 8; mask++) {
            var candidate = copy(modules);
            applyMask(candidate, reserved, size, mask);
            drawFormat(candidate, size, mask);

            int score = penalty(candidate, size);
            if (score < bestScore) {
                bestScore = score;
                best = mask;
            }
        }
        return best;
    }

    private static boolean[][] copy(boolean[][] m) {
        var out = new boolean[m.length][];
        for (int i = 0; i < m.length; i++) out[i] = m[i].clone();
        return out;
    }

    private static int penalty(boolean[][] m, int size) {
        int score = 0;

        // N1:同色连续 5 个以上,每多一个 +1
        for (int i = 0; i < size; i++) {
            score += runPenalty(m, size, i, true);
            score += runPenalty(m, size, i, false);
        }

        // N2:2×2 的同色块
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean v = m[y][x];
                if (v == m[y][x + 1] && v == m[y + 1][x] && v == m[y + 1][x + 1]) score += 3;
            }
        }

        // N3:1011101 前后带 4 个浅色 —— 像定位图案的序列
        for (int i = 0; i < size; i++) {
            score += finderLikePenalty(m, size, i, true);
            score += finderLikePenalty(m, size, i, false);
        }

        // N4:深色模块占比偏离 50% 每 5% +10
        int dark = 0;
        for (boolean[] row : m) {
            for (boolean v : row) if (v) dark++;
        }
        int percent = dark * 100 / (size * size);
        score += Math.abs(percent - 50) / 5 * 10;
        return score;
    }

    private static int runPenalty(boolean[][] m, int size, int line, boolean horizontal) {
        int score = 0;
        int run = 1;
        for (int i = 1; i < size; i++) {
            boolean previous = horizontal ? m[line][i - 1] : m[i - 1][line];
            boolean current = horizontal ? m[line][i] : m[i][line];
            if (current == previous) {
                run++;
                continue;
            }
            if (run >= 5) score += run - 2;
            run = 1;
        }
        if (run >= 5) score += run - 2;
        return score;
    }

    private static final boolean[] FINDER = {
            true, false, true, true, true, false, true};

    private static int finderLikePenalty(boolean[][] m, int size, int line, boolean horizontal) {
        int score = 0;
        for (int i = 0; i + 7 <= size; i++) {
            boolean matches = true;
            for (int k = 0; k < 7; k++) {
                boolean v = horizontal ? m[line][i + k] : m[i + k][line];
                if (v != FINDER[k]) {
                    matches = false;
                    break;
                }
            }
            if (!matches) continue;

            // 前后任一侧有 4 个浅色
            if (allLight(m, size, line, horizontal, i - 4, i)
                    || allLight(m, size, line, horizontal, i + 7, i + 11)) {
                score += 40;
            }
        }
        return score;
    }

    private static boolean allLight(boolean[][] m, int size, int line, boolean horizontal,
                                    int from, int to) {
        if (from < 0 || to > size) return false;
        for (int i = from; i < to; i++) {
            boolean v = horizontal ? m[line][i] : m[i][line];
            if (v) return false;
        }
        return true;
    }

    // ================= 格式与版本信息 =================

    /** 纠错级别 M 的两位指示符 */
    private static final int EC_LEVEL_M = 0b00;

    private static void drawFormat(boolean[][] m, int size, int mask) {
        int data = (EC_LEVEL_M << 3) | mask;
        int bch = bch(data, 0b10100110111, 10);
        int format = ((data << 10) | bch) ^ 0b101010000010010;

        for (int i = 0; i <= 5; i++) set(m, 8, i, format, 14 - i);
        set(m, 8, 7, format, 8);
        set(m, 8, 8, format, 7);
        set(m, 7, 8, format, 6);
        for (int i = 9; i <= 14; i++) set(m, 14 - i, 8, format, 14 - i);

        for (int i = 0; i <= 7; i++) set(m, size - 1 - i, 8, format, 14 - i);
        for (int i = 8; i <= 14; i++) set(m, 8, size - 15 + i, format, 14 - i);

        m[size - 8][8] = true;          // 恒为深色的那个模块
    }

    private static void drawVersion(boolean[][] m, int size, int version) {
        int bch = bch(version, 0b1111100100101, 12);
        int bits = (version << 12) | bch;

        for (int i = 0; i < 18; i++) {
            boolean bit = ((bits >>> i) & 1) == 1;
            int a = i / 3;
            int b = i % 3;
            m[size - 11 + b][a] = bit;
            m[a][size - 11 + b] = bit;
        }
    }

    /** BCH 校验位 —— 格式信息(10 位)与版本信息(12 位)共用一个算法 */
    private static int bch(int data, int generator, int bits) {
        int value = data << bits;
        int generatorBits = Integer.SIZE - Integer.numberOfLeadingZeros(generator);
        while (Integer.SIZE - Integer.numberOfLeadingZeros(value) >= generatorBits) {
            value ^= generator << (Integer.SIZE - Integer.numberOfLeadingZeros(value)
                    - generatorBits);
        }
        return value;
    }

    private static void set(boolean[][] m, int y, int x, int bits, int index) {
        m[y][x] = ((bits >>> index) & 1) == 1;
    }

    // ================= 位缓冲 =================

    private static final class BitBuffer {
        private final java.util.BitSet bits = new java.util.BitSet();
        private int length;

        void append(int value, int count) {
            for (int i = count - 1; i >= 0; i--) {
                bits.set(length++, ((value >>> i) & 1) == 1);
            }
        }

        int size() {
            return length;
        }

        byte[] toBytes() {
            var out = new byte[(length + 7) / 8];
            for (int i = 0; i < length; i++) {
                if (bits.get(i)) out[i / 8] |= (byte) (1 << (7 - i % 8));
            }
            return out;
        }
    }
}
