/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.stack;

import static com.android.systemui.Dependency.ALLOW_NOTIFICATION_LONG_PRESS_NAME;

import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.PointF;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.SwipeHelper;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.media.KeyguardMediaController;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin.OnMenuEventListener;
import com.android.systemui.plugins.statusbar.NotificationSwipeActionHelper;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationLockscreenUserManager.UserChangedListener;
import com.android.systemui.statusbar.NotificationShelfController;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.ActivityLaunchAnimator;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotificationActivityStarter;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.phone.HeadsUpAppearanceController;
import com.android.systemui.statusbar.phone.HeadsUpManagerPhone;
import com.android.systemui.statusbar.phone.HeadsUpTouchHelper;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.NotificationPanelViewController;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.dagger.StatusBarComponent;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.tuner.TunerService;

import java.util.function.BiConsumer;

import javax.inject.Inject;
import javax.inject.Named;

import kotlin.Unit;

/**
 * Controller for {@link NotificationStackScrollLayout}.
 */
@StatusBarComponent.StatusBarScope
public class NotificationStackScrollLayoutController {
    private static final String TAG = "StackScrollerController";

    private final boolean mAllowLongPress;
    private final NotificationGutsManager mNotificationGutsManager;
    private final HeadsUpManagerPhone mHeadsUpManager;
    private final NotificationRoundnessManager mNotificationRoundnessManager;
    private final TunerService mTunerService;
    private final DynamicPrivacyController mDynamicPrivacyController;
    private final ConfigurationController mConfigurationController;
    private final ZenModeController mZenModeController;
    private final MetricsLogger mMetricsLogger;
    private final FalsingManager mFalsingManager;
    private final NotificationSectionsManager mNotificationSectionsManager;
    private final Resources mResources;
    private final NotificationSwipeHelper.Builder mNotificationSwipeHelperBuilder;
    private final KeyguardMediaController mKeyguardMediaController;
    private final SysuiStatusBarStateController mStatusBarStateController;
    private final KeyguardBypassController mKeyguardBypassController;
    private final SysuiColorExtractor mColorExtractor;
    private final NotificationLockscreenUserManager mLockscreenUserManager;
    // TODO: StatusBar should be encapsulated behind a Controller
    private final StatusBar mStatusBar;

    private NotificationStackScrollLayout mView;
    private boolean mFadeNotificationsOnDismiss;

    private final NotificationListContainerImpl mNotificationListContainer =
            new NotificationListContainerImpl();

    @VisibleForTesting
    final View.OnAttachStateChangeListener mOnAttachStateChangeListener =
            new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    mConfigurationController.addCallback(mConfigurationListener);
                    mStatusBarStateController.addCallback(
                            mStateListener, SysuiStatusBarStateController.RANK_STACK_SCROLLER);
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    mConfigurationController.removeCallback(mConfigurationListener);
                    mStatusBarStateController.removeCallback(mStateListener);
                }
            };

    private final DynamicPrivacyController.Listener mDynamicPrivacyControllerListener = () -> {
        if (mView.isExpanded()) {
            // The bottom might change because we're using the final actual height of the view
            mView.setAnimateBottomOnLayout(true);
        }
        // Let's update the footer once the notifications have been updated (in the next frame)
        mView.post(() -> {
            updateFooter();
            updateSectionBoundaries("dynamic privacy changed");
        });
    };

    @VisibleForTesting
    final ConfigurationListener mConfigurationListener = new ConfigurationListener() {
        @Override
        public void onDensityOrFontScaleChanged() {
            mView.reinflateViews();
        }

        @Override
        public void onOverlayChanged() {
            mView.updateCornerRadius();
            mView.reinflateViews();
        }

        @Override
        public void onUiModeChanged() {
            mView.updateBgColor();
        }

        @Override
        public void onThemeChanged() {
            updateFooter();
        }
    };

    private final StatusBarStateController.StateListener mStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onStatePreChange(int oldState, int newState) {
                    if (oldState == StatusBarState.SHADE_LOCKED
                            && newState == StatusBarState.KEYGUARD) {
                        mView.requestAnimateEverything();
                    }
                }

                @Override
                public void onStateChanged(int newState) {
                    mView.setStatusBarState(newState);
                }

                @Override
                public void onStatePostChange() {
                    mView.updateSensitiveness(mStatusBarStateController.goingToFullShade(),
                            mLockscreenUserManager.isAnyProfilePublicMode());
                    mView.onStatePostChange(mStatusBarStateController.fromShadeLocked());
                }
            };

    private final UserChangedListener mLockscreenUserChangeListener = new UserChangedListener() {
        @Override
        public void onUserChanged(int userId) {
            mView.setCurrentUserid(userId);
            mView.updateSensitiveness(false, mLockscreenUserManager.isAnyProfilePublicMode());
        }
    };

    private final OnMenuEventListener mMenuEventListener = new OnMenuEventListener() {
        @Override
        public void onMenuClicked(
                View view, int x, int y, NotificationMenuRowPlugin.MenuItem item) {
            if (!mAllowLongPress) {
                return;
            }
            if (view instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) view;
                mMetricsLogger.write(row.getEntry().getSbn().getLogMaker()
                        .setCategory(MetricsEvent.ACTION_TOUCH_GEAR)
                        .setType(MetricsEvent.TYPE_ACTION)
                );
            }
            mNotificationGutsManager.openGuts(view, x, y, item);
        }

        @Override
        public void onMenuReset(View row) {
            mView.onMenuReset(row);
        }

        @Override
        public void onMenuShown(View row) {
            if (row instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow notificationRow = (ExpandableNotificationRow) row;
                mMetricsLogger.write(notificationRow.getEntry().getSbn().getLogMaker()
                        .setCategory(MetricsEvent.ACTION_REVEAL_GEAR)
                        .setType(MetricsEvent.TYPE_ACTION));
                mHeadsUpManager.setMenuShown(notificationRow.getEntry(), true);
                mView.onMenuShown(row);
                mNotificationGutsManager.closeAndSaveGuts(true /* removeLeavebehind */,
                        false /* force */, false /* removeControls */, -1 /* x */, -1 /* y */,
                        false /* resetMenu */);

                // Check to see if we want to go directly to the notification guts
                NotificationMenuRowPlugin provider = notificationRow.getProvider();
                if (provider.shouldShowGutsOnSnapOpen()) {
                    NotificationMenuRowPlugin.MenuItem item = provider.menuItemToExposeOnSnap();
                    if (item != null) {
                        Point origin = provider.getRevealAnimationOrigin();
                        mNotificationGutsManager.openGuts(row, origin.x, origin.y, item);
                    } else  {
                        Log.e(TAG, "Provider has shouldShowGutsOnSnapOpen, but provided no "
                                + "menu item in menuItemtoExposeOnSnap. Skipping.");
                    }

                    // Close the menu row since we went directly to the guts
                    mView.resetExposedMenuView(false, true);
                }
            }
        }
    };

    private final NotificationSwipeHelper.NotificationCallback mNotificationCallback =
            new NotificationSwipeHelper.NotificationCallback() {

                @Override
                public void onDismiss() {
                    mNotificationGutsManager.closeAndSaveGuts(true /* removeLeavebehind */,
                            false /* force */, false /* removeControls */, -1 /* x */, -1 /* y */,
                            false /* resetMenu */);
                }

                @Override
                public void onSnooze(StatusBarNotification sbn,
                        NotificationSwipeActionHelper.SnoozeOption snoozeOption) {
                    mStatusBar.setNotificationSnoozed(sbn, snoozeOption);
                }

                @Override
                public void onSnooze(StatusBarNotification sbn, int hours) {
                    mStatusBar.setNotificationSnoozed(sbn, hours);
                }

                @Override
                public boolean shouldDismissQuickly() {
                    return mView.isExpanded() && mView.isFullyAwake();
                }

                @Override
                public void onDragCancelled(View v) {
                    mView.setSwipingInProgress(false);
                    mFalsingManager.onNotificationStopDismissing();
                }

                /**
                 * Handles cleanup after the given {@code view} has been fully swiped out (including
                 * re-invoking dismiss logic in case the notification has not made its way out yet).
                 */
                @Override
                public void onChildDismissed(View view) {
                    if (!(view instanceof ActivatableNotificationView)) {
                        return;
                    }
                    ActivatableNotificationView row = (ActivatableNotificationView) view;
                    if (!row.isDismissed()) {
                        handleChildViewDismissed(view);
                    }
                    ViewGroup transientContainer = row.getTransientContainer();
                    if (transientContainer != null) {
                        transientContainer.removeTransientView(view);
                    }
                }

                /**
                 * Starts up notification dismiss and tells the notification, if any, to remove
                 * itself from the layout.
                 *
                 * @param view view (e.g. notification) to dismiss from the layout
                 */

                public void handleChildViewDismissed(View view) {
                    mView.setSwipingInProgress(false);
                    if (mView.getDismissAllInProgress()) {
                        return;
                    }

                    boolean isBlockingHelperShown = false;

                    mView.removeDraggedView(view);
                    mView.updateContinuousShadowDrawing();

                    if (view instanceof ExpandableNotificationRow) {
                        ExpandableNotificationRow row = (ExpandableNotificationRow) view;
                        if (row.isHeadsUp()) {
                            mHeadsUpManager.addSwipedOutNotification(
                                    row.getEntry().getSbn().getKey());
                        }
                        isBlockingHelperShown =
                                row.performDismissWithBlockingHelper(false /* fromAccessibility */);
                    }

                    if (view instanceof PeopleHubView) {
                        mNotificationSectionsManager.hidePeopleRow();
                    }

                    if (!isBlockingHelperShown) {
                        mView.addSwipedOutView(view);
                    }
                    mFalsingManager.onNotificationDismissed();
                    if (mFalsingManager.shouldEnforceBouncer()) {
                        mStatusBar.executeRunnableDismissingKeyguard(
                                null,
                                null /* cancelAction */,
                                false /* dismissShade */,
                                true /* afterKeyguardGone */,
                                false /* deferred */);
                    }
                }

                @Override
                public boolean isAntiFalsingNeeded() {
                    return mView.onKeyguard();
                }

                @Override
                public View getChildAtPosition(MotionEvent ev) {
                    View child = mView.getChildAtPosition(
                            ev.getX(),
                            ev.getY(),
                            true /* requireMinHeight */,
                            false /* ignoreDecors */);
                    if (child instanceof ExpandableNotificationRow) {
                        ExpandableNotificationRow row = (ExpandableNotificationRow) child;
                        ExpandableNotificationRow parent = row.getNotificationParent();
                        if (parent != null && parent.areChildrenExpanded()
                                && (parent.areGutsExposed()
                                || mSwipeHelper.getExposedMenuView() == parent
                                || (parent.getAttachedChildren().size() == 1
                                && parent.getEntry().isClearable()))) {
                            // In this case the group is expanded and showing the menu for the
                            // group, further interaction should apply to the group, not any
                            // child notifications so we use the parent of the child. We also do the
                            // same if we only have a single child.
                            child = parent;
                        }
                    }
                    return child;
                }

                @Override
                public void onBeginDrag(View v) {
                    mFalsingManager.onNotificationStartDismissing();
                    mView.setSwipingInProgress(true);
                    mView.addDraggedView(v);
                    mView.updateContinuousShadowDrawing();
                    mView.updateContinuousBackgroundDrawing();
                    mView.requestChildrenUpdate();
                }

                @Override
                public void onChildSnappedBack(View animView, float targetLeft) {
                    mView.addDraggedView(animView);
                    mView.updateContinuousShadowDrawing();
                    mView.updateContinuousBackgroundDrawing();
                    if (animView instanceof ExpandableNotificationRow) {
                        ExpandableNotificationRow row = (ExpandableNotificationRow) animView;
                        if (row.isPinned() && !canChildBeDismissed(row)
                                && row.getEntry().getSbn().getNotification().fullScreenIntent
                                == null) {
                            mHeadsUpManager.removeNotification(row.getEntry().getSbn().getKey(),
                                    true /* removeImmediately */);
                        }
                    }
                }

                @Override
                public boolean updateSwipeProgress(View animView, boolean dismissable,
                        float swipeProgress) {
                    // Returning true prevents alpha fading.
                    return !mFadeNotificationsOnDismiss;
                }

                @Override
                public float getFalsingThresholdFactor() {
                    return mStatusBar.isWakeUpComingFromTouch() ? 1.5f : 1.0f;
                }

                @Override
                public int getConstrainSwipeStartPosition() {
                    NotificationMenuRowPlugin menuRow = mSwipeHelper.getCurrentMenuRow();
                    if (menuRow != null) {
                        return Math.abs(menuRow.getMenuSnapTarget());
                    }
                    return 0;
                }

                @Override
                public boolean canChildBeDismissed(View v) {
                    return NotificationStackScrollLayout.canChildBeDismissed(v);
                }

                @Override
                public boolean canChildBeDismissedInDirection(View v, boolean isRightOrDown) {
                    //TODO: b/131242807 for why this doesn't do anything with direction
                    return canChildBeDismissed(v);
                }
            };

    private NotificationSwipeHelper mSwipeHelper;

    @Inject
    public NotificationStackScrollLayoutController(
            @Named(ALLOW_NOTIFICATION_LONG_PRESS_NAME) boolean allowLongPress,
            NotificationGutsManager notificationGutsManager,
            HeadsUpManagerPhone headsUpManager,
            NotificationRoundnessManager notificationRoundnessManager,
            TunerService tunerService,
            DynamicPrivacyController dynamicPrivacyController,
            ConfigurationController configurationController,
            SysuiStatusBarStateController statusBarStateController,
            KeyguardMediaController keyguardMediaController,
            KeyguardBypassController keyguardBypassController,
            ZenModeController zenModeController,
            SysuiColorExtractor colorExtractor,
            NotificationLockscreenUserManager lockscreenUserManager,
            MetricsLogger metricsLogger,
            FalsingManager falsingManager,
            NotificationSectionsManager notificationSectionsManager,
            @Main Resources resources,
            NotificationSwipeHelper.Builder notificationSwipeHelperBuilder,
            StatusBar statusBar) {
        mAllowLongPress = allowLongPress;
        mNotificationGutsManager = notificationGutsManager;
        mHeadsUpManager = headsUpManager;
        mNotificationRoundnessManager = notificationRoundnessManager;
        mTunerService = tunerService;
        mDynamicPrivacyController = dynamicPrivacyController;
        mConfigurationController = configurationController;
        mStatusBarStateController = statusBarStateController;
        mKeyguardMediaController = keyguardMediaController;
        mKeyguardBypassController = keyguardBypassController;
        mZenModeController = zenModeController;
        mColorExtractor = colorExtractor;
        mLockscreenUserManager = lockscreenUserManager;
        mMetricsLogger = metricsLogger;
        mFalsingManager = falsingManager;
        mNotificationSectionsManager = notificationSectionsManager;
        mResources = resources;
        mNotificationSwipeHelperBuilder = notificationSwipeHelperBuilder;
        mStatusBar = statusBar;
    }

    public void attach(NotificationStackScrollLayout view) {
        mView = view;
        mView.setController(this);

        mSwipeHelper = mNotificationSwipeHelperBuilder
                .setSwipeDirection(SwipeHelper.X)
                .setNotificationCallback(mNotificationCallback)
                .setOnMenuEventListener(mMenuEventListener)
                .build();

        mView.initView(mView.getContext(), mKeyguardBypassController::getBypassEnabled,
                mSwipeHelper);

        mHeadsUpManager.addListener(mNotificationRoundnessManager); // TODO: why is this here?
        mDynamicPrivacyController.addListener(mDynamicPrivacyControllerListener);

        mLockscreenUserManager.addUserChangedListener(mLockscreenUserChangeListener);
        mView.setCurrentUserid(mLockscreenUserManager.getCurrentUserId());

        mFadeNotificationsOnDismiss =  // TODO: this should probably be injected directly
                mResources.getBoolean(R.bool.config_fadeNotificationsOnDismiss);

        mNotificationRoundnessManager.setOnRoundingChangedCallback(mView::invalidate);
        mView.addOnExpandedHeightChangedListener(mNotificationRoundnessManager::setExpanded);

        mTunerService.addTunable(
                (key, newValue) -> {
                    if (key.equals(Settings.Secure.NOTIFICATION_DISMISS_RTL)) {
                        mView.updateDismissRtlSetting("1".equals(newValue));
                    } else if (key.equals(Settings.Secure.NOTIFICATION_HISTORY_ENABLED)) {
                        updateFooter();
                    }
                },
                Settings.Secure.NOTIFICATION_DISMISS_RTL,
                Settings.Secure.NOTIFICATION_HISTORY_ENABLED);

        mColorExtractor.addOnColorsChangedListener((colorExtractor, which) -> {
            final boolean useDarkText = mColorExtractor.getNeutralColors().supportsDarkText();
            mView.updateDecorViews(useDarkText);
        });

        mKeyguardMediaController.setVisibilityChangedListener(visible -> {
            mView.setKeyguardMediaControllorVisible(visible);
            if (visible) {
                mView.generateAddAnimation(
                        mKeyguardMediaController.getView(), false /*fromMoreCard */);
            } else {
                mView.generateRemoveAnimation(mKeyguardMediaController.getView());
            }
            mView.requestChildrenUpdate();
            return Unit.INSTANCE;
        });

        if (mView.isAttachedToWindow()) {
            mOnAttachStateChangeListener.onViewAttachedToWindow(mView);
        }
        mView.addOnAttachStateChangeListener(mOnAttachStateChangeListener);
    }

    public void addOnExpandedHeightChangedListener(BiConsumer<Float, Float> listener) {
        mView.addOnExpandedHeightChangedListener(listener);
    }

    public void removeOnExpandedHeightChangedListener(BiConsumer<Float, Float> listener) {
        mView.removeOnExpandedHeightChangedListener(listener);
    }

    public void addOnLayoutChangeListener(View.OnLayoutChangeListener listener) {
        mView.addOnLayoutChangeListener(listener);
    }

    public void removeOnLayoutChangeListener(View.OnLayoutChangeListener listener) {
        mView.removeOnLayoutChangeListener(listener);
    }

    public void setHeadsUpAppearanceController(HeadsUpAppearanceController controller) {
        mView.setHeadsUpAppearanceController(controller);
    }

    public void requestLayout() {
        mView.requestLayout();
    }

    public Display getDisplay() {
        return mView.getDisplay();
    }

    public WindowInsets getRootWindowInsets() {
        return mView.getRootWindowInsets();
    }

    public int getRight() {
        return mView.getRight();
    }

    public boolean isLayoutRtl() {
        return mView.isLayoutRtl();
    }

    public float getLeft() {
        return  mView.getLeft();
    }

    public float getTranslationX() {
        return mView.getTranslationX();
    }

    public void setOnHeightChangedListener(
            ExpandableView.OnHeightChangedListener listener) {
        mView.setOnHeightChangedListener(listener);
    }

    public void setOverscrollTopChangedListener(
            NotificationStackScrollLayout.OnOverscrollTopChangedListener listener) {
        mView.setOverscrollTopChangedListener(listener);
    }

    public void setOnEmptySpaceClickListener(
            NotificationStackScrollLayout.OnEmptySpaceClickListener listener) {
        mView.setOnEmptySpaceClickListener(listener);
    }

    public void setTrackingHeadsUp(ExpandableNotificationRow expandableNotificationRow) {
        mView.setTrackingHeadsUp(expandableNotificationRow);
    }

    public void wakeUpFromPulse() {
        mView.wakeUpFromPulse();
    }

    public boolean isPulseExpanding() {
        return mView.isPulseExpanding();
    }

    public void setOnPulseHeightChangedListener(Runnable listener) {
        mView.setOnPulseHeightChangedListener(listener);
    }

    public void setDozeAmount(float amount) {
        mView.setDozeAmount(amount);
    }

    public float getWakeUpHeight() {
        return mView.getWakeUpHeight();
    }

    public void setHideAmount(float linearAmount, float amount) {
        mView.setHideAmount(linearAmount, amount);
    }

    public void notifyHideAnimationStart(boolean hide) {
        mView.notifyHideAnimationStart(hide);
    }

    public float setPulseHeight(float height) {
        return mView.setPulseHeight(height);
    }

    public void getLocationOnScreen(int[] outLocation) {
        mView.getLocationOnScreen(outLocation);
    }

    public ExpandableView getChildAtRawPosition(float x, float y) {
        return mView.getChildAtRawPosition(x, y);
    }

    public FrameLayout.LayoutParams getLayoutParams() {
        return (FrameLayout.LayoutParams) mView.getLayoutParams();
    }

    public void setLayoutParams(FrameLayout.LayoutParams lp) {
        mView.setLayoutParams(lp);
    }

    public void setIsFullWidth(boolean isFullWidth) {
        mView.setIsFullWidth(isFullWidth);
    }

    public boolean isAddOrRemoveAnimationPending() {
        return mView.isAddOrRemoveAnimationPending();
    }

    public int getVisibleNotificationCount() {
        return mView.getVisibleNotificationCount();
    }

    public int getIntrinsicContentHeight() {
        return mView.getIntrinsicContentHeight();
    }

    public void setIntrinsicPadding(int intrinsicPadding) {
        mView.setIntrinsicPadding(intrinsicPadding);
    }

    public int getHeight() {
        return mView.getHeight();
    }

    public int getChildCount() {
        return mView.getChildCount();
    }

    public ExpandableView getChildAt(int i) {
        return (ExpandableView) mView.getChildAt(i);
    }

    public void goToFullShade(long delay) {
        mView.goToFullShade(delay);
    }

    public void setOverScrollAmount(float amount, boolean onTop, boolean animate,
            boolean cancelAnimators) {
        mView.setOverScrollAmount(amount, onTop, animate, cancelAnimators);
    }

    public void setOverScrollAmount(float amount, boolean onTop, boolean animate) {
        mView.setOverScrollAmount(amount, onTop, animate);
    }

    public void setOverScrolledPixels(float numPixels, boolean onTop, boolean animate) {
        mView.setOverScrolledPixels(numPixels, onTop, animate);
    }

    public void resetScrollPosition() {
        mView.resetScrollPosition();
    }

    public void setShouldShowShelfOnly(boolean shouldShowShelfOnly) {
        mView.setShouldShowShelfOnly(shouldShowShelfOnly);
    }

    public void cancelLongPress() {
        mView.cancelLongPress();
    }

    public float getX() {
        return mView.getX();
    }

    public boolean isBelowLastNotification(float x, float y) {
        return mView.isBelowLastNotification(x, y);
    }

    public float getWidth() {
        return mView.getWidth();
    }

    public float getOpeningHeight() {
        return mView.getOpeningHeight();
    }

    public float getBottomMostNotificationBottom() {
        return mView.getBottomMostNotificationBottom();
    }

    public void checkSnoozeLeavebehind() {
        mView.checkSnoozeLeavebehind();
    }

    public void setQsExpanded(boolean expanded) {
        mView.setQsExpanded(expanded);
    }

    public void setScrollingEnabled(boolean enabled) {
        mView.setScrollingEnabled(enabled);
    }

    public void setQsExpansionFraction(float expansionFraction) {
        mView.setQsExpansionFraction(expansionFraction);
    }

    public float calculateAppearFractionBypass() {
        return mView.calculateAppearFractionBypass();
    }

    public void updateTopPadding(float qsHeight, boolean animate) {
        mView.updateTopPadding(qsHeight, animate);
    }

    public void resetCheckSnoozeLeavebehind() {
        mView.resetCheckSnoozeLeavebehind();
    }

    public boolean isScrolledToBottom() {
        return mView.isScrolledToBottom();
    }

    public int getNotGoneChildCount() {
        return mView.getNotGoneChildCount();
    }

    public float getIntrinsicPadding() {
        return mView.getIntrinsicPadding();
    }

    public float getLayoutMinHeight() {
        return mView.getLayoutMinHeight();
    }

    public int getEmptyBottomMargin() {
        return mView.getEmptyBottomMargin();
    }

    public float getTopPaddingOverflow() {
        return mView.getTopPaddingOverflow();
    }

    public int getTopPadding() {
        return mView.getTopPadding();
    }

    public float getEmptyShadeViewHeight() {
        return mView.getEmptyShadeViewHeight();
    }

    public void setAlpha(float alpha) {
        mView.setAlpha(alpha);
    }

    public float getCurrentOverScrollAmount(boolean top) {
        return mView.getCurrentOverScrollAmount(top);
    }

    public float getCurrentOverScrolledPixels(boolean top) {
        return mView.getCurrentOverScrolledPixels(top);
    }

    public float calculateAppearFraction(float height) {
        return mView.calculateAppearFraction(height);
    }

    public void onExpansionStarted() {
        mView.onExpansionStarted();
    }

    public void onExpansionStopped() {
        mView.onExpansionStopped();
    }

    public void onPanelTrackingStarted() {
        mView.onPanelTrackingStarted();
    }

    public void onPanelTrackingStopped() {
        mView.onPanelTrackingStopped();
    }

    public void setHeadsUpBoundaries(int height, int bottomBarHeight) {
        mView.setHeadsUpBoundaries(height, bottomBarHeight);
    }

    public void setUnlockHintRunning(boolean running) {
        mView.setUnlockHintRunning(running);
    }

    public float getPeekHeight() {
        return mView.getPeekHeight();
    }

    public boolean isFooterViewNotGone() {
        return mView.isFooterViewNotGone();
    }

    public boolean isFooterViewContentVisible() {
        return mView.isFooterViewContentVisible();
    }

    public int getFooterViewHeightWithPadding() {
        return mView.getFooterViewHeightWithPadding();
    }

    public void updateEmptyShadeView(boolean visible) {
        mView.updateEmptyShadeView(visible, mZenModeController.areNotificationsHiddenInShade());
    }

    public void setHeadsUpAnimatingAway(boolean headsUpAnimatingAway) {
        mView.setHeadsUpAnimatingAway(headsUpAnimatingAway);
    }

    public HeadsUpTouchHelper.Callback getHeadsUpCallback() {
        return mView.getHeadsUpCallback();
    }

    public void forceNoOverlappingRendering(boolean force) {
        mView.forceNoOverlappingRendering(force);
    }

    public void setTranslationX(float translation) {
        mView.setTranslationX(translation);
    }

    public void setExpandingVelocity(float velocity) {
        mView.setExpandingVelocity(velocity);
    }

    public void setExpandedHeight(float expandedHeight) {
        mView.setExpandedHeight(expandedHeight);
    }

    public void setQsContainer(ViewGroup view) {
        mView.setQsContainer(view);
    }

    public void setAnimationsEnabled(boolean enabled) {
        mView.setAnimationsEnabled(enabled);
    }

    public void setDozing(boolean dozing, boolean animate, PointF wakeUpTouchLocation) {
        mView.setDozing(dozing, animate, wakeUpTouchLocation);
    }

    public void setPulsing(boolean pulsing, boolean animatePulse) {
        mView.setPulsing(pulsing, animatePulse);
    }

    public boolean hasActiveClearableNotifications(
            @NotificationStackScrollLayout.SelectedRows int selection) {
        return mView.hasActiveClearableNotifications(selection);
    }

    public RemoteInputController.Delegate createDelegate() {
        return mView.createDelegate();
    }

    public void updateSectionBoundaries(String reason) {
        mView.updateSectionBoundaries(reason);
    }

    public void updateSpeedBumpIndex() {
        mView.updateSpeedBumpIndex();
    }

    public void updateFooter() {
        mView.updateFooter();
    }

    public void onUpdateRowStates() {
        mView.onUpdateRowStates();
    }

    public ActivatableNotificationView getActivatedChild() {
        return mView.getActivatedChild();
    }

    public void setActivatedChild(ActivatableNotificationView view) {
        mView.setActivatedChild(view);
    }

    public void runAfterAnimationFinished(Runnable r) {
        mView.runAfterAnimationFinished(r);
    }

    public void setNotificationPanelController(
            NotificationPanelViewController notificationPanelViewController) {
        mView.setNotificationPanelController(notificationPanelViewController);
    }

    public void setStatusBar(StatusBar statusBar) {
        mView.setStatusBar(statusBar);
    }

    public void setGroupManager(NotificationGroupManager groupManager) {
        mView.setGroupManager(groupManager);
    }

    public void setShelfController(NotificationShelfController notificationShelfController) {
        mView.setShelfController(notificationShelfController);
    }

    public void setScrimController(ScrimController scrimController) {
        mView.setScrimController(scrimController);
    }

    public ExpandableView getFirstChildNotGone() {
        return mView.getFirstChildNotGone();
    }

    public void setInHeadsUpPinnedMode(boolean inPinnedMode) {
        mView.setInHeadsUpPinnedMode(inPinnedMode);
    }

    public void generateHeadsUpAnimation(NotificationEntry entry, boolean isHeadsUp) {
        mView.generateHeadsUpAnimation(entry, isHeadsUp);
    }

    public void generateHeadsUpAnimation(ExpandableNotificationRow row, boolean isHeadsUp) {
        mView.generateHeadsUpAnimation(row, isHeadsUp);
    }

    public void setMaxTopPadding(int padding) {
        mView.setMaxTopPadding(padding);
    }

    public int getTransientViewCount() {
        return mView.getTransientViewCount();
    }

    public View getTransientView(int i) {
        return mView.getTransientView(i);
    }

    public int getPositionInLinearLayout(ExpandableView row) {
        return mView.getPositionInLinearLayout(row);
    }

    public NotificationStackScrollLayout getView() {
        return mView;
    }

    public float calculateGapHeight(ExpandableView previousView, ExpandableView child, int count) {
        return mView.calculateGapHeight(previousView, child, count);
    }

    public NotificationRoundnessManager getNoticationRoundessManager() {
        return mNotificationRoundnessManager;
    }

    public NotificationListContainer getNotificationListContainer() {
        return mNotificationListContainer;
    }

    private class NotificationListContainerImpl implements NotificationListContainer {
        @Override
        public void setChildTransferInProgress(boolean childTransferInProgress) {
            mView.setChildTransferInProgress(childTransferInProgress);
        }

        @Override
        public void changeViewPosition(ExpandableView child, int newIndex) {
            mView.changeViewPosition(child, newIndex);
        }

        @Override
        public void notifyGroupChildAdded(ExpandableView row) {
            mView.notifyGroupChildAdded(row);
        }

        @Override
        public void notifyGroupChildRemoved(ExpandableView row, ViewGroup childrenContainer) {
            mView.notifyGroupChildRemoved(row, childrenContainer);
        }

        @Override
        public void generateAddAnimation(ExpandableView child, boolean fromMoreCard) {
            mView.generateAddAnimation(child, fromMoreCard);
        }

        @Override
        public void generateChildOrderChangedEvent() {
            mView.generateChildOrderChangedEvent();
        }

        @Override
        public int getContainerChildCount() {
            return mView.getContainerChildCount();
        }

        @Override
        public void setNotificationActivityStarter(
                NotificationActivityStarter notificationActivityStarter) {
            mView.setNotificationActivityStarter(notificationActivityStarter);
        }

        @Override
        public View getContainerChildAt(int i) {
            return mView.getContainerChildAt(i);
        }

        @Override
        public void removeContainerView(View v) {
            mView.removeContainerView(v);
        }

        @Override
        public void addContainerView(View v) {
            mView.addContainerView(v);
        }

        @Override
        public void addContainerViewAt(View v, int index) {
            mView.addContainerViewAt(v, index);
        }

        @Override
        public void setMaxDisplayedNotifications(int maxNotifications) {
            mView.setMaxDisplayedNotifications(maxNotifications);
        }

        @Override
        public ViewGroup getViewParentForNotification(NotificationEntry entry) {
            return mView.getViewParentForNotification(entry);
        }

        @Override
        public void resetExposedMenuView(boolean animate, boolean force) {
            mView.resetExposedMenuView(animate, force);
        }

        @Override
        public NotificationSwipeActionHelper getSwipeActionHelper() {
            return mSwipeHelper;
        }

        @Override
        public void cleanUpViewStateForEntry(NotificationEntry entry) {
            mView.cleanUpViewStateForEntry(entry);
        }

        @Override
        public void setChildLocationsChangedListener(
                NotificationLogger.OnChildLocationsChangedListener listener) {
            mView.setChildLocationsChangedListener(listener);
        }

        public boolean hasPulsingNotifications() {
            return mView.hasPulsingNotifications();
        }

        @Override
        public boolean isInVisibleLocation(NotificationEntry entry) {
            return mView.isInVisibleLocation(entry);
        }

        @Override
        public void onHeightChanged(ExpandableView view, boolean needsAnimation) {
            mView.onChildHeightChanged(view, needsAnimation);
        }

        @Override
        public void onReset(ExpandableView view) {
            mView.onChildHeightReset(view);
        }

        @Override
        public void bindRow(ExpandableNotificationRow row) {
            mView.bindRow(row);
        }

        @Override
        public void applyExpandAnimationParams(
                ActivityLaunchAnimator.ExpandAnimationParameters params) {
            mView.applyExpandAnimationParams(params);
        }

        @Override
        public void setExpandingNotification(ExpandableNotificationRow row) {
            mView.setExpandingNotification(row);
        }

        @Override
        public boolean containsView(View v) {
            return mView.containsView(v);
        }

        @Override
        public void setWillExpand(boolean willExpand) {
            mView.setWillExpand(willExpand);
        }
    }
}
