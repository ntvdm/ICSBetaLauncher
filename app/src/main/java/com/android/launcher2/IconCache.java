package com.android.launcher2;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.content.res.AssetManager;
import android.util.Log;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.InputStream;

import java.util.HashMap;

/**
 * Cache of application icons.  Icons can be made from any thread.
 */
public class IconCache {
    private static final String TAG = "Launcher.IconCache";

    private static final int INITIAL_ICON_CACHE_CAPACITY = 50;
    private static final String THEME_PACKAGE = "com.kaanelloed.iconerationiconpack";
    private HashMap<String, String> mThemeMapping = new HashMap<String, String>();

    private static class CacheEntry {
        public Bitmap icon;
        public String title;
    }

    private final Bitmap mDefaultIcon;

    // FIXED: Changed from 'private final LauncherApplication getContext();'
    private final LauncherApplication mContext;

    private final PackageManager mPackageManager;
    private final HashMap<ComponentName, CacheEntry> mCache =
            new HashMap<ComponentName, CacheEntry>(INITIAL_ICON_CACHE_CAPACITY);
    private int mIconDpi;

    public IconCache(LauncherApplication context) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        // ... (keep your existing density logic) ...
        mDefaultIcon = makeDefaultIcon();

        // Trigger the theme load
        loadThemeMapping();
    }

    private void loadThemeMapping() {
        mThemeMapping.clear();
        try {
            Resources res = mPackageManager.getResourcesForApplication(THEME_PACKAGE);
            AssetManager am = res.getAssets();
            InputStream is = am.open("appfilter.xml");
            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(is, "UTF-8");

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.getName().equals("item")) {
                    String component = parser.getAttributeValue(null, "component");
                    String drawable = parser.getAttributeValue(null, "drawable");
                    if (component != null && drawable != null) {
                        mThemeMapping.put(component, drawable);
                    }
                }
                eventType = parser.next();
            }
            is.close();
        } catch (Exception e) {
            Log.w(TAG, "Theme pack not found or failed to parse: " + THEME_PACKAGE);
        }
    }

    // ADDED: A proper getter method so other parts of the code can still call getContext()
    public LauncherApplication getContext() {
        return mContext;
    }

    public Drawable getFullResDefaultActivityIcon() {
        return getFullResIcon(Resources.getSystem(),
                android.R.mipmap.sym_def_app_icon);
    }

    public Drawable getFullResIcon(Resources resources, int iconId) {
        Drawable d;
        try {
            // Internal AOSP used getDrawableForDensity, which is public in newer SDKs
            d = resources.getDrawableForDensity(iconId, mIconDpi);
        } catch (Resources.NotFoundException e) {
            d = null;
        }

        return (d != null) ? d : getFullResDefaultActivityIcon();
    }

    public Drawable getFullResIcon(ResolveInfo info, PackageManager packageManager) {
        Resources resources;
        try {
            resources = packageManager.getResourcesForApplication(
                    info.activityInfo.applicationInfo);
        } catch (PackageManager.NameNotFoundException e) {
            resources = null;
        }
        if (resources != null) {
            int iconId = info.activityInfo.getIconResource();
            if (iconId != 0) {
                return getFullResIcon(resources, iconId);
            }
        }
        return getFullResDefaultActivityIcon();
    }

    private Bitmap makeDefaultIcon() {
        Drawable d = getFullResDefaultActivityIcon();
        Bitmap b = Bitmap.createBitmap(Math.max(d.getIntrinsicWidth(), 1),
                Math.max(d.getIntrinsicHeight(), 1),
                Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, b.getWidth(), b.getHeight());
        d.draw(c);
        c.setBitmap(null);
        return b;
    }

    public void remove(ComponentName componentName) {
        synchronized (mCache) {
            mCache.remove(componentName);
        }
    }

    public void flush() {
        synchronized (mCache) {
            mCache.clear();
        }
    }

    public void getTitleAndIcon(ApplicationInfo application, ResolveInfo info,
                                HashMap<Object, CharSequence> labelCache) {
        synchronized (mCache) {
            CacheEntry entry = cacheLocked(application.componentName, info, labelCache);

            application.title = entry.title;
            application.iconBitmap = entry.icon;
        }
    }

    public Bitmap getIcon(Intent intent) {
        synchronized (mCache) {
            final ResolveInfo resolveInfo = mPackageManager.resolveActivity(intent, 0);
            ComponentName component = intent.getComponent();

            if (resolveInfo == null || component == null) {
                return mDefaultIcon;
            }

            CacheEntry entry = cacheLocked(component, resolveInfo, null);
            return entry.icon;
        }
    }

    public Bitmap getIcon(ComponentName component, ResolveInfo resolveInfo) {
        synchronized (mCache) {
            if (resolveInfo == null || component == null) {
                return null;
            }

            CacheEntry entry = cacheLocked(component, resolveInfo, null);
            return entry.icon;
        }
    }

    public boolean isDefaultIcon(Bitmap icon) {
        return mDefaultIcon == icon;
    }

    private CacheEntry cacheLocked(ComponentName componentName, ResolveInfo info,
                                   HashMap<Object, CharSequence> labelCache) {
        CacheEntry entry = mCache.get(componentName);
        if (entry == null) {
            entry = new CacheEntry();
            mCache.put(componentName, entry);

            // 1. Handle the Title (Original Logic)
            if (labelCache != null && labelCache.containsKey(info)) {
                entry.title = labelCache.get(info).toString();
            } else {
                entry.title = info.loadLabel(mPackageManager).toString();
                if (labelCache != null) {
                    labelCache.put(info, entry.title);
                }
            }
            if (entry.title == null) {
                entry.title = info.activityInfo.name;
            }

            // 2. Handle the Icon (Themed Hijack)
            Drawable icon = null;

            // Generate the key: ComponentInfo{com.package/com.package.Activity}
            String componentKey = "ComponentInfo{" + componentName.flattenToString() + "}";
            String drawableName = mThemeMapping.get(componentKey);

            if (drawableName != null) {
                try {
                    Resources themeRes = mPackageManager.getResourcesForApplication(THEME_PACKAGE);
                    // Look for the drawable name in the icon pack's resources
                    int resId = themeRes.getIdentifier(drawableName, "drawable", THEME_PACKAGE);
                    if (resId != 0) {
                        // Use your mIconDpi to ensure the icon pack looks sharp
                        icon = themeRes.getDrawableForDensity(resId, mIconDpi);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error loading themed icon from pack", e);
                }
            }

            // Fallback: If no themed icon was found, use the original system icon
            if (icon == null) {
                icon = getFullResIcon(info, mPackageManager);
            }

            // Convert the Drawable to a Bitmap using the Launcher's utility
            entry.icon = Utilities.createIconBitmap(icon, getContext());
        }
        return entry;
    }

    public HashMap<ComponentName,Bitmap> getAllIcons() {
        synchronized (mCache) {
            HashMap<ComponentName,Bitmap> set = new HashMap<ComponentName,Bitmap>();
            for (ComponentName cn : mCache.keySet()) {
                final CacheEntry e = mCache.get(cn);
                set.put(cn, e.icon);
            }
            return set;
        }
    }
}
