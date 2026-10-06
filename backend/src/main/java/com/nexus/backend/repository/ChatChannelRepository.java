package com.nexus.backend.repository;

import com.nexus.backend.domain.chat.ChatChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatChannelRepository extends JpaRepository<ChatChannel, Long> {

    Optional<ChatChannel> findByNameAndType(String name, ChatChannel.Type type);

    List<ChatChannel> findByTypeOrderByIdAsc(ChatChannel.Type type);
}
