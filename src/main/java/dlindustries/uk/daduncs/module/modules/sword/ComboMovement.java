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

package dlindustries.uk.daduncs.module.modules.sword;

import dlindustries.uk.daduncs.System;
import dlindustries.uk.daduncs.event.events.PacketSendListener;
import dlindustries.uk.daduncs.event.events.TickListener;
import dlindustries.uk.daduncs.imixin.IKeyBinding;
import dlindustries.uk.daduncs.module.Category;
import dlindustries.uk.daduncs.module.Module;
import dlindustries.uk.daduncs.module.setting.bool;
import dlindustries.uk.daduncs.module.setting.number;
import dlindustries.uk.daduncs.module.setting.range;
import dlindustries.uk.daduncs.utils.EncryptedString;
import dlindustries.uk.daduncs.utils.MathUtils;
import dlindustries.uk.daduncs.utils.MovementAssist;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import org.lwjgl.glfw.GLFW;

/**
 * Movement assist for chaining hits.
 * <p>
 * It steers the player into a comfortable hit distance and circles the enemy, and it releases
 * forward for a tick after every landed hit. That release resets sprint, which is what makes the
 * next swing a sprint hit again: the enemy is knocked back on every hit instead of only the first,
 * so they stay in the combo. The direction is eased instead of snapped, so the movement keeps its
 * momentum and never fights the camera.
 * <p>
 * The player always keeps control: as soon as one of the movement keys is held, the assist fades
 * out and the key input is used on its own, and it fades back in once the keys are released.
 */
public final class ComboMovement extends Module implements TickListener, PacketSendListener {

	private static final double MAX_TARGET_DISTANCE = 6.5;

	private final bool onlyAttack = new bool(EncryptedString.of("Only While Attacking"), true)
			.setDescription(EncryptedString.of("Only moves you while your attack button is held, otherwise you keep full control"));
	private final number holdDistance = new number(EncryptedString.of("Hold Distance"), 1.5, 4.0, 2.5, 0.05)
			.setDescription(EncryptedString.of("Distance it settles at: further away it walks in, closer it backs off"));
	private final number strafe = new number(EncryptedString.of("Strafe"), 0, 100, 65, 5)
			.setDescription(EncryptedString.of("How much it circles around the enemy while holding the distance"));
	private final number smoothness = new number(EncryptedString.of("Smoothness"), 0, 100, 85, 5)
			.setDescription(EncryptedString.of("How gently the direction changes, 100 glides and 0 snaps"));
	private final bool wTap = new bool(EncryptedString.of("W Tap"), true)
			.setDescription(EncryptedString.of("Releases forward for a moment after every hit so the next hit is a sprint hit again"));
	private final range tapTime = new range(EncryptedString.of("Tap Time"), 20, 200, 5, 40, 70)
			.setDescription(EncryptedString.of("How long forward is released for after a hit, in milliseconds"));
	private final bool sprint = new bool(EncryptedString.of("Sprint"), true)
			.setDescription(EncryptedString.of("Holds sprint while walking in, so hits land with sprint knockback"));

	private LivingEntity target;
	private double steerX;
	private double steerZ;
	private double userBlend;
	private int strafeTicks;
	private int strafeDirection = 1;
	private int tapTicks;
	private boolean tapping;

	public ComboMovement() {
		super(EncryptedString.of("Combo Movement"),
				EncryptedString.of("Glides you into the enemy and W taps after every hit so combos keep landing"),
				-1,
				Category.SWORD);
		setTag(EncryptedString.of("combo"));
		addSettings(onlyAttack, holdDistance, strafe, smoothness, wTap, tapTime, sprint);
	}

	@Override
	public void onEnable() {
		eventManager.add(TickListener.class, this);
		eventManager.add(PacketSendListener.class, this);
		resetState();
		// Enabling while already walking must not tug the player sideways for a moment.
		userBlend = keysHeld() ? 1.0 : 0.0;
		super.onEnable();
	}

	@Override
	public void onDisable() {
		eventManager.remove(TickListener.class, this);
		eventManager.remove(PacketSendListener.class, this);
		resetState();
		super.onDisable();
	}

	private void resetState() {
		MovementAssist.clear();
		target = null;
		steerX = 0.0;
		steerZ = 0.0;
		tapTicks = 0;
		tapping = false;
	}

	@Override
	public void onTick() {
		try {
			if (mc.player == null || mc.world == null) {
				release();
				return;
			}

			if (mc.currentScreen != null) {
				release();
				return;
			}

			if (onlyAttack.getValue() && !attackHeld()) {
				release();
				return;
			}

			LivingEntity found = findTarget();
			if (found == null) {
				release();
				return;
			}
			target = found;

			// The player's own keys win: fade the assist out while they steer, and back in after.
			// Fading instead of switching keeps the handover smooth in both directions.
			double wanted = keysHeld() ? 1.0 : 0.0;
			double rate = wanted > userBlend ? 0.6 : 0.2;
			userBlend += (wanted - userBlend) * rate;
			if (userBlend < 1.0E-3) {
				userBlend = 0.0;
			} else if (userBlend > 1.0 - 1.0E-3) {
				userBlend = 1.0;
			}

			// A tap stops the player for a tick, which would be felt as a stutter while they are
			// steering themselves, so it is dropped as soon as they take over.
			if (tapping && userBlend > 0.2) {
				tapping = false;
				tapTicks = 0;
			}

			MovementAssist.setWeight((float) (1.0 - userBlend));

			// The tap is a deliberate pause of the forward input: one tick without it is enough
			// for the client to drop sprint, and the next tick walks in again at full speed.
			if (tapping) {
				tapTicks--;
				if (tapTicks <= 0) {
					tapping = false;
					tapTicks = 0;
				}
				MovementAssist.set(0.0f, 0.0f);
				mc.player.setSprinting(false);
				return;
			}

			Vec3d delta = new Vec3d(found.getX() - mc.player.getX(), 0.0, found.getZ() - mc.player.getZ());
			double distance = delta.length();
			if (distance < 1.0E-3) {
				MovementAssist.clear();
				return;
			}
			Vec3d radial = delta.multiply(1.0 / distance);

			// Kept slightly forward even at the hold distance: a hit only knocks the enemy back
			// while you are still walking into them, and that knockback is the combo.
			double error = distance - holdDistance.getValue();
			double radialWeight = MathHelper.clamp(error * 1.6, -0.55, 1.0);
			if (radialWeight > 0.0) {
				radialWeight = Math.max(radialWeight, 0.45);
			}

			double strafeWeight = strafe.getValueFloat() / 100.0 * 0.85;
			if (--strafeTicks <= 0) {
				strafeTicks = MathUtils.randomInt(70, 130);
				strafeDirection = -strafeDirection;
			}

			double desiredX = radial.x * radialWeight - radial.z * strafeWeight * strafeDirection;
			double desiredZ = radial.z * radialWeight + radial.x * strafeWeight * strafeDirection;

			double length = Math.hypot(desiredX, desiredZ);
			if (length > 1.0) {
				desiredX /= length;
				desiredZ /= length;
			}

			// Easing the whole vector, not just the angle, means starting and stopping is gradual
			// too, so the movement never jerks when the target changes distance.
			double glide = 0.5 - 0.42 * (smoothness.getValue() / 100.0);
			steerX += (desiredX - steerX) * glide;
			steerZ += (desiredZ - steerZ) * glide;

			float yaw = (float) Math.toRadians(mc.player.getYaw());
			Vec3d look = new Vec3d(-MathHelper.sin(yaw), 0.0, MathHelper.cos(yaw));
			Vec3d left = new Vec3d(MathHelper.cos(yaw), 0.0, MathHelper.sin(yaw));

			float forward = (float) MathHelper.clamp(steerX * look.x + steerZ * look.z, -1.0, 1.0);
			float sideways = (float) MathHelper.clamp(steerX * left.x + steerZ * left.z, -1.0, 1.0);

			MovementAssist.set(sideways, forward);

			// Started directly instead of holding the sprint key down, so the player's own key
			// state (including toggled sprint) is never overwritten.
			if (sprint.getValue() && userBlend < 0.5
					&& forward > 0.15f
					&& !mc.player.isSneaking()
					&& !mc.player.isSprinting()) {
				mc.player.setSprinting(true);
			}
		} catch (RuntimeException e) {
			release();
		}
	}

	private void release() {
		MovementAssist.clear();
		target = null;
		steerX = 0.0;
		steerZ = 0.0;
	}

	@Override
	public void onPacketSend(PacketSendEvent event) {
		if (!wTap.getValue() || mc.player == null) return;
		if (!(event.packet instanceof PlayerInteractEntityC2SPacket packet)) return;
		// Only a grounded sprint hit knocks the enemy back, and in the air the tap would just
		// stall the jump arc for nothing.
		if (!mc.player.isSprinting() || !mc.player.isOnGround()) return;
		// While the player walks themselves the tap would delete their own W press.
		if (keysHeld() || userBlend > 0.2) return;

		// Auto WTap already does the same thing; running both would tap twice per hit.
		AutoWTap auto = System.INSTANCE.getModuleManager().getModule(AutoWTap.class);
		if (auto != null && auto.isEnabled()) return;

		packet.handle(new PlayerInteractEntityC2SPacket.Handler() {
			@Override
			public void interact(Hand hand) {
			}

			@Override
			public void interactAt(Hand hand, Vec3d pos) {
			}

			@Override
			public void attack() {
				mc.player.setSprinting(false);
				tapping = true;
				tapTicks = MathHelper.clamp(Math.round(tapTime.getRandomValueFloat() / 50.0f), 1, 5);
			}
		});
	}

	/**
	 * Whether the player is actually holding a movement key. The physical key state is used on
	 * purpose: automation pressing keys would otherwise look like the player steering.
	 */
	private boolean keysHeld() {
		if (mc.options == null) return false;

		return ((IKeyBinding) mc.options.forwardKey).isActuallyPressed()
				|| ((IKeyBinding) mc.options.backKey).isActuallyPressed()
				|| ((IKeyBinding) mc.options.leftKey).isActuallyPressed()
				|| ((IKeyBinding) mc.options.rightKey).isActuallyPressed();
	}

	private LivingEntity findTarget() {
		// Follow the triggerbot when it is on, so the movement and the hits always point at the
		// same enemy instead of walking towards someone the aim is not on.
		MarlowTBot bot = System.INSTANCE.getModuleManager().getModule(MarlowTBot.class);
		if (bot != null && bot.isEnabled()) {
			LivingEntity locked = bot.currentTarget();
			if (usable(locked)) return locked;
		}

		LivingEntity best = null;
		double bestDistance = MAX_TARGET_DISTANCE;

		for (PlayerEntity player : mc.world.getPlayers()) {
			if (!usable(player)) continue;

			double distance = mc.player.distanceTo(player);
			if (distance >= bestDistance) continue;

			bestDistance = distance;
			best = player;
		}

		return best;
	}

	private boolean usable(LivingEntity entity) {
		if (entity == null || entity == mc.player) return false;
		if (entity.isRemoved() || !entity.isAlive() || entity.getHealth() <= 0.0f) return false;
		if (entity.isSpectator() || entity.isInvulnerable()) return false;
		if (mc.player.distanceTo(entity) > MAX_TARGET_DISTANCE) return false;

		return mc.player.canSee(entity);
	}

	private boolean attackHeld() {
		if (mc.getWindow() == null) return false;

		return GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
	}
}
