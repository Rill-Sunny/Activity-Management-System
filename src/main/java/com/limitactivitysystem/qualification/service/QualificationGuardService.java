package com.limitactivitysystem.qualification.service;

import com.limitactivitysystem.qualification.mapper.QualificationGuardMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.TreeSet;
import java.util.LinkedHashMap;

@Service
public class QualificationGuardService {
    private final QualificationGuardMapper guards;

    public QualificationGuardService(QualificationGuardMapper guards) {
        this.guards = guards;
    }

    /** Locks only the affected activities, always in ascending order, within the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, Long> lockActivities(Long userId, Collection<Long> activityIds) {
        if (userId == null || userId <= 0 || activityIds == null) {
            throw new IllegalArgumentException("A positive user ID and activity IDs are required");
        }
        TreeSet<Long> ordered = new TreeSet<>();
        for (Long activityId : activityIds) {
            if (activityId == null || activityId <= 0) {
                throw new IllegalArgumentException("Activity IDs must be positive");
            }
            ordered.add(activityId);
        }
        for (Long activityId : ordered) {
            guards.ensureExists(userId, activityId);
        }
        Map<Long, Long> versions = new LinkedHashMap<>();
        for (Long activityId : ordered) {
            Long version = guards.lockVersion(userId, activityId);
            if (version == null || version < 0) {
                throw new IllegalStateException("Qualification guard row is missing or invalid");
            }
            versions.put(activityId, version);
        }
        return Collections.unmodifiableMap(versions);
    }

    /** Records a committed-set mutation after a valid Ticket is issued or refunded. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long advanceVersion(Long userId, Long activityId) {
        if (guards.advanceVersion(userId, activityId) != 1) {
            throw new IllegalStateException("Qualification guard version could not advance");
        }
        Long version = guards.lockVersion(userId, activityId);
        if (version == null) {
            throw new IllegalStateException("Qualification guard version disappeared");
        }
        return version;
    }
}
