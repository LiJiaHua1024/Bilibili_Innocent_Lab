package tv.danmaku.bili.ui.splash.brand.config;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
import tv.danmaku.bili.ui.splash.brand.model.BrandSplash;
public final class BrandSplashStorage {
    public static List<BrandSplash> selected=new ArrayList<>();
    public static boolean mode=false;
    public static List<BrandSplash> j(boolean value){return selected;}
    public static void k(List<BrandSplash> items){selected=new ArrayList<>(items);mode=true;}
    public static void l(boolean value){mode=value;if(!value)selected=new ArrayList<>();}
    public static String d(Context context){return "";}
    public static SharedPreferences f(Context context){
        return (SharedPreferences) java.lang.reflect.Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
            new Class[]{SharedPreferences.class},(p,m,a)->{
                if(m.getName().equals("getBoolean"))return mode;
                if(m.getName().equals("getString"))return "";
                return null;
            });
    }
}
