/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.wm.shell.unfold;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.view.SurfaceControl;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.unfold.animation.DuoUnfoldGeometry;
import com.android.wm.shell.unfold.animation.FullscreenUnfoldTaskAnimator;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class FullscreenUnfoldTaskAnimatorDuoTest {
    private final SurfaceControl.Transaction mTransaction = mock(SurfaceControl.Transaction.class);
    private int mStyle = UnfoldAnimationStyleProvider.STYLE_PIXEL;
    private FullscreenUnfoldTaskAnimator mAnimator;

    @Before
    public void setUp() {
        when(mTransaction.setWindowCrop(any(), any())).thenReturn(mTransaction);
        when(mTransaction.setMatrix(any(), any(Matrix.class), any())).thenReturn(mTransaction);
        when(mTransaction.setMatrix(any(), anyFloat(), anyFloat(), anyFloat(), anyFloat()))
                .thenReturn(mTransaction);
        when(mTransaction.setCornerRadius(any(), anyFloat())).thenReturn(mTransaction);
        when(mTransaction.setPosition(any(), anyFloat(), anyFloat())).thenReturn(mTransaction);
        when(mTransaction.show(any())).thenReturn(mTransaction);
        mAnimator = new FullscreenUnfoldTaskAnimator(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                mock(UnfoldBackgroundController.class),
                mock(ShellController.class),
                mock(DisplayInsetsController.class),
                Optional.of(() -> mStyle));
    }

    @Test
    public void homeTask_isApplicable_butInactiveOutsideDuo() {
        ActivityManager.RunningTaskInfo home = task(1, ACTIVITY_TYPE_HOME);
        assertThat(mAnimator.isApplicableTask(home)).isTrue();
        mAnimator.onTaskAppeared(home, mock(SurfaceControl.class));

        assertThat(mAnimator.hasActiveTasks()).isFalse();
        mStyle = UnfoldAnimationStyleProvider.STYLE_DUO;
        assertThat(mAnimator.hasActiveTasks()).isTrue();
    }

    @Test
    public void pixel_doesNotTouchHomeTask() {
        SurfaceControl leash = mock(SurfaceControl.class);
        ActivityManager.RunningTaskInfo home = task(1, ACTIVITY_TYPE_HOME);
        mAnimator.onTaskAppeared(home, leash);

        mAnimator.applyAnimationProgress(0.3f, mTransaction);
        mAnimator.resetAllSurfaces(mTransaction);
        mAnimator.resetSurface(home, mTransaction);

        verify(mTransaction, never()).setMatrix(eq(leash), any(Matrix.class), any());
        verify(mTransaction, never()).setMatrix(eq(leash), anyFloat(), anyFloat(), anyFloat(),
                anyFloat());
        verify(mTransaction, never()).setWindowCrop(eq(leash), any());
        verify(mTransaction, never()).setPosition(eq(leash), anyFloat(), anyFloat());
    }

    @Test
    public void duo_squeezesHorizontallyOnlyAtStart() {
        mStyle = UnfoldAnimationStyleProvider.STYLE_DUO;
        SurfaceControl leash = mock(SurfaceControl.class);
        mAnimator.onTaskAppeared(task(1, ACTIVITY_TYPE_STANDARD), leash);

        mAnimator.applyAnimationProgress(0f, mTransaction);

        ArgumentCaptor<Matrix> matrix = ArgumentCaptor.forClass(Matrix.class);
        verify(mTransaction).setMatrix(eq(leash), matrix.capture(), any());
        float[] values = new float[9];
        matrix.getValue().getValues(values);
        assertThat(values[Matrix.MSCALE_X]).isWithin(1e-4f).of(DuoUnfoldGeometry.scaleX(0f));
        assertThat(values[Matrix.MSCALE_Y]).isWithin(1e-4f).of(1f);
        verify(mTransaction).setWindowCrop(leash, new Rect(0, 0, 2268, 2440));
    }

    @Test
    public void duo_startTransaction_squeezesBeforeFirstProgress() {
        mStyle = UnfoldAnimationStyleProvider.STYLE_DUO;
        SurfaceControl app = mock(SurfaceControl.class);
        SurfaceControl home = mock(SurfaceControl.class);
        mAnimator.onTaskAppeared(task(1, ACTIVITY_TYPE_STANDARD), app);
        mAnimator.onTaskAppeared(task(2, ACTIVITY_TYPE_HOME), home);

        mAnimator.prepareStartTransaction(mTransaction);

        for (SurfaceControl leash : new SurfaceControl[] {app, home}) {
            ArgumentCaptor<Matrix> matrix = ArgumentCaptor.forClass(Matrix.class);
            verify(mTransaction).setMatrix(eq(leash), matrix.capture(), any());
            float[] values = new float[9];
            matrix.getValue().getValues(values);
            assertThat(values[Matrix.MSCALE_X]).isWithin(1e-4f).of(DuoUnfoldGeometry.scaleX(0f));
        }
    }

    @Test
    public void pixel_startTransaction_leavesTasksUntransformed() {
        SurfaceControl app = mock(SurfaceControl.class);
        mAnimator.onTaskAppeared(task(1, ACTIVITY_TYPE_STANDARD), app);

        mAnimator.prepareStartTransaction(mTransaction);

        verify(mTransaction, never()).setMatrix(eq(app), any(Matrix.class), any());
    }

    @Test
    public void duo_resetSurface_restoresIdentityForHome() {
        mStyle = UnfoldAnimationStyleProvider.STYLE_DUO;
        SurfaceControl leash = mock(SurfaceControl.class);
        ActivityManager.RunningTaskInfo home = task(1, ACTIVITY_TYPE_HOME);
        mAnimator.onTaskAppeared(home, leash);
        mAnimator.applyAnimationProgress(0f, mTransaction);

        mAnimator.resetSurface(home, mTransaction);

        verify(mTransaction).setMatrix(leash, 1.0F, 0.0F, 0.0F, 1.0F);
        verify(mTransaction).setWindowCrop(leash, null);
    }

    private static ActivityManager.RunningTaskInfo task(int id, int activityType) {
        ActivityManager.RunningTaskInfo info = new ActivityManager.RunningTaskInfo();
        info.taskId = id;
        info.isVisible = true;
        info.realActivity = new ComponentName("p", "c");
        info.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        info.configuration.windowConfiguration.setActivityType(activityType);
        info.configuration.windowConfiguration.setBounds(new Rect(0, 0, 2268, 2440));
        info.positionInParent.set(0, 0);
        return info;
    }
}
