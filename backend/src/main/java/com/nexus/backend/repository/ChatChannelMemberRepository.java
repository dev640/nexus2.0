package com.nexus.backend.repository;

import com.nexus.backend.domain.chat.ChatChannel;
import com.nexus.backend.domain.chat.ChatChannelMember;
import com.nexus.backend.domain.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatChannelMemberRepository extends JpaRepository<ChatChannelMember, Long> {

    /** A user's memberships with their channels, sidebar order. */
    @Query("""
        select m from ChatChannelMember m
        join fetch m.channel
        where m.user = :user
        order by m.channel.createdAt asc, m.channel.id asc
        """)
    List<ChatChannelMember> findWithChannelByUser(@Param("user") User user);

    List<ChatChannelMember> findByChannel(ChatChannel channel);

    List<ChatChannelMember> findByChannelId(Long channelId);

    /**
     * Removes a channel's membership rows in one statement, before the channel
     * itself. See {@code ChatMessageRepository#deleteAllInChannel} for why the
     * children are removed explicitly instead of relying on a cascade.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ChatChannelMember m where m.channel.id = :channelId")
    void deleteAllInChannel(@Param("channelId") Long channelId);

    List<ChatChannelMember> findByChannelAndUserNot(ChatChannel channel, User user);

    Optional<ChatChannelMember> findByChannelAndUser(ChatChannel channel, User user);

    boolean existsByChannelAndUser(ChatChannel channel, User user);

    boolean existsByChannelIdAndUserId(Long channelId, Long userId);

    /** Ids of the other members of every channel the user belongs to (DM resolution). */
    @Query("""
        select m2.user.id from ChatChannelMember m1
        join ChatChannelMember m2 on m2.channel = m1.channel
        where m1.user = :user and m2.user <> :user
        """)
    List<Long> findPartnerIdsOfMyChannels(@Param("user") User user);
}
