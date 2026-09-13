package com.example.chunkpreload;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Only ever loaded if Mod Menu is actually installed - Fabric only invokes the
 * "modmenu" entrypoint when Mod Menu itself asks for it, so this is safe to ship
 * even for players who don't have Mod Menu.
 */
public class ChunkPreloadModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return ChunkPreloadConfigScreen::create;
	}
}