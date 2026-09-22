package com.lian.aicode.ai.model.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 流式事件的稳定 JSON 载体。
 *
 * <p>arguments 和 result 只放经过脱敏、截断的展示摘要；文件内容由受控工具写入磁盘，
 * 不通过 SSE 重复传输，避免大响应和敏感数据泄露。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamMessage {

    private String type;
    private String data;
    private String id;
    private String name;
    private String displayName;
    private String arguments;
    private String result;
}
