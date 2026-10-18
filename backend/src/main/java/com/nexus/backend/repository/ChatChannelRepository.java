package com.nexus.backend.repository;

import com.nexus.backend.domain.chat.ChatChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatChannelRepository extends JpaRepository<ChatChannel, Long> {

    /** One channel, only if it belongs to this company. */
    Optional<ChatChannel> findByIdAndCompanyId(Long id, Long companyId);

    /**
     * A named channel inside this company. Channel names are unique per company
     * rather than globally, so two companies can both have a #general and
     * neither one is the other's.
     */
    Optional<ChatChannel> findByNameAndTypeAndCompanyId(String name, ChatChannel.Type type, Long companyId);

    /** The company's public channels, for browse-and-join. */
    List<ChatChannel> findByTypeAndCompanyIdOrderByIdAsc(ChatChannel.Type type, Long companyId);
}
