package com.vocablab.config;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@Profile("local")
public class LocalIdentityConfiguration {
    @Bean
    CommandLineRunner initializeLabUser(
            JdbcTemplate jdbcTemplate,
            @Value("${spring.lab.user-id}") UUID userId,
            @Value("${spring.lab.user-email}") String email,
            @Value("${spring.lab.admin:false}") boolean admin) {
        return args -> {
            jdbcTemplate.update("""
                    insert into users (id, email, display_name, status)
                    values (?, ?, 'Lab User', 'ACTIVE')
                    on conflict (id) do nothing
                    """, userId, email);
            String roleCode = admin ? "ADMIN" : "USER";
            jdbcTemplate.update("""
                    insert into user_roles (user_id, role_id)
                    select ?, id from roles where code = ?
                    on conflict (user_id, role_id) do nothing
                    """, userId, roleCode);
        };
    }
}
