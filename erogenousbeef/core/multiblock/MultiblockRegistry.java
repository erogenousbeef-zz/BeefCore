package erogenousbeef.core.multiblock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;

/**
 * This is a very simple static singleton registry class, used to send ticks to active multiblocks.
 * Register when your multiblock is created and unregister it when it loses its last connected block.
 */
public class MultiblockRegistry {
	// World > WorldRegistry map
	private static HashMap<World, MultiblockWorldRegistry> registries = new HashMap<World, MultiblockWorldRegistry>();
	
	// Parts that need to be initialized.
	private static HashMap<Integer, HashMap<Long, List<IMultiblockPart>>> partsAwaitingInit = new HashMap<Integer, HashMap<Long, List<IMultiblockPart>>>();
	
	// All parts that are active, indexed by dimension and chunk.
	private static HashMap<Integer, HashMap<Long, List<IMultiblockPart>>> loadedParts = new HashMap<Integer, HashMap<Long, List<IMultiblockPart>>>();

	/**
	 * Called before Tile Entities are ticked in the world. Do bookkeeping here.
	 * @param world The world being ticked
	 */
	public static void tickStart(World world) {
		if(registries.containsKey(world)) {
			MultiblockWorldRegistry registry = registries.get(world);
			if(!registry.isSameWorld(world)) {
				throw new IllegalArgumentException("Mismatched world in world registry - this should not happen!");
			}
			
			registry.tickStart(world);
		}
	}
	
	/**
	 * Called after Tile Entities are ticked in the world.
	 * @param world The world being ticked
	 */
	public static void tickEnd(World world) {
		if(registries.containsKey(world)) {
			MultiblockWorldRegistry registry = registries.get(world);
			if(!registry.isSameWorld(world)) {
				throw new IllegalArgumentException("Mismatched world in world registry - this should not happen!");
			}
			
			registry.tickEnd(world);
		}
	}
	
	/**
	 * Called when the world has finished loading a chunk.
	 * @param world The world which has finished loading a chunk
	 * @param hashedChunkCoord The hashed XZ coordinates of the chunk.
	 * @param zPosition 
	 */
	public static void onChunkLoaded(World world, int chunkX, int chunkZ) {
		if(registries.containsKey(world)) {
			registries.get(world).onChunkLoaded(chunkX, chunkZ);
		}
	}

	/**
	 * Register a new part in the system. The part has been created either through user action or via a chunk loading.
	 * @param world The world into which this part is loading.
	 * @param chunkCoord The chunk at which this part is located.
	 * @param part The part being loaded.
	 */
	public static void registerNewPart(World world, IMultiblockPart part) {
		MultiblockWorldRegistry registry = getOrCreateRegistry(world);
		registry.onPartAdded(part);
	}
	
	/**
	 * Called whenever a world is unloaded. Unload the relevant registry, if we have one.
	 * @param world The world being unloaded.
	 */
	public static void onWorldUnloaded(World world) {
		if(registries.containsKey(world)) {
			registries.get(world).onWorldUnloaded();
			registries.remove(world);
		}
	}
	
	/// *** PRIVATE HELPERS *** ///
	
	private static MultiblockWorldRegistry getOrCreateRegistry(World world) {
		if(registries.containsKey(world)) {
			return registries.get(world);
		}
		else {
			MultiblockWorldRegistry newRegistry = new MultiblockWorldRegistry(world);
			registries.put(world, newRegistry);
			return newRegistry;
		}
	}
}
