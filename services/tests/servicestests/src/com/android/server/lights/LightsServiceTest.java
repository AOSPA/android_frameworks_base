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

package com.android.server.lights;

import static android.graphics.Color.BLACK;
import static android.graphics.Color.BLUE;
import static android.graphics.Color.GREEN;
import static android.graphics.Color.RED;
import static android.graphics.Color.TRANSPARENT;
import static android.graphics.Color.WHITE;
import static android.graphics.Color.YELLOW;
import static android.hardware.lights.LightsRequest.Builder;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.content.Context;
import android.hardware.light.BrightnessMode;
import android.hardware.light.FlashMode;
import android.hardware.light.HwLight;
import android.hardware.light.HwLightEffect;
import android.hardware.light.HwLightState;
import android.hardware.light.ILights;
import android.hardware.light.InterpolationType;
import android.hardware.light.LightType;
import android.hardware.lights.ColorSequence;
import android.hardware.lights.Light;
import android.hardware.lights.LightState;
import android.hardware.lights.LightsManager;
import android.hardware.lights.LightsRequest;
import android.hardware.lights.MultiLightEffect;
import android.hardware.lights.SystemLightsManager;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.PermissionEnforcer;
import android.os.TestLooperManager;
import android.os.test.FakePermissionEnforcer;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.lights.feature.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.List;
import java.util.stream.Collectors;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class LightsServiceTest {
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Rule public MockitoRule mocks = MockitoJUnit.rule();

    private static final int HIGH_PRIORITY = Integer.MAX_VALUE;
    private static final int DEFAULT_PRIORITY = 0;
    private static final int MAX_EFFECT_QUEUE_SIZE = 10;

    // Index of the first animated light available.
    private static final int ANIMATED_LIGHT_INDEX = 1;

    public static class TestLightsHal extends ILights.Stub {
        @Override
        public void setLightState(int id, HwLightState state) {
        }

        @Override
        public HwLight[] getLights() {
            return new HwLight[] {
                    fakeHwLight(101, 3, 1, 0),
                    fakeHwLight(102, LightsManager.LIGHT_TYPE_MICROPHONE, 4, 0),
                    fakeHwLight(103, LightsManager.LIGHT_TYPE_MICROPHONE, 3, 33),
                    fakeHwLight(104, LightsManager.LIGHT_TYPE_MICROPHONE, 1, 33),
                    fakeHwLight(105, LightsManager.LIGHT_TYPE_MICROPHONE, 2, 33)
            };
        }

        @Override
        public void setLightEffects(HwLightEffect[] effects) {
        }

        @Override
        public int getInterfaceVersion() {
            return this.VERSION;
        }

        @Override
        public String getInterfaceHash() {
            return this.HASH;
        }
    }

    @Spy
    private final TestLightsHal mHal = new TestLightsHal();

    private static HwLight fakeHwLight(int id, int type, int ordinal, int updatePeriodMillis) {
        HwLight light = new HwLight();
        light.id = id;
        light.type = (byte) type;
        light.ordinal = ordinal;
        light.minUpdatePeriodMillis = updatePeriodMillis;
        return light;
    }

    @Mock
    Context mContext;
    private HandlerThread mServiceThread;
    private TestLooperManager mTestLooperManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        // The AIDL stub will use PermissionEnforcer to check permission from the caller.
        FakePermissionEnforcer permissionEnforcer = new FakePermissionEnforcer();
        permissionEnforcer.grant(Manifest.permission.CONTROL_DEVICE_LIGHTS);
        doReturn(Context.PERMISSION_ENFORCER_SERVICE).when(mContext).getSystemServiceName(
                eq(PermissionEnforcer.class));
        doReturn(permissionEnforcer).when(mContext).getSystemService(
                eq(Context.PERMISSION_ENFORCER_SERVICE));

        mServiceThread = new HandlerThread("MockUiThread");
        mServiceThread.start();
        mTestLooperManager =
                InstrumentationRegistry.getInstrumentation()
                        .acquireLooperManager(mServiceThread.getLooper());
    }

    @After
    public void tearDown() {
        mTestLooperManager.release();
        mServiceThread.quit();
    }

    private void consumeAllTasks() {
        Message m = mTestLooperManager.poll();
        while (m != null) {
            mTestLooperManager.execute(m);
            m = mTestLooperManager.poll();
        }
    }

    @Test
    public void testGetLights_filtersSystemLights() {
        LightsService service = new LightsService(mContext, () -> mHal, Looper.getMainLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);

        // When lights are listed, only the 4 MICROPHONE lights should be visible.
        assertThat(manager.getLights().size()).isEqualTo(4);
    }

    /** Verifies that all system-only light types are filtered from the LightsManager API. */
    @Test
    public void testGetLights_filtersAllSystemLightTypes() {
        // Report one of each light type, to test filtering.
        when(mHal.getLights()).thenReturn(new HwLight[]{
                        // System lights that should be filtered
                        fakeHwLight(1, LightType.BACKLIGHT, 1, 0),
                        fakeHwLight(2, LightType.KEYBOARD, 1, 0),
                        fakeHwLight(3, LightType.BUTTONS, 1, 0),
                        fakeHwLight(4, LightType.BATTERY, 1, 0),
                        fakeHwLight(5, LightType.NOTIFICATIONS, 1, 0),
                        fakeHwLight(6, LightType.ATTENTION, 1, 0),
                        fakeHwLight(7, LightType.BLUETOOTH, 1, 0),
                        fakeHwLight(8, LightType.WIFI, 1, 0),
                        // Non-system lights that should be available to apps
                        fakeHwLight(9, Light.LIGHT_TYPE_MICROPHONE, 1, 0),
                        fakeHwLight(10, Light.LIGHT_TYPE_APPLICATION, 1, 0),
                        // A light with a type that is not defined in LightType, but is not a system
                        // light
                        fakeHwLight(100, 100, 1, 0)});

        LightsService service = new LightsService(mContext, () -> mHal, Looper.getMainLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);

        // When lights are listed
        List<Light> availableLights = manager.getLights();
        List<Integer> availableLightIds = availableLights.stream()
                .map(Light::getId)
                .collect(Collectors.toList());

        // Then only the non-system lights should be available
        assertThat(availableLightIds).containsExactly(9, 10, 100);
    }

    @Test
    public void testControlMultipleLights() {
        LightsService service = new LightsService(mContext, () -> mHal, Looper.getMainLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);

        // When the session requests to turn 3/4 lights on:
        LightsManager.LightsSession session = manager.openSession();
        session.requestLights(new Builder()
                .addLight(manager.getLights().get(0), new LightState(0xf1))
                .addLight(manager.getLights().get(1), new LightState(0xf2))
                .addLight(manager.getLights().get(2), new LightState(0xf3))
                .build());

        // Then all 3 should turn on.
        assertThat(manager.getLightState(manager.getLights().get(0)).getColor()).isEqualTo(0xf1);
        assertThat(manager.getLightState(manager.getLights().get(1)).getColor()).isEqualTo(0xf2);
        assertThat(manager.getLightState(manager.getLights().get(2)).getColor()).isEqualTo(0xf3);

        // And the 4th should remain off.
        assertThat(manager.getLightState(manager.getLights().get(3)).getColor()).isEqualTo(0x00);
    }

    @Test
    public void testControlLights_onlyEffectiveForLifetimeOfClient() throws Exception {
        LightsService service = new LightsService(mContext, () -> mHal, Looper.getMainLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);
        Light micLight = manager.getLights().get(0);

        // The light should begin by being off.
        assertThat(manager.getLightState(micLight).getColor()).isEqualTo(TRANSPARENT);

        // When a session commits changes:
        LightsManager.LightsSession session = manager.openSession();
        session.requestLights(new Builder().addLight(micLight, new LightState(GREEN)).build());
        // Then the light should turn on.
        assertThat(manager.getLightState(micLight).getColor()).isEqualTo(GREEN);

        // When the session goes away:
        session.close();

        // Then the light should turn off.
        assertThat(manager.getLightState(micLight).getColor()).isEqualTo(TRANSPARENT);
    }

    @Test
    public void testControlLights_firstCallerWinsContention() throws Exception {
        LightsService service = new LightsService(mContext, () -> mHal, Looper.getMainLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);
        Light micLight = manager.getLights().get(0);

        LightsManager.LightsSession session1 = manager.openSession();
        LightsManager.LightsSession session2 = manager.openSession();

        // When session1 and session2 both request the same light:
        session1.requestLights(new Builder().addLight(micLight, new LightState(BLUE)).build());
        session2.requestLights(new Builder().addLight(micLight, new LightState(WHITE)).build());
        // Then session1 should win because it was created first.
        assertThat(manager.getLightState(micLight).getColor()).isEqualTo(BLUE);

        // When session1 goes away:
        session1.close();

        // Then session2 should have its request go into effect.
        assertThat(manager.getLightState(micLight).getColor()).isEqualTo(WHITE);

        // When session2 goes away:
        session2.close();

        // Then the light should turn off because there are no more sessions.
        assertThat(manager.getLightState(micLight).getColor()).isEqualTo(0);
    }

    @Test
    public void testClearLight() {
        LightsService service = new LightsService(mContext, () -> mHal, Looper.getMainLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);
        Light micLight = manager.getLights().get(0);

        // When the session turns a light on:
        LightsManager.LightsSession session = manager.openSession();
        session.requestLights(new Builder().addLight(micLight, new LightState(WHITE)).build());

        // And then the session clears it again:
        session.requestLights(new Builder().clearLight(micLight).build());

        // Then the light should turn back off.
        assertThat(manager.getLightState(micLight).getColor()).isEqualTo(0);
    }

    @Test
    public void testControlLights_higherPriorityCallerWinsContention() throws Exception {
        LightsService service = new LightsService(mContext, () -> mHal, Looper.getMainLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);
        Light micLight = manager.getLights().get(0);

        // The light should begin by being off.
        assertThat(manager.getLightState(micLight).getColor()).isEqualTo(TRANSPARENT);

        try (LightsManager.LightsSession session1 = manager.openSession(DEFAULT_PRIORITY)) {
            try (LightsManager.LightsSession session2 = manager.openSession(HIGH_PRIORITY)) {
                // When session1 and session2 both request the same light:
                session1.requestLights(
                        new Builder().addLight(micLight, new LightState(BLUE)).build());
                session2.requestLights(
                        new Builder().addLight(micLight, new LightState(WHITE)).build());
                // Then session2 should win because it has a higher priority.
                assertThat(manager.getLightState(micLight).getColor()).isEqualTo(WHITE);
            }
            // Then session1 should have its request go into effect.
            assertThat(manager.getLightState(micLight).getColor()).isEqualTo(BLUE);
        }
        // Then the light should turn off because there are no more sessions.
        assertThat(manager.getLightState(micLight).getColor()).isEqualTo(TRANSPARENT);
    }

    @Test
    public void testControlLights_multipleSessionsWithDifferentPriorities() throws Exception {
        LightsService service = new LightsService(mContext, () -> mHal, Looper.getMainLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);
        List<Light> lights = manager.getLights();
        Light micLight1 = lights.get(0);
        Light micLight2 = lights.get(1);
        Light micLight3 = lights.get(2);

        // The lights should begin by being off.
        assertThat(manager.getLightState(micLight1).getColor()).isEqualTo(TRANSPARENT);
        assertThat(manager.getLightState(micLight2).getColor()).isEqualTo(TRANSPARENT);
        assertThat(manager.getLightState(micLight3).getColor()).isEqualTo(TRANSPARENT);

        LightsManager.LightsSession lowPrioritySession = manager.openSession(DEFAULT_PRIORITY);
        LightsManager.LightsSession mediumPrioritySession = manager.openSession(
                DEFAULT_PRIORITY + 100);
        LightsManager.LightsSession highPrioritySession = manager.openSession(HIGH_PRIORITY);

        // Low priority session requests two lights.
        lowPrioritySession.requestLights(new Builder()
                .addLight(micLight1, new LightState(BLUE))
                .addLight(micLight2, new LightState(BLUE))
                .build());
        assertThat(manager.getLightState(micLight1).getColor()).isEqualTo(BLUE);
        assertThat(manager.getLightState(micLight2).getColor()).isEqualTo(BLUE);
        assertThat(manager.getLightState(micLight3).getColor()).isEqualTo(TRANSPARENT);

        // High priority session requests one of the lights, overriding the low priority session.
        highPrioritySession.requestLights(new Builder()
                .addLight(micLight1, new LightState(WHITE))
                .build());
        assertThat(manager.getLightState(micLight1).getColor()).isEqualTo(WHITE);
        assertThat(manager.getLightState(micLight2).getColor()).isEqualTo(BLUE);
        assertThat(manager.getLightState(micLight3).getColor()).isEqualTo(TRANSPARENT);

        // Medium priority session requests another light and a new one.
        mediumPrioritySession.requestLights(new Builder()
                .addLight(micLight2, new LightState(GREEN))
                .addLight(micLight3, new LightState(GREEN))
                .build());
        assertThat(manager.getLightState(micLight1).getColor()).isEqualTo(WHITE);
        assertThat(manager.getLightState(micLight2).getColor()).isEqualTo(GREEN);
        assertThat(manager.getLightState(micLight3).getColor()).isEqualTo(GREEN);

        lowPrioritySession.requestLights(new Builder()
                .addLight(micLight1, new LightState(RED))
                .build());

        // High priority session closes, medium and low priority requests should take effect.
        highPrioritySession.close();
        assertThat(manager.getLightState(micLight1).getColor()).isEqualTo(RED); // low takes over
        assertThat(manager.getLightState(micLight2).getColor()).isEqualTo(GREEN); // medium has it
        assertThat(manager.getLightState(micLight3).getColor()).isEqualTo(GREEN); // medium has it

        // Medium priority session closes, low priority requests should take effect.
        mediumPrioritySession.close();
        assertThat(manager.getLightState(micLight1).getColor()).isEqualTo(RED); // low has it
        assertThat(manager.getLightState(micLight2).getColor()).isEqualTo(BLUE); // low takes over
        assertThat(manager.getLightState(micLight3).getColor()).isEqualTo(TRANSPARENT);

        // Low priority session closes, all lights should be off.
        lowPrioritySession.close();
        assertThat(manager.getLightState(micLight1).getColor()).isEqualTo(TRANSPARENT);
        assertThat(manager.getLightState(micLight2).getColor()).isEqualTo(TRANSPARENT);
        assertThat(manager.getLightState(micLight3).getColor()).isEqualTo(TRANSPARENT);
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_LIGHT_ANIMATIONS})
    public void testControlLights_animationCapabilityLights() throws Exception {
        LightsService service = new LightsService(mContext, () -> mHal, Looper.getMainLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);

        assertThat(manager.getLights().get(0).hasAnimationControl()).isFalse();
        assertThat(manager.getLights().get(0).hasRgbControl()).isFalse();
        assertThat(manager.getLights().get(0).getMinUpdatePeriodMillis()).isEqualTo(0);

        assertThat(manager.getLights().get(1).hasAnimationControl()).isTrue();
        assertThat(manager.getLights().get(1).hasRgbControl()).isTrue();
        assertThat(manager.getLights().get(1).getMinUpdatePeriodMillis()).isEqualTo(33);

        assertThat(manager.getLights().get(2).hasAnimationControl()).isTrue();
        assertThat(manager.getLights().get(2).hasRgbControl()).isTrue();
        assertThat(manager.getLights().get(2).getMinUpdatePeriodMillis()).isEqualTo(33);

        assertThat(manager.getLights().get(3).hasAnimationControl()).isTrue();
        assertThat(manager.getLights().get(3).hasRgbControl()).isTrue();
        assertThat(manager.getLights().get(3).getMinUpdatePeriodMillis()).isEqualTo(33);

    }

    @Test
    @DisableFlags({Flags.FLAG_ENABLE_LIGHT_ANIMATIONS})
    public void testControlLights_animationCapabilityLights_flagOff() throws Exception {
        LightsService service = new LightsService(mContext, () -> mHal, Looper.getMainLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);

        assertThat(manager.getLights().get(0).hasAnimationControl()).isFalse();
        assertThat(manager.getLights().get(0).hasRgbControl()).isFalse();
        assertThat(manager.getLights().get(0).getMinUpdatePeriodMillis()).isEqualTo(0);

        assertThat(manager.getLights().get(1).hasAnimationControl()).isFalse();
        assertThat(manager.getLights().get(1).hasRgbControl()).isFalse();
        assertThat(manager.getLights().get(1).getMinUpdatePeriodMillis()).isEqualTo(0);

        assertThat(manager.getLights().get(2).hasAnimationControl()).isFalse();
        assertThat(manager.getLights().get(2).hasRgbControl()).isFalse();
        assertThat(manager.getLights().get(1).getMinUpdatePeriodMillis()).isEqualTo(0);

        assertThat(manager.getLights().get(3).hasAnimationControl()).isFalse();
        assertThat(manager.getLights().get(3).hasRgbControl()).isFalse();
        assertThat(manager.getLights().get(1).getMinUpdatePeriodMillis()).isEqualTo(0);
    }

    @Test
    public void testControlLights_exceptionSettingEffectOnV2Hal() throws Exception {
        // Force the spy to return 2 as the current version number.
        when(mHal.getInterfaceVersion()).thenReturn(2);

        LightsService service = new LightsService(mContext, () -> mHal, Looper.getMainLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);

        try (LightsManager.LightsSession session = manager.openSession()) {
            assertThrows(IllegalArgumentException.class, () -> {
                session.requestLights(
                        new LightsRequest.Builder().setEffect(
                                new MultiLightEffect.Builder().addLightSequence(
                                        manager.getLights().get(ANIMATED_LIGHT_INDEX),
                                        new ColorSequence.Builder()
                                                .addControlPoint(100, BLUE)
                                                .build())
                                        .build())
                                .build());
            });
        }
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_LIGHT_ANIMATIONS})
    public void testControlLights_setSimpleEffect() throws Exception {
        LightsService service = new LightsService(mContext, () -> mHal, mServiceThread.getLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);
        Light light = manager.getLights().get(ANIMATED_LIGHT_INDEX);
        ArgumentCaptor<HwLightEffect[]> effectCaptor =
                ArgumentCaptor.forClass(HwLightEffect[].class);
        ArgumentCaptor<HwLightState> stateCaptor =
                ArgumentCaptor.forClass(HwLightState.class);

        // Create a simple effect for a light that supports effects.
        MultiLightEffect effect = new MultiLightEffect.Builder()
                .addLightSequence(
                        light, new ColorSequence.Builder().addControlPoint(100, BLUE).build())
                .build();

        ColorSequence sequence;
        try (LightsManager.LightsSession session = manager.openSession()) {
            session.requestLights(new LightsRequest.Builder().setEffect(effect).build());

            // Request the color sequence within the session.
            sequence = manager.getLightSequence(light);

            consumeAllTasks();
        }

        // Validate the internal state of the service.
        assertThat(sequence.getColors().length).isEqualTo(1);
        assertThat(sequence.getColors()[0]).isEqualTo(BLUE);
        assertThat(sequence.getDelaysMillis().length).isEqualTo(1);
        assertThat(sequence.getDelaysMillis()[0]).isEqualTo(100);

        // Validate that the service sent the hal the expected content.
        verify(mHal, times(1)).setLightEffects(effectCaptor.capture());
        HwLightEffect[] capturedEffects = effectCaptor.getValue();
        assertThat(capturedEffects.length).isEqualTo(1);
        assertThat(capturedEffects[0].lightId).isEqualTo(light.getId());
        assertThat(capturedEffects[0].frames).asList().containsExactly(3);
        assertThat(capturedEffects[0].colors).asList().containsExactly(BLUE);
        assertThat(capturedEffects[0].preemptive).isTrue();
        assertThat(capturedEffects[0].iterations).isEqualTo(1);
        assertThat(capturedEffects[0].interpolationType).isEqualTo(InterpolationType.LINEAR);
        assertThat(capturedEffects[0].framePeriodMillis)
                .isEqualTo(light.getMinUpdatePeriodMillis());

        // After the session closes, the light is turned off.
        verify(mHal).setLightState(eq(light.getId()), stateCaptor.capture());
        HwLightState finalState = stateCaptor.getValue();
        assertThat(finalState.color).isEqualTo(0);
        assertThat(finalState.brightnessMode).isEqualTo(BrightnessMode.USER);
        assertThat(finalState.flashMode).isEqualTo(FlashMode.NONE);
        assertThat(finalState.flashOnMs).isEqualTo(0);
        assertThat(finalState.flashOffMs).isEqualTo(0);
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_LIGHT_ANIMATIONS})
    public void testControlLights_setEffectUnsupportedByLight() throws Exception {
        LightsService service = new LightsService(mContext, () -> mHal, Looper.getMainLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);
        Light light = manager.getLights().get(0);

        assertThat(light.hasAnimationControl()).isFalse();

        // Create a simple effect for a light that supports effects.
        MultiLightEffect.Builder effectBuilder = new MultiLightEffect.Builder();

        assertThrows(IllegalArgumentException.class, () -> {
            effectBuilder.addLightSequence(
                    light, new ColorSequence.Builder().addControlPoint(100, BLUE).build());
        });
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_LIGHT_ANIMATIONS})
    public void testControlLights_setEffect_overfillingEffectQueueThrows() throws Exception {
        LightsService service = new LightsService(mContext, () -> mHal, Looper.getMainLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);

        // Create a simple non-preemptive effect for a light that supports effects.
        MultiLightEffect effect = new MultiLightEffect.Builder()
                .addLightSequence(
                       manager.getLights().get(1),
                       new ColorSequence.Builder().addControlPoint(100, BLUE).build())
                .setPreemptive(false)
                .build();

        try (LightsManager.LightsSession session = manager.openSession()) {
            for (int i = 0; i < MAX_EFFECT_QUEUE_SIZE; i++) {
                session.requestLights(new LightsRequest.Builder().setEffect(effect).build());
            }
            assertThrows(IllegalStateException.class, () -> {
                session.requestLights(new LightsRequest.Builder().setEffect(effect).build());
            });
        }
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_LIGHT_ANIMATIONS})
    public void testControlLights_setEffect_preemptPreviousEffect() throws Exception {
        LightsService service = new LightsService(mContext, () -> mHal, mServiceThread.getLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);
        Light light = manager.getLights().get(ANIMATED_LIGHT_INDEX);
        ArgumentCaptor<HwLightEffect[]> effectCaptor =
                ArgumentCaptor.forClass(HwLightEffect[].class);

        // Create two simple preemptive effects.
        MultiLightEffect effect1 = new MultiLightEffect.Builder()
                .addLightSequence(
                        light, new ColorSequence.Builder().addControlPoint(100, BLUE).build())
                .setPreemptive(true)
                .build();
        MultiLightEffect effect2 = new MultiLightEffect.Builder()
                .addLightSequence(
                        light, new ColorSequence.Builder().addControlPoint(100, YELLOW).build())
                .setPreemptive(true)
                .build();

        try (LightsManager.LightsSession session = manager.openSession()) {
            // Set effect1 and validate it took effect.
            session.requestLights(new LightsRequest.Builder().setEffect(effect1).build());

            ColorSequence sequence = manager.getLightSequence(light);
            assertThat(sequence.getColors()[0]).isEqualTo(BLUE);

            // Set effect2 and validate that the effect was applied immediately.
            session.requestLights(new LightsRequest.Builder().setEffect(effect2).build());

            sequence = manager.getLightSequence(light);
            assertThat(sequence.getColors()[0]).isEqualTo(YELLOW);

            consumeAllTasks();
        }

        // Validate that the service sent the hal the expected content.
        verify(mHal, times(2)).setLightEffects(effectCaptor.capture());
        List<HwLightEffect[]> effectInteractions = effectCaptor.getAllValues();

        assertThat(effectInteractions.get(0).length).isEqualTo(1);
        assertThat(effectInteractions.get(0)[0].lightId).isEqualTo(light.getId());
        assertThat(effectInteractions.get(0)[0].frames).asList().containsExactly(3);
        assertThat(effectInteractions.get(0)[0].colors).asList().containsExactly(BLUE);
        assertThat(effectInteractions.get(0)[0].preemptive).isTrue();
        assertThat(effectInteractions.get(0)[0].iterations).isEqualTo(1);
        assertThat(effectInteractions.get(0)[0].interpolationType)
                .isEqualTo(InterpolationType.LINEAR);
        assertThat(effectInteractions.get(0)[0].framePeriodMillis)
                .isEqualTo(light.getMinUpdatePeriodMillis());

        assertThat(effectInteractions.get(1).length).isEqualTo(1);
        assertThat(effectInteractions.get(1)[0].lightId).isEqualTo(light.getId());
        assertThat(effectInteractions.get(1)[0].frames).asList().containsExactly(3);
        assertThat(effectInteractions.get(1)[0].colors).asList().containsExactly(YELLOW);
        assertThat(effectInteractions.get(1)[0].preemptive).isTrue();
        assertThat(effectInteractions.get(1)[0].iterations).isEqualTo(1);
        assertThat(effectInteractions.get(1)[0].interpolationType)
                .isEqualTo(InterpolationType.LINEAR);
        assertThat(effectInteractions.get(1)[0].framePeriodMillis)
                .isEqualTo(light.getMinUpdatePeriodMillis());
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_LIGHT_ANIMATIONS})
    public void testControlLights_setEffect_appendEffect() throws Exception {
        InOrder inOrder = inOrder(mHal);
        LightsService service = new LightsService(mContext, () -> mHal, mServiceThread.getLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);
        Light light = manager.getLights().get(ANIMATED_LIGHT_INDEX);
        ArgumentCaptor<HwLightEffect[]> effectCaptor =
                ArgumentCaptor.forClass(HwLightEffect[].class);

        // Create a simple effect and a continuation.
        MultiLightEffect effect1 = new MultiLightEffect.Builder()
                .addLightSequence(
                        light, new ColorSequence.Builder().addControlPoint(100, BLUE).build())
                .setPreemptive(true)
                .build();
        MultiLightEffect effect2 = new MultiLightEffect.Builder()
                .addLightSequence(
                        light, new ColorSequence.Builder().addControlPoint(100, YELLOW).build())
                .setPreemptive(false)
                .build();

        try (LightsManager.LightsSession session = manager.openSession()) {
            // Set effect1 and validate the configuration.
            session.requestLights(new LightsRequest.Builder().setEffect(effect1).build());

            ColorSequence sequence = manager.getLightSequence(light);
            assertThat(sequence.getColors()[0]).isEqualTo(BLUE);

            // Set effect2 and validate that the effect did not apply immediately.
            session.requestLights(new LightsRequest.Builder().setEffect(effect2).build());

            sequence = manager.getLightSequence(light);
            assertThat(sequence.getColors()[0]).isEqualTo(BLUE);

            // Validate that the service sent the hal the expected content.
            inOrder.verify(mHal, times(1)).setLightEffects(effectCaptor.capture());
            HwLightEffect[] effectInteractions = effectCaptor.getValue();

            assertThat(effectInteractions.length).isEqualTo(1);
            assertThat(effectInteractions[0].lightId).isEqualTo(light.getId());
            assertThat(effectInteractions[0].frames).asList().containsExactly(3);
            assertThat(effectInteractions[0].colors).asList().containsExactly(BLUE);
            assertThat(effectInteractions[0].preemptive).isTrue();
            assertThat(effectInteractions[0].iterations).isEqualTo(1);
            assertThat(effectInteractions[0].interpolationType)
                    .isEqualTo(InterpolationType.LINEAR);
            assertThat(effectInteractions[0].framePeriodMillis)
                    .isEqualTo(light.getMinUpdatePeriodMillis());

            consumeAllTasks();

            inOrder.verify(mHal, times(1)).setLightEffects(effectCaptor.capture());
            effectInteractions = effectCaptor.getValue();

            assertThat(effectInteractions.length).isEqualTo(1);
            assertThat(effectInteractions[0].lightId).isEqualTo(light.getId());
            assertThat(effectInteractions[0].colors).asList().containsExactly(YELLOW);
        }
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_LIGHT_ANIMATIONS})
    public void testControlLights_setState_cancelsSameSessionEffect() throws Exception {
        LightsService service = new LightsService(mContext, () -> mHal, mServiceThread.getLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);
        Light light1 = manager.getLights().get(ANIMATED_LIGHT_INDEX);
        Light light2 = manager.getLights().get(2);
        ArgumentCaptor<HwLightEffect[]> effectCaptor =
                ArgumentCaptor.forClass(HwLightEffect[].class);

        // Create a simple effect on multiple lights.
        MultiLightEffect effect1 = new MultiLightEffect.Builder()
                .addLightSequence(
                        light1, new ColorSequence.Builder().addControlPoint(100, BLUE).build())
                .addLightSequence(
                        light2, new ColorSequence.Builder().addControlPoint(100, YELLOW).build())
                .build();

        try (LightsManager.LightsSession session = manager.openSession()) {
            // Set effect1 and validate the configuration.
            session.requestLights(new LightsRequest.Builder().setEffect(effect1).build());

            ColorSequence sequence = manager.getLightSequence(light1);
            assertThat(sequence.getColors()[0]).isEqualTo(BLUE);
            sequence = manager.getLightSequence(light2);
            assertThat(sequence.getColors()[0]).isEqualTo(YELLOW);

            // Set light 1 to a static state. Should cancel the effect.
            session.requestLights(new LightsRequest.Builder().addLight(
                    light1, new LightState.Builder().setColor(RED).build()).build());

            sequence = manager.getLightSequence(light1);
            assertThat(sequence).isNull();
            sequence = manager.getLightSequence(light2);
            assertThat(sequence).isNull();

            assertThat(manager.getLightState(light1).getColor()).isEqualTo(RED);
            assertThat(manager.getLightState(light2).getColor()).isEqualTo(TRANSPARENT);

            // Validate that the service sent the hal the expected content.
            verify(mHal, times(1)).setLightEffects(effectCaptor.capture());
            HwLightEffect[] effectInteractions = effectCaptor.getValue();

            assertThat(effectInteractions.length).isEqualTo(2);
            assertThat(effectInteractions[0].lightId).isEqualTo(light1.getId());
            assertThat(effectInteractions[0].frames).asList().containsExactly(3);
            assertThat(effectInteractions[0].colors).asList().containsExactly(BLUE);
            assertThat(effectInteractions[0].preemptive).isTrue();
            assertThat(effectInteractions[0].iterations).isEqualTo(1);
            assertThat(effectInteractions[0].interpolationType)
                    .isEqualTo(InterpolationType.LINEAR);
            assertThat(effectInteractions[0].framePeriodMillis)
                    .isEqualTo(light1.getMinUpdatePeriodMillis());

            assertThat(effectInteractions[1].lightId).isEqualTo(light2.getId());
            assertThat(effectInteractions[1].frames).asList().containsExactly(3);
            assertThat(effectInteractions[1].colors).asList().containsExactly(YELLOW);
            assertThat(effectInteractions[1].preemptive).isTrue();
            assertThat(effectInteractions[1].iterations).isEqualTo(1);
            assertThat(effectInteractions[1].interpolationType)
                    .isEqualTo(InterpolationType.LINEAR);
            assertThat(effectInteractions[1].framePeriodMillis)
                    .isEqualTo(light2.getMinUpdatePeriodMillis());
        }
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_LIGHT_ANIMATIONS})
    public void testControlLights_effectAndStatePriorityInteractions() throws Exception {
        LightsService service = new LightsService(mContext, () -> mHal, mServiceThread.getLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);
        Light light1 = manager.getLights().get(1);
        Light light2 = manager.getLights().get(2);
        Light light3 = manager.getLights().get(3);

        // Create a simple effect on multiple lights.
        MultiLightEffect effect = new MultiLightEffect.Builder()
                .addLightSequence(
                        light1, new ColorSequence.Builder().addControlPoint(100, BLUE).build())
                .addLightSequence(
                        light2, new ColorSequence.Builder().addControlPoint(100, YELLOW).build())
                .addLightSequence(
                        light3, new ColorSequence.Builder().addControlPoint(100, RED).build())
                .build();

        // Set up three sessions. By default priority is by age of the session.
        LightsManager.LightsSession session1 = manager.openSession();
        LightsManager.LightsSession session2 = manager.openSession();
        LightsManager.LightsSession session3 = manager.openSession();

        // Set light3 to green and validate.
        session3.requestLights(new LightsRequest.Builder().addLight(
                light3, new LightState.Builder().setColor(GREEN).build()).build());
        assertThat(manager.getLightState(light1).getColor()).isEqualTo(TRANSPARENT);
        assertThat(manager.getLightSequence(light1)).isNull();
        assertThat(manager.getLightState(light2).getColor()).isEqualTo(TRANSPARENT);
        assertThat(manager.getLightSequence(light2)).isNull();
        assertThat(manager.getLightState(light3).getColor()).isEqualTo(GREEN);
        assertThat(manager.getLightSequence(light3)).isNull();

        // Session2 sets an effect on all animatable lights and wins the priority.
        session2.requestLights(new LightsRequest.Builder().setEffect(effect).build());
        assertThat(manager.getLightState(light1).getColor()).isEqualTo(BLACK);
        assertThat(manager.getLightSequence(light1).getColors()[0]).isEqualTo(BLUE);
        assertThat(manager.getLightState(light2).getColor()).isEqualTo(BLACK);
        assertThat(manager.getLightSequence(light2).getColors()[0]).isEqualTo(YELLOW);
        assertThat(manager.getLightState(light3).getColor()).isEqualTo(BLACK);
        assertThat(manager.getLightSequence(light3).getColors()[0]).isEqualTo(RED);

        // Session1 sets a single light and hides the effect.
        session1.requestLights(new LightsRequest.Builder().addLight(
                light1, new LightState.Builder().setColor(GREEN).build()).build());
        assertThat(manager.getLightState(light1).getColor()).isEqualTo(GREEN);
        assertThat(manager.getLightSequence(light1)).isNull();
        assertThat(manager.getLightState(light2).getColor()).isEqualTo(TRANSPARENT);
        assertThat(manager.getLightSequence(light2)).isNull();
        assertThat(manager.getLightState(light3).getColor()).isEqualTo(GREEN);
        assertThat(manager.getLightSequence(light3)).isNull();

        // Session2 cancels the effect by clearing a light.
        session2.requestLights(new LightsRequest.Builder().clearLight(light1).build());
        assertThat(manager.getLightState(light1).getColor()).isEqualTo(GREEN);
        assertThat(manager.getLightSequence(light1)).isNull();
        assertThat(manager.getLightState(light2).getColor()).isEqualTo(TRANSPARENT);
        assertThat(manager.getLightSequence(light2)).isNull();
        assertThat(manager.getLightState(light3).getColor()).isEqualTo(GREEN);
        assertThat(manager.getLightSequence(light3)).isNull();

        // Session2 sets an effect again.
        session2.requestLights(new LightsRequest.Builder().setEffect(effect).build());
        assertThat(manager.getLightState(light1).getColor()).isEqualTo(GREEN);
        assertThat(manager.getLightSequence(light1)).isNull();
        assertThat(manager.getLightState(light2).getColor()).isEqualTo(TRANSPARENT);
        assertThat(manager.getLightSequence(light2)).isNull();
        assertThat(manager.getLightState(light3).getColor()).isEqualTo(GREEN);
        assertThat(manager.getLightSequence(light3)).isNull();

        // Session1 goes away and lets session 2 take control.
        session1.close();
        assertThat(manager.getLightState(light1).getColor()).isEqualTo(BLACK);
        assertThat(manager.getLightSequence(light1).getColors()[0]).isEqualTo(BLUE);
        assertThat(manager.getLightState(light2).getColor()).isEqualTo(BLACK);
        assertThat(manager.getLightSequence(light2).getColors()[0]).isEqualTo(YELLOW);
        assertThat(manager.getLightState(light3).getColor()).isEqualTo(BLACK);
        assertThat(manager.getLightSequence(light3).getColors()[0]).isEqualTo(RED);

        // Session2 goes away and let's session3 take control.
        session2.close();
        assertThat(manager.getLightState(light1).getColor()).isEqualTo(TRANSPARENT);
        assertThat(manager.getLightSequence(light1)).isNull();
        assertThat(manager.getLightState(light2).getColor()).isEqualTo(TRANSPARENT);
        assertThat(manager.getLightSequence(light2)).isNull();
        assertThat(manager.getLightState(light3).getColor()).isEqualTo(GREEN);
        assertThat(manager.getLightSequence(light3)).isNull();

        session3.close();
        assertThat(manager.getLightState(light1).getColor()).isEqualTo(TRANSPARENT);
        assertThat(manager.getLightSequence(light1)).isNull();
        assertThat(manager.getLightState(light2).getColor()).isEqualTo(TRANSPARENT);
        assertThat(manager.getLightSequence(light2)).isNull();
        assertThat(manager.getLightState(light3).getColor()).isEqualTo(TRANSPARENT);
        assertThat(manager.getLightSequence(light3)).isNull();
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_LIGHT_ANIMATIONS})
    public void testControlLights_effectRequest_scheduleAContinuation() throws Exception {
        InOrder inOrder = inOrder(mHal);
        LightsService service = new LightsService(mContext, () -> mHal, mServiceThread.getLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);
        Light light = manager.getLights().get(1);
        ArgumentCaptor<HwLightEffect[]> effectCaptor =
                ArgumentCaptor.forClass(HwLightEffect[].class);


        MultiLightEffect effect = new MultiLightEffect.Builder()
                .addLightSequence(
                        manager.getLights().get(1),
                        new ColorSequence.Builder().addControlPoint(500, BLUE).build())
                .setPreemptive(false)
                .build();
        MultiLightEffect continuation = new MultiLightEffect.Builder()
                .addLightSequence(
                        manager.getLights().get(1),
                        new ColorSequence.Builder().addControlPoint(500, GREEN).build())
                .setPreemptive(false)
                .build();

        try (LightsManager.LightsSession session = manager.openSession()) {
            session.requestLights(new LightsRequest.Builder().setEffect(effect).build());

            // Light effect is immediately applied:
            //  - Light sequence color is matches the effect.
            //  - One tansition task scheduled.
            //  - Hal received the configuration for 'effect'.
            assertThat(manager.getLightState(light).getColor()).isEqualTo(BLACK);
            assertThat(manager.getLightSequence(light).getColors()[0]).isEqualTo(BLUE);
            Message message = mTestLooperManager.poll();
            assertThat(message).isNotNull();
            assertThat(mTestLooperManager.poll()).isNull();

            inOrder.verify(mHal, times(1)).setLightEffects(effectCaptor.capture());
            HwLightEffect[] effectInteractions = effectCaptor.getValue();
            assertThat(effectInteractions.length).isEqualTo(1);
            assertThat(effectInteractions[0].colors).asList().containsExactly(BLUE);

            // Request the continuation effect.
            session.requestLights(new LightsRequest.Builder().setEffect(continuation).build());

            // Continuation is queued and the state of the light remains the same. No new hal
            // requests and no new scheduled tasks.
            assertThat(manager.getLightState(light).getColor()).isEqualTo(BLACK);
            assertThat(manager.getLightSequence(light).getColors()[0]).isEqualTo(BLUE);
            inOrder.verify(mHal, never()).setLightEffects(any());

            assertThat(mTestLooperManager.poll()).isNull();

            // Execute the transition task and validate that a new state is sent to hal, and the
            // light changes configuration to the continuation effect.
            mTestLooperManager.execute(message);

            assertThat(manager.getLightState(light).getColor()).isEqualTo(BLACK);
            assertThat(manager.getLightSequence(light).getColors()[0]).isEqualTo(GREEN);
            message = mTestLooperManager.poll();
            assertThat(message).isNotNull();
            assertThat(mTestLooperManager.poll()).isNull();

            inOrder.verify(mHal, times(1)).setLightEffects(effectCaptor.capture());
            effectInteractions = effectCaptor.getValue();
            assertThat(effectInteractions.length).isEqualTo(1);
            assertThat(effectInteractions[0].colors).asList().containsExactly(GREEN);

            // Execute the transition for the continuation.
            mTestLooperManager.execute(message);
            assertThat(mTestLooperManager.poll()).isNull();
            inOrder.verify(mHal, never()).setLightEffects(any());
        }
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_LIGHT_ANIMATIONS})
    public void testControlLights_muteLights_criticalUserAndSystemBehavior() throws Exception {
        // Report one of each light type, to test filtering.
        when(mHal.getLights()).thenReturn(new HwLight[]{
                // System lights that should be filtered
                fakeHwLight(1, LightType.BACKLIGHT, 1, 0),
                fakeHwLight(2, LightType.KEYBOARD, 1, 0),
                fakeHwLight(3, LightType.BUTTONS, 1, 0),
                fakeHwLight(4, LightType.BATTERY, 1, 0),
                fakeHwLight(5, LightType.NOTIFICATIONS, 1, 0),
                fakeHwLight(6, LightType.ATTENTION, 1, 0),
                fakeHwLight(7, LightType.BLUETOOTH, 1, 0),
                fakeHwLight(8, LightType.WIFI, 1, 0),
                // Non-system lights that should be available to apps
                fakeHwLight(9, Light.LIGHT_TYPE_MICROPHONE, 1, 0),
                fakeHwLight(10, Light.LIGHT_TYPE_APPLICATION, 1, 0),
                fakeHwLight(11, Light.LIGHT_TYPE_CAMERA, 1, 0),
                // A light with a type that is not defined in LightType, but is not a system
                // light
                fakeHwLight(100, 100, 1, 0)});

        InOrder inOrder = inOrder(mHal);
        LightsService service = new LightsService(mContext, () -> mHal, Looper.getMainLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);
        ArgumentCaptor<HwLightState> stateCaptor =
                ArgumentCaptor.forClass(HwLightState.class);

        LightsRequest.Builder requestBuilder = new LightsRequest.Builder();
        // Set all user ligths to one color.
        for (Light light : manager.getLights()) {
            requestBuilder.addLight(
                    light, new LightState.Builder().setColor(RED).build());
        }

        try (LightsManager.LightsSession session = manager.openSession()) {
            session.requestLights(requestBuilder.build());

            inOrder.verify(mHal).setLightState(eq(9), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(RED);
            inOrder.verify(mHal).setLightState(eq(10), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(RED);
            inOrder.verify(mHal).setLightState(eq(11), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(RED);
            inOrder.verify(mHal).setLightState(eq(100), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(RED);

            // Set the color for system-only lights. Light retrieval is by type.
            for (int i = 0; i < service.mLightsByType.size(); i++) {
                LogicalLight light = service.mLightsByType.valueAt(i);
                light.setColor(RED);
            }

            inOrder.verify(mHal).setLightState(eq(1), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(RED);
            inOrder.verify(mHal).setLightState(eq(2), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(RED);
            inOrder.verify(mHal).setLightState(eq(3), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(RED);
            inOrder.verify(mHal).setLightState(eq(4), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(RED);
            inOrder.verify(mHal).setLightState(eq(5), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(RED);
            inOrder.verify(mHal).setLightState(eq(6), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(RED);
            inOrder.verify(mHal).setLightState(eq(7), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(RED);
            inOrder.verify(mHal).setLightState(eq(8), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(RED);

            service.setMutedState(true);

            inOrder.verify(mHal, never()).setLightState(eq(1), any());
            inOrder.verify(mHal, never()).setLightState(eq(2), any());
            inOrder.verify(mHal, never()).setLightState(eq(3), any());
            inOrder.verify(mHal).setLightState(eq(4), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(0);
            inOrder.verify(mHal).setLightState(eq(5), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(0);
            inOrder.verify(mHal).setLightState(eq(6), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(0);
            inOrder.verify(mHal).setLightState(eq(7), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(0);
            inOrder.verify(mHal).setLightState(eq(8), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(0);
            inOrder.verify(mHal).setLightState(eq(9), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(0);
            inOrder.verify(mHal).setLightState(eq(10), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(0);
            inOrder.verify(mHal).setLightState(eq(11), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(0);
            inOrder.verify(mHal).setLightState(eq(100), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(0);

            service.setMutedState(false);

            inOrder.verify(mHal, never()).setLightState(eq(1), any());
            inOrder.verify(mHal, never()).setLightState(eq(2), any());
            inOrder.verify(mHal, never()).setLightState(eq(3), any());
            inOrder.verify(mHal).setLightState(eq(4), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(RED);
            inOrder.verify(mHal).setLightState(eq(5), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(RED);
            inOrder.verify(mHal).setLightState(eq(6), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(RED);
            inOrder.verify(mHal).setLightState(eq(7), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(RED);
            inOrder.verify(mHal).setLightState(eq(8), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(RED);
            inOrder.verify(mHal).setLightState(eq(9), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(RED);
            inOrder.verify(mHal).setLightState(eq(10), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(RED);
            inOrder.verify(mHal).setLightState(eq(11), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(RED);
            inOrder.verify(mHal).setLightState(eq(100), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(RED);
        }
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_LIGHT_ANIMATIONS})
    public void testControlLights_disableDuringEffect() throws Exception {
        InOrder inOrder = inOrder(mHal);
        LightsService service = new LightsService(mContext, () -> mHal, Looper.getMainLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);
        Light light = manager.getLights().get(ANIMATED_LIGHT_INDEX);
        ArgumentCaptor<HwLightState> stateCaptor =
                ArgumentCaptor.forClass(HwLightState.class);

        // Create a simple effect for a light that supports effects.
        MultiLightEffect effect = new MultiLightEffect.Builder()
                .addLightSequence(
                        light, new ColorSequence.Builder().addControlPoint(100, BLUE).build())
                .build();
        try (LightsManager.LightsSession session = manager.openSession()) {
            session.requestLights(new LightsRequest.Builder().setEffect(effect).build());

            // Validate that the service sent the hal the effect.
            inOrder.verify(mHal).setLightEffects(any());

            service.setMutedState(true);

            inOrder.verify(mHal).setLightState(eq(light.getId()), stateCaptor.capture());
            assertThat(stateCaptor.getValue().color).isEqualTo(0);
        }
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_LIGHT_ANIMATIONS})
    public void testControlLights_mute_systemLightUpdateWhileMuted() throws Exception {
        // Report one of each light type, to test filtering.
        when(mHal.getLights()).thenReturn(new HwLight[]{
                fakeHwLight(5, LightType.NOTIFICATIONS, 1, 0)
        });

        InOrder inOrder = inOrder(mHal);
        LightsService service = new LightsService(mContext, () -> mHal, Looper.getMainLooper());
        ArgumentCaptor<HwLightState> stateCaptor =
                ArgumentCaptor.forClass(HwLightState.class);

        LogicalLight light = service.mLightsByType.get(LightType.NOTIFICATIONS);
        light.setColor(BLUE);

        // Set an initial color
        inOrder.verify(mHal).setLightState(eq(5), stateCaptor.capture());
        assertThat(stateCaptor.getValue().color).isEqualTo(BLUE);

        // Mute the lights and verify that the light got muted.
        service.setMutedState(true);

        inOrder.verify(mHal).setLightState(eq(5), stateCaptor.capture());
        assertThat(stateCaptor.getValue().color).isEqualTo(0);

        // Change the color on a muted light and verify that the mute blocks the HAL update.
        light.setColor(GREEN);
        inOrder.verify(mHal, never()).setLightState(eq(5), any());

        // Unmute the lights.
        service.setMutedState(false);

        // Validate that the light got the new value set during the mute.
        inOrder.verify(mHal).setLightState(eq(5), stateCaptor.capture());
        assertThat(stateCaptor.getValue().color).isEqualTo(GREEN);
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_LIGHT_ANIMATIONS})
    public void testControlLights_staleCompletionAfterSetState() throws Exception {
        LightsService service = new LightsService(mContext, () -> mHal, mServiceThread.getLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);
        Light light = manager.getLights().get(ANIMATED_LIGHT_INDEX);
        MultiLightEffect effect = new MultiLightEffect.Builder()
                .addLightSequence(
                        light, new ColorSequence.Builder().addControlPoint(100, BLUE).build())
                .build();

        try (LightsManager.LightsSession session = manager.openSession()) {
            session.requestLights(new LightsRequest.Builder().setEffect(effect).build());
            Message completion = mTestLooperManager.poll();
            assertThat(completion).isNotNull();
            try {
                // Cancel playback after the completion leaves the handler queue.
                session.requestLights(new LightsRequest.Builder()
                        .addLight(light, new LightState(RED)).build());
                mTestLooperManager.execute(completion);

                assertThat(manager.getLightState(light).getColor()).isEqualTo(RED);
                assertThat(manager.getLightSequence(light)).isNull();
            } finally {
                mTestLooperManager.recycle(completion);
            }
        }
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_LIGHT_ANIMATIONS})
    public void testControlLights_staleCompletionAfterSessionClose() throws Exception {
        LightsService service = new LightsService(mContext, () -> mHal, mServiceThread.getLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);
        Light light = manager.getLights().get(ANIMATED_LIGHT_INDEX);
        MultiLightEffect effect = new MultiLightEffect.Builder()
                .addLightSequence(
                        light, new ColorSequence.Builder().addControlPoint(100, BLUE).build())
                .build();

        try (LightsManager.LightsSession session = manager.openSession()) {
            session.requestLights(new LightsRequest.Builder().setEffect(effect).build());
            Message completion = mTestLooperManager.poll();
            assertThat(completion).isNotNull();
            try {
                // Close the session after the completion leaves the handler queue.
                session.close();
                mTestLooperManager.execute(completion);

                assertThat(manager.getLightState(light).getColor()).isEqualTo(TRANSPARENT);
                assertThat(manager.getLightSequence(light)).isNull();
            } finally {
                mTestLooperManager.recycle(completion);
            }
        }
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_LIGHT_ANIMATIONS})
    public void testControlLights_staleCompletionAfterPreempt() throws Exception {
        LightsService service = new LightsService(mContext, () -> mHal, mServiceThread.getLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);
        Light light = manager.getLights().get(ANIMATED_LIGHT_INDEX);
        MultiLightEffect effect1 = new MultiLightEffect.Builder()
                .addLightSequence(
                        light, new ColorSequence.Builder().addControlPoint(100, BLUE).build())
                .build();
        MultiLightEffect effect2 = new MultiLightEffect.Builder()
                .addLightSequence(
                        light, new ColorSequence.Builder().addControlPoint(100, YELLOW).build())
                .setPreemptive(true)
                .build();

        try (LightsManager.LightsSession session = manager.openSession()) {
            session.requestLights(new LightsRequest.Builder().setEffect(effect1).build());
            Message completion = mTestLooperManager.poll();
            assertThat(completion).isNotNull();
            try {
                // Replace playback after the first completion leaves the handler queue.
                session.requestLights(new LightsRequest.Builder().setEffect(effect2).build());
                mTestLooperManager.execute(completion);

                assertThat(manager.getLightSequence(light)).isNotNull();
                assertThat(manager.getLightSequence(light).getColors()).asList()
                        .containsExactly(YELLOW);
            } finally {
                mTestLooperManager.recycle(completion);
            }
        }
    }
}
