package endpagefixture;

import java.util.Collections;
import java.util.List;

/** 9.12.0+ 结束页推荐 service 形状：没有 (List) -> List 合并入口，只有 d() / e(String)。 */
public final class UGCEndPageRelatedRecommendService {
    public List<Object> d() { return Collections.emptyList(); }

    public List<Object> e(String key) { return Collections.emptyList(); }
}
