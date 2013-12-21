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
	
	private Set<MultiblockControllerBase> controllers;		// Active controllers
	private Set<MultiblockControllerBase> dirtyControllers;	// Controllers whose parts lists have changed
	private Set<MultiblockControllerBase> deadControllers;	// Controllers which are empty

	// A list of orphan parts - parts which currently have no master, but should seek one this tick
	// Indexed by the hashed chunk coordinate
	private Set<IMultiblockPart> orphanedParts;

	// A list of parts which have been detached during internal operations
	private Set<IMultiblockPart> detachedParts;
	
	// A list of parts whose chunks have not yet finished loading
	// They will be added to the orphan list when they are finished loading.
	// Indexed by the hashed chunk coordinate
	private HashMap<Long, Set<IMultiblockPart>> partsAwaitingChunkLoad;
	
	public MultiblockWorldRegistry(World world) {
		worldObj = world;
		
		controllers = new HashSet<MultiblockControllerBase>();
		deadControllers = new HashSet<MultiblockControllerBase>();
		dirtyControllers = new HashSet<MultiblockControllerBase>();
		
		detachedParts = new HashSet<IMultiblockPart>();
		orphanedParts = new HashSet<IMultiblockPart>();

		partsAwaitingChunkLoad = new HashMap<Long, Set<IMultiblockPart>>();
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
	public void tickStart() {
		if(controllers.size() > 0) {
			for(MultiblockControllerBase controller : controllers) {
				if(controller.worldObj == worldObj && controller.worldObj.isRemote == worldObj.isRemote) {
					if(controller.isEmpty()) {
						FMLLog.warning("Found an empty controller at the beginning of a world tick. This shouldn't happen. Marking it dead and skipping it.");
						deadControllers.add(controller);
					}
					else {
						// Run the game logic for this world
						controller.updateMultiblockEntity();
					}
				}
				else {
					FMLLog.info("Controller %d is registered in the wrong world!", controller.hashCode());
				}
			}
		}
	}
	
	/**
	 * Called after Tile Entities are ticked in the world.
	 * @param world The world being ticked
	 */
	public void tickEnd() {
		IChunkProvider chunkProvider = worldObj.getChunkProvider();
		CoordTriplet coord;

		// TODO: This is not thread-safe. Wrap this in a mutex.
		// Merge pools - sets of adjacent machines which should be merged later on in processing
		if(orphanedParts.size() > 0) {
			FMLLog.info("World registry processing %d orphans", orphanedParts.size());
			Set<MultiblockControllerBase> compatibleControllers;
			List<Set<MultiblockControllerBase>> mergePools = new ArrayList<Set<MultiblockControllerBase>>();
			
			// Process orphaned blocks
			// These are blocks that exist in a valid chunk and require a machine
			for(IMultiblockPart orphan : orphanedParts) {
				coord = orphan.getWorldLocation();
				if(!chunkProvider.chunkExists(coord.getChunkX(), coord.getChunkZ())) {
					FMLLog.info("Orphaned part at %s is in an invalid chunk, ignoring!", coord);
					continue;
				}
				
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
					FMLLog.info("Part @ %s has %d nearby compatible controllers, attached to %d and will merge the others", coord, compatibleControllers.size(), orphan.getMultiblockController().hashCode());
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
				else {
					FMLLog.info("Part @ %s has attached to controller %d", coord, orphan.getMultiblockController().hashCode());
				}
			}
			orphanedParts.clear();

			// Process merges - any machines that have been marked for merge should be merged
			// into the "master" machine.
			// To do this, we combine lists of machines that are touching one another and therefore
			// should voltron the fuck up.
			for(Set<MultiblockControllerBase> mergePool : mergePools) {
				FMLLog.info("Merging a pool of %d controllers", mergePool.size());
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
					// TODO: Verify this
					// Merge all the other machines into the master machine, then unregister them
					addDirtyController(newMaster);
					for(MultiblockControllerBase controller : mergePool) {
						if(controller != newMaster) {
							newMaster.assimilate(controller);
							addDeadController(controller);
						}
					}
				}
			}
		}

		// Process splits
		// Any controllers which have had parts removed must be checked to see if some parts are no longer
		// physically connected to their master.
		if(dirtyControllers.size() > 0) {
			// TODO: Verify this
			Set<IMultiblockPart> newlyDetachedParts = null;
			for(MultiblockControllerBase controller : dirtyControllers) {
				// Tell the machine to check if any parts are disconnected.
				// It should return a set of parts which are no longer connected.
				// POSTCONDITION: The controller must have informed those parts that
				// they are no longer connected to this machine.
				newlyDetachedParts = controller.checkForDisconnections();
				
				if(!controller.isEmpty()) {
					controller.checkIfMachineIsWhole();
					controller.recalculateMinMaxCoords();
				}
				else {
					addDeadController(controller);
				}
				
				if(newlyDetachedParts != null && newlyDetachedParts.size() > 0) {
					// Controller has shed some parts - add them to the detached list for delayed processing
					detachedParts.addAll(newlyDetachedParts);
				}
			}
			
			dirtyControllers.clear();
		}
		
		// Unregister dead controllers
		if(deadControllers.size() > 0) {
			for(MultiblockControllerBase controller : deadControllers) {
				// Go through any controllers which have marked themselves as potentially dead.
				// Validate that they are empty/dead, then unregister them.
				if(!controller.isEmpty()) {
					FMLLog.severe("Found a non-empty controller. Forcing it to shed its blocks and die. This should never happen!");
					detachedParts.addAll(controller.detachAllBlocks());
				}
				else {
					FMLLog.info("[DEBUG] Successfully removing dead controller %d", controller.hashCode());
				}

				// THIS IS THE ONLY PLACE WHERE CONTROLLERS ARE UNREGISTERED.
				this.controllers.remove(controller);
			}
			
			deadControllers.clear();
		}
		
		// Process detached blocks
		// Any blocks which have been detached this tick should be moved to the orphaned
		// list, and will be checked next tick to see if their chunk is still loaded.
		for(IMultiblockPart part : detachedParts) {
			// Ensure parts know they're detached
			part.assertDetached();
		}
		
		orphanedParts.addAll(detachedParts);
		detachedParts.clear();
	}

	/**
	 * Called when a part is added to the world, either via chunk-load or 
	 * @param chunkCoord
	 * @param part
	 */
	public void onPartAdded(IMultiblockPart part) {
		FMLLog.info("Adding new part to world %s at %s", worldObj.toString(), part.getWorldLocation());
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
			
			FMLLog.info("[%s] Adding new part @ %s to chunkload list for chunk %d, %d", clientOrServer(), worldLocation, worldLocation.getChunkX(), worldLocation.getChunkZ());
			partSet.add(part);
		}
		else {
			// Part goes into the orphan queue, to be checked this tick
			orphanedParts.add(part);
			FMLLog.info("[%s] Adding new part @ %s to orphan list, which is now size %d", clientOrServer(), worldLocation, orphanedParts.size());
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
			if(partsAwaitingChunkLoad.containsKey(hash)) {
				partsAwaitingChunkLoad.get(hash).remove(part);
				if(partsAwaitingChunkLoad.get(hash).size() <= 0) {
					partsAwaitingChunkLoad.remove(hash);
				}
			}
		}

		detachedParts.remove(part);
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
		dirtyControllers.clear();
		
		detachedParts.clear();
		partsAwaitingChunkLoad.clear();
		
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

	/**
	 * Registers a controller as dirty - its list of attached blocks has changed, and it
	 * must be re-checked for assembly and, possibly, for orphans.
	 * 
	 * @param dirtyController The dirty controller.
	 */
	public void addDirtyController(MultiblockControllerBase dirtyController) {
		this.dirtyControllers.add(dirtyController);
	}
	
	private String clientOrServer() {
		return worldObj.isRemote ? "CLIENT":"SERVER";
	}
}
