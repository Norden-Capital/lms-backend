package org.example.backend.dto;

import tools.jackson.databind.json.JsonMapper;
import org.example.backend.exception.ApiException;

import java.util.List;

/**
 * How the app looks for one person — Settings → Theme, added 2026-11-11.
 * Every value comes from a short fixed list, so nothing odd ever reaches the
 * page as a class name or attribute.
 */
public record Appearance(String mode, String palette, String radius, String font, boolean cardFrame) {

    public static final List<String> MODES = List.of("light", "dark");
    // Must match lib/appearance.ts in the frontend.
    public static final List<String> PALETTES = List.of(
            "default", "claude", "caffeine", "darkmatter", "mono", "supabase", "sage-garden",
            "twitter", "sunset-horizon", "modern-minimal", "violet-bloom", "t3-chat", "mocha-mousse",
            "bubblegum", "amethyst-haze", "notebook", "doom-64", "catppuccin", "graphite",
            "perpetuity", "kodama-grove", "cosmic-night", "tangerine", "quantum-rose", "nature",
            "bold-tech", "elegant-luxury", "amber-minimal", "neo-brutalism", "solar-dusk",
            "claymorphism", "cyberpunk", "pastel-dreams", "clean-slate", "ocean-breeze",
            "retro-arcade", "midnight-bloom", "candyland", "northern-lights", "vintage-paper",
            "starry-night", "vercel", "soft-pop");
    public static final List<String> RADII = List.of("none", "small", "medium", "large", "full");
    public static final List<String> FONTS = List.of(
            "geist", "inter", "dm-sans", "ibm-plex-sans", "roboto", "open-sans", "poppins",
            "montserrat", "nunito", "outfit", "figtree", "manrope", "plus-jakarta-sans",
            "space-grotesk");

    /** The standard look: what everyone gets until they choose. */
    public static final Appearance STANDARD = new Appearance("light", "default", "medium", "geist", true);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Refuses anything not on the lists. */
    public Appearance checked() {
        if (!MODES.contains(mode)) throw ApiException.badRequest("Choose light or dark.");
        if (!PALETTES.contains(palette)) throw ApiException.badRequest("There is no such colour theme.");
        if (!RADII.contains(radius)) throw ApiException.badRequest("There is no such corner size.");
        if (!FONTS.contains(font)) throw ApiException.badRequest("There is no such font.");
        return this;
    }

    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** What is saved, or the standard look when nothing is (or it no longer fits the lists). */
    public static Appearance of(String saved) {
        if (saved == null || saved.isBlank()) return STANDARD;
        try {
            Appearance read = JSON.readValue(saved, Appearance.class);
            return new Appearance(
                    MODES.contains(read.mode) ? read.mode : STANDARD.mode,
                    PALETTES.contains(read.palette) ? read.palette : STANDARD.palette,
                    RADII.contains(read.radius) ? read.radius : STANDARD.radius,
                    FONTS.contains(read.font) ? read.font : STANDARD.font,
                    read.cardFrame);
        } catch (Exception e) {
            return STANDARD;
        }
    }
}
