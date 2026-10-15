package com.dkmz.photo.support;

/**
 * 制作流程的阶段编号（后端视角）。
 *
 * <p>后端一次制作请求是线性 6 段：身份校验 → 限流校验 → 内容安全检测 → 人像处理 →
 * 换底合成 → 输出。与端上 5 步<b>不是一套划分</b>：端上的 1/5（本地裁剪）、4/5（换底合成）、
 * 5/5（按尺寸导出）全在手机里完成，后端看不到；后端第 5 段（换底合成）只有成品图模式才走。
 * 两边各自编号、互不换算，靠阶段名对齐即可。
 *
 * <p>日志行首统一为 {@code [photo][流程] N/6 名称}，与端上格式一致 ——
 * 前后端日志混在一起翻时也能一眼认出序号，先看序号定位卡在哪一段，
 * 再看该行后面的字段（耗时/结果）判断原因。
 */
public final class FlowLog {

    public static final int TOTAL = 6;

    public static final int LOGIN = 1;
    public static final int RATE_LIMIT = 2;
    public static final int SEC_CHECK = 3;
    public static final int PORTRAIT = 4;
    public static final int COMPOSE = 5;
    public static final int OUTPUT = 6;

    private static final String[] NAMES = {
            "身份校验",
            "限流校验",
            "内容安全检测",
            "人像处理（美肤 + 分割）",
            "换底合成",
            "输出",
    };

    private FlowLog() {
    }

    /** 行首前缀，如 {@code [photo][流程] 3/6 内容安全检测} */
    public static String tag(int step) {
        if (step < 1 || step > TOTAL) {
            throw new IllegalArgumentException("流程阶段越界: " + step);
        }
        return "[photo][流程] " + step + "/" + TOTAL + " " + NAMES[step - 1];
    }

    /** 阶段名（不含序号），供「跳过 / 中止」这类行复用 */
    public static String name(int step) {
        if (step < 1 || step > TOTAL) {
            throw new IllegalArgumentException("流程阶段越界: " + step);
        }
        return NAMES[step - 1];
    }

    /** 阶段名快照，守门测试用 */
    public static String[] names() {
        return NAMES.clone();
    }
}
