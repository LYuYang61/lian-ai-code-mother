package com.lian.aicode.core.parser;

/** 不同代码生成模式的解析策略接口。 */
public interface CodeParser<T> {

    /**
     * 解析模型返回的完整文本。
     *
     * @param codeContent 模型返回内容
     * @return 结构化代码结果
     */
    T parseCode(String codeContent);
}
