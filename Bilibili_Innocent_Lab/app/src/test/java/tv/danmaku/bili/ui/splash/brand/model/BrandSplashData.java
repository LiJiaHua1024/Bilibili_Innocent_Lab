package tv.danmaku.bili.ui.splash.brand.model;
import java.util.List;
public final class BrandSplashData {
    public final List<BrandSplash> list;
    public BrandSplashData(List<BrandSplash> list){ this.list=list; }
    public List<BrandSplash> getBrandList(){ return list; }
}
