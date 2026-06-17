/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.android.customization.model.font;

import static com.android.customization.model.ResourceConstants.ANDROID_PACKAGE;
import static com.android.customization.model.ResourceConstants.OVERLAY_CATEGORY_FONT;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.content.Intent;
import com.android.themepicker.R;
import androidx.annotation.Nullable;
import com.android.internal.statusbar.IStatusBarService;
import android.os.RemoteException;
import android.os.ServiceManager;


import com.android.customization.model.CustomizationManager;
import com.android.customization.model.theme.OverlayManagerCompat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.List;

import org.json.JSONException;
import org.json.JSONObject;

public class FontManager implements CustomizationManager<FontOption> {

    private static FontManager sFontOptionManager;
    private Context mContext;
    private FontOption mActiveOption;
    private OverlayManagerCompat mOverlayManager;
    private FontOptionProvider mProvider;
    private static final String TAG = "FontManager";
    private static final String KEY_STATE_CURRENT_SELECTION = "FontManager.currentSelection";

    FontManager(Context context, OverlayManagerCompat overlayManager, FontOptionProvider provider) {
        mContext = context;
        mProvider = provider;
        mOverlayManager = overlayManager;
    }

    @Override
    public boolean isAvailable() {
        return mOverlayManager.isAvailable();
    }

    @Override
    public void apply(FontOption option, @Nullable Callback callback) {
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) {
            if (callback != null) callback.onError(null);
            return;
        }
    
        String packageName = option.getPackageName();
    
        try {
            if (packageName == null) {
                String current = getCurrentEnabledPackage();
                if (current != null) {
                    mOverlayManager.disableOverlay(current, UserHandle.USER_SYSTEM);
                }
            } else {
                mOverlayManager.setEnabledExclusiveInCategory(packageName, UserHandle.USER_SYSTEM);
            }
    
            if (!persistOverlay(option)) {
                if (callback != null) callback.onError(null);
                return;
            }
    
            if (callback != null) callback.onSuccess();
            mActiveOption = option;
    
            restartSystemUI(packageName);
    
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply font overlay", e);
            if (callback != null) callback.onError(null);
        }
    }

    @Override
    public void fetchOptions(OptionsFetchedListener<FontOption> callback, boolean reload) {
        List<FontOption> options = mProvider.getOptions(reload);
        for (FontOption option : options) {
            if (isActive(option)) {
                mActiveOption = option;
                break;
            }
        }
        callback.onOptionsLoaded(options);
    }

    public OverlayManagerCompat getOverlayManager() {
        return mOverlayManager;
    }

    public boolean isActive(FontOption option) {
        String enabledPkg = getCurrentEnabledPackage();
        if (enabledPkg != null) {
            return enabledPkg.equals(option.getPackageName());
        } else {
            return option.getPackageName() == null;
        }
    }

    private String getCurrentEnabledPackage() {
        return mOverlayManager.getEnabledPackageName(ANDROID_PACKAGE, OVERLAY_CATEGORY_FONT);
    }

    private boolean persistOverlay(FontOption toPersist) {
        String value = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES, UserHandle.myUserId());
        
        JSONObject json;
        try {
            if (value == null) {
                json = new JSONObject();
            } else {
                json = new JSONObject(value);
            }
            
            if (toPersist.getPackageName() == null) {
                if (json.has(OVERLAY_CATEGORY_FONT)) {
                    json.remove(OVERLAY_CATEGORY_FONT);
                }
            } else {
                json.put(OVERLAY_CATEGORY_FONT, toPersist.getPackageName());
            }
            
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                    json.toString(), UserHandle.myUserId());
            
            return true;
            
        } catch (JSONException e) {
            Log.e(TAG, "Error persisting overlay settings", e);
            return false;
        }
    }

    private void restartSystemUI(String fontPackage) {
        Settings.Global.putString(mContext.getContentResolver(),
                "systemui_font_package_request",
                fontPackage != null ? fontPackage : "none");
    
        Settings.Global.putLong(mContext.getContentResolver(),
                "systemui_font_restart_request",
                System.currentTimeMillis());
    }
    
    public static FontManager getInstance(Context context, OverlayManagerCompat overlayManager) {
      if (sFontOptionManager == null) {
          Context applicationContext = context.getApplicationContext();
          sFontOptionManager = new FontManager(applicationContext, overlayManager,
                  new FontOptionProvider(applicationContext, overlayManager));
      }
        return sFontOptionManager;
      }
}