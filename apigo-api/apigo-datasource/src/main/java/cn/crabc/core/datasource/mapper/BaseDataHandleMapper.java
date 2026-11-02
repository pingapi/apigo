/*
 * Copyright 2023, crabc.cn (creabc@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.crabc.core.datasource.mapper;

import cn.crabc.core.datasource.util.CachedXmlLanguageDriver;
import org.apache.ibatis.annotations.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用数据操作 Mapper
 *
 * @author yuqf
 */
@Mapper
public interface BaseDataHandleMapper {

    /**
     * 查询类SQL
     * @param params
     * @return
     */
    // @Lang 让 ProviderSqlSource 使用带 SqlSource 缓存的解析器，避免每请求重新解析动态标签
    @Lang(CachedXmlLanguageDriver.class)
    @SelectProvider(type = BaseSelectProvider.class, method = "executeQuery")
    @ResultType(LinkedHashMap.class)
    @Options(timeout = 30)  // 超时时间
    List<LinkedHashMap<String, Object>> executeQuery(Map<String, Object> params);

    /**
     * 新增类SQL
     * @param params
     * @return
     */
    @Lang(CachedXmlLanguageDriver.class)
    @InsertProvider(type = BaseSelectProvider.class, method = "executeInsert")
    @Options(timeout = 60)
    Integer executeInsert(Map<String, Object> params);

    /**
     * 修改类SQL
     * @param params
     * @return
     */
    @Lang(CachedXmlLanguageDriver.class)
    @UpdateProvider(type = BaseSelectProvider.class, method = "executeUpdate")
    @Options(timeout = 30)
    Integer executeUpdate(Map<String, Object> params);

    /**
     * 删除类SQL
     * @param params
     * @return
     */
    @Lang(CachedXmlLanguageDriver.class)
    @UpdateProvider(type = BaseSelectProvider.class, method = "executeDelete")
    @Options(timeout = 30)
    Integer executeDelete(Map<String, Object> params);
}
