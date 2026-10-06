package com.nexus.backend.service;

import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserAvatar;
import com.nexus.backend.exception.ValidationException;
import com.nexus.backend.repository.UserAvatarRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Avatar normalisation rules. The images are generated in-test rather than
 * checked in as fixtures so the assertions can describe intent ("the centre
 * survives the crop") instead of comparing against an opaque blob.
 */
@ExtendWith(MockitoExtension.class)
class AvatarServiceTest {

    @Mock private UserAvatarRepository avatarRepository;

    private AvatarService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new AvatarService(avatarRepository);
        user = new User("Ada", "ada@nexus.com", null, null);
        user.setId(7L);
    }

    /**
     * save() is stubbed per test rather than in setUp: the rejection tests never
     * reach the repository, and strict stubs would rightly call that out.
     */
    private void givenSaveReturnsTheSavedEntity() {
        when(avatarRepository.save(any(UserAvatar.class))).thenAnswer(call -> call.getArgument(0));
    }

    // ---------- rejections ----------

    @Test
    void rejectsEmptyUpload() {
        assertThatThrownBy(() -> service.store(user, new MockMultipartFile("file", new byte[0])))
            .isInstanceOf(ValidationException.class)
            .hasMessage("Avatar file is required");

        assertThatThrownBy(() -> service.store(user, null))
            .isInstanceOf(ValidationException.class)
            .hasMessage("Avatar file is required");

        verify(avatarRepository, never()).save(any());
    }

    @Test
    void rejectsUploadOverTheSizeLimit() {
        byte[] tooBig = new byte[(int) AvatarService.MAX_UPLOAD_BYTES + 1];

        assertThatThrownBy(() -> service.store(user, upload(tooBig, "image/png")))
            .isInstanceOf(ValidationException.class)
            .hasMessage("Avatar must be smaller than 256 KB");

        verify(avatarRepository, never()).save(any());
    }

    /**
     * A text file renamed to .png, and a WebP (which the JDK cannot decode).
     * Neither may be stored, and neither may be laundered through by lying in
     * the declared content type.
     */
    @Test
    void rejectsBytesThatAreNotJpegOrPng() {
        assertThatThrownBy(() -> service.store(user, upload("not an image".getBytes(), "image/png")))
            .isInstanceOf(ValidationException.class)
            .hasMessage("Avatar must be a JPEG or PNG image");

        assertThatThrownBy(() -> service.store(user, upload("not an image".getBytes(), "image/webp")))
            .isInstanceOf(ValidationException.class)
            .hasMessage("Avatar must be a JPEG or PNG image");
    }

    @Test
    void rejectsTruncatedImageRatherThanStoringHalfAnImage() {
        byte[] whole = png(twoToneHorizontal(200, 200));
        byte[] truncated = java.util.Arrays.copyOf(whole, whole.length / 2);

        assertThatThrownBy(() -> service.store(user, upload(truncated, "image/png")))
            .isInstanceOf(ValidationException.class)
            .hasMessage("Avatar must be a JPEG or PNG image");

        verify(avatarRepository, never()).save(any());
    }

    // ---------- normalisation ----------

    /**
     * The centre square is the only part kept, so the stored image must be the
     * red middle band. Scaling the whole 400x200 frame into the square instead
     * — the obvious wrong implementation — would put blue in the corners.
     */
    @Test
    void centreCropsToSquareAndScalesToTwoFiftySix() throws IOException {
        givenSaveReturnsTheSavedEntity();
        service.store(user, upload(png(twoToneHorizontal(400, 200)), "image/png"));

        BufferedImage stored = decode(captured().getBytes());
        assertThat(stored.getWidth()).isEqualTo(256);
        assertThat(stored.getHeight()).isEqualTo(256);
        assertThat(isReddish(stored.getRGB(0, 0))).isTrue();
        assertThat(isReddish(stored.getRGB(255, 0))).isTrue();
        assertThat(isReddish(stored.getRGB(0, 255))).isTrue();
        assertThat(isReddish(stored.getRGB(255, 255))).isTrue();
        // The blue outer thirds were cropped away, so no blue survives anywhere.
        assertThat(isBluish(stored.getRGB(128, 128))).isFalse();
    }

    /**
     * JPEG has no alpha channel, so an unflattened transparent PNG would encode
     * as black. The service fills white first, which is why the corners of a
     * fully transparent upload come back white rather than black.
     */
    @Test
    void transparentPngFlattensToWhiteInsteadOfBlack() throws IOException {
        givenSaveReturnsTheSavedEntity();
        service.store(user, upload(png(new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB)), "image/png"));

        BufferedImage stored = decode(captured().getBytes());
        int rgb = stored.getRGB(128, 128);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        assertThat(r + g + b).isGreaterThan(700);
    }

    @Test
    void storesAsJpegRegardlessOfWhatTheClientDeclared() {
        givenSaveReturnsTheSavedEntity();
        service.store(user, upload(png(twoToneHorizontal(300, 300)), "image/webp"));

        UserAvatar saved = captured();
        assertThat(saved.getContentType()).isEqualTo("image/jpeg");
        // JPEG SOI marker: proves the bytes were re-encoded, not stored as-is.
        assertThat(saved.getBytes()[0]).isEqualTo((byte) 0xFF);
        assertThat(saved.getBytes()[1]).isEqualTo((byte) 0xD8);
    }

    @Test
    void savesAgainstTheOwningUserId() {
        givenSaveReturnsTheSavedEntity();
        service.store(user, upload(png(twoToneHorizontal(300, 300)), "image/png"));

        assertThat(captured().getUserId()).isEqualTo(7L);
    }

    // ---------- read / remove ----------

    @Test
    void readIsEmptyWhenTheUserHasNoAvatar() {
        when(avatarRepository.findByUserId(7L)).thenReturn(Optional.empty());

        assertThat(service.read(7L)).isEmpty();
    }

    @Test
    void readReturnsTheStoredAvatar() {
        UserAvatar existing = new UserAvatar(7L, new byte[] { 1, 2, 3 }, "image/jpeg");
        when(avatarRepository.findByUserId(7L)).thenReturn(Optional.of(existing));

        assertThat(service.read(7L)).contains(existing);
    }

    @Test
    void removeDeletesTheUsersAvatarRow() {
        service.remove(user);

        verify(avatarRepository).deleteById(7L);
    }

    // ---------- helpers ----------

    private UserAvatar captured() {
        ArgumentCaptor<UserAvatar> captor = ArgumentCaptor.forClass(UserAvatar.class);
        verify(avatarRepository).save(captor.capture());
        return captor.getValue();
    }

    private static MockMultipartFile upload(byte[] bytes, String contentType) {
        return new MockMultipartFile("file", "avatar", contentType, bytes);
    }

    private static byte[] png(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new AssertionError("test fixture could not be encoded", e);
        }
    }

    private static BufferedImage decode(byte[] bytes) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(image).as("stored avatar must still be a decodable image").isNotNull();
        return image;
    }

    /** Blue on the outer thirds, red through the middle — see centreCropsToSquare. */
    private static BufferedImage twoToneHorizontal(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        int centreStart = width / 4;
        int centreEnd = width - width / 4;
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, centreStart, height);
        g.setColor(Color.RED);
        g.fillRect(centreStart, 0, centreEnd - centreStart, height);
        g.setColor(Color.BLUE);
        g.fillRect(centreEnd, 0, width - centreEnd, height);
        g.dispose();
        return image;
    }

    private static boolean isReddish(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int b = rgb & 0xFF;
        return r > 150 && r > b + 80;
    }

    private static boolean isBluish(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int b = rgb & 0xFF;
        return b > 150 && b > r + 80;
    }
}