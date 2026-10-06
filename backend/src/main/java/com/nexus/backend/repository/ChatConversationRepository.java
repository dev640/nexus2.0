package com.nexus.backend.repository;

import com.nexus.backend.domain.chat.ChatConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, UUID> {

    List<ChatConversation> findByUser_IdOrderByUpdatedAtDesc(Long userId);

    Optional<ChatConversation> findByIdAndUser_Id(UUID id, Long userId);

    boolean existsByIdAndUser_Id(UUID id, Long userId);

    long countByUser_Id(Long userId);
}
