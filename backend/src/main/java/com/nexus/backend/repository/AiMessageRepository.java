package com.nexus.backend.repository;

import com.nexus.backend.domain.ai.AiMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AiMessageRepository extends JpaRepository<AiMessage, UUID> {

    List<AiMessage> findByConversation_IdOrderByCreatedAtAsc(UUID conversationId);

    long countByConversation_Id(UUID conversationId);

    @Modifying
    @Query("delete from AiMessage m where m.conversation.id = :conversationId and m.id = :id")
    long deleteByConversation_IdAndId(@Param("conversationId") UUID conversationId, @Param("id") UUID id);
}
