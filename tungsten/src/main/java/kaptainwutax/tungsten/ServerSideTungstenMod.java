package kaptainwutax.tungsten;

import net.fabricmc.api.DedicatedServerModInitializer;

// Server-side fakeplayerapi support disabled (requires io.github.hackerokuz:fakeplayerapi)
public class ServerSideTungstenMod implements DedicatedServerModInitializer {

	public void onInitializeServer() {
		// no-op: fakeplayerapi not available
	}

}
