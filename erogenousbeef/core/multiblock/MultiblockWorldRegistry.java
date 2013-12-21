package erogenousbeef.core.multiblock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import cpw.mods.fml.common.FMLLog;

import erogenousbeef.core.common.CoordTriplet;

import net.minecraft.world.ChunkCoordIntPair;
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
	
	private Set<MultiblockControllerBase> controllers;					// Active controllers
	private Set<MultiblockControllerBase> recalculatableControllers;	// Controllers whose parts lists have changed
	private Set<MultiblockControllerBase> deadControllers;				// Controllers which are empty

	// A list of orphan parts - parts which currently have no master, but should seek one this tick
	// Indexed by the hashed chunk coordinate
	private Set<IMultiblockPart> orphanedParts;

	// A list of parts which have been detached during internal operations
	private Set<IMultiblockPart> detachedParts;
	
	// A list of parts which were detached during the last frame.
	// TODO: Can we remove detachedParts and just add parts directly to this list?
	private Set<IMultiblockPart> incomingParts;

	// A list of parts whose chunks have not yet finished loading
	// They will be added to the orphan list when they are finished loading.
	// Indexed by the hashed chunk coordinate
	private HashMap<Long, Set<IMultiblockPart>> partsAwaitingChunkLoad;
	
	// A list of all active parts - parts which are known to be attached to a machine
	// Indexed by the hashed chunk coordinate
	private HashMap<Long, Set<IMultiblockPart>> activeParts;
	
	public MultiblockWorldRegistry(World world) {
		worldObj = world;
		
		controllers = new HashSet<MultiblockControllerBase>();
		deadControllers = new HashSet<MultiblockControllerBase>();
		recalculatableControllers = new HashSet<MultiblockControllerBase>();
		
		detachedParts = new HashSet<IMultiblockPart>();
		incomingParts = new HashSet<IMultiblockPart>();
		orphanedParts = new HashSet<IMultiblockPart>();

		partsAwaitingChunkLoad = new HashMap<Long, Set<IMultiblockPart>>();
		activeParts = new HashMap<Long, Set<IMultiblockPart>>();
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
		if(controllers.size() > 0) {
			for(MultiblockControllerBase controller : controllers) {
				if(controller.worldObj == world && controller.worldObj.isRemote == world.isRemote) {
					if(controller.isEmpty()) {
						FMLLog.warning("Found an empty controller at the beginning of a world tick. This shouldn't happen. Marking it dead and skipping it.");
						deadControllers.add(controller);
					}
					else {
						// Run the game logic for this world
						controller.updateMultiblockEntity();
					}
				}
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
					FMLLog.info("[DEBUG] Creating a new controller for part @ %s", orphan.getWorldLocation());
					MultiblockControllerBase newController = orphan.createNewMultiblock();
					newController.attachBlock(orphan);
					this.controllers.add(newController);
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

			// Process merges - any machines that have been marked for merge should be merged
			// into the "master" machine.
			// To do this, we combine lists of machines that are touching one another and therefore
			// should voltron the fuck up.
			for(Set<MultiblockControllerBase> mergePool : mergePools) {
				
				// Search for the new master machine, which will take over all the blocks contained in the other machines
				MultiblockControllerBase newMaster = null;
				for(MultiblockControllerBase controller : mergePool) {
					if(newMaster == null || controller.shouldConsume(newMaster)) {
						newMaster = controller;
					}
				}
				
				if(newMaster == null) {
					FMLLog.severe("Multiblock system checked a merge pool of size %d, found no master candidates. This should never happen.", mergePool.size());
				}
				else {
					// Merge all the other machines into the master machine, then unregister them
					for(MultiblockControllerBase controller : mergePool) {
						if(controller != newMaster) {
							newMaster.merge(controller);
							controller.onMachineMerge(newMaster);
							addDeadController(controller);
						}
					}
				}
			}
		}

		// Process splits
		// Any controllers which have had parts removed must be checked to see if some parts are no longer
		// physically connected to their master.
		if(recalculatableControllers.size() > 0) {
			Set<IMultiblockPart> newlyDetachedParts = null;
			for(MultiblockControllerBase controller : recalculatableControllers) {
				// Tell the machine to check if any parts are disconnected.
				// It should return a set of parts which are no longer connected.
				// POSTCONDITION: The controller must have informed those parts that
				// they are no longer connected to this machine.
				newlyDetachedParts = controller.checkIfMachineIsWhole();
				
				if(newlyDetachedParts != null && newlyDetachedParts.size() > 0) {
					// Controller has shed some parts - add them to the detached list for delayed processing
					detachedParts.addAll(newlyDetachedParts);
				}
			}
			
			recalculatableControllers.clear();
		}
		
		// TODO: Unregister dead machines
		// Go through any machines which have marked themselves as potentially dead.
		// Validate that they are dead, then unregister them.
		// THIS IS THE ONLY PLACE WHERE CONTROLLERS ARE UNREGISTERED.
		if(deadControllers.size() > 0) {
			for(MultiblockControllerBase controller : deadControllers) {
				if(!controller.isEmpty()) {
					FMLLog.severe("Found a non-empty controller. Forcing it to shed its blocks and die. This should never happen!");
					detachedParts.addAll(controller.detachAllBlocks());
				}
				else {
					FMLLog.info("[DEBUG] Successfully removing dead controller %d", controller.hashCode());
					this.controllers.remove(controller);
				}
			}
			
			deadControllers.clear();
		}
		
		// Process detached blocks
		// Any blocks which have been detached this tick should be moved to the Incoming
		// list, and will be checked next tick to see if their chunk is still loaded.
		// This is a separate list, as it can be modified prior to the tickEnd running.
		for(IMultiblockPart part : detachedParts) {
			// TODO: ensure parts know they're detached and shit?
			part.assertDetached();
		}
		
		Set<IMultiblockPart> tmp = incomingParts;
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
	
	/**
	 * Called when a part is removed from the world, via user action or via chunk unloads.
	 * This part is removed from any lists in which it may be, and its machine is marked for recalculation.
	 * @param part The part which is being removed.
	 */
	public void onPartRemovedFromWorld(IMultiblockPart part) {
		CoordTriplet coord = part.getWorldLocation();
		if(coord != null) {
			long hash = coord.getChunkXZHash();
			if(activeParts.containsKey(hash)) {
				activeParts.get(hash).remove(part);
				if(activeParts.get(hash).size() <= 0) {
					activeParts.remove(hash);
				}
			}
			
			if(partsAwaitingChunkLoad.containsKey(hash)) {
				partsAwaitingChunkLoad.get(hash).remove(part);
				if(partsAwaitingChunkLoad.get(hash).size() <= 0) {
					partsAwaitingChunkLoad.remove(hash);
				}
			}
		}

		detachedParts.remove(part);
		incomingParts.remove(part);
		orphanedParts.remove(part);
		
		part.assertDetached();
	}

	/**
	 * Called when the world which this World Registry represents is fully unloaded from the system.
	 * Does some housekeeping just to be nice.
	 */
	public void onWorldUnloaded() {
		controllers.clear();
		deadControllers.clear();
		recalculatableControllers.clear();
		
		detachedParts.clear();
		incomingParts.clear();
		partsAwaitingChunkLoad.clear();
		activeParts.clear();
		
		worldObj = null;
	}

	/**
	 * Called when a chunk has finished loading. Adds all of the parts which are awaiting
	 * load to the list of parts which are orphans and therefore will be added to machines
	 * after the next world tick.
	 * 
	 * @param chunkX Chunk X coordinate (world coordate >> 4) of the chunk that was loaded
	 * @param chunkZ Chunk Z coordinate (world coordate >> 4) of the chunk that was loaded
	 */
	public void onChunkLoaded(int chunkX, int chunkZ) {
		long chunkHash = ChunkCoordIntPair.chunkXZ2Int(chunkX, chunkZ);
		if(partsAwaitingChunkLoad.containsKey(chunkHash)) {
			orphanedParts.addAll(partsAwaitingChunkLoad.get(chunkHash));
			partsAwaitingChunkLoad.remove(chunkHash);
		}
	}

	/**
	 * Registers a controller as dead. It will be cleaned up at the end of the next world tick.
	 * Note that a controller must shed all of its blocks before being marked as dead, or the system
	 * will complain at you.
	 * 
	 * @param deadController The controller which is dead.
	 */
	public void addDeadController(MultiblockControllerBase deadController) {
		this.deadControllers.add(deadController);
	}
}
