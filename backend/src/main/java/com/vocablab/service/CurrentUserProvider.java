package com.vocablab.service;

import java.util.UUID;

public interface CurrentUserProvider {
    UUID userId();
    boolean isAdmin();
}
