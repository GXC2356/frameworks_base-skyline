/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server.devicepolicy;

import static android.app.admin.DevicePolicyManager.CONTENT_PROTECTION_DISABLED;
import static android.app.admin.DevicePolicyManager.ContentProtectionPolicy;

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.admin.DevicePolicyCache;
import android.app.admin.DevicePolicyManager;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.IndentingPrintWriter;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implementation of {@link DevicePolicyCache}, to which {@link DevicePolicyManagerService} pushes
 * policies.
 *
 * TODO Move other copies of policies into this class too.
 */
public class DevicePolicyCacheImpl extends DevicePolicyCache {
    /**
     * Lock object. For simplicity we just always use this as the lock. We could use each object
     * as a lock object to make it more fine-grained, but that'd make copy-paste error-prone.
     */
    private final Object mLock = new Object();

    /**
     * Indicates which user is screen capture disallowed on. Can be {@link UserHandle#USER_NULL},
     * {@link UserHandle#USER_ALL} or a concrete user ID.
     */
    @GuardedBy("mLock")
    private int mScreenCaptureDisallowedUser = UserHandle.USER_NULL;

    /**
     * Indicates if screen capture is disallowed on a specific user or all users if
     * it contains {@link UserHandle#USER_ALL}.
     */
    @GuardedBy("mLock")
    private Set<Integer> mScreenCaptureDisallowedUsers = new HashSet<>();

    @GuardedBy("mLock")
    private final SparseIntArray mPasswordQuality = new SparseIntArray();

    @GuardedBy("mLock")
    private final SparseIntArray mPermissionPolicy = new SparseIntArray();

    @GuardedBy("mLock")
    private ArrayMap<String, String> mLauncherShortcutOverrides = new ArrayMap<>();


    /** Maps to {@code ActiveAdmin.mAdminCanGrantSensorsPermissions}. */
    private final AtomicBoolean mCanGrantSensorsPermissions = new AtomicBoolean(false);

    @GuardedBy("mLock")
    private final SparseIntArray mContentProtectionPolicy = new SparseIntArray();

    public void onUserRemoved(int userHandle) {
        synchronized (mLock) {
            mPasswordQuality.delete(userHandle);
            mPermissionPolicy.delete(userHandle);
            mContentProtectionPolicy.delete(userHandle);
        }
    }

    @Override
    public boolean isScreenCaptureAllowed(int userHandle) {
        return isScreenCaptureAllowedInPolicyEngine(userHandle);
    }

    private boolean isScreenCaptureAllowedInPolicyEngine(int userHandle) {
        // This won't work if resolution mechanism is not strictest applies, but it's ok for now.
        synchronized (mLock) {
            return !mScreenCaptureDisallowedUsers.contains(userHandle)
                    && !mScreenCaptureDisallowedUsers.contains(UserHandle.USER_ALL);
        }
    }

    public int getScreenCaptureDisallowedUser() {
        synchronized (mLock) {
            return mScreenCaptureDisallowedUser;
        }
    }

    public void setScreenCaptureDisallowedUser(int userHandle) {
        synchronized (mLock) {
            mScreenCaptureDisallowedUser = userHandle;
        }
    }

    public void setScreenCaptureDisallowedUser(int userHandle, boolean disallowed) {
        synchronized (mLock) {
            if (disallowed) {
                mScreenCaptureDisallowedUsers.add(userHandle);
            } else {
                mScreenCaptureDisallowedUsers.remove(userHandle);
            }
        }
    }

    @Override
    public int getPasswordQuality(@UserIdInt int userHandle) {
        synchronized (mLock) {
            return mPasswordQuality.get(userHandle,
                    DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED);
        }
    }

    /** Updat the password quality cache for the given user */
    public void setPasswordQuality(int userHandle, int quality) {
        synchronized (mLock) {
            mPasswordQuality.put(userHandle, quality);
        }
    }

    @Override
    public int getPermissionPolicy(@UserIdInt int userHandle) {
        synchronized (mLock) {
            return mPermissionPolicy.get(userHandle,
                    DevicePolicyManager.PERMISSION_POLICY_PROMPT);
        }
    }

    /** Update the permission policy for the given user. */
    public void setPermissionPolicy(@UserIdInt int userHandle, int policy) {
        synchronized (mLock) {
            mPermissionPolicy.put(userHandle, policy);
        }
    }

    @Override
    public @ContentProtectionPolicy int getContentProtectionPolicy(@UserIdInt int userId) {
        synchronized (mLock) {
            return mContentProtectionPolicy.get(userId, CONTENT_PROTECTION_DISABLED);
        }
    }

    /** Update the content protection policy for the given user. */
    public void setContentProtectionPolicy(@UserIdInt int userId, @Nullable Integer value) {
        synchronized (mLock) {
            if (value == null) {
                mContentProtectionPolicy.delete(userId);
            } else {
                mContentProtectionPolicy.put(userId, value);
            }
        }
    }

    @Override
    public boolean canAdminGrantSensorsPermissions() {
        return mCanGrantSensorsPermissions.get();
    }

    /** Sets admin control over permission grants. */
    public void setAdminCanGrantSensorsPermissions(boolean canGrant) {
        mCanGrantSensorsPermissions.set(canGrant);
    }

    @Override
    public Map<String, String> getLauncherShortcutOverrides() {
        synchronized (mLock) {
            return new ArrayMap<>(mLauncherShortcutOverrides);
        }
    }

    /**
     * Sets a map of packages names to package names, for which all launcher shortcuts which
     * match a key package name should be modified to launch the corresponding value package
     * name in the managed profile. The overridden shortcut should be badged accordingly.
     */
    public void setLauncherShortcutOverrides(ArrayMap<String, String> launcherShortcutOverrides) {
        synchronized (mLock) {
            mLauncherShortcutOverrides = new ArrayMap<>(launcherShortcutOverrides);
        }
    }

    /** Dump content */
    public void dump(IndentingPrintWriter pw) {
        synchronized (mLock) {
            pw.println("Device policy cache:");
            pw.increaseIndent();
            pw.println("Screen capture disallowed users: " + mScreenCaptureDisallowedUsers);
            pw.println("Password quality: " + mPasswordQuality);
            pw.println("Permission policy: " + mPermissionPolicy);
            pw.println("Content protection policy: " + mContentProtectionPolicy);
            pw.println("Admin can grant sensors permission: " + mCanGrantSensorsPermissions.get());
            pw.print("Shortcuts overrides: ");
            pw.println(mLauncherShortcutOverrides);
            pw.decreaseIndent();
        }
    }
}
