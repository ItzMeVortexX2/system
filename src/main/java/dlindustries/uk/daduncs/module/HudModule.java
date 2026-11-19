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

package dlindustries.uk.daduncs.module;

import dlindustries.uk.daduncs.gui.HudEditor;
import dlindustries.uk.daduncs.module.setting.number;
import dlindustries.uk.daduncs.utils.EncryptedString;

/**
 * Base for every HUD element. Adds X and Y settings so the element can be placed from the module
 * settings, and keeps those settings in sync with the drag and drop HUD editor.
 */
public abstract class HudModule extends Module {

	private final number posX;
	private final number posY;

	private int knownX;
	private int knownY;

	protected HudModule(CharSequence name, CharSequence description, int key, Category category, int defaultX, int defaultY) {
		super(name, description, key, category);

		this.knownX = defaultX;
		this.knownY = defaultY;

		this.posX = new number(EncryptedString.of("Hud X"), 0, 4000, defaultX, 1)
				.setDescription(EncryptedString.of("Horizontal position of this HUD element"));
		this.posY = new number(EncryptedString.of("Hud Y"), 0, 4000, defaultY, 1)
				.setDescription(EncryptedString.of("Vertical position of this HUD element"));

		addSettings(posX, posY);
		HudEditor.register(this);
	}

	protected int hudX() {
		syncPosition();
		return knownX;
	}

	protected int hudY() {
		syncPosition();
		return knownY;
	}

	public number getPosX() {
		return posX;
	}

	public number getPosY() {
		return posY;
	}

	/**
	 * The settings win when they were just moved, otherwise the drag and drop editor wins.
	 */
	private void syncPosition() {
		int sliderX = (int) Math.round(posX.getValue());
		int sliderY = (int) Math.round(posY.getValue());

		int editorX = HudEditor.x(this, knownX);
		int editorY = HudEditor.y(this, knownY);

		if (sliderX != knownX || sliderY != knownY) {
			HudEditor.place(this, sliderX, sliderY);
			knownX = sliderX;
			knownY = sliderY;
			return;
		}

		if (editorX != knownX || editorY != knownY) {
			posX.setValue(editorX);
			posY.setValue(editorY);
			knownX = editorX;
			knownY = editorY;
		}
	}
}
