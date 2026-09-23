package com.lian.aicode.ai;

import com.lian.aicode.model.enums.CodeGenTypeEnum;

import java.util.Locale;

/**
 * 路由模型不可用或返回非法结果时的本地确定性回退策略。
 *
 * <p>回退策略不是 AI 替身，而是为了保证没有外部模型时创建接口仍然可用，并且结果可测试、可解释。</p>
 */
public final class CodeGenTypeRoutingHeuristic {

    private CodeGenTypeRoutingHeuristic() {
    }

    public static CodeGenTypeEnum choose(String prompt) {
        String normalized = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "vue", "react", "管理系统", "后台", "电商", "订单管理",
                "用户管理", "商品管理", "状态管理", "路由", "组件", "复杂交互", "数据管理")) {
            return CodeGenTypeEnum.VUE_PROJECT;
        }
        if (containsAny(normalized, "多个页面", "多页面", "首页", "关于我们", "联系我们", "html", "css", "javascript",
                "多文件", "多页")) {
            return CodeGenTypeEnum.MULTI_FILE;
        }
        return CodeGenTypeEnum.HTML;
    }

    private static boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
