package com.nexus.backend.service;

import com.nexus.backend.chat.ChatBroadcaster;
import com.nexus.backend.domain.chat.ChatChannel;
import com.nexus.backend.domain.chat.ChatChannelMember;
import com.nexus.backend.domain.chat.ChatMessage;
import com.nexus.backend.domain.notification.Notification;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.dto.ChatChannelRequest;
import com.nexus.backend.dto.ChatMessageResponse;
import com.nexus.backend.dto.ChatUnreadResponse;
import com.nexus.backend.exception.ValidationException;
import com.nexus.backend.repository.ChatChannelMemberRepository;
import com.nexus.backend.repository.ChatChannelRepository;
import com.nexus.backend.repository.ChatMessageRepository;
import com.nexus.backend.repository.NotificationRepository;
import com.nexus.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    private static final String MY_EMAIL = "devendra@nexus.com";

    @Mock private ChatChannelRepository channelRepository;
    @Mock private ChatChannelMemberRepository memberRepository;
    @Mock private ChatMessageRepository messageRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private UserRepository userRepository;
    @Mock private ChatBroadcaster broadcaster;

    private ChatService chatService;

    private User me;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(
            channelRepository, memberRepository, messageRepository,
            notificationRepository, userRepository, broadcaster);

        me = new User("Devendra", MY_EMAIL, "$bcrypt", UserRole());
        me.setId(1L);

        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(MY_EMAIL, null, List.of()));
        when(userRepository.findByEmail(MY_EMAIL)).thenReturn(Optional.of(me));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static com.nexus.backend.domain.user.UserRole UserRole() {
        return com.nexus.backend.domain.user.UserRole.ADMIN;
    }

    private ChatChannel channel(long id, String name, ChatChannel.Type type) {
        ChatChannel c = new ChatChannel();
        c.setId(id);
        c.setName(name);
        c.setType(type);
        return c;
    }

    private ChatChannelMember membership(ChatChannel channel, User user, Long lastRead) {
        ChatChannelMember m = new ChatChannelMember();
        m.setId(100L);
        m.setChannel(channel);
        m.setUser(user);
        m.setLastReadMessageId(lastRead);
        return m;
    }

    private ChatMessage message(long id, ChatChannel channel, User author, String body, String reactions) {
        ChatMessage m = new ChatMessage();
        m.setId(id);
        m.setChannel(channel);
        m.setAuthor(author);
        m.setBody(body);
        m.setReactions(reactions);
        return m;
    }

    // ---------- posting ----------

    @Test
    void postingAMessageSavesItAndMarksTheChannelReadForTheAuthor() {
        ChatChannel general = channel(10L, "general", ChatChannel.Type.PUBLIC);
        ChatChannelMember mine = membership(general, me, null);
        when(channelRepository.findById(10L)).thenReturn(Optional.of(general));
        when(memberRepository.findByChannelAndUser(general, me)).thenReturn(Optional.of(mine));
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            m.setId(55L);
            return m;
        });

        ChatMessageResponse response = chatService.postMessage(10L, "  hello world  ");

        assertThat(response.body()).isEqualTo("hello world");
        assertThat(response.authorId()).isEqualTo(1L);
        assertThat(mine.getLastReadMessageId()).isEqualTo(55L); // author has read their own message
    }

    @Test
    void postingToAChannelTheUserIsNotAMemberOfIsRejected() {
        ChatChannel secret = channel(11L, "secret", ChatChannel.Type.PRIVATE);
        when(channelRepository.findById(11L)).thenReturn(Optional.of(secret));
        when(memberRepository.findByChannelAndUser(secret, me)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.postMessage(11L, "hi"))
            .isInstanceOf(com.nexus.backend.exception.ResourceNotFoundException.class);
        verify(messageRepository, never()).save(any());
    }

    @Test
    void anEmptyMessageIsRejected() {
        assertThatThrownBy(() -> chatService.postMessage(10L, "   "))
            .isInstanceOf(ValidationException.class);
    }

    // ---------- reading history ----------

    @Test
    void messageHistoryIsReturnedOldestFirstAndSupportsPagingBeforeAnId() {
        ChatChannel general = channel(10L, "general", ChatChannel.Type.PUBLIC);
        when(channelRepository.findById(10L)).thenReturn(Optional.of(general));
        when(memberRepository.findByChannelAndUser(general, me)).thenReturn(Optional.of(membership(general, me, null)));
        when(messageRepository.findByChannelAndIdLessThanOrderByIdDesc(eq(general), eq(9L), any(Pageable.class)))
            .thenReturn(List.of(
                message(8L, general, me, "older", "{}"),
                message(7L, general, me, "oldest", "{}")));

        List<ChatMessageResponse> page = chatService.messages(10L, 9L, 50);

        assertThat(page).extracting(ChatMessageResponse::id).containsExactly(7L, 8L);
    }

    // ---------- reactions ----------

    @Test
    void addingARactionTwiceIsIdempotent() {
        ChatChannel general = channel(10L, "general", ChatChannel.Type.PUBLIC);
        ChatMessage msg = message(30L, general, me, "nice", "{}");
        when(messageRepository.findWithAuthorById(30L)).thenReturn(Optional.of(msg));
        when(memberRepository.findByChannelAndUser(general, me))
            .thenReturn(Optional.of(membership(general, me, null)));
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        ChatMessageResponse first = chatService.react(30L, "👍", true);
        assertThat(first.reactions()).containsOnlyKeys("👍").containsValue(List.of(1L));

        // second add: no change — returned as-is without another save
        ChatMessageResponse second = chatService.react(30L, "👍", true);
        assertThat(second.reactions()).containsOnlyKeys("👍").containsValue(List.of(1L));
        verify(messageRepository, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    void removingTheLastReactionEmptiesTheMap() {
        ChatChannel general = channel(10L, "general", ChatChannel.Type.PUBLIC);
        ChatMessage msg = message(31L, general, me, "nice", "{\"🎉\":[1]}");
        when(messageRepository.findWithAuthorById(31L)).thenReturn(Optional.of(msg));
        when(memberRepository.findByChannelAndUser(general, me))
            .thenReturn(Optional.of(membership(general, me, null)));
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        ChatMessageResponse response = chatService.react(31L, "🎉", false);

        assertThat(response.reactions()).isEmpty();
        assertThat(msg.getReactions()).isEqualTo("{}");
    }

    // ---------- unread ----------

    @Test
    void unreadCountsMessagesAfterTheLastReadPositionPlusMentions() {
        ChatChannel general = channel(10L, "general", ChatChannel.Type.PUBLIC);
        when(memberRepository.findWithChannelByUser(me))
            .thenReturn(List.of(membership(general, me, 4L)));
        when(messageRepository.countByChannelAndIdGreaterThan(general, 4L)).thenReturn(3L);
        when(notificationRepository.countByRecipientAndCategoryAndReadFalse(me, Notification.Category.MENTIONS))
            .thenReturn(2L);

        ChatUnreadResponse unread = chatService.unread();

        assertThat(unread.total()).isEqualTo(5L);
        assertThat(unread.channels()).hasSize(1);
        assertThat(unread.channels().get(0).channelId()).isEqualTo(10L);
        assertThat(unread.channels().get(0).count()).isEqualTo(3L);
    }

    @Test
    void markReadAdvancesToTheLatestMessage() {
        ChatChannel general = channel(10L, "general", ChatChannel.Type.PUBLIC);
        ChatChannelMember mine = membership(general, me, 4L);
        when(channelRepository.findById(10L)).thenReturn(Optional.of(general));
        when(memberRepository.findByChannelAndUser(general, me)).thenReturn(Optional.of(mine));
        when(messageRepository.findLatestIdByChannel(general)).thenReturn(Optional.of(42L));

        chatService.markRead(10L);

        assertThat(mine.getLastReadMessageId()).isEqualTo(42L);
        verify(memberRepository).save(mine);
    }

    // ---------- channels ----------

    @Test
    void creatingAChannelNormalizesTheNameAndRejectsBadOnes() {
        when(channelRepository.findByNameAndType("general", ChatChannel.Type.PUBLIC)).thenReturn(Optional.empty());
        when(channelRepository.save(any(ChatChannel.class))).thenAnswer(inv -> {
            ChatChannel c = inv.getArgument(0);
            c.setId(77L);
            return c;
        });

        var created = chatService.createChannel(new ChatChannelRequest("#General", "public", null, null));
        assertThat(created.name()).isEqualTo("general");

        assertThatThrownBy(() ->
            chatService.createChannel(new ChatChannelRequest("has space", "public", null, null)))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    void creatingADuplicateChannelIsRejected() {
        ChatChannel existing = channel(10L, "general", ChatChannel.Type.PUBLIC);
        when(channelRepository.findByNameAndType("general", ChatChannel.Type.PUBLIC))
            .thenReturn(Optional.of(existing));

        assertThatThrownBy(() ->
            chatService.createChannel(new ChatChannelRequest("general", "public", null, null)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    void openingADMReusesTheExistingConversationWithThatPerson() {
        User partner = new User("Achal", "achal@nexus.com", "$bcrypt", UserRole());
        partner.setId(2L);
        ChatChannel dm = channel(90L, null, ChatChannel.Type.DM);
        when(userRepository.findById(2L)).thenReturn(Optional.of(partner));
        when(memberRepository.findWithChannelByUser(me))
            .thenReturn(List.of(membership(dm, me, null)));
        when(memberRepository.findByChannelAndUserNot(dm, me))
            .thenReturn(List.of(membership(dm, partner, null)));

        var response = chatService.openDirectMessage(2L);

        assertThat(response.partnerId()).isEqualTo(2L);
        assertThat(response.partnerName()).isEqualTo("Achal");
        verify(channelRepository, never()).save(any());
    }

    // ---------- mentions ----------

    @Test
    void postingAMessageWithAMentionCreatesANotificationForTheMentionedUser() {
        User partner = new User("Achal", "achal@nexus.com", "$bcrypt", UserRole());
        partner.setId(2L);
        ChatChannel general = channel(10L, "general", ChatChannel.Type.PUBLIC);
        when(channelRepository.findById(10L)).thenReturn(Optional.of(general));
        when(memberRepository.findByChannelAndUser(general, me)).thenReturn(Optional.of(membership(general, me, null)));
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            m.setId(60L);
            return m;
        });
        when(userRepository.findAll()).thenReturn(List.of(me, partner));
        when(memberRepository.findByChannel(general)).thenReturn(List.of(membership(general, partner, null)));

        chatService.postMessage(10L, "ping @achal please review");

        org.mockito.ArgumentCaptor<Notification> captor =
            org.mockito.ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getCategory()).isEqualTo(Notification.Category.MENTIONS);
        assertThat(saved.getRecipient().getId()).isEqualTo(2L);
    }

    // ---------- channel deletion ----------

    /**
     * The children are removed explicitly, in this order, before the channel.
     *
     * <p>This is a regression guard, not a description: the first
     * implementation leaned on the table's ON DELETE CASCADE and returned an
     * opaque 500 on every attempt, including on a freshly created channel. A
     * unit test with mocked repositories cannot see a database constraint, so it
     * cannot prove the cascade exists — but it does pin that we ask for the
     * child deletes explicitly and that we ask before the parent.
     */
    @Test
    void deletingAChannelRemovesItsMessagesAndMembersFirst() {
        ChatChannel general = channel(7L, "general", ChatChannel.Type.PUBLIC);
        when(channelRepository.findById(7L)).thenReturn(Optional.of(general));

        chatService.deleteChannel(7L);

        InOrder inOrder = org.mockito.Mockito.inOrder(messageRepository, memberRepository, channelRepository);
        inOrder.verify(messageRepository).deleteAllInChannel(7L);
        inOrder.verify(memberRepository).deleteAllInChannel(7L);
        inOrder.verify(channelRepository).delete(general);
    }

    @Test
    void anyoneWhoIsNotTheCreatorOrAnAdminCannotDeleteAChannel() {
        me.setRole(com.nexus.backend.domain.user.UserRole.MEMBER);
        ChatChannel other = channel(8L, "general", ChatChannel.Type.PUBLIC);
        other.setCreatedBy("someone-else@nexus.com");
        when(channelRepository.findById(8L)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> chatService.deleteChannel(8L))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("creator or an admin");

        verify(channelRepository, org.mockito.Mockito.never()).delete(any(ChatChannel.class));
    }

    @Test
    void directMessagesCannotBeDeleted() {
        ChatChannel dm = channel(9L, null, ChatChannel.Type.DM);
        dm.setCreatedBy(MY_EMAIL);
        when(channelRepository.findById(9L)).thenReturn(Optional.of(dm));

        assertThatThrownBy(() -> chatService.deleteChannel(9L))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Direct messages");

        verify(messageRepository, org.mockito.Mockito.never()).deleteAllInChannel(anyLong());
    }
}
