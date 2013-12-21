package erogenousbeef.core.multiblock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import erogenousbeef.core.common.CoordTriplet;

import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;

/**
 * This class manages all the multiblock controllers that exist in a given world,
 * either client- or server-side.
 * You must create additional Registries for server or client world.
 * 
 * @author Erogenous Beef
 *
 */
public class MultiblockWorldRegistry {

	private World worldObj;
	
	private Set<MultiblockControllerBase> controllers;
	private Set<MultiblockControllerBase> deadControllers;

	private List<IMultiblockPart> detachedParts;
	private List<IMultiblockPart> incomingParts;
	
	// A list of parts whose chunks have not yet finished loading
	// They will be added to the orphan list when they are finished loading.
	// Indexed by the hashed chunk coordinate
	private HashMap<Long, Set<IMultiblockPart>> partsAwaitingChunkLoad;
	
	// A list of orphan parts - parts which currently have no master, but should seek one this tick
	// Indexed by the hashed chunk coordinate
	private Set<IMultiblockPart> orphanedParts;
	
	// A list of all active parts - parts which are known to be attached to a machine
	// Indexed by the hashed chunk coordinate
	private HashMap<Long, Set<IMultiblockPart>> parts;
	
	public MultiblockWorldRegistry(World world) {
		worldObj = world;
		
		controllers = new HashSet<MultiblockControllerBase>();
		deadControllers = new HashSet<MultiblockControllerBase>();
	}
	
	public boolean isSameWorld(World world) {
		return worldObj.provider.dimensionId == world.provider.dimensionId && worldObj.isRemote == world.isRemote;
	}
	
	public boolean isServerWorld() { return !worldObj.isRemote; }
	public boolean isClientWorld() { return worldObj.isRemote; }
	
	
	
	/**
	 * Called before Tile Entities are ticked in the world. Do bookkeeping here.
	 * @param world The world being ticked
	 */
	public void tickStart(World world) {
		for(MultiblockControllerBase controller : controllers) {
			if(controller.worldObj == world && controller.worldObj.isRemote == world.isRemote) {
				// Run the game logic for this world
				controller.updateMultiblockEntity();
			}
		}
	}
	
	/**
	 * Called after Tile Entities are ticked in the world.
	 * @param world The world being ticked
	 */
	public void tickEnd(World world) {
		IChunkProvider chunkProvider = worldObj.getChunkProvider();

		// Process incoming blocks
		// These are blocks which were removed from a machine 1 full tick ago.
		CoordTriplet coord;
		for(IMultiblockPart part : incomingParts) {
			// Check that the block's chunk exists. If it does, move it to the orphan list. Otherwise,
			// it was in a chunk that was unloading. Ignore it, it's dead.
			coord = part.getWorldLocation();
			if(chunkProvider.chunkExists(coord.getChunkX(), coord.getChunkZ())) {
				orphanedParts.add(part);
			}
		}
		incomingParts.clear();

		// TODO: This is not thread-safe. Wrap this in a mutex.
		// Merge pools - sets of adjacent machines which should be merged later on in processing
		if(orphanedParts.size() > 0) {
			Set<MultiblockControllerBase> compatibleControllers;
			List<Set<MultiblockControllerBase>> mergePools = new ArrayList<Set<MultiblockControllerBase>>();
			
			// Process orphaned blocks
			// These are blocks that exist in a valid chunk and require a machine
			for(IMultiblockPart orphan : orphanedParts) {
				// THIS IS THE ONLY PLACE WHERE PARTS ATTACH TO MACHINES
				// Try to attach to a neighbor's master controller
				compatibleControllers = orphan.attachToNeighbors();
				if(compatibleControllers == null) {
					// FOREVER ALONE! Create and register a new controller.
					// THIS IS THE ONLY PLACE WHERE NEW CONTROLLERS ARE CREATED.
				}
				else if(compatibleControllers.size() > 1) {
					// THIS IS THE ONLY PLACE WHERE MERGES ARE DETECTED
					// Multiple compatible controllers indicates an impending merge.
					// Locate the appropriate merge pool, add this set to that set.
					boolean hasAddedToPool = false;
					for(Set<MultiblockControllerBase> candidatePool : mergePools) {
						if(!Collections.disjoint(candidatePool, compatibleControllers)) {
							// They share at least one element, so that means they will all touch after the merge
							candidatePool.addAll(compatibleControllers);
							hasAddedToPool = true;
							break;
						}
					}
					
					if(!hasAddedToPool) {
						mergePools.add(compatibleControllers);
					}
				}
			}

			// TODO: Process merges
			// Any machines that have been marked for merge should be merged.
			// This must take place via "merge pools". This is a set of machines that are logically
			// touching.
		}


		// TODO: Process splits
		// Any machines which have had parts removed must be checked for splits.
		// The machine is commanded to re-evaluate itself. It detaches any parts found
		// to be disconnected from the reference coordinate.
		// Those parts are added to the 'incoming' list, here.
		
		// TODO: Unregister dead machines
		// Go through any machines which have marked themselves as potentially dead.
		// Validate that they are dead, then unregister them.
		// THIS IS THE ONLY PLACE WHERE CONTROLLERS ARE UNREGISTERED.
		
		// Process detached blocks
		// Any blocks which have been detached this tick should be moved to the Incoming
		// list, and will be checked next tick to see if their chunk is still loaded.
		// This is a separate list, as it can be modified prior to the tickEnd running.
		for(IMultiblockPart part : detachedParts) {
			// TODO: ensure parts know they're detached and shit?
		}
		
		List<IMultiblockPart> tmp = incomingParts;
		incomingParts = detachedParts;
		detachedParts = tmp;		// Swap between two objects to eliminate memory thrashing
	}

	/**
	 * Called when a part is added to the world, either via chunk-load or 
	 * @param chunkCoord
	 * @param part
	 */
	public void onPartAdded(IMultiblockPart part) {
		CoordTriplet worldLocation = part.getWorldLocation();
		if(!worldObj.getChunkProvider().chunkExists(worldLocation.getChunkX(), worldLocation.getChunkZ())) {
			// Part goes into the waiting-for-chunk-load list
			Set<IMultiblockPart> partSet;
			long chunkHash = worldLocation.getChunkXZHash();
			if(!partsAwaitingChunkLoad.containsKey(chunkHash)) {
				partSet = new HashSet<IMultiblockPart>();
				partsAwaitingChunkLoad.put(chunkHash, partSet);
			}
			else {
				partSet = partsAwaitingChunkLoad.get(chunkHash);
			}
			
			partSet.add(part);
		}
		else {
			// Part goes into the orphan queue, to be checked this tick
			orphanedParts.add(part);
		}
	}
	
	public void onPartDetached(IMultiblockPart part) {
		// Part goes into the Detached list, which will be migrated to the orphan list next tick
	}

	public void onWorldUnloaded() {
		// TODO Auto-generated method stub
		
	}

	public void onChunkLoaded(int chunkX, int chunkZ) {
		// TODO Auto-generated method stub
		
	}
}
