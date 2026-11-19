package org.example.backend.dto;

/** Configuration → Channels. */
public final class ChannelDtos {

    private ChannelDtos() {
    }

    /**
     * One channel. {@code name} is the tag (utm_source) and never changes.
     * {@code label} is the name shown in the LMS, or null (then show the tag).
     * {@code active} false = off: not offered for new links, still counted.
     * {@code seo} true for the SEO row: no link, always on, can't be changed.
     */
    public record Channel(String name, String label, boolean active, boolean seo) {
    }
}
