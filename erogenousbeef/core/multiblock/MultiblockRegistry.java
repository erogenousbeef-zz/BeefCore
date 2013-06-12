package erogenousbeef.core.multiblock;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import net.minecraft.world.ChunkCoordIntPair;

/**
 * This is a very simple static singleton registry class, used to send ticks to active multiblocks.
 * Register when your multiblock is created and unregister it when it loses its last connected block.
 */
public class MultiblockRegistry {
	private static List<MultiblockControllerBase> controllers = new LinkedList<MultiblockControllerBase>();
	private static List<MultiblockControllerBase> newControllers = new LinkedList<MultiblockControllerBase>();
	private static List<MultiblockControllerBase> deadControllers = new LinkedList<MultiblockControllerBase>();
	// Parts that need to be initialized.
	private static HashMap<Long, List<IMultiblockPart>> partsAwaitingInit = new HashMap<Long, List<IMultiblockPart>>();
	
	// Priority parts (i.e. parts containing saved data) that need to be initialized
	private static HashMap<Long, List<IMultiblockPart>> priorityPartsAwaitingInit = new HashMap<Long, List<IMultiblockPart>>();

	/**
	 * Called once per world-tick when this object is registered.
	 */
	public static void tick() {
		if(!deadControllers.isEmpty()) {
			controllers.removeAll(deadControllers);
			newControllers.removeAll(deadControllers);
			deadControllers.clear();
		}
		
		if(!newControllers.isEmpty()) {
			controllers.addAll(newControllers);
			newControllers.clear();
		}

		for(MultiblockControllerBase reactor : controllers) {
			reactor.updateMultiblockEntity();
		}
	}
	
	/**
	 * Register a multiblock machine to receive world ticks.
	 * @param reactor The machine that should begin receiving world ticks.
	 */
	public static void register(MultiblockControllerBase reactor) {
		if(!controllers.contains(reactor) && !newControllers.contains(reactor)) {
			newControllers.add(reactor);
		}
	}

	/**
	 * Unregister a multiblock machine so that it no longer receives world ticks.
	 * @param reactor The machine that should no longer receive world ticks.
	 */
	public static void unregister(MultiblockControllerBase reactor) {
		deadControllers.add(reactor);
	}
	
	/**
	 * Call this when a multiblock part loads during validate() and the chunk is not yet valid.
	 * If the chunk is valid, it means the block has been placed by a user/machine and that should,
	 * instead, be calling onBlockAdded.
	 * @param chunkCoord The hashed coord of the chunk, from ChunkCoordIntPair.chunkXZ2Int()
	 * @param part The part being loaded
	 * @param priority True if this part needs priority loading (i.e. it has saved machine data)
	 */
	public static void onPartLoaded(long chunkCoord, IMultiblockPart part, boolean priority) {
		HashMap<Long, List<IMultiblockPart>> destList = partsAwaitingInit;
		if(priority) {
			destList = priorityPartsAwaitingInit;
		}

		if(!destList.containsKey(chunkCoord)) {
			destList.put(chunkCoord, new LinkedList<IMultiblockPart>());
		}
		
		destList.get(chunkCoord).add(part);
	}
	
	public static void onChunkLoaded(long chunkCoord) {
		if(priorityPartsAwaitingInit.containsKey(chunkCoord)) {
			List<IMultiblockPart> parts = priorityPartsAwaitingInit.get(chunkCoord);
			for(IMultiblockPart part : parts) {
				part.onChunkLoad();
			}
			priorityPartsAwaitingInit.remove(chunkCoord);
			
		}

		if(partsAwaitingInit.containsKey(chunkCoord)) {
			List<IMultiblockPart> parts = partsAwaitingInit.get(chunkCoord);
			for(IMultiblockPart part : parts) {
				part.onChunkLoad();
			}
			partsAwaitingInit.remove(chunkCoord);
		}
	}
}
