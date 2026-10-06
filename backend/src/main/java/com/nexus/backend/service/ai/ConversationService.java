package com.nexus.backend.service.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.backend.domain.ai.AiConversation;
import com.nexus.backend.domain.ai.AiMessage;
import com.nexus.backend.domain.ai.AiRole;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.dto.AiConversationDetailResponse;
import com.nexus.backend.dto.AiConversationResponse;
import com.nexus.backend.dto.AiMessageResponse;
import com.nexus.backend.dto.AiSourceResponse;
import com.nexus.backend.exception.ResourceNotFoundException;
import com.nexus.backend.exception.ValidationException;
import com.nexus.backend.repository.AiConversationRepository;
import com.nexus.backend.repository.AiMessageRepository;
import com.nexus.backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Conversation service: threads are strictly per-user (ownership is checked on
 * every read and write), messages persist with their citations, and
 * regeneration drops stale assistant replies before re-answering.
 */
@Service
public class ConversationService {

    /** Question + prior history for a regeneration run. */
    public record RegeneratePlan(String question, List<AiMessage> history) {}

    private static final TypeReference<List<AiSourceResponse>> SOURCES_TYPE = new TypeReference<>() {};

    private final AiConversationRepository conversationRepository;
    private final AiMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    public ConversationService(
            AiConversationRepository conversationRepository,
            AiMessageRepository messageRepository,
            UserRepository userRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<AiConversationResponse> list(User user) {
        List<AiConversationResponse> out = new ArrayList<>();
        for (AiConversation conversation : conversationRepository.findByUser_IdOrderByUpdatedAtDesc(user.getId())) {
            out.add(toConversation(conversation));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public AiConversationDetailResponse detail(UUID id, User user) {
        AiConversation conversation = requireOwned(id, user);
        List<AiMessageResponse> messages = messageRepository
            .findByConversation_IdOrderByCreatedAtAsc(id).stream()
            .map(this::toMessage)
            .toList();
        return new AiConversationDetailResponse(toConversation(conversation), messages);
    }

    @Transactional
    public AiConversation create(User user, String title) {
        AiConversation conversation = new AiConversation();
        conversation.setUser(userRepository.getReferenceById(user.getId()));
        conversation.setTitle(titleFromQuestion(title));
        return conversationRepository.save(conversation);
    }

    @Transactional
    public void delete(UUID id, User user) {
        AiConversation conversation = requireOwned(id, user);
        conversationRepository.delete(conversation); // messages follow via FK cascade
    }

    /** Everything in the conversation before the next question is asked. */
    @Transactional(readOnly = true)
    public List<AiMessage> history(UUID conversationId, User user) {
        requireOwned(conversationId, user);
        return messageRepository.findByConversation_IdOrderByCreatedAtAsc(conversationId);
    }

    @Transactional
    public AiMessage saveUserMessage(UUID conversationId, User user, String question) {
        AiConversation conversation = requireOwned(conversationId, user);
        AiMessage message = new AiMessage();
        message.setConversation(conversation);
        message.setRole(AiRole.USER);
        message.setContent(question);
        touch(conversation);
        return messageRepository.save(message);
    }

    @Transactional
    public AiMessage saveAssistantMessage(
            UUID conversationId,
            String content,
            List<AiSourceResponse> sources,
            String mode,
            String model) {
        AiConversation conversation = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", conversationId));
        AiMessage message = new AiMessage();
        message.setConversation(conversation);
        message.setRole(AiRole.ASSISTANT);
        message.setContent(content);
        message.setSources(toJson(sources));
        message.setMode(mode);
        message.setModel(model);
        touch(conversation);
        return messageRepository.save(message);
    }

    /** Removes stale assistant replies and returns the question to re-ask. */
    @Transactional
    public RegeneratePlan prepareRegenerate(UUID conversationId, User user) {
        AiConversation conversation = requireOwned(conversationId, user);
        List<AiMessage> messages = messageRepository.findByConversation_IdOrderByCreatedAtAsc(conversationId);

        int lastUser = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == AiRole.USER) {
                lastUser = i;
                break;
            }
        }
        if (lastUser < 0) {
            throw new ValidationException("There is no question to regenerate in this conversation");
        }

        for (int i = messages.size() - 1; i > lastUser; i--) {
            if (messages.get(i).getRole() != AiRole.ASSISTANT) break;
            messageRepository.delete(messages.get(i));
        }

        String question = messages.get(lastUser).getContent();
        List<AiMessage> history = List.copyOf(messages.subList(0, lastUser));
        touch(conversation);
        return new RegeneratePlan(question, history);
    }

    // ---------- helpers ----------

    private AiConversation requireOwned(UUID id, User user) {
        return conversationRepository.findByIdAndUser_Id(id, user.getId())
            .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", id));
    }

    private void touch(AiConversation conversation) {
        conversation.setUpdatedAt(java.time.LocalDateTime.now());
    }

    private AiConversationResponse toConversation(AiConversation conversation) {
        return new AiConversationResponse(
            conversation.getId(),
            conversation.getTitle(),
            conversation.getCreatedAt(),
            conversation.getUpdatedAt(),
            messageRepository.countByConversation_Id(conversation.getId()));
    }

    private AiMessageResponse toMessage(AiMessage message) {
        return new AiMessageResponse(
            message.getId(),
            message.getRole().name(),
            message.getContent(),
            parseSources(message.getSources()),
            message.getMode(),
            message.getModel(),
            message.getCreatedAt());
    }

    private List<AiSourceResponse> parseSources(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, SOURCES_TYPE);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(List<AiSourceResponse> sources) {
        if (sources == null || sources.isEmpty()) return null;
        try {
            return mapper.writeValueAsString(sources);
        } catch (Exception e) {
            return null;
        }
    }

    static String titleFromQuestion(String question) {
        if (question == null || question.isBlank()) return "New conversation";
        String cleaned = question.trim().replaceAll("\\s+", " ");
        if (cleaned.length() <= 80) return cleaned;
        int cut = cleaned.lastIndexOf(' ', 80);
        return cleaned.substring(0, cut > 20 ? cut : 80) + "…";
    }
}
