package com.nexus.backend.repository;

import com.nexus.backend.domain.ai.AiConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AiConversationRepository extends JpaRepository<AiConversation, UUID> {

    List<AiConversation> findByUser_IdOrderByUpdatedAtDesc(Long userId);

    Optional<AiConversation> findByIdAndUser_Id(UUID id, Long userId);

    boolean existsByIdAndUser_Id(UUID id, Long userId);

    long countByUser_Id(Long userId);
}
