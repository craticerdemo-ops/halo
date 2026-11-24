package run.halo.app.zhihu;

/**
 * Fully composed archive document and its derived page identity.
 *
 * @param html rendered snapshot HTML
 * @param title display title for the archive page
 * @param slug URL slug for the archive page
 */
public record RenderedArchive(String html, String title, String slug) {}
