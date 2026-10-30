-- Read-only reconciliation. Every result set must be empty. Use the intended database before running.
-- Run after concurrent requests finish, or within one consistent read-only snapshot.

-- Logical references and FREE-only scope, including retained history.
SELECT r.reservation_id, r.user_id, r.session_id, 'INVALID_RESERVATION_SCOPE' AS violation
FROM free_reservation r
LEFT JOIN app_user u ON u.id = r.user_id
LEFT JOIN activity_session s ON s.activity_session_id = r.session_id
LEFT JOIN activity a ON a.activity_id = s.activity_id
WHERE u.id IS NULL OR s.activity_session_id IS NULL OR a.activity_id IS NULL OR a.type <> 'FREE';

-- The generated unique key should make this result impossible through valid database writes.
SELECT user_id, session_id, COUNT(*) AS active_count, 'DUPLICATE_ACTIVE_RESERVATION' AS violation
FROM free_reservation
WHERE status = 'ACTIVE'
GROUP BY user_id, session_id
HAVING COUNT(*) > 1;

-- A quota snapshot must still match the immutable published session capacity.
SELECT q.session_id, q.capacity, q.remaining, 'INVALID_QUOTA_SCOPE_OR_CAPACITY' AS violation
FROM free_session_quota q
LEFT JOIN activity_session s ON s.activity_session_id = q.session_id
LEFT JOIN activity a ON a.activity_id = s.activity_id
WHERE s.activity_session_id IS NULL OR a.activity_id IS NULL OR a.type <> 'FREE'
   OR q.capacity <> s.capacity OR q.remaining < 0 OR q.remaining > q.capacity;

-- Compare against the session capacity as well as the quota snapshot.
-- CANCELLED history consumes no quota.
SELECT q.session_id, s.capacity AS session_capacity, q.capacity AS quota_capacity,
       q.remaining, COUNT(r.reservation_id) AS active_count,
       'QUOTA_RESERVATION_MISMATCH' AS violation
FROM free_session_quota q
JOIN activity_session s ON s.activity_session_id = q.session_id
LEFT JOIN free_reservation r ON r.session_id = q.session_id AND r.status = 'ACTIVE'
GROUP BY q.session_id, s.capacity, q.capacity, q.remaining
HAVING q.remaining + COUNT(r.reservation_id) <> s.capacity
    OR q.remaining + COUNT(r.reservation_id) <> q.capacity;

-- Missing quota is allowed before first use, but never when ACTIVE reservations exist.
SELECT r.reservation_id, r.session_id, 'ACTIVE_RESERVATION_WITHOUT_QUOTA' AS violation
FROM free_reservation r
LEFT JOIN free_session_quota q ON q.session_id = r.session_id
WHERE r.status = 'ACTIVE' AND q.session_id IS NULL;
