/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.keyguard.domain.interactor

import android.content.Context
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.data.repository.KeyguardSurfaceBehindRepository
import com.android.systemui.keyguard.domain.interactor.WindowManagerLockscreenVisibilityInteractor.Companion.isSurfaceVisible
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardSurfaceBehindModel
import com.android.systemui.util.kotlin.toPx
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Distance over which the surface behind the keyguard is animated in during a Y-translation
 * animation.
 */
const val SURFACE_TRANSLATION_Y_DISTANCE_DP = 250

@SysUISingleton
class KeyguardSurfaceBehindInteractor
@Inject
constructor(
    private val repository: KeyguardSurfaceBehindRepository,
    context: Context,
    transitionInteractor: KeyguardTransitionInteractor,
    inWindowLauncherUnlockAnimationInteractor: Lazy<InWindowLauncherUnlockAnimationInteractor>,
    swipeToDismissInteractor: SwipeToDismissInteractor,
) {
    /**
     * The view params to use for the surface. These params describe the alpha/translation values to
     * apply, as well as animation parameters if necessary.
     */
    val viewParams: Flow<KeyguardSurfaceBehindModel> =
        combine(
                transitionInteractor.startedKeyguardTransitionStep,
                transitionInteractor.currentKeyguardState,
            ) { startedStep, currentState ->
                // If we're in transition to GONE, special unlock animation params apply.
                if (startedStep.to == KeyguardState.GONE && currentState != KeyguardState.GONE) {
                    if (inWindowLauncherUnlockAnimationInteractor.get().isLauncherUnderneath()) {
                        // The Launcher icons have their own translation/alpha animations during the
                        // in-window animation. We'll just make the surface visible and let Launcher
                        // do its thing.
                        return@combine KeyguardSurfaceBehindModel(
                            alpha = 1f,
                        )
                    } else {
                        // Otherwise, animate a surface in via alpha/translation, and apply the
                        // swipe velocity (if available) to the translation spring.
                        return@combine KeyguardSurfaceBehindModel(
                            animateFromAlpha = 0f,
                            alpha = 1f,
                            animateFromTranslationY =
                                SURFACE_TRANSLATION_Y_DISTANCE_DP.toPx(context).toFloat(),
                            translationY = 0f,
                            startVelocity = swipeToDismissInteractor.dismissFling.value?.velocity
                                    ?: 0f,
                        )
                    }
                }

                // Default to the visibility of the current state, with no animations.
                KeyguardSurfaceBehindModel(alpha = if (isSurfaceVisible(currentState)) 1f else 0f)
            }
            .distinctUntilChanged()

    val isAnimatingSurface = repository.isAnimatingSurface

    fun setAnimatingSurface(animating: Boolean) {
        repository.setAnimatingSurface(animating)
    }

    fun setSurfaceRemoteAnimationTargetAvailable(available: Boolean) {
        repository.setSurfaceRemoteAnimationTargetAvailable(available)
    }
}
