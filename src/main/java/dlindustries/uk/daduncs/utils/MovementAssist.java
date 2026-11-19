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

package dlindustries.uk.daduncs.utils;

/**
 * A single slot for module driven movement input.
 * <p>
 * The keyboard mixin applies these values to the same vector vanilla builds from the WASD keys,
 * so whatever writes here gets analog movement instead of on/off key presses. Values are relative
 * to the real camera, exactly like the keys: positive forward is W, positive sideways is A, and
 * both stay in the range -1 to 1.
 * <p>
 * The weight is how much of the assist should be used at all. At zero the mixin leaves the real
 * key input completely untouched, which is what keeps held keys from fighting the assist.
 */
public final class MovementAssist {
	private static float sideways;
	private static float forward;
	private static float weight;
	private static boolean active;

	private MovementAssist() {
	}

	public static void set(float sideways, float forward) {
		MovementAssist.sideways = Math.max(-1.0f, Math.min(1.0f, sideways));
		MovementAssist.forward = Math.max(-1.0f, Math.min(1.0f, forward));
		MovementAssist.active = true;
	}

	public static void setWeight(float weight) {
		MovementAssist.weight = Math.max(0.0f, Math.min(1.0f, weight));
	}

	public static void clear() {
		sideways = 0.0f;
		forward = 0.0f;
		weight = 0.0f;
		active = false;
	}

	public static boolean active() {
		return active;
	}

	public static float weight() {
		return weight;
	}

	public static float sideways() {
		return sideways;
	}

	public static float forward() {
		return forward;
	}
}
