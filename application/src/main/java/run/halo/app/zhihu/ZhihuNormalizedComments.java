package run.halo.app.zhihu;

import java.util.List;

public record ZhihuNormalizedComments(
        long total, List<ZhihuCommentThread> threads, int suppliedRoots, int selectedRoots, int omittedRoots) {

    public boolean truncated() {
        return omittedRoots > 0;
    }

    public record ZhihuCommentThread(ZhihuComment root, List<ZhihuComment> replies, int omittedReplies) {

        public boolean truncated() {
            return omittedReplies > 0;
        }
    }
}
