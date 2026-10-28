package com.limitactivitysystem.identity.mapper;

import com.limitactivitysystem.identity.model.AppUser;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AppUserMapper {

    @Select("""
            SELECT id, username, password_hash, role, status, created_at
            FROM app_user
            WHERE id = #{id}
            """)
    @Results(id = "appUserResultMap", value = {
            @Result(column = "id", property = "id", id = true),
            @Result(column = "username", property = "username"),
            @Result(column = "password_hash", property = "passwordHash"),
            @Result(column = "role", property = "role"),
            @Result(column = "status", property = "status"),
            @Result(column = "created_at", property = "createdAt")
    })
    AppUser findById(@Param("id") Long id);

    @Select("""
            SELECT id, username, password_hash, role, status, created_at
            FROM app_user WHERE id = #{id} FOR SHARE
            """)
    @ResultMap("appUserResultMap")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    AppUser findByIdForShare(@Param("id") Long id);

    @Select("""
            SELECT id, username, password_hash, role, status, created_at
            FROM app_user
            WHERE username = #{username}
            """)
    @ResultMap("appUserResultMap")
    AppUser findByUsername(@Param("username") String username);

    @Insert("""
            INSERT INTO app_user (username, password_hash, role, status)
            VALUES (#{username}, #{passwordHash}, #{role}, #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(AppUser user);
}
