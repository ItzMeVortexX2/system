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
import dlindustries.uk.daduncs.event.events.TickListener;
import dlindustries.uk.daduncs.managers.AntibanbyDaduncs;
import dlindustries.uk.daduncs.module.Category;
import dlindustries.uk.daduncs.module.Module;
import dlindustries.uk.daduncs.module.modules.client.Friends;
import dlindustries.uk.daduncs.module.setting.bool;
import dlindustries.uk.daduncs.module.setting.mode;
import dlindustries.uk.daduncs.module.setting.number;
import dlindustries.uk.daduncs.module.setting.range;
import dlindustries.uk.daduncs.utils.EncryptedString;
import dlindustries.uk.daduncs.utils.MathUtils;
import dlindustries.uk.daduncs.utils.RotationUtils;
import dlindustries.uk.daduncs.utils.TimerUtils;
import dlindustries.uk.daduncs.utils.WorldUtils;
import dlindustries.uk.daduncs.utils.rotation.Rotation;
import dlindustries.uk.daduncs.utils.rotation.SilentRot;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import org.lwjgl.glfw.GLFW;

import java.util.Optional;

/**
 * Triggerbot with silent rotations.
 * <p>
 * The module owns the rotation itself: every tick it turns the rotation that is sent to the server
 * towards a point inside the target's hitbox, and only swings once the server already holds a
 * rotation that lands on that hitbox. The local camera is never touched, so the player can aim
 * anywhere and still hit.
 */
public final class MarlowTBot extends Module implements TickListener {

	private static final double LOCK_GRACE = 3.0;

	public enum ClickType {
		LEFT_CLICK("Left Click"),
		RIGHT_CLICK("Right Click"),
		ANY_CLICK("Any Click"),
		AUTOMATIC("Automatic");

		private final String name;

		ClickType(String name) {
			this.name = name;
		}

		@Override
		public String toString() {
			return name;
		}
	}

	public enum EntityMode {
		PLAYERS_ONLY("Players Only"),
		ALL_ENTITIES("All Entities");

		private final String name;

		EntityMode(String name) {
			this.name = name;
		}

		@Override
		public String toString() {
			return name;
		}
	}

	private final mode<ClickType> trigger = new mode<>(EncryptedString.of("Trigger"), ClickType.LEFT_CLICK, ClickType.class)
			.setDescription(EncryptedString.of("Which click activates the triggerbot"));
	private final range attackDelay = new range(EncryptedString.of("Attack Delay"), 50, 1000, 1, 500, 560)
			.setDescription(EncryptedString.of("Delay between attacks in milliseconds"));
	private final number reach = new number(EncryptedString.of("Reach"), 2.0, 3.0, 3.0, 0.05)
			.setDescription(EncryptedString.of("Maximum distance to attack a target from, the server rejects hits past 3 blocks"));
	private final number fov = new number(EncryptedString.of("FOV"), 10, 360, 360, 5)
			.setDescription(EncryptedString.of("How far from your real aim a target can be, 360 targets every direction"));
	private final mode<EntityMode> entities = new mode<>(EncryptedString.of("Entities"), EntityMode.PLAYERS_ONLY, EntityMode.class);
	private final bool seeOnly = new bool(EncryptedString.of("Visible Only"), true)
			.setDescription(EncryptedString.of("Only attacks targets you can see"));
	private final bool aimAlways = new bool(EncryptedString.of("Always Aim"), true)
			.setDescription(EncryptedString.of("Silently aims at targets even while the trigger is not held"));
	private final bool lock = new bool(EncryptedString.of("Lock Mode"), false)
			.setDescription(EncryptedString.of("Sticks to the first enemy you hit until they die or leave your reach"));
	private final bool weaponsOnly = new bool(EncryptedString.of("Weapons Only"), true)
			.setDescription(EncryptedString.of("Only attacks while holding a sword, axe or mace"));
	private final bool onlyReady = new bool(EncryptedString.of("Only Ready"), true)
			.setDescription(EncryptedString.of("Only attacks when the attack cooldown is full"));
	private final bool whileUse = new bool(EncryptedString.of("While Use"), false)
			.setDescription(EncryptedString.of("Keeps attacking while you are using an item"));
	private final bool inScreen = new bool(EncryptedString.of("Work In Screen"), false)
			.setDescription(EncryptedString.of("Keeps working while a screen is open"));
	private final bool swing = new bool(EncryptedString.of("Swing Hand"), true);
	private final number aimSpeed = new number(EncryptedString.of("Aim Speed"), 1, 100, 32, 1)
			.setDescription(EncryptedString.of("How fast the silent aim turns, 1 is very slow and 100 is nearly instant"));
	private final number smoothing = new number(EncryptedString.of("Smoothing"), 0, 100, 80, 5)
			.setDescription(EncryptedString.of("How gradually the aim speeds up and slows down, 0 is linear and 100 is the smoothest"));
	private final number multipoint = new number(EncryptedString.of("Multipoint"), 0, 100, 60, 5)
			.setDescription(EncryptedString.of("0 aims at the middle of the hitbox, 100 at the closest point of the hitbox"));

	private final TimerUtils timer = new TimerUtils();
	private final double[] gcd = new double[2];
	private final double[] anchor = new double[3];
	private int anchorTicks;
	private int currentDelay;
	private LivingEntity target;
	private LivingEntity locked;

	private float aimYaw;
	private float aimPitch;
	private float velocityYaw;
	private float velocityPitch;
	private float sentYaw;
	private float sentPitch;
	private boolean aiming;
	private boolean ownsRotation;

	private int pauseTicks;
	private int hitStreak;
	private boolean reportedError;

	public MarlowTBot() {
		super(EncryptedString.of("MarlowTbot"),
				EncryptedString.of("Triggerbot that silently aims at the server for you without moving your camera"),
				-1,
				Category.SWORD);
		setTag(EncryptedString.of("silent aim"));
		addSettings(trigger, attackDelay, reach, fov, entities, seeOnly, aimAlways, lock, weaponsOnly,
				onlyReady, whileUse, inScreen, swing, aimSpeed, smoothing, multipoint);
	}

	@Override
	public void onEnable() {
		// The first hit after enabling should land as soon as the aim is on target, instead of
		// waiting out a full attack delay.
		currentDelay = 0;
		timer.reset();
		target = null;
		locked = null;
		aiming = false;
		ownsRotation = false;
		velocityYaw = 0;
		velocityPitch = 0;
		gcd[0] = 0;
		gcd[1] = 0;
		anchorTicks = 0;
		pauseTicks = 0;
		hitStreak = 0;
		reportedError = false;
		eventManager.add(TickListener.class, this);
		super.onEnable();
	}

	@Override
	public void onDisable() {
		eventManager.remove(TickListener.class, this);
		SilentRot.stop(this);
		ownsRotation = false;
		aiming = false;
		target = null;
		locked = null;
		velocityYaw = 0;
		velocityPitch = 0;
		super.onDisable();
	}

	@Override
	public void onTick() {
		try {
			if (mc.player == null || mc.world == null || mc.interactionManager == null) {
				releaseAim();
				return;
			}

			if (!inScreen.getValue() && mc.currentScreen != null) {
				releaseAim();
				return;
			}

			LivingEntity found = findTarget();
			if (found != target) {
				aiming = false;
				anchorTicks = 0;
			}
			target = found;

			if (found == null) {
				releaseAim();
				return;
			}

			boolean triggered = clickHeld() && holdingWeapon() && (whileUse.getValue() || !mc.player.isUsingItem());

			if (!triggered && !aimAlways.getValue()) {
				releaseAim();
				return;
			}

			// The attack packet leaves before this tick's movement packet, so the server still
			// holds the rotation that was sent on the previous tick. Other modules can write that
			// rotation too, so read what actually went over the wire instead of our own copy.
			boolean serverAimed = AntibanbyDaduncs.hasSentRotation()
					&& isAimedAt(found, AntibanbyDaduncs.getLastSentYaw(), AntibanbyDaduncs.getLastSentPitch());

			aimAt(found, maxStepPerTick(), smoothing.getValueFloat() / 100.0f);

			// Counted down every tick, not only on ticks where an attack would otherwise be ready,
			// otherwise a short human pause can last far longer than intended.
			if (pauseTicks > 0) {
				pauseTicks--;
				return;
			}

			if (!triggered || !serverAimed) return;
			// Never swing from the outer acquisition range: the server rejects hits past the real
			// reach, which would look like missed clicks.
			if (WorldUtils.getEyeToBoxDistance(mc.player, found) > reach.getValue() - 0.02) return;
			if (AntibanbyDaduncs.isBlockActionSent() || AntibanbyDaduncs.isSlotPending()) return;
			if (onlyReady.getValue() && mc.player.getAttackCooldownProgress(0.5F) < 1.0F) return;
			if (!timer.delay(currentDelay)) return;

			WorldUtils.hitEntity(found, swing.getValue());

			if (lock.getValue()) locked = found;

			// Humans do not click in a perfectly even rhythm: every few hits there is a short
			// break, and every delay is slightly off its nominal value.
			hitStreak++;
			if (hitStreak >= MathUtils.randomInt(4, 7)) {
				hitStreak = 0;
				pauseTicks = MathUtils.randomInt(1, 3);
			}

			currentDelay = humanDelay();
			timer.reset();
		} catch (RuntimeException e) {
			if (!reportedError) {
				reportedError = true;
				dlindustries.uk.daduncs.managers.Recorder recorder = dlindustries.uk.daduncs.managers.Recorder.getInstance();
				if (recorder != null) {
					recorder.recordModuleEvent("MarlowTbot", "ERROR",
							"Tick failed: " + e.getClass().getSimpleName(), null);
				}
			}
			releaseAim();
		}
	}

	/**
	 * The enemy the bot is currently aiming and swinging at, so other movement assists can follow
	 * the same target instead of picking their own.
	 */
	public LivingEntity currentTarget() {
		return target;
	}

	private int humanDelay() {
		int base = attackDelay.getRandomValueInt();
		int jittered = (int) Math.round(base * (0.9 + MathUtils.random.nextDouble() * 0.2));

		return MathHelper.clamp(jittered, 1, 1000);
	}

	/**
	 * Turns the server side rotation towards the target with a damped, eased motion: the turn
	 * accelerates gradually, eases out near the target and never overshoots. This always runs while
	 * a target exists, independently of any other module's aim helper.
	 */
	private void aimAt(LivingEntity entity, float maxStep, float smooth) {
		if (!aiming || !ownsRotation) {
			aimYaw = mc.player.getYaw();
			aimPitch = mc.player.getPitch();
			sentYaw = aimYaw;
			sentPitch = aimPitch;
			velocityYaw = 0;
			velocityPitch = 0;
			gcd[0] = 0;
			gcd[1] = 0;
			anchorTicks = 0;
			aiming = true;
		}

		Vec3d eye = mc.player.getEyePos();
		// The current hitbox, not the interpolated one: the interpolated position can be up to a
		// tick behind, which shows up as the aim dragging behind a moving target.
		Box box = entity.getBoundingBox();

		// A fresh point inside the hitbox every half second or so, so a held aim drifts over the
		// target the way a hand does instead of freezing on one exact angle.
		if (--anchorTicks <= 0) {
			pickAnchor();
		}

		Rotation wanted = RotationUtils.getDirection(eye, aimPoint(box, eye));

		float deltaYaw = MathHelper.wrapDegrees((float) wanted.yaw() - aimYaw);
		float deltaPitch = (float) wanted.pitch() - aimPitch;
		float error = Math.max(Math.abs(deltaYaw), Math.abs(deltaPitch));

		// One motion model for every turn, big or small: the velocity is eased towards what the
		// remaining error asks for, so even a flick accelerates over a few ticks and eases into the
		// target instead of snapping there. Nothing teleports, which is what made it obvious.
		float gain = 1.0f - smooth * 0.7f;
		float urgency = MathHelper.clamp(error / 30.0f, 0.0f, 1.0f);
		float acceleration = maxStep * (0.10f + 0.40f * urgency) * (1.0f - smooth * 0.55f);

		float wantedYawVelocity = MathHelper.clamp(deltaYaw * gain, -maxStep, maxStep);
		float wantedPitchVelocity = MathHelper.clamp(deltaPitch * gain, -maxStep, maxStep);

		velocityYaw = approach(velocityYaw, wantedYawVelocity, deltaYaw, acceleration);
		velocityPitch = approach(velocityPitch, wantedPitchVelocity, deltaPitch, acceleration);

		aimYaw += velocityYaw;
		aimPitch = MathHelper.clamp(aimPitch + velocityPitch, -90.0f, 90.0f);

		Rotation quantized = RotationUtils.applyGCD(sentYaw, sentPitch, aimYaw, aimPitch, gcd, true);

		// The sensitivity steps can round the angle far enough off a small hitbox that the server
		// would see a rotation which misses. When that happens send the exact angle instead.
		if (!aimsInto(box, (float) quantized.yaw(), (float) quantized.pitch())) {
			quantized = new Rotation(aimYaw, aimPitch);
			gcd[0] = 0;
			gcd[1] = 0;
		}

		aimYaw = (float) quantized.yaw();
		aimPitch = MathHelper.clamp((float) quantized.pitch(), -90.0f, 90.0f);

		SilentRot.set(aimYaw, aimPitch, this);
		sentYaw = aimYaw;
		sentPitch = aimPitch;
		ownsRotation = true;
	}

	private float maxStepPerTick() {
		return 0.5f + 34.5f * (aimSpeed.getValueFloat() / 100.0f);
	}

	/**
	 * Eases a rotation velocity towards the one the error asks for. The acceleration limit is what
	 * produces the rounded start and stop of a real turn, and the last clamp stops the turn exactly
	 * on the target instead of letting it overshoot and wobble back.
	 */
	private static float approach(float velocity, float wanted, float delta, float acceleration) {
		float next = velocity + MathHelper.clamp(wanted - velocity, -acceleration, acceleration);

		if (Math.abs(next) > Math.abs(delta)) {
			next = delta;
		}

		return next;
	}

	/**
	 * Picks a spot inside the hitbox for the aim to settle on for the next few ticks. The point stays
	 * away from the edges, so the small drift never costs a hit.
	 */
	private void pickAnchor() {
		anchor[0] = MathUtils.random.nextDouble() * 2.0 - 1.0;
		anchor[1] = MathUtils.random.nextDouble() * 2.0 - 1.0;
		anchor[2] = MathUtils.random.nextDouble() * 2.0 - 1.0;
		anchorTicks = MathUtils.randomInt(8, 22);
	}

	/**
	 * A point that is always inside the hitbox: the middle of the box blended towards the point of
	 * the box closest to the eyes, so the rotation actually lands on the target.
	 */
	private Vec3d aimPoint(Box box, Vec3d eye) {
		Vec3d nearest = new Vec3d(
				MathHelper.clamp(eye.x, box.minX, box.maxX),
				MathHelper.clamp(eye.y, box.minY, box.maxY),
				MathHelper.clamp(eye.z, box.minZ, box.maxZ));

		Vec3d center = box.getCenter();
		Vec3d point = center.lerp(nearest, multipoint.getValue() / 100.0);

		// Even the closest point of the box sits exactly on its surface, and rounding the angle to
		// the mouse sensitivity steps can then push the ray just outside the hitbox. Pull the aim
		// a little towards the middle so a hit is never lost to that rounding.
		double margin = Math.min(0.12, Math.min(box.getLengthX(),
				Math.min(box.getLengthY(), box.getLengthZ())) * 0.25);
		Vec3d offset = point.subtract(center);
		double length = offset.length();

		if (length > margin && length > 1.0E-4) {
			point = center.add(offset.multiply((length - margin) / length));
		}

		// Let the aim wander inside the box between anchor picks. Staying clear of the edges keeps
		// that drift harmless: every angle it produces still lands on the target.
		double halfX = Math.max(0.0, box.getLengthX() * 0.5 - 0.22);
		double halfY = Math.max(0.0, box.getLengthY() * 0.5 - 0.22);
		double halfZ = Math.max(0.0, box.getLengthZ() * 0.5 - 0.22);

		point = point.add(new Vec3d(anchor[0] * halfX * 0.7, anchor[1] * halfY * 0.55, anchor[2] * halfZ * 0.7));

		return new Vec3d(
				MathHelper.clamp(point.x, box.minX + 0.12, box.maxX - 0.12),
				MathHelper.clamp(point.y, box.minY + 0.12, box.maxY - 0.12),
				MathHelper.clamp(point.z, box.minZ + 0.12, box.maxZ - 0.12));
	}

	/**
	 * Whether a rotation from the eyes lands inside the given box. Used to check that rounding the
	 * angle to the mouse sensitivity steps did not push the aim off the target.
	 */
	private boolean aimsInto(Box box, float yaw, float pitch) {
		Vec3d eye = mc.player.getEyePos();
		Vec3d end = eye.add(WorldUtils.getPlayerLookVec(yaw, pitch).multiply(reach.getValue() + 0.6));

		return box.expand(0.02).raycast(eye, end).isPresent();
	}

	private void releaseAim() {
						target = null;
				aiming = false;
				velocityYaw = 0;
				velocityPitch = 0;

		if (mc.player == null || !ownsRotation || !SilentRot.on()) return;

		float realYaw = mc.player.getYaw();
		float realPitch = mc.player.getPitch();
		float yawDiff = MathHelper.wrapDegrees(sentYaw - realYaw);
		float pitchDiff = sentPitch - realPitch;

		if (Math.abs(yawDiff) < 1.0f && Math.abs(pitchDiff) < 1.0f) {
			SilentRot.stop(this);
			ownsRotation = false;
			sentYaw = realYaw;
			sentPitch = realPitch;
			return;
		}

		// Return to the real camera the same way the aim turns: gradually, not with a jump that
		// reads as a snap the moment the target dies or leaves range.
		float step = Math.max(0.75f, maxStepPerTick() * 0.45f);
		sentYaw = realYaw + MathHelper.clamp(yawDiff, -step, step);
		sentPitch = realPitch + MathHelper.clamp(pitchDiff, -step, step);

		SilentRot.set(sentYaw, MathHelper.clamp(sentPitch, -90.0f, 90.0f), this);
	}

	private LivingEntity findTarget() {
		if (!lock.getValue()) {
			locked = null;
		} else if (locked != null) {
			if (!lockStillValid())
				locked = null;
			else
				// A hidden locked target pauses aiming instead of falling back to someone else,
				// so walking behind a wall cannot silently break the lock.
				return visible(locked) ? locked : null;
		}

		if (target != null && isValidTarget(target) && inRange(target)) return target;

		LivingEntity best = null;
		double bestDistance = Double.MAX_VALUE;
		double maxDistance = reach.getValue() + 0.15;

		if (entities.isMode(EntityMode.PLAYERS_ONLY)) {
			for (PlayerEntity player : mc.world.getPlayers()) {
				if (!isValidTarget(player) || !inRange(player)) continue;

				double distance = mc.player.distanceTo(player);
				if (distance >= bestDistance || distance > maxDistance) continue;

				bestDistance = distance;
				best = player;
			}
		} else {
			for (Entity entity : mc.world.getEntities()) {
				if (!(entity instanceof LivingEntity living)) continue;
				if (!isValidTarget(living) || !inRange(living)) continue;

				double distance = mc.player.distanceTo(living);
				if (distance >= bestDistance || distance > maxDistance) continue;

				bestDistance = distance;
				best = living;
			}
		}

		return best;
	}

	private boolean inRange(LivingEntity entity) {
		if (WorldUtils.getEyeToBoxDistance(mc.player, entity) > reach.getValue() + 0.15) return false;
		if (!visible(entity)) return false;

		float fovValue = fov.getValueFloat();
		if (fovValue < 360f) {
			Rotation look = RotationUtils.getDirection(mc.player.getEyePos(), entity.getBoundingBox().getCenter());
			double difference = Math.hypot(
					MathHelper.wrapDegrees(look.yaw() - mc.player.getYaw()),
					look.pitch() - mc.player.getPitch());

			if (difference > fovValue / 2.0) return false;
		}

		return true;
	}

	/**
	 * A lock is dropped only when the target is gone or has escaped the lock range, never just
	 * because it is behind a wall.
	 */
	private boolean lockStillValid() {
		return isValidTarget(locked) && WorldUtils.getEyeToBoxDistance(mc.player, locked) <= reach.getValue() + LOCK_GRACE;
	}

	private boolean visible(LivingEntity entity) {
		return !seeOnly.getValue() || mc.player.canSee(entity);
	}

	private boolean isValidTarget(LivingEntity entity) {
		if (entity == mc.player || entity.isRemoved() || !entity.isAlive()) return false;
		if (entity.isSpectator() || entity.isInvulnerable()) return false;
		if (entity.getHealth() <= 0.0F) return false;

		if (entity instanceof PlayerEntity player) {
			Friends friends = System.INSTANCE.getModuleManager().getModule(Friends.class);

			if (friends != null && friends.isEnabled() && friends.antiAttack.getValue() &&
					System.INSTANCE.getFriendManager().isFriend(player)) {
				return false;
			}
		}

		return true;
	}

	private boolean isAimedAt(Entity entity, float yaw, float pitch) {
		if (mc.player == null || mc.world == null || entity == null) return false;

		Vec3d eye = mc.player.getEyePos();
		Vec3d end = eye.add(WorldUtils.getPlayerLookVec(yaw, pitch).multiply(reach.getValue() + 0.6));

		Box box = entity.getBoundingBox().expand(0.08);

		// The rotation went out on the previous tick, so the target may have moved since the aim
		// was computed. Accept the swing when the ray crosses the path the target took, not only
		// where it stands now, otherwise a moving target swallows the click.
		Vec3d motion = entity.getVelocity();
		box = box.expand(
				MathHelper.clamp(Math.abs(motion.x), 0.0, 0.5),
				MathHelper.clamp(Math.abs(motion.y), 0.0, 0.5),
				MathHelper.clamp(Math.abs(motion.z), 0.0, 0.5));

		Optional<Vec3d> hit = box.raycast(eye, end);
		if (hit.isEmpty()) return false;

		// Solid blocks only: tall grass, flowers and torches have an outline but do not stop a
		// swing, and letting them fail this check just means the module refuses to attack.
		BlockHitResult block = mc.world.raycast(new RaycastContext(
				eye,
				hit.get(),
				RaycastContext.ShapeType.COLLIDER,
				RaycastContext.FluidHandling.NONE,
				mc.player));

		return block.getType() == HitResult.Type.MISS;
	}

	private boolean holdingWeapon() {
		return !weaponsOnly.getValue() || WorldUtils.isWeapon(mc.player.getMainHandStack());
	}

	private boolean clickHeld() {
		return switch (trigger.getMode()) {
			case LEFT_CLICK -> isMousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
			case RIGHT_CLICK -> isMousePressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
			case ANY_CLICK -> isMousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT) || isMousePressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
			case AUTOMATIC -> true;
		};
	}

	private boolean isMousePressed(int button) {
		if (mc.getWindow() == null) return false;

		return GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), button) == GLFW.GLFW_PRESS;
	}
}
