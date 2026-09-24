package com.lian.aicode.workflow.model;

import com.lian.aicode.model.enums.CodeGenTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bsc.langgraph4j.prebuilt.MessagesState;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * LangGraph4j 节点之间共享的工作流状态。
 *
 * <p>只放业务数据：数据库版本、输出目录、素材和质检结果。SSE 回调（chunk/事件/取消）
 * 属于单次执行而不是状态——LangGraph4j 1.9.x 在状态传递时会重建值对象，曾以 transient
 * 字段挂在上下文里的运行时回调在节点执行期间丢失，导致工具事件全部静默丢弃
 * （2026-09-24 阶段 2 实测被零变更守卫误杀）。回调必须通过节点 action 参数注入。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowContext implements Serializable {

    public static final String WORKFLOW_CONTEXT_KEY = "workflowContext";

    @Serial
    private static final long serialVersionUID = 1L;

    private Long appId;
    private Integer versionNo;
    private Long excludedMessageId;
    private String actorAccount;
    private String outputDirectory;
    private String baselineDirectory;
    private String currentStep;
    private String originalPrompt;
    private String imageListStr;
    private List<ImageResource> imageList;
    private String enhancedPrompt;
    private CodeGenTypeEnum generationType;
    private String generatedCodeDir;
    private String buildResultDir;
    private QualityResult qualityResult;
    private String errorMessage;
    private ImageCollectionPlan imageCollectionPlan;
    private List<ImageResource> contentImages;
    private List<ImageResource> illustrations;
    private List<ImageResource> diagrams;
    private List<ImageResource> logos;
    private boolean modification;
    private int qualityAttempts;
    /** 质检软失败放行时遗留问题的非阻断警告，随完成事件透出给用户。 */
    private String qualityWarning;

    public static WorkflowContext getContext(MessagesState<String> state) {
        if (state == null) {
            return null;
        }
        Object value = state.data().get(WORKFLOW_CONTEXT_KEY);
        return value instanceof WorkflowContext context ? context : null;
    }

    public static Map<String, Object> saveContext(WorkflowContext context) {
        return Collections.singletonMap(WORKFLOW_CONTEXT_KEY, context);
    }
}
