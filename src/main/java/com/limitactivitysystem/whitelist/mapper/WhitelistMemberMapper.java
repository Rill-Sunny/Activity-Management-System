package com.limitactivitysystem.whitelist.mapper;

import com.limitactivitysystem.whitelist.model.WhitelistMember;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WhitelistMemberMapper {
    @Select("""
            SELECT member_id FROM whitelist_member
            WHERE config_id = #{configId} AND user_id = #{userId}
            FOR SHARE
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Long findIdByConfigIdAndUserIdForShare(@Param("configId") Long configId, @Param("userId") Long userId);

    @Insert("INSERT INTO whitelist_member (config_id, user_id) VALUES (#{configId}, #{userId})")
    @Options(useGeneratedKeys = true, keyProperty = "memberId", keyColumn = "member_id")
    int insert(WhitelistMember member);

    @Delete("DELETE FROM whitelist_member WHERE config_id = #{configId} AND user_id = #{userId}")
    int deleteMember(@Param("configId") Long configId, @Param("userId") Long userId);

    @Delete("DELETE FROM whitelist_member WHERE config_id = #{configId}")
    int deleteByConfigId(@Param("configId") Long configId);
}
