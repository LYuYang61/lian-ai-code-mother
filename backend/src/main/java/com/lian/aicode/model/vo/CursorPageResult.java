package com.lian.aicode.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 基于时间和 id 的稳定游标分页结果，避免新增消息导致 offset 分页重复或遗漏。 */
@Data
@Builder
public class CursorPageResult<T> {

    private List<T> records;
    private boolean hasMore;
    private LocalDateTime nextCreateTime;
    private Long nextId;
}
