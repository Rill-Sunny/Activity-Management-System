-- Outbox replay is an operational action, so it has a distinct ADMIN role.
ALTER TABLE app_user DROP CHECK chk_app_user_role;
ALTER TABLE app_user
    ADD CONSTRAINT chk_app_user_role CHECK (role IN ('USER', 'PUBLISHER', 'ADMIN'));
