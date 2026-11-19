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

package dlindustries.uk.daduncs.mixin;

import dlindustries.uk.daduncs.utils.MovementAssist;
import dlindustries.uk.daduncs.utils.rotation.SilentRot;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends Input {
	@Inject(method = "tick", at = @At("TAIL"))
	private void onTickTail(CallbackInfo ci) {
		if (MovementAssist.active()) {
			applyAssist();
		}

		if (SilentRot.moving()) {
			compensateSilentRotation();
		}
	}

	/**
	 * Blends the analog vector a module asked for into the key based input. It runs before the
	 * silent rotation compensation, so the assist is expressed in real camera space and then moved
	 * into the rotation the server holds, exactly like a held key would be.
	 * <p>
	 * The key input is kept and mixed in rather than replaced: at a weight of zero the vanilla
	 * vector is left exactly as the player pressed it, and in between the two are combined, so
	 * holding WASD takes over without the movement snapping or stuttering.
	 */
	private void applyAssist() {
		float weight = MovementAssist.weight();
		if (weight <= 0.001f) {
			return;
		}

		Vec2f keys = this.movementVector != null ? this.movementVector : new Vec2f(0.0f, 0.0f);
		float keep = 1.0f - weight;

		float fwd = keys.y * keep + MovementAssist.forward() * weight;
		float side = keys.x * keep + MovementAssist.sideways() * weight;

		((InputAccessor) this).setMovementVector(new Vec2f(side, fwd));

		float eps = 0.001f;
		PlayerInput pi = this.playerInput != null ? this.playerInput : PlayerInput.DEFAULT;
		this.playerInput = new PlayerInput(fwd > eps, fwd < -eps, side > eps, side < -eps, pi.jump(), pi.sneak(), pi.sprint());
	}

	/**
	 * Rotates the key input so that the movement the server simulates with the silent yaw still
	 * points where the player pointed the camera.
	 */
	private void compensateSilentRotation() {
		Vec2f in = this.movementVector;
		float fwd = in.y;
		float side = in.x;
		if (fwd == 0.0f && side == 0.0f) {
			return;
		}

		float pow = Math.max(Math.abs(fwd), Math.abs(side));
		if (pow <= 0.0f) {
			return;
		}

		float sx = side / pow;
		float sz = fwd / pow;
		float d = (SilentRot.real() - SilentRot.yaw()) * 0.017453292F;
		float cos = MathHelper.cos(d);
		float sin = MathHelper.sin(d);
		float nside = Math.round(sx * cos - sz * sin) * pow;
		float nfwd = Math.round(sz * cos + sx * sin) * pow;

		Vec2f mv = new Vec2f(nside, nfwd);
		if (mv.lengthSquared() <= 1.0E-8f) {
			return;
		}

		mv = mv.normalize();
		((InputAccessor) this).setMovementVector(mv);

		float eps = 0.001f;
		PlayerInput pi = this.playerInput != null ? this.playerInput : PlayerInput.DEFAULT;
		this.playerInput = new PlayerInput(mv.y > eps, mv.y < -eps, mv.x > eps, mv.x < -eps, pi.jump(), pi.sneak(), pi.sprint());
	}
}
