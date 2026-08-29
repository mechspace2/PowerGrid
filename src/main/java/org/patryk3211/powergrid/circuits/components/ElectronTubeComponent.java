/*
 * Copyright 2026 patryk3211
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.patryk3211.powergrid.circuits.components;

import com.google.common.collect.ImmutableCollection;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.render.RenderTypes;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;
import org.patryk3211.powergrid.PowerGrid;
import org.patryk3211.powergrid.circuits.circuitboard.CircuitBoardBlockEntity;
import org.patryk3211.powergrid.circuits.circuitboard.ComponentCircuitBuilder;
import org.patryk3211.powergrid.circuits.components.properties.CalculatedProperty;
import org.patryk3211.powergrid.circuits.components.properties.ComponentProperty;
import org.patryk3211.powergrid.circuits.components.properties.FloatProperty;
import org.patryk3211.powergrid.circuits.schematic.ComponentFootprint;
import org.patryk3211.powergrid.circuits.schematic.PlacedComponent;
import org.patryk3211.powergrid.circuits.thermal.ThermalBuilder;
import org.patryk3211.powergrid.collections.ModdedPartialModels;
import org.patryk3211.powergrid.electricity.base.ThermalBehaviour;
import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.patryk3211.powergrid.electricity.sim.special.ElectronTubeWire;

public class ElectronTubeComponent extends MirrorableComponent implements IRenderedComponent {
    // ECC82 / 12AU7
    // Koren mu (amplification factor), not small-signal voltage gain
    public static final FloatProperty GAIN = new FloatProperty(PowerGrid.MOD_ID, "tube_gain", 21.5f, 1, 100);
    public static final FloatProperty K_G = new FloatProperty(PowerGrid.MOD_ID, "tube_kg", 1_180, 200, 20_000);
    public static final FloatProperty K_P = new FloatProperty(PowerGrid.MOD_ID, "tube_kp", 84, 50, 2000);
    public static final FloatProperty K_VB = new FloatProperty(PowerGrid.MOD_ID, "tube_kvb", 300, 10, 1000);
    public static final FloatProperty EX = new FloatProperty(PowerGrid.MOD_ID, "tube_ex", 1.3f, 1.2f, 1.6f);
    public static final FloatProperty SATURATION_CURRENT = new FloatProperty(PowerGrid.MOD_ID, "tube_saturation_current", 0.04f, 0.001f, 20);
    public static final FloatProperty HEATER_VOLTAGE = new FloatProperty(PowerGrid.MOD_ID, "tube_heater_voltage", 6.3f, 1f, 16f);
    public static final CalculatedProperty<Float> HEATER_POWER = new CalculatedProperty<>(PowerGrid.MOD_ID, "tube_heater_power",
            state -> 1.9f,
            value -> String.format("%.1f W", value));
    public static final FloatProperty PLATE_DISSIPATION = new FloatProperty(PowerGrid.MOD_ID, "tube_plate_dissipation", 2.75f, 0.25f, 100);

    private static final float REDPLATE_START_C = 130f;
    private static final float REDPLATE_FULL_C = 175f;

    public ElectronTubeComponent(ComponentFootprint footprint) {
        super(footprint);
    }

    @Override
    protected void addProperties(ImmutableCollection.Builder<ComponentProperty<?>> properties) {
        super.addProperties(properties);
        properties.add(GAIN, K_G, K_P, K_VB, EX, SATURATION_CURRENT, HEATER_VOLTAGE, HEATER_POWER, PLATE_DISSIPATION);
    }

    @Override
    public void bake(@NotNull PlacedComponent placed, @NotNull ComponentCircuitBuilder builder, @NotNull ThermalBuilder.IEmitter thermals) {
        final var saturationCurrent = placed.get(SATURATION_CURRENT);
        var tube = new ElectronTubeWire(
                placed.get(GAIN), placed.get(K_G), placed.get(K_P), placed.get(K_VB), placed.get(EX), saturationCurrent,
                builder.terminalNode(0), // Cathode
                builder.terminalNode(2), // Anode
                builder.terminalNode(1)  // Grid
        );
        builder.add(tube);

        var targetPower = placed.get(HEATER_POWER);
        var heaterCurrent = targetPower / placed.get(HEATER_VOLTAGE);
        var heaterResistance = placed.get(HEATER_VOLTAGE) / heaterCurrent;
        var heater = builder.connect(heaterResistance, builder.terminalNode(3), builder.terminalNode(4));

        placed.add(tube);
        placed.add(heater);

        var data = new RenderData();
        placed.customData = data;

        final var operatingTemperature = 1400f;
        final var dissipationFactor = ThermalBehaviour.dissipationFactor(targetPower, operatingTemperature);
        thermals.builder()
                .addHeatSource(heater)
                .setThermalMass(0.001f * targetPower / 5f)
                .setOverheatTemperature(1600f)
                .setDissipationFactor(dissipationFactor)
                .withTemperatureCallback(temperature -> {
                    tube.setSaturationCurrent(
                            Mth.clamp(temperature - 1300f, 0, 150) * saturationCurrent / 100
                    );
                    data.prev = data.current;
                    data.current = Mth.clamp((temperature - 1000) / 400f, 0, 1.125f);
                });

        addPlateThermal(thermals, tube, data, placed.get(PLATE_DISSIPATION));
    }

    static void addPlateThermal(ThermalBuilder.IEmitter thermals, AbstractElectricWire tube, RenderData data, float plateWatts) {
        thermals.builder()
                .addHeatSource(tube)
                .setThermalMass(Math.max(0.015f, plateWatts * 0.01f))
                .setMaxPower(plateWatts, 125f)
                .withTemperatureCallback(temperature -> {
                    data.redplatePrev = data.redplate;
                    data.redplate = Mth.clamp((temperature - REDPLATE_START_C) / (REDPLATE_FULL_C - REDPLATE_START_C), 0, 1.25f);
                });
    }

    @Override
    public void dataFixup(@NotNull CompoundTag tag, int version) {
        if(version == 1) {
            var props = tag.getCompound("Properties");
            // tube_kp present means saved under the full Koren model (or already migrated)
            if (props.contains(K_P.id().toString()))
                return;

            // Mid-migration saves have to keep the old stuff
            final float legacyKp = 600;
            final float legacyKvb = 300;
            final float legacyEx = 1.5f;

            var fromAnodeResistance = props.contains("powergrid:tube_anode_resistance");
            if (fromAnodeResistance) {
                var resistance = props.getFloat("powergrid:tube_anode_resistance");
                var kg = ElectronTubeWire.calculateKg1(
                        1, 0,
                        props.getFloat(GAIN.id().toString()),
                        legacyKp,
                        legacyKvb,
                        legacyEx,
                        1 / resistance);
                PowerGrid.LOGGER.info("Fixing electron tube anode resistance ({}) into k_g ({})", resistance, kg);
                props.putFloat(K_G.id().toString(), kg);
                props.remove("powergrid:tube_anode_resistance");
            } else if (props.contains(K_G.id().toString())) {
                var kg = props.getFloat(K_G.id().toString());
                PowerGrid.LOGGER.info("Migrating electron tube k_g from {} to {} for Koren model", kg, kg * 2);
                props.putFloat(K_G.id().toString(), kg * 2f);
            }

            props.putFloat(K_P.id().toString(), legacyKp);
            props.putFloat(K_VB.id().toString(), legacyKvb);
            props.putFloat(EX.id().toString(), legacyEx);
            tag.put("Properties", props);
        }
    }

    public static class RenderData {
        float prev;
        float current;
        float redplatePrev;
        float redplate;
    }

    static void renderTubeGlow(CircuitBoardBlockEntity be, PlacedComponent placed, float partialTicks, PoseStack ms, MultiBufferSource bufferSource, PartialModel glow, float heaterScale) {
        float heater = 0;
        float redplate = 0;
        if (placed.customData instanceof RenderData data) {
            heater = Mth.lerp(partialTicks, data.prev, data.current);
            redplate = Mth.lerp(partialTicks, data.redplatePrev, data.redplate);
        }

        int heaterAlpha = (int) (heater * heaterScale);
        if (heaterAlpha > 0) {
            CachedBuffers.partial(glow, be.getBlockState())
                    .disableDiffuse()
                    .color(heaterAlpha, heaterAlpha, heaterAlpha, 255)
                    .light(LightTexture.FULL_BRIGHT)
                    .renderInto(ms, bufferSource.getBuffer(RenderTypes.additive()));
        }

        if (redplate <= 0.01f)
            return;
        int r = (int) (Mth.clamp(redplate, 0, 1.25f) * 220);
        int g = (int) (redplate * redplate * 55);
        int b = (int) (redplate * redplate * redplate * 12);
        CachedBuffers.partial(glow, be.getBlockState())
                .disableDiffuse()
                .color(r, g, b, 255)
                .light(LightTexture.FULL_BRIGHT)
                .renderInto(ms, bufferSource.getBuffer(RenderTypes.additive()));
    }

    @Override
    public void render(CircuitBoardBlockEntity be, PlacedComponent placed, float partialTicks, PoseStack ms, MultiBufferSource bufferSource, int light, int overlay) {
        renderTubeGlow(be, placed, partialTicks, ms, bufferSource, ModdedPartialModels.ELECTRON_TUBE_GLOW, 160);
    }
}
