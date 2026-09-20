package com.lian.aicode.mapper;

import com.lian.aicode.model.entity.App;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 应用数据访问接口。 */
@Mapper
public interface AppMapper extends BaseMapper<App> {
}
