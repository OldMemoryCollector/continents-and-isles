package com.cai.continents_and_isles;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 「大陆与群岛」世界类型的群系源。
 * <p>
 * 与密度函数 {@link RadialLand} 使用同一套 {@link ContinentIslandField} 判定：
 * <ul>
 *   <li>大陆核心：不使用原版多噪声（60+ 群系碎片化），而是用大尺度气候噪声（温度/湿度/地形起伏）
 *       从少量大陆群系池中选取，每种群系占据大片面积，形成"地形全面、群系种类不多"的超大陆</li>
 *   <li>海岸带：委托原版多噪声源（continentalness 在此从陆地滑向深海，自然给出沙滩/浅海/海洋）</li>
 *   <li>外围岛屿：每个岛屿固定为一个群系（按岛屿网格单元哈希从岛群系池中选取）</li>
 *   <li>外围深海：委托原版多噪声源给出海洋群系</li>
 * </ul>
 * <p>
 * {@link #getNoiseBiome} 的判定优先级（自上而下，先命中先返回）：
 * <ol>
 *   <li>林地府邸固定点（周围强制黑森林）</li>
 *   <li>三个必生成大湖（湖面群系按湖型固定）</li>
 *   <li>群岛-环山带过渡湿地浅滩带（沼泽/红树林，与地形 ArchipelagoWetland 严格对齐）</li>
 *   <li>超大陆内部：群岛过渡带委托原版 → 扇区群系（山脉分级/群岛/配置化主附属）→ 普通大陆群系</li>
 *   <li>超大陆之外：外岛固定单群系；深海委托原版</li>
 * </ol>
 */
public class ContinentsAndIslesBiomeSource extends BiomeSource {

    /** 诊断日志 */
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ContinentsAndIslesBiomeSource.class);

    /** 湿地带采样日志计数（限制打印次数，避免刷屏） */
    private static int wetlandLogCount = 0;

    /**
     * 单个扇区的群系配置（从配置字符串解析完成后的数据）。
     * 主群系隐含权重 = 1.0 - sum(extrasWeight)（当 extrasWeight 加和 <=1 时）；
     * 若 extrasWeight 加和 > 1，则整体归一化保证主群系占比 > 0。
     */
    private record SectorBiomeData(
        Holder<Biome> main,
        List<Holder<Biome>> extras,
        double[] extrasCumulative  // 长度 = extras.size()，值为"绝对概率"（0~1，递增，最后一个 = sumExtrasP）
    ) {}

    /** 6 个固定扇区（0..5）的群系配置 */
    private SectorBiomeData[] sectorBiomeData;

    // ── 群系限制规则：区域枚举 & 缓存 ─────────────────────────────────────────
    /** 可施加群系限制的区域。only_biomes 支持多区域并集（并集=写进多个区域 only_biomes 列表）。 */
    enum RestrictRegion {
        RING_MOUNTAIN,            // 环山带（独立于扇区，dist > 0.95R）
        SECTOR_0, SECTOR_1, SECTOR_2, SECTOR_3, SECTOR_4, SECTOR_5, // 6 扇区
        ARCHIPELAGO_INNER_ISLAND  // 群岛扇区内部小岛（独立于扇区 2 本体 & 外岛）
    }

    /** 单个群系限制规则（从配置字符串→ResourceLocation 解析完成后的缓存） */
    private static final class BiomeRules {
        final Set<ResourceLocation> supercontinentBlacklist = new HashSet<>();
        final Set<ResourceLocation> outerIslandBlacklist    = new HashSet<>();
        final Set<ResourceLocation> ringMountainBlacklist   = new HashSet<>();
        final Set<ResourceLocation> ringMountainOnly        = new HashSet<>();
        final List<Set<ResourceLocation>> sectorBlacklist   = new ArrayList<>(6);
        final List<Set<ResourceLocation>> sectorOnly        = new ArrayList<>(6);
        final Set<ResourceLocation> innerIslandBlacklist    = new HashSet<>();
        final Set<ResourceLocation> innerIslandOnly         = new HashSet<>();
        /** 反向索引：群系 ResourceLocation → 它被列进 only_biomes 的区域集合（并集白名单） */
        final Map<ResourceLocation, EnumSet<RestrictRegion>> onlyIndex = new HashMap<>();

        BiomeRules() {
            for (int i = 0; i < 6; i++) {
                sectorBlacklist.add(new HashSet<>());
                sectorOnly.add(new HashSet<>());
            }
        }
    }

    /** 群系限制规则缓存（首次使用时解析配置） */
    private BiomeRules biomeRules;

    /** 外岛群系黑名单（资源定位符），超大陆外围岛屿上禁止出现的群系
     *  @deprecated 已迁移到 BiomeRules.outerIslandBlacklist；保留字段避免直接引用崩溃，初始化时会同步填充。 */
    @Deprecated
    private Set<ResourceLocation> outerIslandBlacklist = Set.of();

    /** Biomes O' Plenty 的 outback 群系（懒加载：模组未加载时为 null，沙漠扇区作为附属群系） */
    private Holder<Biome> bopOutbackCache;
    private boolean bopOutbackChecked;

    /** 石头滩群系（左右两侧悬崖海岸用，懒加载） */
    private Holder<Biome> stonyShoreCache;
    private boolean stonyShoreChecked;

    public static final MapCodec<ContinentsAndIslesBiomeSource> CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
            BiomeSource.CODEC.fieldOf("delegate").forGetter(ContinentsAndIslesBiomeSource::delegate),
            Codec.INT.fieldOf("radius").forGetter(ContinentsAndIslesBiomeSource::radius),
            Codec.INT.fieldOf("transition").forGetter(ContinentsAndIslesBiomeSource::transition),
            Codec.INT.fieldOf("grid").forGetter(ContinentsAndIslesBiomeSource::grid),
            Codec.DOUBLE.fieldOf("island_chance").forGetter(ContinentsAndIslesBiomeSource::islandChance),
            Biome.CODEC.listOf().fieldOf("island_biomes").forGetter(ContinentsAndIslesBiomeSource::islandPool),
            Biome.CODEC.listOf().fieldOf("mainland_biomes").forGetter(ContinentsAndIslesBiomeSource::mainlandPool)
        ).apply(instance, ContinentsAndIslesBiomeSource::new)
    );

    private BiomeSource delegate;
    private int radius;
    private int transition;
    private int grid;
    private double islandChance;
    /** 扇区配置：构造函数读取并缓存（getNoiseBiome 高频调用，避免每次 new） */
    private ContinentIslandField.Config cfg;
    private List<Holder<Biome>> islandPool;
    private List<Holder<Biome>> mainlandPool;

    public ContinentsAndIslesBiomeSource(
        BiomeSource delegate,
        int radius,
        int transition,
        int grid,
        double islandChance,
        List<Holder<Biome>> islandPool,
        List<Holder<Biome>> mainlandPool
    ) {
        this.delegate = delegate;
        // JSON 传入的 radius/transition/grid/islandChance 只是注册期静态占位：
        // 注册期早于配置文件加载，真实数值在 ensureConfig() 首次调用时
        // 从 CAIConfig / ContinentIslandField 静态变量刷新（世界生成开始后）。
        this.radius = radius;
        this.transition = transition;
        this.grid = grid;
        this.islandChance = islandChance;
        // cfg / sectorBiomeData / outerIslandBlacklist 全部延迟到第一次 getNoiseBiome() 初始化：
        // 构造函数阶段世界生成注册表尚未完整绑定，此时遍历 delegate.possibleBiomes()
        // 或读取配置可能崩溃；首次群系分配时世界已就绪，读取安全。
        this.cfg = null;
        this.islandPool = islandPool;
        this.mainlandPool = mainlandPool;
    }

    /** 判定某群系是否在外岛黑名单中（若没有 key 或未命中返回 false） */
    private boolean isOuterIslandBlacklisted(Holder<Biome> biome) {
        return biome.unwrapKey()
            .map(key -> this.outerIslandBlacklist.contains(key.location()))
            .orElse(false);
    }

    /** 外岛黑名单兜底：返回一个安全的岛屿群系（优先用 islandPool 第一个非黑条目 → 平原 → 森林） */
    private Holder<Biome> outerIslandFallback() {
        for (Holder<Biome> h : this.islandPool) {
            if (!isOuterIslandBlacklisted(h)) return h;
        }
        // mainlandPool 索引：1 = plains, 16 = forest
        if (this.mainlandPool.size() > 16) {
            Holder<Biome> forest = this.mainlandPool.get(16);
            if (!isOuterIslandBlacklisted(forest)) return forest;
        }
        if (this.mainlandPool.size() > 1) {
            return this.mainlandPool.get(1);
        }
        return this.mainlandPool.get(0);
    }

    /** 石头滩群系（左右两侧悬崖海岸用，懒加载；找不到时退回沙滩） */
    private Holder<Biome> stonyShore() {
        if (!this.stonyShoreChecked) {
            this.stonyShoreChecked = true;
            this.stonyShoreCache = findBiome("minecraft:stony_shore", this.mainlandPool.get(BEACH));
        }
        return this.stonyShoreCache;
    }

    public BiomeSource delegate() {
        return this.delegate;
    }

    public int radius() {
        return this.radius;
    }

    public int transition() {
        return this.transition;
    }

    public int grid() {
        return this.grid;
    }

    public double islandChance() {
        return this.islandChance;
    }

    public List<Holder<Biome>> islandPool() {
        return this.islandPool;
    }

    public List<Holder<Biome>> mainlandPool() {
        return this.mainlandPool;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return Stream.concat(
            this.delegate.possibleBiomes().stream(),
            Stream.concat(this.mainlandPool.stream(), this.islandPool.stream())
        ).distinct();
    }

    /** 配置懒加载：首次调用（世界生成，晚于 ServerAboutToStart）时用静态变量填充 radius、cfg */
    private void ensureConfig() {
        if (this.cfg != null) return;
        // 【双保险】在第一次群系分配时同步配置（世界已存在，CAIConfig 必已加载）
        ContinentIslandField.ensureConfigLoaded();
        // 直接读 CAIConfig（不再依赖 ServerAboutToStart 的时序）
        try {
            this.radius = CAIConfig.RADIUS.get();
            this.transition = CAIConfig.TRANSITION.get();
            this.grid = CAIConfig.GRID.get();
            this.islandChance = CAIConfig.ISLAND_CHANCE.get();
        } catch (Exception ignored) {
            // 极端情况：回退 ContinentIslandField 的硬编码默认
            this.radius = ContinentIslandField.continentRadius;
            this.transition = ContinentIslandField.continentTransition;
            this.grid = ContinentIslandField.continentGrid;
            this.islandChance = ContinentIslandField.continentIslandChance;
        }
        this.cfg = new ContinentIslandField.Config(this.radius, this.transition, this.grid, this.islandChance);
        // 初始化群系限制规则（黑名单 + only_biomes 反向索引）
        ensureBiomeRules();
    }

    /** 懒加载 BiomeRules 并同步填充 legacy outerIslandBlacklist（保持向后引用兼容） */
    private void ensureBiomeRules() {
        if (this.biomeRules != null) return;
        this.biomeRules = buildBiomeRules();
        // 同步 legacy 字段（pickIslandBiome / isOuterIslandBlacklisted 仍可能直接引用）
        this.outerIslandBlacklist = this.biomeRules.outerIslandBlacklist;
    }

    /** 从 CAIConfig 构建 BiomeRules（解析 23 项字符串→ResourceLocation，并预构建 onlyIndex） */
    private BiomeRules buildBiomeRules() {
        BiomeRules r = new BiomeRules();
        try {
            for (String s : CAIConfig.SUPERCONTINENT_BIOME_BLACKLIST.get()) parseAdd(s, r.supercontinentBlacklist);
        } catch (Exception ignored) {}
        try {
            for (String s : CAIConfig.OUTER_ISLAND_BIOME_BLACKLIST.get()) parseAdd(s, r.outerIslandBlacklist);
        } catch (Exception ignored) {}
        try {
            for (String s : CAIConfig.RING_MOUNTAIN_BIOME_BLACKLIST.get()) parseAdd(s, r.ringMountainBlacklist);
        } catch (Exception ignored) {}
        try {
            for (String s : CAIConfig.RING_MOUNTAIN_ONLY_BIOMES.get()) {
                ResourceLocation loc = parseLoc(s);
                if (loc != null) {
                    r.ringMountainOnly.add(loc);
                    r.onlyIndex.computeIfAbsent(loc, k -> EnumSet.noneOf(RestrictRegion.class))
                              .add(RestrictRegion.RING_MOUNTAIN);
                }
            }
        } catch (Exception ignored) {}
        // 6 扇区
        var secBL = List.of(CAIConfig.SECTOR_0_BIOME_BLACKLIST, CAIConfig.SECTOR_1_BIOME_BLACKLIST,
                            CAIConfig.SECTOR_2_BIOME_BLACKLIST, CAIConfig.SECTOR_3_BIOME_BLACKLIST,
                            CAIConfig.SECTOR_4_BIOME_BLACKLIST, CAIConfig.SECTOR_5_BIOME_BLACKLIST);
        var secOL = List.of(CAIConfig.SECTOR_0_ONLY_BIOMES, CAIConfig.SECTOR_1_ONLY_BIOMES,
                            CAIConfig.SECTOR_2_ONLY_BIOMES, CAIConfig.SECTOR_3_ONLY_BIOMES,
                            CAIConfig.SECTOR_4_ONLY_BIOMES, CAIConfig.SECTOR_5_ONLY_BIOMES);
        var secReg = List.of(RestrictRegion.SECTOR_0, RestrictRegion.SECTOR_1, RestrictRegion.SECTOR_2,
                             RestrictRegion.SECTOR_3, RestrictRegion.SECTOR_4, RestrictRegion.SECTOR_5);
        for (int s = 0; s < 6; s++) {
            try {
                for (String str : secBL.get(s).get()) parseAdd(str, r.sectorBlacklist.get(s));
            } catch (Exception ignored) {}
            try {
                RestrictRegion reg = secReg.get(s);
                for (String str : secOL.get(s).get()) {
                    ResourceLocation loc = parseLoc(str);
                    if (loc != null) {
                        r.sectorOnly.get(s).add(loc);
                        r.onlyIndex.computeIfAbsent(loc, k -> EnumSet.noneOf(RestrictRegion.class))
                                  .add(reg);
                    }
                }
            } catch (Exception ignored) {}
        }
        // E. 群岛内岛
        try {
            for (String s : CAIConfig.ARCHIPELAGO_INNER_ISLAND_BIOME_BLACKLIST.get()) parseAdd(s, r.innerIslandBlacklist);
        } catch (Exception ignored) {}
        try {
            for (String s : CAIConfig.ARCHIPELAGO_INNER_ISLAND_ONLY_BIOMES.get()) {
                ResourceLocation loc = parseLoc(s);
                if (loc != null) {
                    r.innerIslandOnly.add(loc);
                    r.onlyIndex.computeIfAbsent(loc, k -> EnumSet.noneOf(RestrictRegion.class))
                              .add(RestrictRegion.ARCHIPELAGO_INNER_ISLAND);
                }
            }
        } catch (Exception ignored) {}
        return r;
    }

    private static ResourceLocation parseLoc(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        try { return ResourceLocation.parse(t); } catch (Exception e) { return null; }
    }
    private static void parseAdd(String s, Set<ResourceLocation> set) {
        ResourceLocation l = parseLoc(s);
        if (l != null) set.add(l);
    }

    // ─── 统一群系限制过滤器 ────────────────────────────────────────────────
    /**
     * 当前点所在区域（用于 only_biomes 并集白名单判定）。
     * 外岛/超大陆整体判定由调用方直接用 boolean 表达，不进 RestrictRegion（避免把「大陆整体」当 only_biomes 目标）。
     * 传入的 EnumSet 可为空，为空视为「不命中任何 RestrictRegion 区域」。
     *
     * @param biome     候选群系
     * @param inSupercontinent 当前点是否位于超大陆内（dist < radius + transition）
     * @param isOuterIsland    当前点是否属于外海群岛（命中 pickIslandBiome 分支）
     * @param isArchipelagoInnerIsland 当前点是否是群岛扇区内部小岛（命中 randomIslandBiome 分支）
     * @param ringMountainHit  当前点是否命中环山带（dist > 0.95*R && ringMountainEnabled，进入环山带群系分支）
     * @param sectorHit        当前点所属扇区（0..5，仅当在扇区逻辑分支内才传入；-1 表示不在扇区内）
     * @param fallback         过滤不通过时的兜底群系（不得为 null）
     * @return 过滤后的群系（通过=原群系；不通过=fallback）
     */
    private Holder<Biome> applyBiomeRules(Holder<Biome> biome,
                                          boolean inSupercontinent,
                                          boolean isOuterIsland,
                                          boolean isArchipelagoInnerIsland,
                                          boolean ringMountainHit,
                                          int sectorHit,
                                          Holder<Biome> fallback) {
        if (biome == null) return fallback;
        if (this.biomeRules == null) ensureBiomeRules();
        BiomeRules r = this.biomeRules;
        var keyOpt = biome.unwrapKey();
        if (keyOpt.isEmpty()) return biome;
        ResourceLocation loc = keyOpt.get().location();

        // ① 超大陆整体黑名单（最高优先级）
        if (inSupercontinent && r.supercontinentBlacklist.contains(loc)) return fallback;
        // ② 外岛黑名单
        if (isOuterIsland && r.outerIslandBlacklist.contains(loc)) return fallback;

        // ③ 各区域独立黑名单（按当前点落在哪个限制区域来检查）
        if (inSupercontinent) {
            if (ringMountainHit && r.ringMountainBlacklist.contains(loc)) return fallback;
            if (sectorHit >= 0 && sectorHit < 6 && r.sectorBlacklist.get(sectorHit).contains(loc)) return fallback;
            if (isArchipelagoInnerIsland && r.innerIslandBlacklist.contains(loc)) return fallback;
        }

        // ④ only_biomes 并集白名单：若群系被任一区域的 only_biomes 收录，
        //    则当前点必须同时属于其中至少一个允许区域；否则禁止。
        EnumSet<RestrictRegion> allow = r.onlyIndex.get(loc);
        if (allow != null && !allow.isEmpty()) {
            boolean allowedRegionHit = false;
            if (inSupercontinent) {
                // 只要当前点在任一「命中区域」里就通过（并集）
                if (ringMountainHit && allow.contains(RestrictRegion.RING_MOUNTAIN)) allowedRegionHit = true;
                if (!allowedRegionHit && sectorHit >= 0 && sectorHit < 6) {
                    RestrictRegion reg = switch (sectorHit) {
                        case 0 -> RestrictRegion.SECTOR_0;
                        case 1 -> RestrictRegion.SECTOR_1;
                        case 2 -> RestrictRegion.SECTOR_2;
                        case 3 -> RestrictRegion.SECTOR_3;
                        case 4 -> RestrictRegion.SECTOR_4;
                        case 5 -> RestrictRegion.SECTOR_5;
                        default -> null;
                    };
                    if (reg != null && allow.contains(reg)) allowedRegionHit = true;
                }
                if (!allowedRegionHit && isArchipelagoInnerIsland
                    && allow.contains(RestrictRegion.ARCHIPELAGO_INNER_ISLAND)) {
                    allowedRegionHit = true;
                }
            }
            if (!allowedRegionHit) return fallback;
        }
        return biome;
    }

    /** 简化版：扇区内（环带/群岛内岛为 false）的通用过滤，fallback = 对应扇区主群系 */
    private Holder<Biome> applySectorRules(Holder<Biome> biome, int sector, double px, double pz, Holder<Biome> fallback) {
        double dist = Math.sqrt(px * px + pz * pz);
        boolean inSC = dist < this.radius + this.transition;
        boolean ringHit = ContinentIslandField.ringMountainEnabled && dist > this.radius * 0.95;
        // sectorHit 总是通过（sector 是外部给定）；环山带如果同时命中也要把环山带算进去。
        // 但扇区内部调用时，环山带其实是独立区域——若当前点其实属于环山带范围，onlyIndex 需要同时检查环山带命中。
        // 解决：同时传 ringMountainHit=true 当且仅当 dist>0.95R（这样 allow 中的 SECTOR_X 和 RING_MOUNTAIN 都能被命中）
        return applyBiomeRules(biome, inSC, false, false, ringHit, sector, fallback);
    }

    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        ensureConfig();
        double bx = x * 4.0;
        double bz = z * 4.0;
        ContinentIslandField.Config cfg = this.cfg;
        // 预计算 dist（晚一点也可以，但所有分支都需要判断是否在超大陆内）
        double dist = 0.0;
        boolean distComputed = false;
        Holder<Biome> fallbackPlain = this.mainlandPool.get(1); // 兜底：平原

        // 林地府邸固定点：周围 128×128 格区域强制黑森林群系
        // （府邸是 80×80 格建筑且只能建在黑森林中，给足环境避免落在其他群系）
        net.minecraft.world.level.ChunkPos mansionChunk = ContinentIslandField.woodlandMansionChunkPos();
        if (mansionChunk != null) {
            double mcx = mansionChunk.getMiddleBlockX();
            double mcz = mansionChunk.getMiddleBlockZ();
            if (Math.abs(bx - mcx) <= 64.0 && Math.abs(bz - mcz) <= 64.0) {
                if (!distComputed) { dist = Math.sqrt(bx*bx + bz*bz); distComputed = true; }
                boolean inSC = dist < this.radius + this.transition;
                boolean ringHit = ContinentIslandField.ringMountainEnabled && dist > this.radius * 0.95;
                // 林邸位置属于普通大陆区域（无扇区/内岛标志），扇区按 -1 走；通过 mask 自行判定若在扇区外
                Holder<Biome> cand = this.mainlandPool.get(12);
                return applyBiomeRules(cand, inSC, false, false, ringHit, sectorAt(bx, bz), fallbackPlain);
            }
        }

        // 必生成大湖（三个湖之一）
        double lake = ContinentIslandField.lakeValue(bx, bz, cfg);
        if (!Double.isNaN(lake)) {
            if (!distComputed) { dist = Math.sqrt(bx*bx + bz*bz); distComputed = true; }
            boolean inSC = dist < this.radius + this.transition;
            boolean ringHit = ContinentIslandField.ringMountainEnabled && dist > this.radius * 0.95;
            int secAt = sectorAt(bx, bz);
            // 找到命中的湖
            int hit = -1;
            for (int i = 0; i < ContinentIslandField.LAKE_COUNT; i++) {
                if (!Double.isNaN(ContinentIslandField.lakeValueAt(i, bx, bz, cfg))) {
                    hit = i;
                    break;
                }
            }
            if (hit >= 0) {
                int type = ContinentIslandField.lakeType[hit];
                if (type == 0 && lake < 0) {
                    // 群系湖：湖面固定为该湖对应的群系（含海洋与陆地群系，逻辑与陆地群系一致），岛屿仍委托原版
                    int biomeIdx = LAKE_BIOME_MAP[ContinentIslandField.lakeBiomeIndex[hit]];
                    // 非雪原/山脉扇区禁止冰冻海洋湖面（含雪/冰）
                    if (biomeIdx == 22 && !allowSnow(bx, bz)) {
                        biomeIdx = 20; // 改为温水海洋
                    }
                    return applyBiomeRules(this.mainlandPool.get(biomeIdx), inSC, false, false, ringHit, secAt, this.mainlandPool.get(20));
                }
                if (type == 1) {
                    // 深湖：湖面统一深海、湖岸陆地统一樱花树林
                    double b = ContinentIslandField.bias(bx, bz, cfg);
                    Holder<Biome> base = (b >= ContinentIslandField.LAND_BIAS_THRESHOLD)
                        ? this.mainlandPool.get(CHERRY_GROVE)
                        : this.mainlandPool.get(DEEP_OCEAN);
                    // 山湖在山脉扇区（0）：即使 hitSec 检测不到也给 0
                    int mountainSec = 0;
                    return applyBiomeRules(base, inSC, false, false, ringHit, mountainSec, this.mainlandPool.get(DEEP_OCEAN));
                }
                if (type == 2 && lake < 0) {
                    // 岛湖水面：固定普通海洋（湖中岛屿仍委托原版多噪声）
                    return applyBiomeRules(this.mainlandPool.get(ISLAND_SECTOR_OCEAN), inSC, false, false, ringHit,
                                           ContinentIslandField.ISLAND_SECTOR, this.mainlandPool.get(25));
                }
            }
            // 岛湖/群系湖的岛屿：委托原版多噪声（水与岛群系自然给出），并过滤含雪群系
            Holder<Biome> delegateBiome = this.delegate.getNoiseBiome(x, y, z, sampler);
            Holder<Biome> resolved = (this.isExcluded(delegateBiome) || (isSnowy(delegateBiome) && !allowSnow(bx, bz)))
                ? this.pickMainlandBiome(x, y, z, sampler)
                : delegateBiome;
            return applyBiomeRules(resolved, inSC, false, false, ringHit, secAt, fallbackPlain);
        }
        // ===== 群岛-环山带过渡湿地浅滩带（0.80R~0.98R，限群岛扇区角度）=====
        double wetBand = ContinentIslandField.archipelagoWetlandBand(bx, bz, this.radius);
        if (wetBand > 0.01) {
            logWetlandSample(bx, bz, wetBand);
            if (!distComputed) { dist = Math.sqrt(bx*bx + bz*bz); distComputed = true; }
            boolean inSC = dist < this.radius + this.transition;
            boolean ringHit = ContinentIslandField.ringMountainEnabled && dist > this.radius * 0.95;
            // 湿地带落在群岛扇区角度范围 → 扇区 2 本体
            Holder<Biome> cand;
            if (wetBand >= 0.10) {
                double wr = ContinentIslandField.valueNoise(bx, bz, 180, 9101);
                cand = (wr < 0.45) ? this.mainlandPool.get(MANGROVE_SWAMP) : this.mainlandPool.get(SWAMP);
            } else {
                // 带边缘（0.01~0.10）：只进不退
                double baseT = (wetBand - 0.01) / 0.09;
                double noise = ContinentIslandField.valueNoise(bx, bz, 40, 9102);
                double swampProb = Math.min(1.0, baseT + noise * (1.0 - baseT) * 0.8);
                if (swampProb > 0.25) {
                    double wr = ContinentIslandField.valueNoise(bx, bz, 180, 9101);
                    cand = (wr < 0.45) ? this.mainlandPool.get(MANGROVE_SWAMP) : this.mainlandPool.get(SWAMP);
                } else {
                    cand = this.mainlandPool.get(ISLAND_SECTOR_OCEAN);
                }
            }
            return applyBiomeRules(cand, inSC, false, false, ringHit,
                                   ContinentIslandField.ISLAND_SECTOR, this.mainlandPool.get(SWAMP));
        }
        if (!distComputed) { dist = Math.sqrt(bx*bx + bz*bz); distComputed = true; }
        if (dist < this.radius) {
            ContinentIslandField.Config cfgIsl = this.cfg;
            double islExtHere = ContinentIslandField.islandSectorFalloff(bx, bz, cfgIsl);
            if (islExtHere > 0.10) {
                if (islExtHere <= 0.34) {
                    // ===== 沙滩带（falloff 0.0534~0.34）=====
                    double bAngle = Math.atan2(bz, bx);
                    double bCenter = ContinentIslandField.sectorCenterAngle(ContinentIslandField.ISLAND_SECTOR);
                    double bDelta = Math.abs(Math.atan2(Math.sin(bAngle - bCenter), Math.cos(bAngle - bCenter)));
                    double bHalf = ContinentIslandField.islandSectorHalfRad();
                    if (bDelta < bHalf * 0.85) {
                        Holder<Biome> cand = this.mainlandPool.get(BEACH);
                        boolean ringHit = ContinentIslandField.ringMountainEnabled && dist > this.radius * 0.95;
                        return applyBiomeRules(cand, true, false, false, ringHit,
                                               ContinentIslandField.ISLAND_SECTOR, this.mainlandPool.get(BEACH));
                    }
                    // 两侧收窄区：大陆群系
                    return this.pickMainlandBiome(x, y, z, sampler);
                }
                // falloff > 0.34：群岛内部正常群系（小岛/内海）
                return this.pickMainlandBiome(x, y, z, sampler);
            }
            // 大陆核心
            return this.pickMainlandBiome(x, y, z, sampler);
        }
        boolean land = ContinentIslandField.bias(bx, bz, cfg) >= ContinentIslandField.LAND_BIAS_THRESHOLD;
        boolean outerLandBleed = land
            && dist >= this.radius
            && dist < this.radius + this.transition
            && ContinentIslandField.isOuterIslandLand(bx, bz, cfg);
        if (((dist >= this.radius + this.transition && land) || outerLandBleed) && !this.islandPool.isEmpty()) {
            // 外围岛屿：每个岛屿固定一个群系（内部自带 outer 黑名单 + 现在叠加规则过滤）
            Holder<Biome> base = this.pickIslandBiome(bx, bz, cfg);
            boolean inSC = dist < this.radius + this.transition;
            // 外岛：传 sectorHit=-1（不在扇区）、ringHit=false（外岛不在大陆环山带）
            return applyBiomeRules(base, inSC, true, false, false, -1, outerIslandFallback());
        }
        // 超大陆、海岸带、外围深海：委托原版多噪声源
        Holder<Biome> delegateBiome = this.delegate.getNoiseBiome(x, y, z, sampler);
        boolean snowBan = dist < this.radius + this.transition;
        Holder<Biome> resolved = (this.isExcluded(delegateBiome) || (snowBan && isSnowy(delegateBiome) && !allowSnow(bx, bz)))
            ? this.pickMainlandBiome(x, y, z, sampler)
            : delegateBiome;
        boolean inSC = dist < this.radius + this.transition;
        boolean ringHit = ContinentIslandField.ringMountainEnabled && dist > this.radius * 0.95;
        boolean outerIslandHere = (dist >= this.radius + this.transition && land);
        resolved = applyBiomeRules(resolved, inSC, outerIslandHere, false, ringHit, sectorAt(bx, bz), fallbackPlain);
        // 保持原有外岛黑名单兜底（legacy 逻辑，防止上面 applyBiomeRules 仅返回 fallback 但用户期望外岛专属兜底）
        if (outerIslandHere && isOuterIslandBlacklisted(resolved)) {
            return outerIslandFallback();
        }
        return resolved;
    }

    /** 返回某坐标所属扇区（仅作「命中区域」参考：-1 表示不在任何扇区）。
     *  用 best-mask 算法与 pickMainlandBiome 的扇区选择一致，threshold=0.22。 */
    private int sectorAt(double px, double pz) {
        ContinentIslandField.Config c = this.cfg;
        double best = 0.0;
        int bestS = -1;
        for (int i = 0; i < 6; i++) {
            double m = (i == MOUNTAIN_SECTOR)
                ? ContinentIslandField.mountainValue(px, pz, this.radius)
                : ContinentIslandField.sectorMask(i, px, pz, c);
            if (m > best) { best = m; bestS = i; }
        }
        return (best >= 0.22) ? bestS : -1;
    }

    /** 湿地带采样日志（限 10 次）：打印湿地实际出现位置的角度与群岛扇区中心角，
     *  用于核对"湿地带限群岛扇区角度"是否在运行时真正生效 */
    private static void logWetlandSample(double bx, double bz, double band) {
        if (wetlandLogCount >= 10) {
            return;
        }
        wetlandLogCount++;
        double ang = Math.toDegrees(Math.atan2(bz, bx));
        double center = Math.toDegrees(ContinentIslandField.sectorCenterAngle(ContinentIslandField.ISLAND_SECTOR));
        LOGGER.info("WETLAND sample: x={}, z={}, angle={}°, islandSectorCenter={}°, band={}",
            (int) bx, (int) bz,
            String.format(java.util.Locale.ROOT, "%.1f", ang),
            String.format(java.util.Locale.ROOT, "%.1f", center),
            String.format(java.util.Locale.ROOT, "%.3f", band));
    }

    /** 剔除群系判定：冰刺平原、恶地（含变种）、风袭系不在超大陆生成 */
    private boolean isExcluded(Holder<Biome> biome) {
        return biome.unwrapKey()
            .map(key -> EXCLUDED_MAINLAND.contains(key.location()))
            .orElse(false);
    }

    /** 该群系是否含雪（雪原/冰封类）——非雪原扇区、非山脉扇区禁止出现 */
    private boolean isSnowy(Holder<Biome> biome) {
        return biome.unwrapKey()
            .map(key -> {
                String p = key.location().getPath();
                return p.contains("snowy") || p.contains("frozen") || p.contains("ice_spikes") || p.equals("grove");
            })
            .orElse(false);
    }

    /** 该位置是否允许雪群系：
     *  - 雪原扇区（扇区5）与山脉扇区（扇区0）→ 允许（含两扇区内的环山带）
     *  - 群岛扇区（扇区2）岛屿区（islandSectorFalloff ≥ 0.40 且远离湿地带）→ 允许
     *    让群岛内部高大岛屿可自然生成寒冷群系
     *  - 其他扇区（丛林/沙漠/热草）或 非山脉/雪原扇区角度下的环山带 → 一律禁止
     *  - 湿地带（archipelagoWetlandBand > 0.01）→ 在调用方单独处理（替换为湿地群系） */
    private boolean allowSnow(double px, double pz) {
        ContinentIslandField.Config cfg = this.cfg;
        // 先判断角度：只在雪原扇区(5)或山脉扇区(0)的楔形范围内才允许下雪
        // 这样无论 dist 是 0.95R 以内还是以外，群岛/丛林/沙漠/热草扇区的环山带都不会出现雪群系
        double snowMask = ContinentIslandField.sectorMask(5, px, pz, cfg);
        double mountainMask = ContinentIslandField.sectorMask(0, px, pz, cfg);
        // 角度判定（不 warp，纯几何角度差）用于 dist>0.95R 的环山带雪控制，
        // 彻底杜绝 sectorMask 角度扭曲/径向交叠造成的"边缘漏网"：
        // 只有精确位于扇区 0(山脉)/5(雪原) 的半宽 ±20°(不扭曲)之内，环山带才允许雪。
        double angle = Math.atan2(pz, px);
        double snowCenter = ContinentIslandField.sectorCenterAngle(5);
        double mountainCenter = ContinentIslandField.sectorCenterAngle(0);
        double deltaSnow = Math.abs(Math.atan2(Math.sin(angle - snowCenter), Math.cos(angle - snowCenter)));
        double deltaMountain = Math.abs(Math.atan2(Math.sin(angle - mountainCenter), Math.cos(angle - mountainCenter)));
        double half = Math.toRadians(ContinentIslandField.sectorHalfWidthDeg);
        boolean inSnowOrMountainSector = (snowMask > 0.05) || (mountainMask > 0.05);
        boolean ringInSnowOrMountainSector = (deltaSnow < half * 1.0) || (deltaMountain < half * 1.0);

        double mountHeight = ContinentIslandField.mountainValue(px, pz, this.radius);
        double dist = Math.sqrt(px * px + pz * pz);

        // 环山区（0.95R 以外）：仅雪原/山脉扇区角度内 + 山高足够 + 噪声打破完美环形，才落雪；
        // 额外豁免：群岛扇区内的高大岛屿（islExt>=0.40 且非湿地带）也可寒冷群系（单岛单群系、含雪允许）
        if (dist > this.radius * 0.95) {
            if (!ringInSnowOrMountainSector) {
                // 非雪原/山脉扇区角度 → 严格禁雪，除非是群岛扇区内部的岛屿区域（islExt>=0.40 且 不在湿地带）
                double islExt = ContinentIslandField.islandSectorFalloff(px, pz, cfg);
                if (islExt >= 0.40) {
                    double wet = ContinentIslandField.archipelagoWetlandBand(px, pz, this.radius);
                    return wet <= 0.01; // 仅非湿地带的群岛内部小岛允许雪
                }
                return false; // 丛林/沙漠/热草/群岛扇区的环山带 → 严格禁止雪
            }
            double ringNoise = ContinentIslandField.valueNoise(px, pz, 80, 8800);
            return mountHeight > 0.35 && ringNoise > 0.30;
        }

        // 0.95R 以内：雪原扇区 或 山脉扇区山高足够 → 允许
        if (snowMask > 0.35 || (mountainMask > 0.35 && mountHeight > 0.35)) {
            return true;
        }
        // 群岛扇区内部岛屿：允许寒冷群系，但湿地带除外
        double islExt = ContinentIslandField.islandSectorFalloff(px, pz, cfg);
        if (islExt >= 0.40) {
            double wet = ContinentIslandField.archipelagoWetlandBand(px, pz, this.radius);
            return wet <= 0.01; // 岛区（非湿地带）允许雪
        }
        return false;
    }

    /** 超大陆中剔除的群系：冰刺平原、恶地（含变种）、风袭系。海岸带委托原版时也可能给出，需过滤 */
    private static final Set<ResourceLocation> EXCLUDED_MAINLAND = Set.of(
        ResourceLocation.withDefaultNamespace("ice_spikes"),
        ResourceLocation.withDefaultNamespace("badlands"),
        ResourceLocation.withDefaultNamespace("eroded_badlands"),
        ResourceLocation.withDefaultNamespace("wooded_badlands"),
        ResourceLocation.withDefaultNamespace("windswept_gravelly_hills"),
        ResourceLocation.withDefaultNamespace("windswept_hills"),
        ResourceLocation.withDefaultNamespace("windswept_forest")
    );

    /**
     * 群系湖可选群系 → mainlandPool 索引映射（海洋与陆地群系均可作为湖面，逻辑与陆地群系一致）：
     * 0=温水海洋, 1=温水海洋, 2=冷水海洋, 3=冰冻海洋,
     * 4=沼泽, 5=红树林沼泽, 6=丛林, 7=竹林, 8=蘑菇岛, 9=樱花树林
     */
    private static final int[] LAKE_BIOME_MAP = { 19, 20, 21, 22, 6, 17, 4, 16, 24, 23 };

    /** 群岛扇区（原沼泽位置）的岛间海面群系索引（mainlandPool 中的普通海洋） */
    private static final int ISLAND_SECTOR_OCEAN = 25;

    /** 沼泽群系索引（mainlandPool 中的 swamp，群岛-环山带过渡湿地带用） */
    private static final int SWAMP = 6;

    /** 红树林沼泽群系索引（mainlandPool 中的 mangrove_swamp，湿地带外侧浅水用） */
    private static final int MANGROVE_SWAMP = 17;

    /** 樱花树林群系索引（mainlandPool 中的 cherry_grove） */
    private static final int CHERRY_GROVE = 23;

    /** 冰封峰顶群系索引（mainlandPool 中的 frozen_peaks，雪山真实化） */
    private static final int FROZEN_PEAKS = 26;

    /** 沙滩群系索引（mainlandPool 中的 beach，群岛扇区过渡带强制沙滩带用） */
    private static final int BEACH = 27;

    /** 深海群系索引（mainlandPool 中的 deep_ocean，海洋神殿保留区） */
    private static final int DEEP_OCEAN = 32;

    /** 河流群系索引（mainlandPool 中的 river，群岛过渡带河网用） */
    private static final int RIVER = 33;

    /** 原始桦木森林（桦木森林变种，更高的白桦树）索引 */
    private static final int OLD_GROWTH_BIRCH_FOREST = 34;

    /** 原始松木针叶林（针叶林变种）索引 */
    private static final int OLD_GROWTH_PINE_TAIGA = 35;

    /** 原始云杉针叶林（针叶林变种）索引 */
    private static final int OLD_GROWTH_SPRUCE_TAIGA = 36;

    /** 山脉扇区索引（山峰→山脉分级，扇区 0） */
    private static final int MOUNTAIN_SECTOR = 0;

    /** 群岛小岛可用群系全池缓存（含其他模组群系，剔除海洋类），惰性构建 */
    private List<Holder<Biome>> allLandBiomesCache;

    private Holder<Biome> pickMainlandBiome(int x, int y, int z, Climate.Sampler sampler) {
        double px = x * 4.0;
        double pz = z * 4.0;
        double dist = Math.sqrt(px * px + pz * pz);
        double angle = Math.atan2(pz, px);
        List<Holder<Biome>> pool = this.mainlandPool;
        ContinentIslandField.Config cfg = this.cfg;

        // 计算 6 个扇区的平滑 mask，取最强与次强（扇区边界用宽过渡带平滑衰减）。
        // 山脉扇区用 mountainValue（蜿蜒+峰谷结构），与 MountainSector 地形抬升完全一致
        double best = 0.0;
        double second = 0.0;
        int bestS = -1;
        int secondS = -1;
        for (int i = 0; i < 6; i++) {
            double m = (i == MOUNTAIN_SECTOR)
                ? ContinentIslandField.mountainValue(px, pz, this.radius)
                : ContinentIslandField.sectorMask(i, px, pz, cfg);
            if (m > best) {
                second = best;
                secondS = bestS;
                best = m;
                bestS = i;
            } else if (m > second) {
                second = m;
                secondS = i;
            }
        }

        // 无扇区覆盖（扇区间隙/环带外）→ 普通大陆群系
        if (bestS < 0 || best < 0.22) {
            return baseMainlandBiome(px, pz, dist, angle, pool);
        }

        // 最强扇区主导 → 该扇区群系
        if (best >= 0.62) {
            return sectorBiome(bestS, best, px, pz, dist, angle, pool, sampler);
        }

        // 过渡带：高频噪声按强度权重在最强/次强扇区间选择（较大斑块犬牙交错 → 宏观平滑渐变，减少切割感）
        double w = best / (best + second + 1.0E-9);
        double n = ContinentIslandField.valueNoise(px, pz, 64, 8080);
        int chosen = (n < w) ? bestS : secondS;
        double chosenMask = (n < w) ? best : second;
        if (chosen < 0) {
            chosen = bestS;
        }
        if (chosenMask < 0.22) {
            chosenMask = best;
        }
        return sectorBiome(chosen, chosenMask, px, pz, dist, angle, pool, sampler);
    }

    /** 按扇区返回群系：0=山脉分级，2=群岛，其余扇区按配置化的主/附属群系权重选取。
     *  非山脉/群岛扇区先尝试混入其他模组群系点缀（原版为主、模组为辅）。 */
    private Holder<Biome> sectorBiome(int sector, double mask, double px, double pz, double dist, double angle, List<Holder<Biome>> pool, Climate.Sampler sampler) {
        SectorBiomeData sdData = getSectorBiomeData()[sector];
        Holder<Biome> sectorMain = (sdData != null && sdData.main() != null)
            ? sdData.main() : pool.get(FALLBACK_SECTOR_MAIN[sector]);
        // 扇区下：环带命中（dist > 0.95R）也作为环带参与 only 命中；applySectorRules 已内置
        if (sector == ContinentIslandField.ISLAND_SECTOR) {
            Holder<Biome> base = archipelagoBiome(mask, px, pz, dist, angle, pool);
            // 群岛扇区配置的附属群系作为额外点缀（陆地/浅水，不影响海洋神殿保留区）
            if (!ContinentIslandField.isInMonumentClear(px, pz)) {
                SectorBiomeData sd = getSectorBiomeData()[sector];
                if (sd != null && !sd.extras().isEmpty()) {
                    double r = ContinentIslandField.valueNoise(px, pz, 210, 52002);
                    double[] cum = sd.extrasCumulative();
                    for (int i = 0; i < cum.length; i++) {
                        if (r < cum[i]) {
                            Holder<Biome> extra = sd.extras().get(i);
                            // 附属点缀：命中群岛扇区本体（非内岛）
                            boolean inSC = dist < this.radius + this.transition;
                            boolean ringHit = ContinentIslandField.ringMountainEnabled && dist > this.radius * 0.95;
                            return applyBiomeRules(extra, inSC, false, false, ringHit, sector, sectorMain);
                        }
                    }
                }
            }
            // base 已在 archipelagoBiome 内部过滤
            return base;
        }
        if (sector == MOUNTAIN_SECTOR) {
            Holder<Biome> base = mountainRangeBiome(mask, px, pz, pool);
            // 山脉扇区配置的附属群系：只在山脚/山谷（mask 较低）叠加，不抢峰顶群系
            if (mask < 0.40) {
                SectorBiomeData sd = getSectorBiomeData()[sector];
                if (sd != null && !sd.extras().isEmpty()) {
                    double r = ContinentIslandField.valueNoise(px, pz, 220, 52000);
                    double[] cum = sd.extrasCumulative();
                    for (int i = 0; i < cum.length; i++) {
                        if (r < cum[i]) {
                            Holder<Biome> e = sd.extras().get(i);
                            return applySectorRules(e, sector, px, pz, sectorMain);
                        }
                    }
                }
            }
            // base 已在 mountainRangeBiome 内过滤
            return base;
        }
        // 非山脉/群岛扇区
        Holder<Biome> extra = modExtraBiome(px, pz);
        if (extra != null) {
            extra = filterConfiguredBiome(extra, sector, px, pz, pool);
            if (extra != null) extra = applySectorRules(extra, sector, px, pz, sectorMain);
        }
        // 沙漠扇区专属：BOP outback
        if (sector == 3) {
            Holder<Biome> outback = bopOutback();
            if (outback != null && ContinentIslandField.valueNoise(px, pz, 220, 9101) > 0.92) {
                return applySectorRules(outback, sector, px, pz, sectorMain);
            }
        }
        Holder<Biome> configured = pickConfiguredSectorBiome(sector, px, pz, pool.get(FALLBACK_SECTOR_MAIN[sector]));
        configured = filterConfiguredBiome(configured, sector, px, pz, pool);
        // 雪原扇区：针叶林/冰刺只在扇区内部（mask 高）生成，边缘回落雪原主群系
        if (sector == 5 && mask < 0.45 && isSnowSectorExtra(configured)) {
            SectorBiomeData sd = getSectorBiomeData()[sector];
            configured = (sd != null && sd.main() != null) ? sd.main() : pool.get(FALLBACK_SECTOR_MAIN[sector]);
        }
        configured = applySectorRules(configured, sector, px, pz, sectorMain);
        return (extra != null) ? extra : configured;
    }

    /** 是否为雪原扇区的专属附属群系（snowy_taiga / ice_spikes）——只在雪原内部生成 */
    private boolean isSnowSectorExtra(Holder<Biome> biome) {
        return biome.unwrapKey()
            .map(key -> {
                String p = key.location().getPath();
                return p.equals("snowy_taiga") || p.equals("ice_spikes");
            })
            .orElse(false);
    }

    /**
     * 配置化扇区群系安全过滤：防止错误扇区出现冰刺/恶地/风袭系/含雪群系（除非该扇区明确允许）。
     * 不通过时替换为该扇区配置的主群系（再不行走 fallback）。
     */
    private Holder<Biome> filterConfiguredBiome(Holder<Biome> biome, int sector, double px, double pz, List<Holder<Biome>> pool) {
        if (biome == null) return null;
        boolean needFix = false;
        // 剔除群系：只有在"允许的扇区"里才放行
        if (isExcluded(biome)) {
            boolean allowed = false;
            var key = biome.unwrapKey();
            if (key.isPresent()) {
                String path = key.get().location().getPath();
                // 恶地家族 → 只允许沙漠扇区（3）
                if (path.contains("badlands") && sector == 3) allowed = true;
                // 冰刺平原 → 只允许雪原扇区（5）
                if (path.equals("ice_spikes") && sector == 5) allowed = true;
                // 风袭系：所有扇区都排除（超大陆硬约束）
            }
            if (!allowed) needFix = true;
        }
        // 含雪群系：只允许雪原（5）/ 山脉（0）且 allowSnow 通过
        if (!needFix && isSnowy(biome) && !allowSnow(px, pz)) needFix = true;
        // 积雪山坡：禁止在雪原扇区（5）和山脉扇区（0）生成（群岛不受限）
        if (!needFix && (sector == 5 || sector == 0)) {
            var slKey = biome.unwrapKey();
            if (slKey.isPresent() && slKey.get().location().getPath().equals("snowy_slopes")) {
                needFix = true;
            }
        }
        if (!needFix) return biome;
        SectorBiomeData sd = getSectorBiomeData()[sector];
        if (sd != null && sd.main() != null) return sd.main();
        return pool.get(FALLBACK_SECTOR_MAIN[sector]);
    }

    /**
     * 群岛扇区：核心区小岛随机群系（全群系池，不受温度影响）、岛间内海。
     * <p>
     * 群岛扇区：内海 + 小岛；沙滩不强制，由系统自主生成（原版多噪声源 + 地形自然配合）。
     */
    private Holder<Biome> archipelagoBiome(double mask, double px, double pz, double dist, double angle, List<Holder<Biome>> pool) {
        boolean inSC = dist < this.radius + this.transition;
        boolean ringHit = ContinentIslandField.ringMountainEnabled && dist > this.radius * 0.95;
        int sector = ContinentIslandField.ISLAND_SECTOR;
        Holder<Biome> fallbackOcean = pool.get(ISLAND_SECTOR_OCEAN);
        // ===== 海洋神殿保留区：一整片深海（deep ocean）=====
        if (ContinentIslandField.isInMonumentClear(px, pz)) {
            return applyBiomeRules(pool.get(DEEP_OCEAN), inSC, false, false, ringHit, sector, fallbackOcean);
        }
        ContinentIslandField.Config cfg = this.cfg;

        // ===== 分支1：群岛小岛陆地（= ARCHIPELAGO_INNER_ISLAND 独立区域）=====
        if (ContinentIslandField.islandSectorIsLand(px, pz)) {
            double bufExt = ContinentIslandField.islandSectorFalloff(px, pz, cfg);
            double bufMask = ContinentIslandField.islandSectorMask(px, pz, cfg);
            double bufW = (bufExt > 0.20)
                ? Mth.smoothstep((float) Mth.clamp((bufMask - 0.45) / 0.40, 0.0, 1.0))
                : 0.0;
            if (bufW > 0.0) {
                Holder<Biome> base = randomIslandBiome(px, pz);
                // 群岛内岛：使用独立区域（不命中扇区2本体黑名单、不命中外岛限制）
                // 注意：sector 仍传 ISLAND_SECTOR 以便 only_biomes 中"扇区2 only"也可命中（并集语义）
                //  但黑名单层面：sector_2_blacklist 不影响内岛——传 sectorHit=-1 + 单独 isArchipelagoInnerIsland=true
                //  而 only_biomes 的 SECTOR_2 命中需同时考虑——为此专门做两次判定。
                // 实现：先仅按 ARCHIPELAGO_INNER_ISLAND 规则（sectorHit=-1），
                // 再叠加 SECTOR_2 only 的放宽（把结果放宽）。
                // 简化做法：构造一个允许"SECTOR_2 仅用于 only_biomes 匹配，不触发黑名单"的包装。
                // 为避免复杂化，把群岛扇区本体 sector=2 不做 blacklist 的命中（传 -1），
                // 同时仅对 only_biomes 并集时放宽——我们把 only 匹配前的命中区域在 applyBiomeRules 内部已处理
                // only_biomes 并集用 sectorHit 或 ringHit 或 innerIsland 命中。
                // 如果 sectorHit=-1 只触发 innerIsland only；我们希望内岛也能被 SECTOR_2_only 匹配时通过，
                // 那就传 sectorHit=ISLAND_SECTOR，但同时不要触发 sector_2_blacklist。
                // 解决：在 applyBiomeRules 内部不检查 sector 黑名单，当 isArchipelagoInnerIsland=true 时？
                // 更直接：改为单独方法过滤内岛。
                return applyInnerIslandRules(base, px, pz, dist, pool.get(13)); // 兜底草甸
            }
        }

        // ===== 分支2：混合水陆判定 =====
        double finalBias = ContinentIslandField.bias(px, pz, cfg);
        boolean isLand = finalBias >= ContinentIslandField.LAND_BIAS_THRESHOLD;
        if (isLand) {
            // 陆地：baseMainlandBiome 内部已做扇区规则过滤（扇区2本体）
            return baseMainlandBiome(px, pz, dist, angle, pool);
        }

        // ===== 分支3：水域 =====
        /*
        double trans = ContinentIslandField.islandTransitionWeight(px, pz, cfg);
        if (trans > 0.02 && ContinentIslandField.islandTransitionRiver(px, pz) > 0.5) {
            return pool.get(RIVER);
        }
        */
        // 其余水域一律内海
        return applyBiomeRules(pool.get(ISLAND_SECTOR_OCEAN), inSC, false, false, ringHit, sector, fallbackOcean);
    }

    /** 群岛内岛专属过滤：不触发 sector_2_blacklist、不触发 outer_island_blacklist，
     *  但仍使用 SECTOR_2 的 only_biomes 做并集命中（即「群岛只允许」对内岛也视为合法）。 */
    private Holder<Biome> applyInnerIslandRules(Holder<Biome> biome, double px, double pz, double dist, Holder<Biome> fallback) {
        if (biome == null) return fallback;
        if (this.biomeRules == null) ensureBiomeRules();
        BiomeRules r = this.biomeRules;
        var keyOpt = biome.unwrapKey();
        if (keyOpt.isEmpty()) return biome;
        ResourceLocation loc = keyOpt.get().location();
        boolean inSC = dist < this.radius + this.transition;
        boolean ringHit = ContinentIslandField.ringMountainEnabled && dist > this.radius * 0.95;

        // ① 超大陆整体黑名单（最高优先级）
        if (inSC && r.supercontinentBlacklist.contains(loc)) return fallback;
        // ② 外岛黑名单：群岛内岛不在外岛，跳过

        // ③ 各区域独立黑名单
        if (inSC) {
            if (ringHit && r.ringMountainBlacklist.contains(loc)) return fallback;
            // 注意：扇区 2 黑名单对内岛不生效
            if (r.innerIslandBlacklist.contains(loc)) return fallback;
        }
        // ④ only_biomes 并集：命中 RING_MOUNTAIN、SECTOR_2（群岛扇区本体）、ARCHIPELAGO_INNER_ISLAND 均算合法
        EnumSet<RestrictRegion> allow = r.onlyIndex.get(loc);
        if (allow != null && !allow.isEmpty()) {
            boolean ok = false;
            if (inSC) {
                if (ringHit && allow.contains(RestrictRegion.RING_MOUNTAIN)) ok = true;
                if (!ok && allow.contains(RestrictRegion.SECTOR_2)) ok = true;
                if (!ok && allow.contains(RestrictRegion.ARCHIPELAGO_INNER_ISLAND)) ok = true;
            }
            if (!ok) return fallback;
        }
        return biome;
    }

    /**
     * 山脉扇区（模拟真实山脉）：按与地形完全一致的结构值（mountainValue，即参数 mask）分层。
     */
    private Holder<Biome> mountainRangeBiome(double mask, double px, double pz, List<Holder<Biome>> pool) {
        int sector = MOUNTAIN_SECTOR;
        Holder<Biome> fallback = pool.get(FALLBACK_SECTOR_MAIN[MOUNTAIN_SECTOR]); // meadow
        double temp = ContinentIslandField.valueNoise(px, pz, 400, 707);
        if (mask > 0.55) {
            double snow = ContinentIslandField.valueNoise(px, pz, 64, 6006);
            if (temp < 0.48) return applySectorRules(pool.get(FROZEN_PEAKS), sector, px, pz, fallback);
            Holder<Biome> top = (snow > 0.52) ? pool.get(1) : pool.get(0);
            if (temp < 0.72) return applySectorRules(top, sector, px, pz, fallback);
            return applySectorRules(pool.get(0), sector, px, pz, fallback);
        }
        double cherry = ContinentIslandField.valueNoise(px, pz, 90, 5005);
        if (cherry > 0.80 && mask < 0.48) {
            return applySectorRules(pool.get(CHERRY_GROVE), sector, px, pz, fallback);
        }
        if (mask > 0.36) {
            if (temp < 0.66) return applySectorRules(pool.get(13), sector, px, pz, fallback);
            Holder<Biome> taiga = taigaWithVariants(px, pz, pool);
            return applySectorRules(taiga, sector, px, pz, fallback);
        }
        if (temp < 0.30) return applySectorRules(taigaWithVariants(px, pz, pool), sector, px, pz, fallback);
        if (temp < 0.60) return applySectorRules(pool.get(13), sector, px, pz, fallback);
        return applySectorRules(pool.get(10), sector, px, pz, fallback);
    }

    /**
     * 普通大陆群系：大尺度温度/湿度噪声驱动的平原/森林变体 + 少量小斑块 + 可选边缘环山。
     * 此方法可能被以下场景调用：
     *   a) pickMainlandBiome 中「无扇区命中（扇区间隙/环山带外）」分支 → 此时不是扇区内部
     *   b) 扇区 2（群岛）分支 2（陆地）调用 → 群岛扇区本体（sector=2）
     *   c) 其他扇区内部（通过 sectorBiome → 山脉分级/群岛之外不会走到这）；但过渡扇区间隙一般是大陆非扇区
     * 为避免把「非扇区大陆」误传 sector，我们先判定是否在扇区命中，再决定过滤。
     */
    private Holder<Biome> baseMainlandBiome(double px, double pz, double dist, double angle, List<Holder<Biome>> pool) {
        boolean inSC = dist < this.radius + this.transition;
        boolean ringHit = ContinentIslandField.ringMountainEnabled && dist > this.radius * 0.95;
        int secAt = sectorAt(px, pz);
        Holder<Biome> fallback = pool.get(9); // 兜底：平原

        // 环山带（命中 = RestrictRegion.RING_MOUNTAIN 独立 + 同时若在扇区角度内，也参与扇区 only_biomes 并集）
        if (ContinentIslandField.ringMountainEnabled && dist > this.radius * 0.95) {
            double temp = ContinentIslandField.valueNoise(px, pz, 400, 707);
            Holder<Biome> cand;
            if (temp < 0.35) cand = pool.get(2);
            else if (temp < 0.60) cand = pool.get(0);
            else cand = pool.get(1);
            // 环山带命中：ringMountainHit=true，同时若在某扇区角度内，sector=该扇区（取并集）
            return applyBiomeRules(cand, inSC, false, false, true, secAt, fallback);
        }

        double temp = ContinentIslandField.valueNoise(px, pz, 400, 707);
        double humid = ContinentIslandField.valueNoise(px, pz, 400, 808);

        // 小斑块
        double spot = ContinentIslandField.valueNoise(px, pz, 55, 1001);
        if (spot > 0.88) {
            double jungleCenter = ContinentIslandField.sectorCenterAngle(1);
            double angleDelta = Math.abs(Math.atan2(Math.sin(angle - jungleCenter), Math.cos(angle - jungleCenter)));
            if (angleDelta < Math.toRadians(30.0) && temp > 0.45 && temp < 0.75 && humid > 0.55) {
                return applyBiomeRules(pool.get(16), inSC, false, false, ringHit, secAt, fallback);
            }
            if (dist > this.radius * 0.85) {
                return applyBiomeRules(pool.get(18), inSC, false, false, ringHit, secAt, fallback);
            }
        }

        // 樱花树林：只在中心核心区
        if (dist < this.radius * 0.30) {
            double cherrySpot = ContinentIslandField.valueNoise(px, pz, 90, 1004);
            if (cherrySpot > 0.86 && temp > 0.50 && temp < 0.85) {
                return applyBiomeRules(pool.get(CHERRY_GROVE), inSC, false, false, ringHit, secAt, fallback);
            }
        }

        // 温带/大陆气候
        if (temp < 0.30) {
            if (humid < 0.40) return applyBiomeRules(pool.get(9), inSC, false, false, ringHit, secAt, fallback);
            return applyBiomeRules(taigaWithVariants(px, pz, pool), inSC, false, false, ringHit, secAt, fallback);
        }
        if (temp < 0.60) {
            if (humid < 0.45) {
                if (ContinentIslandField.valueNoise(px, pz, 260, 3003) < 0.10) {
                    return applyBiomeRules(pool.get(15), inSC, false, false, ringHit, secAt, fallback);
                }
                return applyBiomeRules(pool.get(9), inSC, false, false, ringHit, secAt, fallback);
            }
            if (humid < 0.75) {
                if (dist < this.radius * 0.35) {
                    double mix = ContinentIslandField.valueNoise(px, pz, 70, 3004);
                    if (mix < 0.25) return applyBiomeRules(birchWithVariants(px, pz, pool), inSC, false, false, ringHit, secAt, fallback);
                    if (mix > 0.85) return applyBiomeRules(pool.get(12), inSC, false, false, ringHit, secAt, fallback);
                }
                return applyBiomeRules(pool.get(10), inSC, false, false, ringHit, secAt, fallback);
            }
            if (humid < 0.90) {
                return applyBiomeRules(birchWithVariants(px, pz, pool), inSC, false, false, ringHit, secAt, fallback);
            }
            return applyBiomeRules(pool.get(12), inSC, false, false, ringHit, secAt, fallback);
        }
        if (temp < 0.85) {
            if (humid < 0.40) return applyBiomeRules(pool.get(13), inSC, false, false, ringHit, secAt, fallback);
            if (humid < 0.70) {
                if (dist < this.radius * 0.35) {
                    double mix = ContinentIslandField.valueNoise(px, pz, 70, 3006);
                    if (mix < 0.25) return applyBiomeRules(birchWithVariants(px, pz, pool), inSC, false, false, ringHit, secAt, fallback);
                    if (mix > 0.85) return applyBiomeRules(pool.get(14), inSC, false, false, ringHit, secAt, fallback);
                }
                return applyBiomeRules(pool.get(10), inSC, false, false, ringHit, secAt, fallback);
            }
            return applyBiomeRules(pool.get(14), inSC, false, false, ringHit, secAt, fallback);
        }
        return applyBiomeRules(pool.get(15), inSC, false, false, ringHit, secAt, fallback);
    }

    /** 针叶林（含变种）：原始松木/原始云杉针叶林约 30% 概率替换普通针叶林（更巨大、更高的针叶树） */
    private Holder<Biome> taigaWithVariants(double px, double pz, List<Holder<Biome>> pool) {
        double v = ContinentIslandField.valueNoise(px, pz, 260, 3002);
        if (v < 0.30) {
            return v < 0.15 ? pool.get(OLD_GROWTH_PINE_TAIGA) : pool.get(OLD_GROWTH_SPRUCE_TAIGA);
        }
        return pool.get(8);
    }

    /** 桦木林（含变种）：原始桦木森林（显著更高的白桦树）约 25% 概率替换普通桦木林 */
    private Holder<Biome> birchWithVariants(double px, double pz, List<Holder<Biome>> pool) {
        if (ContinentIslandField.valueNoise(px, pz, 260, 3005) < 0.25) {
            return pool.get(OLD_GROWTH_BIRCH_FOREST);
        }
        return pool.get(11);
    }

    /** 群岛小岛群系：从全部可能群系（含其他模组）哈希随机选，不受温度影响；必生成一个蘑菇岛 */
    private Holder<Biome> randomIslandBiome(double px, double pz) {
        if (ContinentIslandField.islandMushroomCell(px, pz)) {
            return this.mainlandPool.get(24); // mushroom_fields
        }
        List<Holder<Biome>> all = allLandBiomes();
        if (all.isEmpty()) {
            return this.mainlandPool.get(13); // 兜底：草甸
        }
        // 必须用「所有者格」哈希而非当前格：岛心偏移 ±0.40 格（≈±120 格）
        // 会让同一岛屿横跨 2~3 个网格单元，按当前格哈希会把一岛切成多群系拼贴。
        long[] owner = ContinentIslandField.innerIslandOwner(px, pz);
        double h = ContinentIslandField.hash(owner[0], owner[1], 12345);
        int idx = (int) (h * all.size());
        return all.get(Math.min(idx, all.size() - 1));
    }

    /** 群岛小岛群系池：delegate 的所有可能群系剔除海洋/河流/海滩/蘑菇岛类（蘑菇岛只保留强制生成的一个）。
     *  含雪群系不剔除——群岛扇区不受雪系限制（与 {@link #allowSnow} 的群岛高岛豁免一致），
     *  小岛可随机到雪系群系 */
    private List<Holder<Biome>> allLandBiomes() {
        if (this.allLandBiomesCache == null) {
            this.allLandBiomesCache = this.delegate.possibleBiomes().stream()
                .filter(h -> !isOceanOrBeach(h))
                .distinct()
                .collect(Collectors.toList());
        }
        return this.allLandBiomesCache;
    }

    private boolean isOceanOrBeach(Holder<Biome> biome) {
        return biome.unwrapKey()
            .map(key -> {
                String p = key.location().getPath();
                return p.contains("ocean") || p.contains("beach") || p.equals("river")
                    || p.equals("mushroom_fields") || p.equals("deep_dark");
            })
            .orElse(false);
    }

    /** 是否为原版（minecraft 命名空间）群系 */
    private boolean isVanillaBiome(Holder<Biome> biome) {
        return biome.unwrapKey()
            .map(key -> key.location().getNamespace().equals("minecraft"))
            .orElse(true);
    }

    /** 其他模组群系缓存（非 minecraft 命名空间的陆生群系），惰性构建 */
    private List<Holder<Biome>> modBiomesCache;

    /** Biomes O' Plenty 的 outback：懒加载检测（模组未加载返回 null）。 */
    private Holder<Biome> bopOutback() {
        if (!this.bopOutbackChecked) {
            this.bopOutbackChecked = true;
            this.bopOutbackCache = findBiome("biomesoplenty:outback", null);
        }
        return this.bopOutbackCache;
    }

    /** 是否为 Biomes O' Plenty 的 outback 群系 */
    private boolean isBopOutback(Holder<Biome> biome) {
        return biome.unwrapKey()
            .map(key -> key.location().getNamespace().equals("biomesoplenty") && key.location().getPath().equals("outback"))
            .orElse(false);
    }

    /**
     * 其他模组群系池：delegate possibleBiomes + mainlandPool 中所有非原版命名空间的陆生群系。
     * 无其他群系模组时为空（扇区保持纯原版）；有其他模组时，扇区会以少量比例混入其群系。
     * outback 不在池中：它有专属的沙漠扇区附属群系通道（与恶地相同占比）。
     */
    private List<Holder<Biome>> modBiomes() {
        if (this.modBiomesCache == null) {
            this.modBiomesCache = Stream.concat(
                    this.delegate.possibleBiomes().stream(),
                    this.mainlandPool.stream()
                )
                .filter(h -> !isVanillaBiome(h))
                .filter(h -> !isOceanOrBeach(h))
                .filter(h -> !isSnowy(h))
                .filter(h -> !isExcluded(h))
                .filter(h -> !isBopOutback(h))
                .distinct()
                .collect(Collectors.toList());
        }
        return this.modBiomesCache;
    }

    /**
     * 扇区群系混入其他模组群系点缀：以原版群系为主，约 12% 概率混入一个模组群系
     * （类似沙漠中加恶地，但占比更小）。无其他模组群系时返回 null → 扇区纯原版。
     */
    private Holder<Biome> modExtraBiome(double px, double pz) {
        List<Holder<Biome>> mods = modBiomes();
        if (mods.isEmpty()) {
            return null;
        }
        if (ContinentIslandField.valueNoise(px, pz, 320, 7700) < 0.12) {
            double h = ContinentIslandField.valueNoise(px, pz, 180, 7701);
            int idx = (int) (h * mods.size());
            return mods.get(Math.min(idx, mods.size() - 1));
        }
        return null;
    }

    /** 每个岛屿固定一个群系：通过 3×3 邻域搜索找到"真正生成该岛的网格单元"
     *  （因为岛中心可偏移 ±0.60 格漂进相邻格，直接用当前格哈希会把同一岛拆成多段群系）。
     *  找到所属格后按哈希从岛群系池中确定性选取；命中外岛黑名单则向后扫描兜底。 */
    private Holder<Biome> pickIslandBiome(double bx, double bz, ContinentIslandField.Config cfg) {
        // 所有者格直接由 farIslandOwner 统一计算（best-value 准则，与 bias() 外围岛屿段完全一致），
        // 不再用最近中心反推——同一岛的所有点统一到一个 cx/cz，哈希唯一 → 群系唯一。
        long[] owner = ContinentIslandField.farIslandOwner(bx, bz, cfg);
        long ownerCx = owner[0];
        long ownerCz = owner[1];
        double h = ContinentIslandField.hash(ownerCx, ownerCz, 707);
        int n = this.islandPool.size();
        if (n == 0) return outerIslandFallback();
        int start = (int) (h * n) % n;
        if (!this.outerIslandBlacklist.isEmpty()) {
            int i = start;
            do {
                Holder<Biome> b = this.islandPool.get(i);
                if (!isOuterIslandBlacklisted(b)) return b;
                i = (i + 1) % n;
            } while (i != start);
            return outerIslandFallback();
        }
        return this.islandPool.get(start);
    }

    // ── 配置化扇区群系 ────────────────────────────────────────────────

    /** 从资源定位符字符串查找群系，优先 mainlandPool，其次 delegate 的所有可能群系 */
    private Holder<Biome> findBiome(String locStr, Holder<Biome> fallback) {
        if (locStr == null || locStr.isBlank()) return fallback;
        ResourceLocation loc;
        try {
            loc = ResourceLocation.parse(locStr.trim());
        } catch (Exception ex) {
            return fallback;
        }
        // 1) 在 mainlandPool 中按 key 精确匹配
        for (Holder<Biome> h : this.mainlandPool) {
            if (h.unwrapKey().map(k -> k.location().equals(loc)).orElse(false)) return h;
        }
        // 2) 在 delegate.possibleBiomes() 中按 key 匹配（支持其他模组群系）
        for (Holder<Biome> h : this.delegate.possibleBiomes()) {
            if (h.unwrapKey().map(k -> k.location().equals(loc)).orElse(false)) return h;
        }
        // 找不到（资源定位符写错 / 对应模组未加载）→ 回退到硬编码兜底
        return fallback;
    }

    /** 硬编码的扇区回退主群系（配置找不到群系时兜底），扇区索引 → mainlandPool 索引 */
    private static final int[] FALLBACK_SECTOR_MAIN = { 13, 4, 25, 3, 7, 5 };
    // 0山脉→meadow(13), 1丛林→jungle(4), 2群岛→ocean(25), 3沙漠→desert(3), 4热草→savanna(7), 5雪原→snowy_plains(5)

    /** 延迟获取 sectorBiomeData：第一次调用时才解析配置（避免在注册表加载阶段触发 delegate.possibleBiomes()） */
    private SectorBiomeData[] getSectorBiomeData() {
        if (this.sectorBiomeData == null) {
            this.sectorBiomeData = buildSectorBiomeData();
        }
        return this.sectorBiomeData;
    }

    /** 读取 6 个扇区的配置，构造 SectorBiomeData 数组 */
    private SectorBiomeData[] buildSectorBiomeData() {
        SectorBiomeData[] out = new SectorBiomeData[6];
        var mains = List.of(CAIConfig.SECTOR_0_MAIN, CAIConfig.SECTOR_1_MAIN, CAIConfig.SECTOR_2_MAIN,
                            CAIConfig.SECTOR_3_MAIN, CAIConfig.SECTOR_4_MAIN, CAIConfig.SECTOR_5_MAIN);
        var extras = List.of(CAIConfig.SECTOR_0_EXTRAS, CAIConfig.SECTOR_1_EXTRAS, CAIConfig.SECTOR_2_EXTRAS,
                             CAIConfig.SECTOR_3_EXTRAS, CAIConfig.SECTOR_4_EXTRAS, CAIConfig.SECTOR_5_EXTRAS);
        var weights = List.of(CAIConfig.SECTOR_0_EXTRA_WEIGHTS, CAIConfig.SECTOR_1_EXTRA_WEIGHTS, CAIConfig.SECTOR_2_EXTRA_WEIGHTS,
                              CAIConfig.SECTOR_3_EXTRA_WEIGHTS, CAIConfig.SECTOR_4_EXTRA_WEIGHTS, CAIConfig.SECTOR_5_EXTRA_WEIGHTS);

        for (int s = 0; s < 6; s++) {
            Holder<Biome> fb = this.mainlandPool.get(FALLBACK_SECTOR_MAIN[s]);
            Holder<Biome> main = findBiome(mains.get(s).get(), fb);

            List<? extends String> extrasRaw = extras.get(s).get();
            List<? extends Double> weightsRaw = weights.get(s).get();
            int n = Math.min(extrasRaw.size(), weightsRaw.size());
            List<Holder<Biome>> extrasList = new ArrayList<>(n);
            double[] rawW = new double[n];
            double sumW = 0.0;
            for (int i = 0; i < n; i++) {
                Holder<Biome> b = findBiome(extrasRaw.get(i), null);
                double w = Math.max(0.0, weightsRaw.get(i).doubleValue());
                if (b != null && w > 1.0e-6) {
                    extrasList.add(b);
                    rawW[extrasList.size() - 1] = w;
                    sumW += w;
                }
            }
            // 裁剪实际使用的长度（前面可能有找不到/权重为 0 被跳过的条目）
            int m = extrasList.size();
            double[] cum;
            if (m == 0) {
                cum = new double[0];
            } else {
                // 概率计算：主群系绝对概率 = 1 / (1 + sumW)
                // extras[i] 绝对概率 = w[i] / (1 + sumW)
                // 这样当 sumW≈0.29 时，主≈77%、附属合计≈23%（相对比例，不怕用户填和>1）
                double denom = 1.0 + sumW;
                cum = new double[m];
                double acc = 0.0;
                for (int i = 0; i < m; i++) {
                    acc += rawW[i] / denom;
                    cum[i] = acc;
                }
            }
            out[s] = new SectorBiomeData(main, extrasList, cum);
        }
        return out;
    }

    /**
     * 根据配置从指定扇区抽取一个群系（主/附属）。
     * 对扇区 0/2，这个方法只返回配置的"平地/兜底"群系；分级/内海逻辑由调用方单独跑。
     */
    private Holder<Biome> pickConfiguredSectorBiome(int sector, double px, double pz, Holder<Biome> fallback) {
        if (sector < 0 || sector >= getSectorBiomeData().length) return fallback;
        SectorBiomeData d = getSectorBiomeData()[sector];
        if (d == null) return fallback;
        if (d.extras().isEmpty()) return d.main() != null ? d.main() : fallback;

        // 雪原扇区（5）/丛林扇区（1）用更大噪声尺度 → 附属针叶林/冰刺/竹林等形成大片斑块，而非碎点
        int scale = (sector == 5 || sector == 1) ? 520 : 260;
        double r = ContinentIslandField.valueNoise(px, pz, scale, 51000 + sector * 97);
        double[] cum = d.extrasCumulative();
        for (int i = 0; i < cum.length; i++) {
            if (r < cum[i]) {
                return d.extras().get(i);
            }
        }
        return d.main() != null ? d.main() : fallback;
    }
}






