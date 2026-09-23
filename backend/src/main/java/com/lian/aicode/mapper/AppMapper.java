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

    /** 下载 ZIP 完整写出后原子累加下载次数，避免并发下载丢失计数。 */
    @Update("""
            UPDATE app
            SET download_count = COALESCE(download_count, 0) + 1,
                update_time = CURRENT_TIMESTAMP
            WHERE id = #{appId} AND is_delete = 0
            """)
    int incrementDownloadCount(@Param("appId") Long appId);

    /** 仅当应用仍处于目标版本且封面未被其他操作改写时更新截图 URL。 */
    @Update("""
            <script>
            UPDATE app
            SET cover = #{cover}, update_time = CURRENT_TIMESTAMP
            WHERE id = #{appId}
              AND current_version = #{versionNo}
              AND is_delete = 0
              AND
              <choose>
                <when test="oldCover == null">cover IS NULL</when>
                <otherwise>cover = #{oldCover}</otherwise>
              </choose>
            </script>
            """)
    int updateCoverIfCurrentVersion(@Param("appId") Long appId,
                                   @Param("versionNo") Integer versionNo,
                                   @Param("oldCover") String oldCover,
                                   @Param("cover") String cover);
}
