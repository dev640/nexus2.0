package com.nexus.backend.service;

import com.nexus.backend.domain.message.Message;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.dto.MessageRequest;
import com.nexus.backend.exception.ResourceNotFoundException;
import com.nexus.backend.exception.ValidationException;
import com.nexus.backend.repository.MessageRepository;
import com.nexus.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Internal mail is authorized per message, not per role: the sender and the
 * recipient each own one side of it, and nobody else can see it at all. The
 * last test is the important one — a third party must not be able to tell an
 * existing message id from a missing one.
 */
@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock private MessageRepository messageRepository;
    @Mock private UserRepository userRepository;

    private MessageService messageService;

    private User alice;
    private User bob;
    private User carol;

    @BeforeEach
    void setUp() {
        messageService = new MessageService(messageRepository, userRepository);
        alice = user(1L, "alice@nexus.com");
        bob = user(2L, "bob@nexus.com");
        carol = user(3L, "carol@nexus.com");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void sendStoresTheMessageFromTheSignedInUser() {
        authenticate(alice.getEmail());
        signedInAs(alice);
        when(userRepository.findById(bob.getId())).thenReturn(Optional.of(bob));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
            Message saved = invocation.getArgument(0);
            saved.setId(9L);
            return saved;
        });

        var response = messageService.send(new MessageRequest(bob.getId(), "  Standup  ", "  Moving it to 10.  "));

        assertThat(response.id()).isEqualTo(9L);
        assertThat(response.senderName()).isEqualTo("Alice");
        assertThat(response.recipientName()).isEqualTo("Bob");
        assertThat(response.subject()).isEqualTo("Standup");
        assertThat(response.body()).isEqualTo("Moving it to 10.");
        assertThat(response.read()).isFalse();
    }

    @Test
    void sendToAnUnknownRecipientIsNotFound() {
        authenticate(alice.getEmail());
        signedInAs(alice);
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> messageService.send(new MessageRequest(99L, "Hi", "Anyone there?")))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void sendToYourselfIsRejected() {
        authenticate(alice.getEmail());
        signedInAs(alice);
        when(userRepository.findById(alice.getId())).thenReturn(Optional.of(alice));

        assertThatThrownBy(() -> messageService.send(new MessageRequest(alice.getId(), "Note to self", "Remember the milk")))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("yourself");
        verify(messageRepository, never()).save(any(Message.class));
    }

    @Test
    void inboxReturnsReceivedMail() {
        authenticate(bob.getEmail());
        signedInAs(bob);
        when(messageRepository.findByRecipientAndDeletedByRecipientFalseOrderByIdDesc(bob))
            .thenReturn(List.of(message(5L, alice, bob, false)));

        var inbox = messageService.inbox();

        assertThat(inbox).hasSize(1);
        assertThat(inbox.get(0).senderName()).isEqualTo("Alice");
        assertThat(inbox.get(0).id()).isEqualTo(5L);
    }

    @Test
    void sentReturnsWrittenMail() {
        authenticate(alice.getEmail());
        signedInAs(alice);
        when(messageRepository.findBySenderAndDeletedBySenderFalseOrderByIdDesc(alice))
            .thenReturn(List.of(message(5L, alice, bob, false)));

        var sent = messageService.sent();

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).recipientName()).isEqualTo("Bob");
    }

    @Test
    void unreadCountComesFromTheRepository() {
        authenticate(bob.getEmail());
        signedInAs(bob);
        when(messageRepository.countByRecipientAndReadFalseAndDeletedByRecipientFalse(bob)).thenReturn(3L);

        assertThat(messageService.countUnread()).isEqualTo(3L);
    }

    @Test
    void recipientMayMarkAMessageRead() {
        authenticate(bob.getEmail());
        Message message = message(5L, alice, bob, false);
        when(messageRepository.findById(5L)).thenReturn(Optional.of(message));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        messageService.markRead(5L);

        assertThat(message.isRead()).isTrue();
        verify(messageRepository).save(message);
    }

    @Test
    void senderMayNotMarkTheirOwnMessageRead() {
        authenticate(alice.getEmail());
        when(messageRepository.findById(5L)).thenReturn(Optional.of(message(5L, alice, bob, false)));

        assertThatThrownBy(() -> messageService.markRead(5L))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void thirdPartyCannotTouchAMessageAtAll() {
        authenticate(carol.getEmail());
        when(messageRepository.findById(5L)).thenReturn(Optional.of(message(5L, alice, bob, false)));

        assertThatThrownBy(() -> messageService.markRead(5L))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> messageService.delete(5L))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(messageRepository, never()).save(any(Message.class));
    }

    @Test
    void eachSideDeletesOnlyItsOwnCopy() {
        Message forBob = message(5L, alice, bob, false);
        authenticate(bob.getEmail());
        when(messageRepository.findById(5L)).thenReturn(Optional.of(forBob));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        messageService.delete(5L);

        assertThat(forBob.isDeletedByRecipient()).isTrue();
        assertThat(forBob.isDeletedBySender()).isFalse();
    }

    @Test
    void mailAddressedToYourselfClearsBothBoxesInOneGo() {
        // Pre-guard rows like this exist: the same person is both parties, so a
        // single delete must clear the inbox and the sent copy together.
        Message toSelf = message(7L, alice, alice, false);
        authenticate(alice.getEmail());
        when(messageRepository.findById(7L)).thenReturn(Optional.of(toSelf));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        messageService.delete(7L);

        assertThat(toSelf.isDeletedBySender()).isTrue();
        assertThat(toSelf.isDeletedByRecipient()).isTrue();
    }

    @Test
    void senderDeletingKeepsTheRecipientsCopy() {
        Message forBob = message(5L, alice, bob, false);
        authenticate(alice.getEmail());
        when(messageRepository.findById(5L)).thenReturn(Optional.of(forBob));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        messageService.delete(5L);

        assertThat(forBob.isDeletedBySender()).isTrue();
        assertThat(forBob.isDeletedByRecipient()).isFalse();
    }

    // ---------- helpers ----------

    private void signedInAs(User user) {
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }

    private static void authenticate(String email) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(email, null, List.of()));
    }

    private static Message message(Long id, User sender, User recipient, boolean read) {
        Message message = new Message(sender, recipient, "Subject", "Body");
        message.setId(id);
        message.setRead(read);
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }

    private static User user(Long id, String email) {
        String name = email.substring(0, email.indexOf('@'));
        name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        User user = new User(name, email, "$bcrypt", UserRole.MEMBER);
        user.setId(id);
        return user;
    }
}
