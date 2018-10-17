package org.reactome.server.tools.reaction.exporter.renderer;

public class RenderArgs {

    private int quality = 5;
    private String profile;

    public int getQuality() {
        return quality;
    }

    public RenderArgs setQuality(int quality) {
        if (quality < 1 || quality > 10)
            throw new IllegalArgumentException("quality must be in the range [1-10]");
        this.quality = quality;
        return this;
    }

    public String getProfile() {
        return profile;
    }

    public RenderArgs setProfile(String profile) {
        this.profile = profile;
        return this;
    }
}