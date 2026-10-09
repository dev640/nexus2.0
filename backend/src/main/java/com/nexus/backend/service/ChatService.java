package com.nexus.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.backend.chat.ChatBroadcaster;
import com.nexus.backend.domain.chat.ChatChannel;
import com.nexus.backend.domain.chat.ChatChannelMember;
import com.nexus.backend.domain.chat.ChatMessage;
import com.nexus.backend.domain.notification.Notification;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.dto.ChatChannelRequest;
import com.nexus.backend.dto.ChatChannelResponse;
import com.nexus.backend.dto.ChatEvent;
import com.nexus.backend.dto.ChatMessageResponse;
import com.nexus.backend.dto.ChatUnreadResponse;
import com.nexus.backend.exception.ResourceNotFoundException;
import com.nexus.backend.exception.ValidationException;
import com.nexus.backend.repository.ChatChannelMemberRepository;
import com.nexus.backend.repository.ChatChannelRepository;
import com.nexus.backend.repository.ChatMessageRepository;
import com.nexus.backend.repository.NotificationRepository;
import com.nexus.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Slack-style chat: public and private channels, direct messages, paging,
 * reactions, unread counts, mentions and search. All access is membership
 * checked — a user never sees a channel they do not belong to.
 */
@Service
public class ChatService {

    public static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** Default channels created on first use so a fresh workspace is never empty. */
    private static final List<String> SEED_CHANNELS = List.of("general", "random", "dev");

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int SEARCH_LIMIT = 30;

    private final ChatChannelRepository channelRepository;
    private final ChatChannelMemberRepository memberRepository;
    private final ChatMessageRepository messageRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final ChatBroadcaster broadcaster;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatService(
        ChatChannelRepository channelRepository,
        ChatChannelMemberRepository memberRepository,
        ChatMessageRepository messageRepository,
        NotificationRepository notificationRepository,
        UserRepository userRepository,
        ChatBroadcaster broadcaster
    ) {
        this.channelRepository = channelRepository;
        this.memberRepository = memberRepository;
        this.messageRepository = messageRepository;
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.broadcaster = broadcaster;
    }

    // ---------- channels ----------

    /**
     * The caller's conversations (channels they belong to), creating the default
     * public channels on first use so a new workspace is never empty.
     */
    @Transactional
    public List<ChatChannelResponse> myChannels() {
        User me = currentUser();
        ensureSeedChannels(me);
        return toResponses(memberRepository.findWithChannelByUser(me), me);
    }

    /** Public channels the caller has not joined yet (browse + join). */
    @Transactional(readOnly = true)
    public List<ChatChannelResponse> discoverChannels() {
        User me = currentUser();
        Set<Long> mine = new LinkedHashSet<>();
        for (ChatChannelMember m : memberRepository.findWithChannelByUser(me)) {
            mine.add(m.getChannel().getId());
        }
        return channelRepository.findByTypeOrderByIdAsc(ChatChannel.Type.PUBLIC).stream()
            .filter(c -> !mine.contains(c.getId()))
            .map(c -> ChatChannelResponse.of(c, false, null, null))
            .toList();
    }

    @Transactional
    public ChatChannelResponse createChannel(ChatChannelRequest request) {
        User me = currentUser();
        String name = request.name().trim();
        if (name.startsWith("#")) name = name.substring(1).trim();
        if (name.isBlank()) throw new ValidationException("Channel name is required");
        String normalized = name.toLowerCase();
        if (!normalized.matches("[a-z0-9._-]{1,80}")) {
            throw new ValidationException(
                "Channel names may only contain lowercase letters, numbers, dots, dashes and underscores");
        }

        ChatChannel.Type type = "PRIVATE".equalsIgnoreCase(request.type())
            ? ChatChannel.Type.PRIVATE : ChatChannel.Type.PUBLIC;

        if (channelRepository.findByNameAndType(normalized, type).isPresent()) {
            throw new ValidationException("A " + type.name().toLowerCase() + " channel #" + normalized + " already exists");
        }

        ChatChannel channel = new ChatChannel();
        channel.setName(normalized);
        channel.setType(type);
        channel.setTopic(request.topic() != null ? request.topic().trim() : null);
        channel.setCreatedBy(me.getEmail());
        channel = channelRepository.save(channel);

        joinChannel(channel, me, true);

        broadcaster.broadcast(event(ChatEvent.CHANNEL_CREATED, channel, null, null, null));
        log.info("Created {} channel #{} for {}", type, normalized, me.getEmail());
        return ChatChannelResponse.of(channel, true, null, null);
    }

    /** Opens (or returns the existing) direct message conversation with a teammate. */
    @Transactional
    public ChatChannelResponse openDirectMessage(Long partnerId) {
        User me = currentUser();
        if (partnerId == null || partnerId.equals(me.getId())) {
            throw new ValidationException("Choose another person to message");
        }
        User partner = userRepository.findById(partnerId)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", partnerId));

        ChatChannel existing = findDmWith(me, partnerId);
        if (existing != null) {
            return dmResponse(existing, me, partner);
        }

        ChatChannel channel = new ChatChannel();
        channel.setType(ChatChannel.Type.DM);
        channel.setCreatedBy(me.getEmail());
        channel = channelRepository.save(channel);
        joinChannel(channel, me, false);
        joinChannel(channel, partner, false);

        broadcaster.broadcast(event(ChatEvent.CHANNEL_CREATED, channel, null, null, null));
        return dmResponse(channel, me, partner);
    }

    @Transactional
    public ChatChannelResponse joinChannel(Long channelId) {
        User me = currentUser();
        ChatChannel channel = channelRepository.findById(channelId)
            .orElseThrow(() -> new ResourceNotFoundException("Channel", "id", channelId));
        if (channel.getType() != ChatChannel.Type.PUBLIC) {
            throw new ValidationException("Only public channels can be joined");
        }
        if (!memberRepository.existsByChannelAndUser(channel, me)) {
            joinChannel(channel, me, false);
            broadcaster.broadcast(event(ChatEvent.CHANNEL_CREATED, channel, null, null, null));
        }
        return ChatChannelResponse.of(channel, true, null, null);
    }

    @Transactional
    public void leaveChannel(Long channelId) {
        User me = currentUser();
        ChatChannel channel = requireChannel(channelId);
        ChatChannelMember membership = memberRepository.findByChannelAndUser(channel, me)
            .orElseThrow(() -> new ResourceNotFoundException("Membership", "channel", channelId));
        if (channel.getType() == ChatChannel.Type.DM) {
            throw new ValidationException("Direct messages cannot be left");
        }
        memberRepository.delete(membership);
    }

    @Transactional
    public ChatChannelResponse updateTopic(Long channelId, String topic) {
        User me = currentUser();
        ChatChannel channel = requireChannel(channelId);
        ChatChannelMember membership = requireMembership(channel, me);
        if (channel.getType() == ChatChannel.Type.DM) {
            throw new ValidationException("Direct messages have no topic");
        }
        if (!membership.isAdmin()) {
            throw new ValidationException("Only channel admins can change the topic");
        }
        channel.setTopic(topic != null ? topic.trim() : null);
        channel = channelRepository.save(channel);
        broadcaster.broadcast(event(ChatEvent.CHANNEL_CREATED, channel, null, null, null));
        return ChatChannelResponse.of(channel, true, null, null);
    }

    /**
     * Deletes a channel and everything in it.
     *
     * <p>Restricted to the creator or a workspace ADMIN. Members cannot delete a
     * channel they merely belong to, because a channel is shared history rather
     * than personal content — leaving is available for that. Direct messages are
 * *not* deletable this way: two people share that conversation, and deleting it
     * would silently destroy the other person's history too.
     */
@Transactional
public void deleteChannel(Long channelId) {
    User me = currentUser();
    ChatChannel channel = requireChannel(channelId);

    if (channel.getType() == ChatChannel.Type.DM) {
        throw new ValidationException("Direct messages cannot be deleted");
    }
    boolean creator = me.getEmail().equalsIgnoreCase(channel.getCreatedBy());
    if (!creator && me.getRole() != UserRole.ADMIN) {
        throw new ValidationException("Only the channel creator or an admin can delete this channel");
    }

    String name = channel.getName();
    // Children first, explicitly. Relying on the ON DELETE CASCADE made this
    // endpoint fail outright wherever those constraints were absent, with an
    // opaque 500 and nothing to act on.
    messageRepository.deleteAllInChannel(channelId);
    memberRepository.deleteAllInChannel(channelId);
    channelRepository.delete(channel);
    broadcaster.broadcast(event(ChatEvent.CHANNEL_DELETED, channel, null, null, null));
    log.info("Channel #{} deleted by {}", name, me.getEmail());
}

// ---------- messages ----------

    /** Newest page (or older history with `before`), returned oldest-first. */
    @Transactional(readOnly = true)
    public List<ChatMessageResponse> messages(Long channelId, Long before, Integer limit) {
        User me = currentUser();
        ChatChannel channel = requireChannel(channelId);
        requireMembership(channel, me);

        int size = limit == null ? DEFAULT_PAGE_SIZE : Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        Pageable page = PageRequest.of(0, size);
        List<ChatMessage> found = before == null
            ? messageRepository.findByChannelOrderByIdDesc(channel, page)
            : messageRepository.findByChannelAndIdLessThanOrderByIdDesc(channel, before, page);
        return found.reversed().stream().map(m -> ChatMessageResponse.of(m, parseReactions(m))).toList();
    }

    @Transactional
    public ChatMessageResponse postMessage(Long channelId, String body) {
        User me = currentUser();
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) throw new ValidationException("Message body is required");
        ChatChannel channel = requireChannel(channelId);
        requireMembership(channel, me);

        ChatMessage message = new ChatMessage();
        message.setChannel(channel);
        message.setAuthor(me);
        message.setBody(text);
        ChatMessage saved = messageRepository.save(message);

        // The author has, by definition, read their own message.
        memberRepository.findByChannelAndUser(channel, me).ifPresent(m -> {
            m.setLastReadMessageId(saved.getId());
            memberRepository.save(m);
        });

        notifyMentions(channel, me, saved);
        broadcaster.broadcast(event(ChatEvent.MESSAGE_CREATED, channel,
            ChatMessageResponse.of(saved, Map.of()), null, null));
        return ChatMessageResponse.of(saved, Map.of());
    }

    @Transactional
    public ChatMessageResponse editMessage(Long messageId, String body) {
        User me = currentUser();
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) throw new ValidationException("Message body is required");
        ChatMessage message = messageRepository.findWithAuthorById(messageId)
            .orElseThrow(() -> new ResourceNotFoundException("Message", "id", messageId));
        requireMembership(message.getChannel(), me);
        if (message.getAuthor() == null || !message.getAuthor().getId().equals(me.getId())) {
            throw new ValidationException("You can only edit your own messages");
        }
        message.setBody(text);
        message.setEdited(true);
        message = messageRepository.save(message);
        broadcaster.broadcast(event(ChatEvent.MESSAGE_UPDATED, message.getChannel(),
            ChatMessageResponse.of(message, parseReactions(message)), null, null));
        return ChatMessageResponse.of(message, parseReactions(message));
    }

    @Transactional
    public void deleteMessage(Long messageId) {
        User me = currentUser();
        ChatMessage message = messageRepository.findWithAuthorById(messageId)
            .orElseThrow(() -> new ResourceNotFoundException("Message", "id", messageId));
        ChatChannel channel = message.getChannel();
        ChatChannelMember membership = requireMembership(channel, me);
        boolean author = message.getAuthor() != null && message.getAuthor().getId().equals(me.getId());
        if (!author && !membership.isAdmin()) {
            throw new ValidationException("Only the author or a channel admin can delete a message");
        }
        messageRepository.delete(message);
        broadcaster.broadcast(new ChatEvent(ChatEvent.MESSAGE_DELETED, channel.getId(), null, null, null, null, null, messageId));
    }

    @Transactional
    public ChatMessageResponse react(Long messageId, String emoji, boolean add) {
        User me = currentUser();
        String key = emoji == null ? "" : emoji.trim();
        if (key.isEmpty()) throw new ValidationException("Emoji is required");
        ChatMessage message = messageRepository.findWithAuthorById(messageId)
            .orElseThrow(() -> new ResourceNotFoundException("Message", "id", messageId));
        requireMembership(message.getChannel(), me);

        Map<String, List<Long>> reactions = parseReactions(message);
        List<Long> users = new ArrayList<>(reactions.getOrDefault(key, List.of()));
        boolean changed;
        if (add) {
            changed = !users.contains(me.getId());
            if (changed) users.add(me.getId());
        } else {
            changed = users.remove(me.getId());
        }
        if (!changed) {
            return ChatMessageResponse.of(message, reactions);
        }
        if (users.isEmpty()) {
            reactions.remove(key);
        } else {
            reactions.put(key, users);
        }
        message.setReactions(writeReactions(reactions));
        message = messageRepository.save(message);
        broadcaster.broadcast(new ChatEvent(ChatEvent.REACTIONS_UPDATED, message.getChannel().getId(), null,
            List.of(new ChatEvent.ReactionEvent(message.getId(), key, users)), null, null, null, null));
        return ChatMessageResponse.of(message, reactions);
    }

    // ---------- unread / read state ----------

    @Transactional
    public void markRead(Long channelId) {
        User me = currentUser();
        ChatChannel channel = requireChannel(channelId);
        ChatChannelMember membership = requireMembership(channel, me);
        Long latest = messageRepository.findLatestIdByChannel(channel).orElse(null);
        if (latest != null && (membership.getLastReadMessageId() == null || membership.getLastReadMessageId() < latest)) {
            membership.setLastReadMessageId(latest);
            memberRepository.save(membership);
        }
    }

    /** Per-channel unread counts plus the total; DMs are surfaced by partner id. */
    @Transactional(readOnly = true)
    public ChatUnreadResponse unread() {
        User me = currentUser();
        List<ChatChannelMember> memberships = memberRepository.findWithChannelByUser(me);
        long total = 0;
        List<ChatUnreadResponse.ChannelUnread> channels = new ArrayList<>();
        for (ChatChannelMember membership : memberships) {
            ChatChannel channel = membership.getChannel();
            Long lastRead = membership.getLastReadMessageId();
            long count = lastRead == null
                ? messageRepository.countByChannel(channel)
                : messageRepository.countByChannelAndIdGreaterThan(channel, lastRead);
            if (count > 0) {
                total += count;
                if (channel.getType() == ChatChannel.Type.DM) {
                    User partner = dmPartner(channel, me);
                    channels.add(new ChatUnreadResponse.ChannelUnread(
                        channel.getId(), channel.getType().name(), partner != null ? partner.getId() : null, count));
                } else {
                    channels.add(new ChatUnreadResponse.ChannelUnread(
                        channel.getId(), channel.getType().name(), null, count));
                }
            }
        }
        long mentions = notificationRepository.countByRecipientAndCategoryAndReadFalse(me, Notification.Category.MENTIONS);
        return new ChatUnreadResponse(total + mentions, channels);
    }

    // ---------- search ----------

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> search(String term) {
        User me = currentUser();
        String query = term == null ? "" : term.trim();
        if (query.length() < 2) return List.of();
        List<ChatChannelMember> memberships = memberRepository.findWithChannelByUser(me);
        if (memberships.isEmpty()) return List.of();
        List<ChatChannel> channels = memberships.stream().map(ChatChannelMember::getChannel).toList();
        Pageable page = PageRequest.of(0, SEARCH_LIMIT);
        return messageRepository.searchInChannels(channels, query, page).stream()
            .map(m -> ChatMessageResponse.of(m, parseReactions(m)))
            .toList();
    }

    // ---------- helpers ----------

    /**
     * Ensures the default channels (#general/#random/#dev) exist and that the
     * caller is a member of each — the first user to open chat creates them
     * and admins them, everyone else is auto-joined, Slack-style.
     */
    private void ensureSeedChannels(User joiner) {
        for (String name : SEED_CHANNELS) {
            boolean[] createdNow = { false };
            ChatChannel channel = channelRepository.findByNameAndType(name, ChatChannel.Type.PUBLIC)
                .orElseGet(() -> {
                    createdNow[0] = true;
                    ChatChannel c = new ChatChannel();
                    c.setName(name);
                    c.setType(ChatChannel.Type.PUBLIC);
                    c.setCreatedBy("system");
                    c.setTopic(name.equals("general") ? "Company-wide announcements and work-based matters" : null);
                    ChatChannel saved = channelRepository.save(c);
                    broadcaster.broadcast(event(ChatEvent.CHANNEL_CREATED, saved, null, null, null));
                    return saved;
                });
            if (!memberRepository.existsByChannelAndUser(channel, joiner)) {
                joinChannel(channel, joiner, createdNow[0]);
            }
        }
    }

    private void joinChannel(ChatChannel channel, User user, boolean admin) {
        ChatChannelMember membership = new ChatChannelMember();
        membership.setChannel(channel);
        membership.setUser(user);
        membership.setAdmin(admin);
        memberRepository.save(membership);
    }

    private ChatChannel findDmWith(User me, Long partnerId) {
        for (ChatChannelMember membership : memberRepository.findWithChannelByUser(me)) {
            ChatChannel channel = membership.getChannel();
            if (channel.getType() != ChatChannel.Type.DM) continue;
            for (ChatChannelMember other : memberRepository.findByChannelAndUserNot(channel, me)) {
                if (other.getUser().getId().equals(partnerId)) {
                    return channel;
                }
            }
        }
        return null;
    }

    private void notifyMentions(ChatChannel channel, User author, ChatMessage message) {
        Set<Long> mentioned = mentionedUserIds(message.getBody());
        if (mentioned.isEmpty()) return;
        List<ChatChannelMember> members = memberRepository.findByChannel(channel);
        for (ChatChannelMember member : members) {
            User candidate = member.getUser();
            if (candidate.getId().equals(author.getId())) continue;
            if (!mentioned.contains(candidate.getId())) continue;
            Notification n = new Notification();
            n.setRecipient(candidate);
            n.setCategory(Notification.Category.MENTIONS);
            n.setText(trim("You were mentioned by " + author.getName() + " in " + channelLabel(channel)
                + ": " + message.getBody(), 500));
            n.setLink("/slack?channel=" + channel.getId() + "&message=" + message.getId());
            notificationRepository.save(n);
        }
    }

    /** Users whose @username appears as a whole word in the body. */
    private Set<Long> mentionedUserIds(String body) {
        Set<Long> result = new LinkedHashSet<>();
        String[] tokens = body.toLowerCase().split("[^a-z0-9._@-]+");
        Set<String> names = new LinkedHashSet<>();
        for (String token : tokens) {
            if (token.startsWith("@") && token.length() > 1) names.add(token.substring(1));
        }
        if (names.isEmpty()) return result;
        for (User user : userRepository.findAll()) {
            String username = user.getEmail().contains("@")
                ? user.getEmail().substring(0, user.getEmail().indexOf('@')).toLowerCase()
                : null;
            if (username != null && names.contains(username)) {
                result.add(user.getId());
            }
        }
        return result;
    }

    private Map<String, List<Long>> parseReactions(ChatMessage message) {
        try {
            // TypeReference (not a MapType) keeps the List<Long> element type:
            // with a raw List Jackson yields Integers and membership checks
            // against Long ids silently fail.
            return objectMapper.readValue(
                message.getReactions() == null ? "{}" : message.getReactions(),
                new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, List<Long>>>() {});
        } catch (Exception e) {
            log.warn("Unparseable reactions on message {}: {}", message.getId(), e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private String writeReactions(Map<String, List<Long>> reactions) {
        try {
            return objectMapper.writeValueAsString(reactions);
        } catch (Exception e) {
            log.warn("Could not serialize reactions: {}", e.getMessage());
            return "{}";
        }
    }

    private List<ChatChannelResponse> toResponses(List<ChatChannelMember> memberships, User me) {
        List<ChatChannelResponse> result = new ArrayList<>(memberships.size());
        for (ChatChannelMember membership : memberships) {
            ChatChannel channel = membership.getChannel();
            if (channel.getType() == ChatChannel.Type.DM) {
                User partner = dmPartner(channel, me);
                result.add(ChatChannelResponse.of(channel, true,
                    partner != null ? partner.getId() : null,
                    partner != null ? partner.getName() : "Direct message"));
            } else {
                result.add(ChatChannelResponse.of(channel, true, null, null));
            }
        }
        return result;
    }

    private User dmPartner(ChatChannel channel, User me) {
        List<ChatChannelMember> others = memberRepository.findByChannelAndUserNot(channel, me);
        return others.isEmpty() ? null : others.get(0).getUser();
    }

    private ChatChannelResponse dmResponse(ChatChannel channel, User me, User partner) {
        return ChatChannelResponse.of(channel, true, partner.getId(), partner.getName());
    }

    private ChatChannel requireChannel(Long channelId) {
        return channelRepository.findById(channelId)
            .orElseThrow(() -> new ResourceNotFoundException("Channel", "id", channelId));
    }

    private ChatChannelMember requireMembership(ChatChannel channel, User user) {
        return memberRepository.findByChannelAndUser(channel, user)
            .orElseThrow(() -> new ResourceNotFoundException("You are not a member of this conversation"));
    }

    private String channelLabel(ChatChannel channel) {
        return channel.getType() == ChatChannel.Type.DM ? "a direct message" : "#" + channel.getName();
    }

    private static String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private ChatEvent event(String type, ChatChannel channel, ChatMessageResponse message,
                            List<ChatEvent.ReactionEvent> reactions, String channelName) {
        return new ChatEvent(type, channel != null ? channel.getId() : null, message, reactions, null, null,
            channelName != null ? channelName : (channel != null && channel.getName() != null ? channel.getName() : null),
            null);
    }

    private User currentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
            .orElseThrow(() -> new ResourceNotFoundException("User", "email", email));
    }
}
