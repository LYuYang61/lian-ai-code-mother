package com.lian.aicode.mapper;

import com.lian.aicode.model.entity.UserAccount;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 用户数据访问接口。 */
@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccount> {
}
