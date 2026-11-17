package com.limitactivitysystem.whitelist.service;

import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.service.ActivityOwnershipService;
import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.whitelist.exception.WhitelistErrorCode;
import com.limitactivitysystem.whitelist.exception.WhitelistException;
import com.limitactivitysystem.whitelist.mapper.WhitelistConfigMapper;
import com.limitactivitysystem.whitelist.mapper.WhitelistMemberMapper;
import com.limitactivitysystem.whitelist.model.WhitelistConfig;
import com.limitactivitysystem.whitelist.model.WhitelistMember;
import com.limitactivitysystem.whitelist.model.WhitelistScopeType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class PublisherWhitelistService {
    private final ActivityOwnershipService ownershipService;
    private final AppUserMapper userMapper;
    private final WhitelistConfigMapper configMapper;
    private final WhitelistMemberMapper memberMapper;
    private final com.limitactivitysystem.activity.mapper.ActivitySessionMapper sessions;

    public PublisherWhitelistService(ActivityOwnershipService ownershipService, AppUserMapper userMapper,
                                     WhitelistConfigMapper configMapper, WhitelistMemberMapper memberMapper,
                                     com.limitactivitysystem.activity.mapper.ActivitySessionMapper sessions) {
        this.ownershipService = ownershipService;
        this.userMapper = userMapper;
        this.configMapper = configMapper;
        this.memberMapper = memberMapper;
        this.sessions = sessions;
    }

    public WhitelistConfig enable(WhitelistScopeType scopeType, Long scopeId) {
        lockEditableActivity(scopeType, scopeId);
        WhitelistConfig config = configMapper.findByScopeForUpdate(scopeType, scopeId);
        if (config == null) {
            config = new WhitelistConfig();
            config.setScopeType(scopeType);
            config.setScopeId(scopeId);
            configMapper.insert(config);
        }
        return config;
    }

    public WhitelistMember addMember(WhitelistScopeType scopeType, Long scopeId, Long userId) {
        lockEditableActivity(scopeType, scopeId);
        WhitelistConfig config = requireConfig(scopeType, scopeId);
        if (userMapper.findById(userId) == null) {
            throw new WhitelistException(WhitelistErrorCode.WHITELIST_USER_NOT_FOUND, "Whitelist user not found");
        }
        WhitelistMember member = new WhitelistMember();
        member.setConfigId(config.getConfigId());
        member.setUserId(userId);
        try {
            memberMapper.insert(member);
        } catch (DuplicateKeyException exception) {
            throw new WhitelistException(WhitelistErrorCode.WHITELIST_MEMBER_ALREADY_EXISTS,
                    "User is already a whitelist member");
        }
        configMapper.touch(config.getConfigId());
        return member;
    }

    public void removeMember(WhitelistScopeType scopeType, Long scopeId, Long userId) {
        lockEditableActivity(scopeType, scopeId);
        WhitelistConfig config = requireConfig(scopeType, scopeId);
        if (memberMapper.deleteMember(config.getConfigId(), userId) > 0) {
            configMapper.touch(config.getConfigId());
        }
    }

    public void disable(WhitelistScopeType scopeType, Long scopeId) {
        lockEditableActivity(scopeType, scopeId);
        WhitelistConfig config = configMapper.findByScopeForUpdate(scopeType, scopeId);
        if (config != null) {
            memberMapper.deleteByConfigId(config.getConfigId());
            configMapper.deleteById(config.getConfigId());
        }
    }

    private WhitelistConfig requireConfig(WhitelistScopeType scopeType, Long scopeId) {
        WhitelistConfig config = configMapper.findByScopeForUpdate(scopeType, scopeId);
        if (config == null) {
            throw new WhitelistException(WhitelistErrorCode.WHITELIST_NOT_CONFIGURED,
                    "Enable the whitelist before managing members");
        }
        return config;
    }

    private void lockEditableActivity(WhitelistScopeType scopeType, Long scopeId) {
        Activity activity = scopeType == WhitelistScopeType.SESSION
                ? ownershipService.assertSessionOwner(scopeId).activity()
                : ownershipService.assertActivityOwner(scopeId);
        // The discovery Session may predate a wait on Activity X. Read its current state after that lock.
        if(scopeType==WhitelistScopeType.SESSION) {
            var session=sessions.findByIdForShare(scopeId);
            if(session==null||!activity.getActivityId().equals(session.getActivityId())||!"SCHEDULED".equals(session.getStatus())) {
                throw new WhitelistException(WhitelistErrorCode.WHITELIST_NOT_EDITABLE,"Whitelist requires a scheduled session");
            }
        }
        // Check the current lifecycle under the root lock, which remains held through the mutation.
        // Whitelists stay editable after publication; terminal and unknown states fail closed.
        if (!"DRAFT".equals(activity.getStatus()) && !"PUBLISHED".equals(activity.getStatus())) {
            throw new WhitelistException(WhitelistErrorCode.WHITELIST_NOT_EDITABLE,
                    "Whitelist can only be managed for DRAFT or PUBLISHED activities");
        }
    }
}
