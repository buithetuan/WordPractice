package com.vocablab.service.impl;

import com.vocablab.service.CurrentUserProvider;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
public class LocalCurrentUserProvider implements CurrentUserProvider {
    private final JdbcTemplate jdbcTemplate;
    private final UUID userId;

    public LocalCurrentUserProvider(JdbcTemplate jdbcTemplate,
            @org.springframework.beans.factory.annotation.Value("${spring.lab.user-id}") UUID userId) {
        this.jdbcTemplate = jdbcTemplate;
        this.userId = userId;
    }

    @Override
    public UUID userId() {
        return userId;
    }

    @Override
    public boolean isAdmin() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from user_roles ur
                join roles r on r.id = ur.role_id
                where ur.user_id = ? and r.code = 'ADMIN'
                """, Integer.class, userId);
        return count != null && count > 0;
    }
}
