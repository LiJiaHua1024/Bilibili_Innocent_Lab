package Dr1;

import android.content.Context;
import tv.danmaku.bili.update.model.BiliUpgradeInfo;

/** 9.14.0 同签名缓存/回退包装层，不能被选成更新网络入口。 */
public final class a {
    public BiliUpgradeInfo a(Context context) { return new c().a(context); }
}
