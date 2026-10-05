package com.vocablab.service.impl;

import com.vocablab.dto.response.HealthResponse;
import com.vocablab.service.HealthService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HealthServiceImpl implements HealthService {

    private final JdbcTemplate jdbcTemplate;

    public HealthServiceImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public HealthResponse check() {
        Integer probe = jdbcTemplate.queryForObject("select 1", Integer.class);
        String databaseStatus = Integer.valueOf(1).equals(probe) ? "UP" : "UNKNOWN";
        return new HealthResponse("UP", databaseStatus);
    }
}
