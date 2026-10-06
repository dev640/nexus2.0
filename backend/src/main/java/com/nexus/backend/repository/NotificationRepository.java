package com.nexus.backend.repository;

import com.nexus.backend.domain.notification.Notification;
import com.nexus.backend.domain.user.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByRecipientOrderByCreatedAtDesc(User recipient);

    long countByRecipientAndReadFalse(User recipient);

    long countByRecipientAndCategoryAndReadFalse(User recipient, Notification.Category category);
}
