package com.nexus.backend.repository;

import com.nexus.backend.domain.chat.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    List<ChatMessage> findByConversation_IdOrderByCreatedAtAsc(UUID conversationId);

    long countByConversation_Id(UUID conversationId);

    @Modifying
    @Query("delete from ChatMessage m where m.conversation.id = :conversationId and m.id = :id")
    long deleteByConversation_IdAndId(@Param("conversationId") UUID conversationId, @Param("id") UUID id);
}
