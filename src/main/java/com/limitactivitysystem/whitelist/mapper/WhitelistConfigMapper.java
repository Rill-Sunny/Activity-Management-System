package com.limitactivitysystem.whitelist.mapper;

import com.limitactivitysystem.whitelist.model.WhitelistConfig;
import com.limitactivitysystem.whitelist.model.WhitelistScopeType;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface WhitelistConfigMapper {
    @Select("""
            SELECT config_id, scope_type, scope_id, created_at, updated_at
            FROM whitelist_config
            WHERE scope_type = #{scopeType} AND scope_id = #{scopeId}
            FOR UPDATE
            """)
    @Results(id = "whitelistConfigResultMap", value = {
            @Result(column = "config_id", property = "configId", id = true),
            @Result(column = "scope_type", property = "scopeType"),
            @Result(column = "scope_id", property = "scopeId"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    WhitelistConfig findByScopeForUpdate(@Param("scopeType") WhitelistScopeType scopeType,
                                       @Param("scopeId") Long scopeId);

    @Select("""
            SELECT config_id, scope_type, scope_id, created_at, updated_at
            FROM whitelist_config
            WHERE scope_type = #{scopeType} AND scope_id = #{scopeId}
            FOR SHARE
            """)
    @ResultMap("whitelistConfigResultMap")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    WhitelistConfig findByScopeForShare(@Param("scopeType") WhitelistScopeType scopeType, @Param("scopeId") Long scopeId);

    @Insert("""
            INSERT INTO whitelist_config (scope_type, scope_id) VALUES (#{scopeType}, #{scopeId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "configId", keyColumn = "config_id")
    int insert(WhitelistConfig config);

    @Update("UPDATE whitelist_config SET updated_at = CURRENT_TIMESTAMP(3) WHERE config_id = #{configId}")
    int touch(@Param("configId") Long configId);

    @Delete("DELETE FROM whitelist_config WHERE config_id = #{configId}")
    int deleteById(@Param("configId") Long configId);
}
