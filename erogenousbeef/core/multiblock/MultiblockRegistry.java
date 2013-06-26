package erogenousbeef.core.multiblock;

import java.util.ArrayList;
import java.util.HashMap;
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
	// TODO: Index this by dimension
	private static Set<MultiblockControllerBase> controllers = new CopyOnWriteArraySet<MultiblockControllerBase>();
	
	// Parts that need to be initialized.
	private static HashMap<Integer, HashMap<Long, List<IMultiblockPart>>> partsAwaitingInit = new HashMap<Integer, HashMap<Long, List<IMultiblockPart>>>();
	
	// Priority parts (i.e. parts containing saved data) that need to be initialized
	private static HashMap<Integer, HashMap<Long, List<IMultiblockPart>>> priorityPartsAwaitingInit = new HashMap<Integer, HashMap<Long, List<IMultiblockPart>>>();

	// All parts that are active, indexed by dimension and chunk.
	private static HashMap<Integer, HashMap<Long, List<IMultiblockPart>>> loadedParts = new HashMap<Integer, HashMap<Long, List<IMultiblockPart>>>();
	
	/**
	 * Called once per world-tick when this object is registered.
	 */
	public static void tick(World world) {
		for(MultiblockControllerBase reactor : controllers) {
			if(reactor.worldObj == world && reactor.worldObj.isRemote == world.isRemote) {
				reactor.updateMultiblockEntity();
			}
		}
	}
	
	/**
	 * Register a multiblock machine to receive world ticks.
	 * @param reactor The machine that should begin receiving world ticks.
	 */
	public static void register(MultiblockControllerBase reactor) {
		controllers.add(reactor);
	}

	/**
	 * Unregister a multiblock machine so that it no longer receives world ticks.
	 * @param reactor The machine that should no longer receive world ticks.
	 */
	public static void unregister(MultiblockControllerBase reactor) {
		controllers.remove(reactor);
	}
	
	/**
	 * Call this when a multiblock part loads during validate() and the chunk is not yet valid.
	 * If the chunk is valid, it means the block has been placed by a user/machine and that should,
	 * instead, be calling onBlockAdded.
	 * @param chunkCoord The hashed coord of the chunk, from ChunkCoordIntPair.chunkXZ2Int()
	 * @param part The part being loaded
	 * @param priority True if this part needs priority loading (i.e. it has saved machine data)
	 */
	public static void onPartLoaded(World world, long chunkCoord, IMultiblockPart part, boolean priority) {
		HashMap<Integer, HashMap<Long, List<IMultiblockPart>>> destList = partsAwaitingInit;
		if(priority) {
			destList = priorityPartsAwaitingInit;
		}
		
		int dimensionId = world.provider.dimensionId;
		putPartInList(destList, dimensionId, chunkCoord, part);
	}
	
	public static void onChunkLoaded(World world, long chunkCoord) {
		int dimensionId = world.provider.dimensionId;
		List<IMultiblockPart> parts = getPartListForWorldChunk(priorityPartsAwaitingInit, dimensionId, chunkCoord);
		if(parts != null) {
			for(IMultiblockPart part : parts) {
				part.onChunkLoad();
			}
			priorityPartsAwaitingInit.get(dimensionId).remove(chunkCoord);
		}
		
		parts = getPartListForWorldChunk(partsAwaitingInit, dimensionId, chunkCoord);
		if(parts != null) {
			for(IMultiblockPart part : parts) {
				part.onChunkLoad();
			}
			partsAwaitingInit.get(dimensionId).remove(chunkCoord);
		}
	}
	
	public static void onChunkUnloaded(World world, long chunkCoord) {
		int dimensionId = world.provider.dimensionId;
		List<IMultiblockPart> parts = getPartListForWorldChunk(loadedParts, dimensionId, chunkCoord);
		if(parts != null) {
			for(IMultiblockPart part : parts) {
				part.onChunkUnloaded();
			}
			loadedParts.get(dimensionId).remove(chunkCoord);
		}
	}

	/**
	 * Register a part as having been loaded, regardless of whether it has been initialized or not.
	 * @param world The world into which this part is loading.
	 * @param chunkCoord The chunk at which this part is located.
	 * @param part The part being loaded.
	 */
	public static void registerPart(World world, long chunkCoord, IMultiblockPart part) {
		putPartInList(loadedParts, world.provider.dimensionId, chunkCoord, part);
	}
	
	/// *** PRIVATE HELPERS *** ///

	private static List<IMultiblockPart> getPartListForWorldChunk(HashMap<Integer, HashMap<Long, List<IMultiblockPart>>> sourceList, int dimensionId, long chunkCoord) {
		if(!sourceList.containsKey(dimensionId)) {
			return null;
		}
		
		if(!sourceList.get(dimensionId).containsKey(chunkCoord)) {
			return null;
		}
		
		return sourceList.get(dimensionId).get(chunkCoord);
	}
	
	private static void putPartInList(HashMap<Integer, HashMap<Long, List<IMultiblockPart>>> destList, int dimensionId, long chunkCoord, IMultiblockPart part) {
		if(!destList.containsKey(dimensionId)) {
			destList.put(dimensionId, new HashMap<Long, List<IMultiblockPart>>());
		}
		
		HashMap<Long, List<IMultiblockPart>> innerMap = destList.get(dimensionId);
		
		if(!innerMap.containsKey(chunkCoord)) {
			innerMap.put(chunkCoord, new ArrayList<IMultiblockPart>());
		}
		
		innerMap.get(chunkCoord).add(part);
	}
}
