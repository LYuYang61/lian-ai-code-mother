package com.lian.aicode.mapper;

import com.lian.aicode.model.entity.App;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 应用数据访问接口。 */
@Mapper
public interface AppMapper extends BaseMapper<App> {

    /** 原子累加真实发起的生成轮次，避免并发请求先读后写造成丢失。 */
    @Update("""
            UPDATE app
            SET conversation_rounds = COALESCE(conversation_rounds, 0) + 1,
                update_time = CURRENT_TIMESTAMP
            WHERE id = #{appId} AND is_delete = 0
            """)
    int incrementConversationRounds(@Param("appId") Long appId);
}
