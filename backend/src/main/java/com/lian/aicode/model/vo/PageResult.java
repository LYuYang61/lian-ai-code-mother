package com.lian.aicode.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 对外稳定的分页协议，避免直接暴露 MyBatis-Flex Page 的内部字段。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    private List<T> records;
    private long pageNum;
    private long pageSize;
    private long total;
    private long pages;
}
