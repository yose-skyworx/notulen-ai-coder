package com.ilkeiapps.skyworx.notulenai.utility;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

/**
 * Resizes an image to a fixed width/height, used when storing the authenticated user's avatar and
 * signature.
 * <p>
 * Runs under Spring Boot's default {@code java.awt.headless=true}: {@link BufferedImage} and
 * {@link ImageIO} are supported headless, only windowing/display APIs are not.
 * <p>
 * {@link BufferedImage#TYPE_CUSTOM} is {@code 0}, and the {@link BufferedImage} constructor rejects
 * it with {@link IllegalArgumentException}. The built-in JPEG/PNG/GIF/BMP readers on this JDK do not
 * return {@code TYPE_CUSTOM}, but {@link ImageIO} plugins can, so {@link #outputTypeFor} maps it to
 * {@code TYPE_INT_ARGB} (which preserves alpha) instead.
 */
public final class ImageResizer {

	private ImageResizer() {
	}

	/**
	 * Resizes the image at {@code inputImagePath} to exactly {@code scaledWidth} x
	 * {@code scaledHeight} (not necessarily proportional) and writes it to {@code outputImagePath}.
	 */
	public static void resize(String inputImagePath, String outputImagePath, int scaledWidth, int scaledHeight)
			throws IOException {
		BufferedImage inputImage = ImageIO.read(new File(inputImagePath));
		if (inputImage == null) {
			throw new IOException("Unsupported or unreadable image: " + inputImagePath);
		}

		BufferedImage outputImage = new BufferedImage(scaledWidth, scaledHeight, outputTypeFor(inputImage));

		Graphics2D g2d = outputImage.createGraphics();
		g2d.drawImage(inputImage, 0, 0, scaledWidth, scaledHeight, null);
		g2d.dispose();

		String formatName = outputImagePath.substring(outputImagePath.lastIndexOf(".") + 1);
		ImageIO.write(outputImage, formatName, new File(outputImagePath));
	}

	/**
	 * The image type to allocate the resized image with. {@code TYPE_CUSTOM} is not a usable
	 * constructor argument, so it falls back to {@code TYPE_INT_ARGB}.
	 */
	static int outputTypeFor(BufferedImage inputImage) {
		return inputImage.getType() == BufferedImage.TYPE_CUSTOM
				? BufferedImage.TYPE_INT_ARGB
				: inputImage.getType();
	}
}
