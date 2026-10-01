package endpagefixture;

import java.util.ArrayList;
import java.util.List;

/** 9.12.0+ 同包合并器：详情页卡片 + 结束页卡片 -> 结束页 UI 卡片。 */
public final class d {
    public List<Object> a(List<Object> detail, List<Object> endPage) {
        List<Object> merged = new ArrayList<>(detail);
        merged.addAll(endPage);
        return merged;
    }
}
