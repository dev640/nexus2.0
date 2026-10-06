package com.nexus.backend.web;

import com.nexus.backend.config.SecurityConfig;
import com.nexus.backend.config.SupabaseProperties;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserAvatar;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.repository.UserAvatarRepository;
import com.nexus.backend.repository.UserRepository;
import com.nexus.backend.security.JwtAuthenticationFilter;
import com.nexus.backend.security.JwtUtil;
import com.nexus.backend.security.SupabaseTokenVerifier;
import com.nexus.backend.service.AdminAccountService;
import com.nexus.backend.service.AvatarService;
import com.nexus.backend.service.SupabaseUserService;
import com.nexus.backend.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The avatar HTTP surface, exercised with the real AvatarService so the
 * multipart upload, normalisation and byte serving are tested as wired rather
 * than mocked away. Only the repositories are stubs.
 *
 * <p>The stored row is held in a single-slot reference so a test can upload and
 * then download the same image.
 */
@WebMvcTest(controllers = UserController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, AvatarService.class})
class UserAvatarEndpointTest {

    private static final String EMAIL = "ada@nexus.com";
    private static final long ID = 7L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private JwtUtil jwtUtil;
    @MockitoBean private SupabaseTokenVerifier supabaseTokenVerifier;
    @MockitoBean private SupabaseUserService supabaseUserService;
    @MockitoBean private SupabaseProperties supabaseProperties;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private AdminAccountService adminAccountService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private UserAvatarRepository avatarRepository;
    @MockitoBean private UserService userService;

    private final AtomicReference<UserAvatar> stored = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        User ada = new User("Ada", EMAIL, "hash", UserRole.MEMBER);
        ada.setId(ID);
        ada.setEmployeeCode("NX-0007");

        // Lenient: each test touches a different subset of these three stubs.
        lenient().when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(ada));
        lenient().when(userRepository.findAll()).thenReturn(List.of(ada));
        lenient().when(avatarRepository.save(any(UserAvatar.class))).thenAnswer(call -> {
            UserAvatar saved = call.getArgument(0);
            stored.set(saved);
            return saved;
        });
        lenient().when(avatarRepository.findByUserId(ID))
            .thenAnswer(call -> Optional.ofNullable(stored.get()));
    }

    @Test
    void uploadThenDownloadServesTheNormalisedSquare() throws Exception {
        mockMvc.perform(multipart("/api/users/me/avatar")
                .file(pngUpload(400, 200))
                .with(user(EMAIL).roles("MEMBER")))
            .andExpect(status().isNoContent());

        byte[] body = mockMvc.perform(get("/api/users/{id}/avatar", ID).with(user(EMAIL).roles("MEMBER")))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_JPEG))
            // The bytes sit behind an Authorization header, so a shared cache
            // must never be allowed to keep them.
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
            .andExpect(header().exists(HttpHeaders.ETAG))
            .andReturn().getResponse().getContentAsByteArray();

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(body));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(256);
        assertThat(decoded.getHeight()).isEqualTo(256);
    }

    @Test
    void downloadIsNotModifiedWhenTheEtagStillMatches() throws Exception {
        mockMvc.perform(multipart("/api/users/me/avatar")
                .file(pngUpload(300, 300))
                .with(user(EMAIL).roles("MEMBER")))
            .andExpect(status().isNoContent());

        String etag = mockMvc.perform(get("/api/users/{id}/avatar", ID).with(user(EMAIL).roles("MEMBER")))
            .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        assertThat(etag).isNotBlank();
        mockMvc.perform(get("/api/users/{id}/avatar", ID)
                .header(HttpHeaders.IF_NONE_MATCH, etag)
                .with(user(EMAIL).roles("MEMBER")))
            .andExpect(status().isNotModified())
            .andExpect(content().bytes(new byte[0]));
    }

    @Test
    void reuploadChangesTheEtagSoStaleCopiesCannotBeServed() throws Exception {
        mockMvc.perform(multipart("/api/users/me/avatar")
                .file(pngUpload(300, 300))
                .with(user(EMAIL).roles("MEMBER")));
        String first = mockMvc.perform(get("/api/users/{id}/avatar", ID).with(user(EMAIL).roles("MEMBER")))
            .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        mockMvc.perform(multipart("/api/users/me/avatar")
                .file(pngUpload(120, 700))
                .with(user(EMAIL).roles("MEMBER")));
        String second = mockMvc.perform(get("/api/users/{id}/avatar", ID).with(user(EMAIL).roles("MEMBER")))
            .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        assertThat(second).isNotEqualTo(first);
        // And the old validator is no longer honoured.
        mockMvc.perform(get("/api/users/{id}/avatar", ID)
                .header(HttpHeaders.IF_NONE_MATCH, first)
                .with(user(EMAIL).roles("MEMBER")))
            .andExpect(status().isOk());
    }

    @Test
    void downloadIsNotFoundWhenTheUserHasNoAvatar() throws Exception {
        mockMvc.perform(get("/api/users/{id}/avatar", ID).with(user(EMAIL).roles("MEMBER")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message", containsString("Avatar not found")));
    }

    @Test
    void deleteRemovesTheAvatar() throws Exception {
        mockMvc.perform(delete("/api/users/me/avatar").with(user(EMAIL).roles("MEMBER")))
            .andExpect(status().isNoContent());

        verify(avatarRepository).deleteById(ID);
    }

    @Test
    void uploadRejectsBytesThatAreNotAnImage() throws Exception {
        MockMultipartFile notAnImage =
            new MockMultipartFile("file", "avatar.png", "image/png", "hello".getBytes());

        mockMvc.perform(multipart("/api/users/me/avatar")
                .file(notAnImage)
                .with(user(EMAIL).roles("MEMBER")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("JPEG or PNG")));
    }

    /**
     * An avatar is personal profile state, not workspace content, so it stays
     * outside the VIEWER write ban established in phase 1.
     */
    @Test
    void viewerCanStillUploadAndDeleteTheirOwnAvatar() throws Exception {
        mockMvc.perform(multipart("/api/users/me/avatar")
                .file(pngUpload(200, 200))
                .with(user(EMAIL).roles("VIEWER")))
            .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/users/me/avatar").with(user(EMAIL).roles("VIEWER")))
            .andExpect(status().isNoContent());
    }

    @Test
    void avatarUploadRequiresAuthentication() throws Exception {
        // 403 rather than the 401 a JSON API would normally return: SecurityConfig
        // registers no authentication entry point, so Spring Security falls back to
        // its default Http403ForbiddenEntryPoint. Asserted as-is here; that
        // app-wide wart is not introduced by avatars and is not fixed in this phase.
        mockMvc.perform(multipart("/api/users/me/avatar").file(pngUpload(200, 200)))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/users/{id}/avatar", ID))
            .andExpect(status().isForbidden());
    }

    /**
     * The blob lives in its own table precisely so it cannot leak through an
     * ordinary user listing; this pins that down.
     */
    @Test
    void userResponseCarriesTheEmployeeCodeButNeverTheAvatar() throws Exception {
        mockMvc.perform(get("/api/users").with(user(EMAIL).roles("MEMBER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].employeeCode").value("NX-0007"))
            .andExpect(jsonPath("$[0].avatarBytes").doesNotExist())
            .andExpect(jsonPath("$[0].avatarContentType").doesNotExist())
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("avatar"))));
    }

    // ---------- helpers ----------

    private static MockMultipartFile pngUpload(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        // The fill colour varies with the dimensions so two different fixtures
        // normalise to different bytes. A flat black PNG would crop and re-encode
        // to the same 256x256 JPEG either way, making ETag assertions meaningless.
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(new java.awt.Color(width % 256, height % 256, 90));
        g.fillRect(0, 0, width, height);
        g.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            assertThat(ImageIO.write(image, "png", out)).as("test fixture encodes").isTrue();
        } catch (IOException e) {
            throw new AssertionError("test fixture could not be encoded", e);
        }
        return new MockMultipartFile("file", "avatar.png", MediaType.IMAGE_PNG_VALUE, out.toByteArray());
    }
}