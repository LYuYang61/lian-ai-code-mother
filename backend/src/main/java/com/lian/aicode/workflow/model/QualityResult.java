package com.lian.aicode.workflow.model;

import dev.langchain4j.model.output.structured.Description;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** 代码质量检查结果；错误文本来自模型，仅作为下一轮提示词数据。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Description("代码是否通过检查")
    private Boolean valid;

    @Description("必须修复的问题列表；没有问题时返回空数组")
    private List<String> errors;

    @Description("可选的改进建议")
    private List<String> suggestions;

    public boolean passed() {
        return Boolean.TRUE.equals(valid);
    }
}
