package com.nexus.backend.repository;

import com.nexus.backend.domain.user.UserAvatar;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserAvatarRepository extends JpaRepository<UserAvatar, Long> {
    Optional<UserAvatar> findByUserId(Long userId);
}