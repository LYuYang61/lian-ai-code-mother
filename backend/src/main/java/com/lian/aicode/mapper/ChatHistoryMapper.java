package com.lian.aicode.mapper;

import com.lian.aicode.model.entity.ChatHistory;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 对话历史数据访问接口。 */
@Mapper
public interface ChatHistoryMapper extends BaseMapper<ChatHistory> {
}
