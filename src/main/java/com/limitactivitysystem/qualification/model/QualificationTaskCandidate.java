package com.limitactivitysystem.qualification.model;

public record QualificationTaskCandidate(Long orderId, Long orderItemId, Long activityId,
                                         String qualificationScope, Long qualificationSessionId) { }
