package tv.danmaku.bili.ui.splash.brand.model;
public final class BrandSplash {
    public final long id;
    public final String source;
    public final String hash;
    public BrandSplash(long id, String source) { this(id, source, "hash" + id); }
    public BrandSplash(long id, String source, String hash) { this.id=id;this.source=source;this.hash=hash; }
    public long getId(){ return id; }
    public String getSource(){ return source; }
    public String getThumb(){ return "https://example.invalid/"+hash; }
    public String getThumbHash(){ return hash; }
    public String getMode(){ return "normal"; }
    public boolean getShowLogo(){ return true; }
}
