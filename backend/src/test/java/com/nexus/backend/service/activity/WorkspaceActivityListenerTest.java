package com.nexus.backend.service.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.backend.domain.notification.Notification;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.repository.UserRepository;
import com.nexus.backend.service.NotificationService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The fan-out rules for activity notifications: the whole workspace hears about
 * it, the actor never does, explicit exclusions stay excluded, and a targeted
 * activity reaches exactly the named person.
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceActivityListenerTest {

    @Mock private NotificationService notificationService;
    @Mock private UserRepository userRepository;

    @InjectMocks private WorkspaceActivityListener listener;

    private User devendra;
    private User achal;
    private User vidhi;
    private User palak;

    @BeforeEach
    void setUp() {
        devendra = user(1L, "Devendra", "devendra@nexus.com", UserRole.ADMIN);
        achal = user(2L, "Achal K", "achal@nexus.com", UserRole.MEMBER);
        vidhi = user(3L, "Vidhi", "vidhi@nexus.com", UserRole.MEMBER);
        palak = user(4L, "Palak", "palak@nexus.com", UserRole.MEMBER);
        when(userRepository.findAll()).thenReturn(List.of(devendra, achal, vidhi, palak));
    }

    @Test
    void tellsEveryoneExceptTheActorAndNamesTheActorInTheText() {
        listener.onActivity(activity(null, List.of(), "created the project \"Nimbus\"", devendra.getEmail()));

        ArgumentCaptor<List<User>> audience = audienceCaptor();
        verify(notificationService).notifyUsers(audience.capture(), eq(Notification.Category.PROJECTS),
            eq("Devendra created the project \"Nimbus\""), any());
        assertThat(audience.getValue()).extracting(User::getEmail)
            .containsExactly("achal@nexus.com", "vidhi@nexus.com", "palak@nexus.com");
    }

    @Test
    void carriesTheLinkThePublisherAttachedSoTheAlertCanOpenTheThing() {
        listener.onActivity(activity(Notification.Category.TASKS, null, List.of(),
            "added the task \"Ship it\" to \"Nimbus\"", "/board?task=42", devendra.getEmail()));

        verify(notificationService).notifyUsers(any(), eq(Notification.Category.TASKS),
            eq("Devendra added the task \"Ship it\" to \"Nimbus\""), eq("/board?task=42"));
    }

    @Test
    void leavesOutExcludedUsers() {
        listener.onActivity(activity(null, List.of(palak.getId()), "added the task \"X\" to \"Demo\"",
            devendra.getEmail()));

        ArgumentCaptor<List<User>> audience = audienceCaptor();
        verify(notificationService).notifyUsers(audience.capture(), any(), any(), any());
        assertThat(audience.getValue()).extracting(User::getEmail)
            .doesNotContain("palak@nexus.com", "devendra@nexus.com")
            .contains("achal@nexus.com", "vidhi@nexus.com");
    }

    @Test
    void targetedActivityReachesOnlyTheNamedPerson() {
        listener.onActivity(activity(Notification.Category.TASKS, List.of(vidhi.getId()), List.of(),
            "moved \"Fix login\" to DONE", devendra.getEmail()));

        ArgumentCaptor<List<User>> audience = audienceCaptor();
        verify(notificationService).notifyUsers(audience.capture(), eq(Notification.Category.TASKS),
            eq("Devendra moved \"Fix login\" to DONE"), any());
        assertThat(audience.getValue()).extracting(User::getEmail).containsExactly("vidhi@nexus.com");
    }

    @Test
    void neverNotifiesTheActorEvenWhenTheyAreNamed() {
        listener.onActivity(activity(Notification.Category.TASKS, List.of(devendra.getId()), List.of(),
            "moved \"Fix login\" to DONE", devendra.getEmail()));

        verify(notificationService, never()).notifyUsers(any(), any(), any(), any());
    }

    @Test
    void staysQuietWhenOnlyTheActorWouldBeTold() {
        when(userRepository.findAll()).thenReturn(List.of(devendra));

        listener.onActivity(activity(null, List.of(), "updated the wiki page \"Ops\"", devendra.getEmail()));

        verify(notificationService, never()).notifyUsers(any(), any(), any(), any());
    }

    @Test
    void fallsBackToSomeoneWhenTheActorIsUnknown() {
        listener.onActivity(activity(null, List.of(), "updated the project \"Nimbus\"", null));

        verify(notificationService).notifyUsers(any(), eq(Notification.Category.PROJECTS),
            eq("Someone updated the project \"Nimbus\""), any());
    }

    @Test
    void matchesTheActorCaseInsensitively() {
        listener.onActivity(activity(null, List.of(), "created the wiki page \"Ops\"",
            "Devendra@Nexus.com"));

        ArgumentCaptor<List<User>> audience = audienceCaptor();
        verify(notificationService).notifyUsers(audience.capture(), any(),
            eq("Devendra created the wiki page \"Ops\""), any());
        assertThat(audience.getValue()).extracting(User::getEmail).doesNotContain("devendra@nexus.com");
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<User>> audienceCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private static WorkspaceActivity activity(List<Long> recipients, List<Long> excludes,
                                              String actionText, String actorEmail) {
        return activity(Notification.Category.PROJECTS, recipients, excludes, actionText,
            "/projects?project=1", actorEmail);
    }

    private static WorkspaceActivity activity(Notification.Category category, List<Long> recipients,
                                              List<Long> excludes, String actionText, String actorEmail) {
        return activity(category, recipients, excludes, actionText, "/projects?project=1", actorEmail);
    }

    private static WorkspaceActivity activity(Notification.Category category, List<Long> recipients,
                                              List<Long> excludes, String actionText, String link,
                                              String actorEmail) {
        return new WorkspaceActivity(category, actionText, link, actorEmail, recipients, excludes);
    }

    private static User user(Long id, String name, String email, UserRole role) {
        User user = new User(name, email, "$2a$hash", role);
        user.setId(id);
        return user;
    }
}
