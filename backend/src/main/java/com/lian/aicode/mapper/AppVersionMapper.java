package com.lian.aicode.mapper;

import com.lian.aicode.model.entity.AppVersion;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 应用版本数据访问接口。 */
@Mapper
public interface AppVersionMapper extends BaseMapper<AppVersion> {
}
