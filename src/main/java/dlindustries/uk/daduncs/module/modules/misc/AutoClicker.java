/*
 * This file is part of System Client (https://github.com/daduncs/system)
 * Website: https://download.daduncs.cloud/
 *
 * Copyright (c) 2024 - 2026 DLindustries and project contributors.
 *
 * System Client is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * System Client is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with System Client. If not, see <https://www.gnu.org/licenses/>.
 */

package dlindustries.uk.daduncs.module.modules.misc;

import dlindustries.uk.daduncs.event.events.TickListener;
import dlindustries.uk.daduncs.module.Category;
import dlindustries.uk.daduncs.module.Module;
import dlindustries.uk.daduncs.module.setting.bool;
import dlindustries.uk.daduncs.module.setting.range;
import dlindustries.uk.daduncs.utils.EncryptedString;
import dlindustries.uk.daduncs.utils.MathUtils;
import dlindustries.uk.daduncs.utils.MouseSimulation;
import dlindustries.uk.daduncs.utils.TimerUtils;

import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;

import org.lwjgl.glfw.GLFW;

/**
 * Clicks for you at a configurable rate. Each click is a real left click: it attacks whatever
 * entity is under the crosshair, breaks the block that is being looked at, or just swings when
 * there is nothing to hit.
 */
public final class AutoClicker extends Module implements TickListener {
	private final range cps = new range(EncryptedString.of("CPS"), 1, 25, 1, 8, 14)
			.setDescription(EncryptedString.of("Clicks per second, every click picks a rate inside this range"));
	private final bool clickSimulation = new bool(EncryptedString.of("Click Simulation"), true)
			.setDescription(EncryptedString.of("Reports every click as a simulated mouse click so the CPS hud and recorder count it"));
	private final bool swing = new bool(EncryptedString.of("Swing Hand"), true)
			.setDescription(EncryptedString.of("Swings the hand on every click"));

	private final TimerUtils timer = new TimerUtils();
	private double nextDelay;

	public AutoClicker() {
		super(EncryptedString.of("Auto Clicker"),
				EncryptedString.of("Clicks for you at the CPS you choose"),
				-1,
				Category.MISC);
		setTag(EncryptedString.of("clicker"));
		addSettings(cps, clickSimulation, swing);
	}

	@Override
	public void onEnable() {
		timer.reset();
		nextDelay = 0;
		eventManager.add(TickListener.class, this);
		super.onEnable();
	}

	@Override
	public void onDisable() {
		eventManager.remove(TickListener.class, this);
		timer.reset();
		nextDelay = 0;
		super.onDisable();
	}

	@Override
	public void onTick() {
		if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
		if (mc.currentScreen != null) return;
		if (mc.player.isSpectator() || mc.player.isDead()) return;

		if (!timer.hasReached(nextDelay)) return;

		click();

		nextDelay = nextDelay();
		timer.reset();
	}

	private void click() {
		if (mc.crosshairTarget instanceof EntityHitResult hit) {
			mc.interactionManager.attackEntity(mc.player, hit.getEntity());
			if (swing.getValue()) mc.player.swingHand(Hand.MAIN_HAND);
		} else if (mc.crosshairTarget instanceof BlockHitResult hit
				&& !mc.world.getBlockState(hit.getBlockPos()).isAir()) {
			mc.interactionManager.updateBlockBreakingProgress(hit.getBlockPos(), hit.getSide());
			mc.player.swingHand(Hand.MAIN_HAND);
		} else if (swing.getValue()) {
			mc.player.swingHand(Hand.MAIN_HAND);
		}

		if (clickSimulation.getValue()) {
			MouseSimulation.mouseClick(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		}
	}

	private double nextDelay() {
		double base = 1000.0 / Math.max(1, cps.getRandomValueInt());
		return base * (0.9 + MathUtils.random.nextDouble() * 0.2);
	}
}
