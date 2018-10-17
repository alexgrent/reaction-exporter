package org.reactome.server.tools.reaction.exporter.renderer.profile;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parse colors in hex RGB (#FF0000) and rgba(255,255,0, 0.5)
 */
public class ColorFactory {

    private final static Pattern RGBA = Pattern.compile("rgba\\((.*)\\)");
    // speed up with a color cache
    // of course, this shouldn't be necessary if the Profiles already had the
    // colors parsed
    private static final Map<String, Color> cache = new HashMap<>();
    private static final float INV_255 = 0.003921569f; // 1 / 255

    public static Color parseColor(String color) {
        if (color == null || color.trim().isEmpty()) return null;
        return cache.computeIfAbsent(color, ColorFactory::strToColor);
    }

    private static Color strToColor(String color) {
        return color.startsWith("#")
                ? hexToColor(color)
                : rgbaToColor(color);
    }

    private static Color hexToColor(String input) {
        int r = Integer.valueOf(input.substring(1, 3), 16);
        int g = Integer.valueOf(input.substring(3, 5), 16);
        int b = Integer.valueOf(input.substring(5, 7), 16);

        return new Color(r, g, b);
    }

    private static Color rgbaToColor(String input) {
        final Matcher m = RGBA.matcher(input);
        if (!m.matches()) return null;
        final String[] rgba = m.group(1).split(",");
        return new Color(
                Integer.parseInt(rgba[0].trim()),
                Integer.parseInt(rgba[1].trim()),
                Integer.parseInt(rgba[2].trim()),
                (int) (255 * Float.parseFloat(rgba[3].trim())));
    }

    public static Color blend(Color back, Color front) {
        double b = back.getAlpha() / 255.0;
        double f = front.getAlpha() / 255.0;
        double alpha = b * f + (1 - b);
        int red = (int) (back.getRed() * b + front.getRed() * f);
        int green = (int) (back.getGreen() * b + front.getGreen() * f);
        int blue = (int) (back.getBlue() * b + front.getBlue() * f);
        return new Color(
                Math.min(255, red),
                Math.min(255, green),
                Math.min(255, blue),
                Math.min(255, (int) (alpha * 255)));
    }

    private static Color interpolate(Color a, Color b, double value) {
        if (value <= 0.0) return a;
        if (value >= 1.0) return b;
        return new Color(
                (int) (a.getRed() + (b.getRed() - a.getRed()) * value),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * value),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * value),
                (int) (a.getAlpha() + (b.getAlpha() - a.getAlpha()) * value));
    }

    public static String hex(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    @SuppressWarnings("unused")
    public static String rgba(Color color) {
        final float alpha = color.getAlpha() * INV_255;
        String a;
        if (alpha > 0.99) a = "1";
        else a = String.format("%.2f", alpha);
        return String.format(Locale.UK, "rgba(%d,%d,%d,%s)", color.getRed(),
                color.getGreen(), color.getBlue(), a);
    }

    public static String getColorMatrix(Color color) {
        // X * INV_255 = X / 255
        // multiplication is CPU faster than division
//		final String r = String.format(Locale.UK, "%.2f", color.getRed() * INV_255);
//		final String g = String.format(Locale.UK, "%.2f", color.getGreen() * INV_255);
//		final String b = String.format(Locale.UK, "%.2f", color.getBlue() * INV_255);
//		final String a = String.format(Locale.UK, "%.2f", color.getAlpha() * INV_255);
        final float r = color.getRed() * INV_255;
        final float g = color.getGreen() * INV_255;
        final float b = color.getBlue() * INV_255;
        final float a = color.getAlpha() * INV_255;
        // RR RG RB RA R
        // GR GG GB GA G
        // BR BG BB BA B
        // AR AG AB AA A
        // RG means how much input red to put in output green [0-1]
        // In this case we use absolute values for RGB
        // and
        final Float[] floats = new Float[]{
                0f, 0f, 0f, r, 0f,
                0f, 0f, 0f, g, 0f,
                0f, 0f, 0f, b, 0f,
                0f, 0f, 0f, a, 0f};
        final List<String> strings = Arrays.stream(floats)
                .map(String::valueOf)
                .collect(Collectors.toList());
        return String.join(" ", strings);
    }
}
