package erogenousbeef.core.multiblock;

import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.*;
import net.minecraftforge.event.world.ChunkEvent;

/**
 * In your mod, subscribe this on the server side to handle chunk load events
 * for your multiblock machines. This will guarantee that they can appropriately
 * handle being added to a world by the system instead of just by players.
 */
public class MultiblockEventHandler {
	@ForgeSubscribe(priority = EventPriority.NORMAL)
	public void onChunkLoad(ChunkEvent.Load loadEvent) {
		Chunk chunk = loadEvent.getChunk();
		World world = loadEvent.world;
		if(world.isRemote) { return; } // Ignore client chunk load events, which come in for SSP
		MultiblockRegistry.onChunkLoaded(world, ChunkCoordIntPair.chunkXZ2Int(chunk.xPosition, chunk.zPosition));
	}
	
	@ForgeSubscribe(priority = EventPriority.NORMAL)
	public void onChunkUnload(ChunkEvent.Unload unloadEvent) {
		Chunk chunk = unloadEvent.getChunk();
		World world = unloadEvent.world;
		if(world.isRemote) { return; }
		MultiblockRegistry.onChunkUnloaded(world, ChunkCoordIntPair.chunkXZ2Int(chunk.xPosition, chunk.zPosition));
	}
}
