package com.nexus.backend.repository;

import com.nexus.backend.domain.chat.ChatChannel;
import com.nexus.backend.domain.chat.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findByChannelOrderByIdDesc(ChatChannel channel, Pageable pageable);

    /**
     * Removes a channel's messages in one statement.
     *
     * <p>Deleting the children explicitly rather than leaning on the table's
     * ON DELETE CASCADE: the cascade is invisible at the call site, so a
     * deployment whose constraints differ fails at runtime with an opaque
     * violation. This is also one round trip instead of loading every message.
     *
     * <p>{@code clearAutomatically} matters: any messages already in the
     * persistence context would otherwise still reference the deleted channel.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ChatMessage m where m.channel.id = :channelId")
    void deleteAllInChannel(@Param("channelId") Long channelId);

    List<ChatMessage> findByChannelAndIdLessThanOrderByIdDesc(ChatChannel channel, Long before, Pageable pageable);

    /** Newest message id in a channel — the seed for unread counting. */
    @Query("select max(m.id) from ChatMessage m where m.channel = :channel")
    Optional<Long> findLatestIdByChannel(@Param("channel") ChatChannel channel);

    @Query("select m from ChatMessage m join fetch m.author where m.id = :id")
    Optional<ChatMessage> findWithAuthorById(@Param("id") Long id);

    long countByChannel(ChatChannel channel);

    long countByChannelAndIdGreaterThan(ChatChannel channel, Long id);

    @Query("""
        select m from ChatMessage m
        join fetch m.author
        where m.channel in :channels and lower(m.body) like lower(concat('%', :term, '%'))
        order by m.id desc
        """)
    List<ChatMessage> searchInChannels(@Param("channels") List<ChatChannel> channels,
                                       @Param("term") String term,
                                       Pageable pageable);
}
