package run.halo.app.zhihu;

/** Central limits applied to the Zhihu archive payload contract and to rendering. */
public final class ZhihuArchiveLimits {

    public static final int MAX_ARTICLE_HTML_LENGTH = 2_000_000;

    public static final int MAX_COMMENT_HTML_LENGTH = 20_000;

    public static final int MAX_ROOT_COMMENTS = 200;

    public static final int MAX_REPLIES_PER_ROOT = 500;

    public static final int MAX_ASSETS = 1_000;

    public static final int MAX_TITLE_LENGTH = 500;

    public static final int MAX_AUTHOR_NAME_LENGTH = 200;

    public static final int MAX_URL_LENGTH = 2_048;

    public static final int MAX_POLICY_NAME_LENGTH = 253;

    public static final int MAX_SLUG_LENGTH = 100;

    public static final int RENDER_ROOT_COMMENTS = 10;

    public static final int RENDER_REPLIES_PER_ROOT = 50;

    private ZhihuArchiveLimits() {}
}
