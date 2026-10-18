package com.nexus.backend.repository;

import com.nexus.backend.domain.user.UserAvatar;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserAvatarRepository extends JpaRepository<UserAvatar, Long> {

    /**
     * A user's avatar, only while that user belongs to this company.
     *
     * <p>Avatars are keyed by user id, which makes an id from another company
     * look like any other id — so the read carries the company rather than
     * trusting the number. A miss is a 404 either way, which keeps ids
     * unprobeable.
     */
    Optional<UserAvatar> findByUserIdAndCompanyId(Long userId, Long companyId);
}
