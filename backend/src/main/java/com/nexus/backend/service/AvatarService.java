package com.nexus.backend.service;

import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserAvatar;
import com.nexus.backend.exception.ValidationException;
import com.nexus.backend.repository.UserAvatarRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;

/**
 * Avatar storage. Uploads are normalised server-side to a square JPEG so the
 * database only ever holds one shape and one codec, and a user cannot store an
 * arbitrary file type under an image content type.
 *
 * Only JPEG and PNG are accepted on the way in: ImageIO can decode both and can
 * re-encode to JPEG, whereas WebP has no bundled writer in the JDK and a
 * client-supplied WebP would have to be stored as-is or rejected.
 */
@Service
@RequiredArgsConstructor
public class AvatarService {

    /** Rejection ceiling for the upload itself; the stored image is far smaller. */
    public static final long MAX_UPLOAD_BYTES = 256L * 1024L;

    private static final int SIZE = 256;
    private static final String JPEG = "image/jpeg";

    private final UserAvatarRepository avatarRepository;

    /**
     * Stores {@code file} as {@code user}'s avatar, downscaling and centre-cropping
     * to a {@value #SIZE}x{@value #SIZE} JPEG. Replaces any previous avatar.
     *
     * @throws ValidationException when the upload is empty, too large, or not a
     *                             readable JPEG/PNG
     */
    @Transactional
    public UserAvatar store(User user, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("Avatar file is required");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new ValidationException("Avatar must be smaller than 256 KB");
        }

        byte[] source;
        try {
            source = file.getBytes();
        } catch (IOException e) {
            throw new ValidationException("Avatar could not be read");
        }

        BufferedImage decoded;
        try {
            decoded = ImageIO.read(new ByteArrayInputStream(source));
        } catch (IOException e) {
            // Truncated or corrupt. Reported as a format problem because that is
            // what the caller can act on, and it keeps one message per rule.
            throw new ValidationException("Avatar must be a JPEG or PNG image");
        }
        if (decoded == null) {
            // No reader claimed the bytes: rejects WebP, SVG, PDFs and text files
            // renamed to .png without trusting the declared content type.
            throw new ValidationException("Avatar must be a JPEG or PNG image");
        }

        // save() on a detached instance with an existing id merges, so this both
        // inserts the first avatar and overwrites a later one.
        return avatarRepository.save(new UserAvatar(user.getId(), encodeSquareJpeg(decoded), JPEG));
    }

    /** Drops the avatar, if any. */
    @Transactional
    public void remove(User user) {
        avatarRepository.deleteById(user.getId());
    }

    /**
     * The user's stored avatar, or empty when they have none. Empty is not an
     * error: the caller decides whether a missing avatar is a 404.
     */
    @Transactional(readOnly = true)
    public Optional<UserAvatar> read(Long userId) {
        return avatarRepository.findByUserId(userId);
    }

    /**
     * Centre-crops to a square and scales to {@value #SIZE}x{@value #SIZE},
     * encoding the result as JPEG at quality 0.85.
     */
    private byte[] encodeSquareJpeg(BufferedImage source) {
        int side = Math.min(source.getWidth(), source.getHeight());
        int x = (source.getWidth() - side) / 2;
        int y = (source.getHeight() - side) / 2;

        BufferedImage square = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = square.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            // JPEG has no alpha: fill first so transparent PNGs do not turn black.
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, SIZE, SIZE);
            graphics.drawImage(source, 0, 0, SIZE, SIZE, x, y, x + side, y + side, null);
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(square, "jpg", out)) {
                throw new ValidationException("Avatar could not be encoded");
            }
        } catch (IOException e) {
            throw new ValidationException("Avatar could not be encoded");
        }
        return out.toByteArray();
    }
}