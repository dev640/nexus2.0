package com.nexus.backend.service;

import com.nexus.backend.domain.message.Message;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.dto.MessageBroadcastRequest;
import com.nexus.backend.dto.MessageRequest;
import com.nexus.backend.dto.MessageResponse;
import com.nexus.backend.exception.ResourceNotFoundException;
import com.nexus.backend.exception.ValidationException;
import com.nexus.backend.repository.MessageRepository;
import com.nexus.backend.repository.UserRepository;
import com.nexus.backend.tenant.CurrentTenant;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Internal mail: writing to a colleague, and managing the two boxes that
 * result.
 *
 * Authorization is per-message rather than by role, because a message belongs
 * to exactly two people. Anything else asks for the row and gets a 404 rather
 * than a 403, which keeps one user's message ids from being probeable by
 * another.
 */
@Service
public class MessageService {

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final CurrentTenant currentTenant;

    public MessageService(MessageRepository messageRepository, UserRepository userRepository,
                          CurrentTenant currentTenant) {
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.currentTenant = currentTenant;
    }

    /** Writes a message from the signed-in user to a colleague in the same company. */
    @Transactional
    public MessageResponse send(MessageRequest request) {
        User sender = currentUser();
        // Scoped to the caller's company: mail is correspondence between
        // colleagues, and an address book that spans companies would let anyone
        // write to, and probe the ids of, people in other workspaces.
        User recipient = userRepository.findByIdAndCompanyId(request.recipientId(), currentTenant.require())
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", request.recipientId()));

        // A message to your own inbox is a mis-click, not correspondence: there is
        // nobody on the other end to read it and no reply to wait for. The compose
        // form already leaves you out of the recipient list; this keeps the rule
        // true for anything that talks to the API directly.
        if (sender.getId() != null && sender.getId().equals(recipient.getId())) {
            throw new ValidationException("You cannot send a message to yourself");
        }

        Message message = new Message(
            sender, recipient, request.subject().trim(), request.body().trim());
        return mapToResponse(messageRepository.save(message));
    }

    /**
     * Writes one letter to everyone in the sender's company except the sender.
     *
     * Each recipient gets their own row, because the copy each of them reads and
     * deletes is theirs alone. The sender, however, wrote one letter, so only the
     * first copy stays visible in Sent — the remaining copies are hidden there
     * with the same per-side flag a manual delete uses, rather than inventing a
     * second kind of row. The returned message is the copy the sender keeps.
     *
     * <p>The audience is the sender's own company, not the whole table. V19
     * pairs every message with the company of both its sender and its recipient,
     * so addressing one company's letter to another company's people would be
     * rejected by the database — and would be wrong even if it were not.
     *
     * @throws ValidationException when the company has nobody else in it
     */
    @Transactional
    public MessageResponse broadcast(MessageBroadcastRequest request) {
        User sender = currentUser();
        Long companyId = sender.getCompanyId();
        if (companyId == null) {
            throw new ValidationException("There is nobody else in the workspace to write to");
        }
        List<User> recipients = userRepository.findByIdNotAndCompanyIdOrderByIdAsc(sender.getId(), companyId);
        if (recipients.isEmpty()) {
            throw new ValidationException("There is nobody else in the workspace to write to");
        }

        String subject = request.subject().trim();
        String body = request.body().trim();

        Message kept = null;
        for (User recipient : recipients) {
            Message message = new Message(sender, recipient, subject, body);
            message.setBroadcast(true);
            if (kept != null) {
                message.setDeletedBySender(true);
            }
            Message saved = messageRepository.save(message);
            if (kept == null) {
                kept = saved;
            }
        }
        return mapToResponse(kept);
    }

    /** Received mail, newest first. */
    @Transactional(readOnly = true)
    public List<MessageResponse> inbox() {
        return messageRepository.findByRecipientAndDeletedByRecipientFalseOrderByIdDesc(currentUser())
            .stream()
            .map(MessageService::mapToResponse)
            .toList();
    }

    /** Sent mail, newest first. */
    @Transactional(readOnly = true)
    public List<MessageResponse> sent() {
        return messageRepository.findBySenderAndDeletedBySenderFalseOrderByIdDesc(currentUser())
            .stream()
            .map(MessageService::mapToResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public long countUnread() {
        return messageRepository.countByRecipientAndReadFalseAndDeletedByRecipientFalse(currentUser());
    }

    /** Only the recipient can mark a message read. */
    @Transactional
    public void markRead(Long id) {
        Message message = messageRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Message", "id", id));
        requireRecipient(message);

        if (!message.isRead()) {
            message.setRead(true);
            messageRepository.save(message);
        }
    }

    /** Removes the message from the caller's own box, leaving the other copy. */
    @Transactional
    public void delete(Long id) {
        Message message = messageRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Message", "id", id));
        String email = currentUserEmail();

        // Both flags, not either/or: a message addressed to yourself (which the
        // workspace no longer accepts, but older rows exist) is the caller's own
        // mail on both sides, and clearing one box must not strand the other —
        // that would leave a row nobody can ever delete.
        boolean isRecipient = message.getRecipient().getEmail().equals(email);
        boolean isSender = message.getSender().getEmail().equals(email);
        if (!isRecipient && !isSender) {
            throw new ResourceNotFoundException("Message", "id", id);
        }
        if (isRecipient) {
            message.setDeletedByRecipient(true);
        }
        if (isSender) {
            message.setDeletedBySender(true);
        }
        messageRepository.save(message);
    }

    private void requireRecipient(Message message) {
        if (!message.getRecipient().getEmail().equals(currentUserEmail())) {
            throw new ResourceNotFoundException("Message", "id", message.getId());
        }
    }

    private User currentUser() {
        String email = currentUserEmail();
        return userRepository.findByEmail(email)
            .orElseThrow(() -> new ResourceNotFoundException("User", "email", email));
    }

    private String currentUserEmail() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }

    private static MessageResponse mapToResponse(Message message) {
        return new MessageResponse(
            message.getId(),
            message.getSender().getId(),
            message.getSender().getName(),
            message.getRecipient().getId(),
            message.getRecipient().getName(),
            message.getSubject(),
            message.getBody(),
            message.isRead(),
            message.isBroadcast(),
            message.getCreatedAt()
        );
    }
}
