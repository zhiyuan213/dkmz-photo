package com.dkmz.photo.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 流程序号格式守门。
 *
 * 序号是排障的第一入口（先看卡在第几段，再看该行后面的字段），格式一旦漂移，
 * grep 与肉眼定位都会失效，所以把字面格式锁死在测试里。
 * 阶段数量与名称若真要改，改 {@link FlowLog} 的同时这里会失败提醒补用例。
 */
class FlowLogTest {

    @Test
    void tagFormatIsStableForEveryStage() {
        assertEquals("[photo][流程] 1/6 身份校验", FlowLog.tag(FlowLog.LOGIN));
        assertEquals("[photo][流程] 2/6 限流校验", FlowLog.tag(FlowLog.RATE_LIMIT));
        assertEquals("[photo][流程] 3/6 内容安全检测", FlowLog.tag(FlowLog.SEC_CHECK));
        assertEquals("[photo][流程] 4/6 人像处理（美肤 + 分割）", FlowLog.tag(FlowLog.PORTRAIT));
        assertEquals("[photo][流程] 5/6 换底合成", FlowLog.tag(FlowLog.COMPOSE));
        assertEquals("[photo][流程] 6/6 输出", FlowLog.tag(FlowLog.OUTPUT));
    }

    @Test
    void stageIndexStartsAtOneAndStaysContiguous() {
        assertEquals(1, FlowLog.LOGIN);
        assertEquals(FlowLog.LOGIN + 1, FlowLog.RATE_LIMIT);
        assertEquals(FlowLog.RATE_LIMIT + 1, FlowLog.SEC_CHECK);
        assertEquals(FlowLog.SEC_CHECK + 1, FlowLog.PORTRAIT);
        assertEquals(FlowLog.PORTRAIT + 1, FlowLog.COMPOSE);
        assertEquals(FlowLog.COMPOSE + 1, FlowLog.OUTPUT);
        assertEquals(FlowLog.OUTPUT, FlowLog.TOTAL, "最后一段就是总数");
    }

    @Test
    void namesCoverEveryIndexAndMatchTag() {
        String[] names = FlowLog.names();
        assertEquals(FlowLog.TOTAL, names.length);
        for (int i = 0; i < names.length; i++) {
            assertEquals(names[i], FlowLog.name(i + 1));
            assertTrue(FlowLog.tag(i + 1).endsWith(" " + names[i]), FlowLog.tag(i + 1));
        }
    }

    @Test
    void outOfRangeStepIsRejectedInsteadOfSilentlyLoggingWrongNumber() {
        assertThrows(IllegalArgumentException.class, () -> FlowLog.tag(0));
        assertThrows(IllegalArgumentException.class, () -> FlowLog.tag(FlowLog.TOTAL + 1));
        assertThrows(IllegalArgumentException.class, () -> FlowLog.name(0));
    }
}
