package com.nexus.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.backend.domain.notification.Notification;
import com.nexus.backend.domain.task.Task;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.dto.NotificationResponse;
import com.nexus.backend.repository.NotificationRepository;
import com.nexus.backend.repository.UserRepository;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Notifications are stored and pushed as one step: a row that never reaches the
 * socket sits unseen, and a push with no row vanishes on reload.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private UserRepository userRepository;
    @Mock private ChatSocketFacade socketFacade;

    @InjectMocks private NotificationService notificationService;

    /** Saved rows come back with an id, the way the database would hand them over. */
    private void rowsGetIds() {
        AtomicLong ids = new AtomicLong(10);
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification saved = invocation.getArgument(0);
            saved.setId(ids.getAndIncrement());
            return saved;
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void savesOneRowPerRecipientAndPushesEachCopy() {
        rowsGetIds();
        User achal = user(2L, "Achal K", "achal@nexus.com");
        User palak = user(4L, "Palak", "palak@nexus.com");

        notificationService.notifyUsers(List.of(achal, palak), Notification.Category.TASKS,
            "Devendra added the task \"Ship it\"", "/board?task=42");

        ArgumentCaptor<Notification> rows = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(rows.capture());
        assertThat(rows.getAllValues()).extracting(row -> row.getRecipient().getEmail())
            .containsExactly("achal@nexus.com", "palak@nexus.com");
        // The link a click needs has to reach the stored row and the live push
        // alike, otherwise the popup lands on the app's front door.
        assertThat(rows.getAllValues()).extracting(Notification::getLink)
            .containsOnly("/board?task=42");

        ArgumentCaptor<NotificationResponse> pushes = ArgumentCaptor.forClass(NotificationResponse.class);
        verify(socketFacade, times(2)).pushNotification(any(), pushes.capture());
        assertThat(pushes.getAllValues()).extracting(NotificationResponse::text)
            .containsOnly("Devendra added the task \"Ship it\"");
        assertThat(pushes.getAllValues()).extracting(NotificationResponse::link)
            .containsOnly("/board?task=42");
    }

    @Test
    void keepsOnlyAppRelativeLinks() {
        rowsGetIds();
        User achal = user(2L, "Achal K", "achal@nexus.com");

        // A popup click is a navigation, so an absolute or protocol-relative
        // value must never survive: it would send the browser off-site.
        for (String hostile : List.of("https://evil.example/steal", "//evil.example/steal",
            "javascript:alert(1)", "")) {
            notificationService.notifyUsers(List.of(achal), Notification.Category.SYSTEM,
                "Devendra updated the wiki page \"Ops\"", hostile);
        }

        ArgumentCaptor<Notification> row = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(4)).save(row.capture());
        assertThat(row.getAllValues()).extracting(Notification::getLink).containsOnlyNulls();
    }

    @Test
    void keepsALinkThatIsJustLongerThanTheColumnOut() {
        rowsGetIds();
        notificationService.notifyUsers(List.of(user(2L, "Achal K", "achal@nexus.com")),
            Notification.Category.SYSTEM, "Devendra updated the wiki page \"Ops\"",
            "/wiki?page=1&padding=" + "x".repeat(400));

        ArgumentCaptor<Notification> row = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(row.capture());
        assertThat(row.getValue().getLink()).isNull();
    }

    @Test
    void cutsOverlongTextInsteadOfStoringItOversized() {
        rowsGetIds();
        notificationService.notifyUsers(List.of(user(2L, "Achal K", "achal@nexus.com")),
            Notification.Category.SYSTEM, "x".repeat(900), null);

        ArgumentCaptor<Notification> row = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(row.capture());
        assertThat(row.getValue().getText()).hasSize(500).endsWith("…");
    }

    @Test
    void assigningToSomebodyElseStoresAndPushesThePersonalCopy() {
        rowsGetIds();
        authenticateAs("devendra@nexus.com");
        User palak = user(4L, "Palak", "palak@nexus.com");

        notificationService.notifyTaskAssigned(task("Ship it", 42L), palak);

        ArgumentCaptor<Notification> row = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(row.capture());
        assertThat(row.getValue().getLink()).isEqualTo("/board?task=42");
        verify(socketFacade).pushNotification(eq(4L), any(NotificationResponse.class));
    }

    @Test
    void anUnsavedTaskStillNotifiesWithoutAPointlessLink() {
        rowsGetIds();
        authenticateAs("devendra@nexus.com");

        notificationService.notifyTaskAssigned(task("Ship it", null), user(4L, "Palak", "palak@nexus.com"));

        ArgumentCaptor<Notification> row = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(row.capture());
        assertThat(row.getValue().getLink()).isNull();
    }

    @Test
    void assigningToYourselfNotifiesNobody() {
        authenticateAs("palak@nexus.com");

        notificationService.notifyTaskAssigned(task("Ship it", 42L), user(4L, "Palak", "palak@nexus.com"));

        verify(notificationRepository, never()).save(any(Notification.class));
        verify(socketFacade, never()).pushNotification(any(), any());
    }

    private static void authenticateAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(email, null, List.of()));
    }

    private static Task task(String title, Long id) {
        Task task = new Task();
        task.setTitle(title);
        task.setId(id);
        return task;
    }

    private static User user(Long id, String name, String email) {
        User user = new User(name, email, "$2a$hash", UserRole.MEMBER);
        user.setId(id);
        return user;
    }
}
