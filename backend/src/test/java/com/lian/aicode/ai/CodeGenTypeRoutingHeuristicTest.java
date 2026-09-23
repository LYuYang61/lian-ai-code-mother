package com.lian.aicode.ai;

import com.lian.aicode.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodeGenTypeRoutingHeuristicTest {

    @Test
    void choosesHtmlForSimplePage() {
        assertEquals(CodeGenTypeEnum.HTML, CodeGenTypeRoutingHeuristic.choose("做一个个人介绍页"));
    }

    @Test
    void choosesMultiFileForStaticMultiPage() {
        assertEquals(CodeGenTypeEnum.MULTI_FILE,
                CodeGenTypeRoutingHeuristic.choose("制作首页、关于我们、联系我们三个静态页面"));
    }

    @Test
    void choosesVueForComplexManagementSystem() {
        assertEquals(CodeGenTypeEnum.VUE_PROJECT,
                CodeGenTypeRoutingHeuristic.choose("做一个包含用户管理、订单管理和状态管理的后台系统"));
    }
}
