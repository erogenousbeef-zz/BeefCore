package erogenousbeef.core.multiblock;

import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import erogenousbeef.core.common.CoordTriplet;

/**
 * This class contains the base logic for "multiblock controllers". You can think of them
 * as meta-TileEntities. They govern the logic for an associated group of TileEntities.
 * 
 * Subordinate TileEntities implement the IMultiblockPart class and, generally, should not have an update() loop.
 */
public abstract class MultiblockControllerBase {
	// Multiblock stuff - do not mess with
	protected World worldObj;
	
	// Disassembled -> Assembled; Assembled -> Disassembled OR Paused; Paused -> Assembled
	protected enum AssemblyState { Disassembled, Assembled, Paused };
	protected AssemblyState assemblyState;

	protected Set<CoordTriplet> connectedBlocks;
	
	/** This is a deterministically-picked coordinate that identifies this
	 * multiblock uniquely in its dimension.
	 * Currently, this is the coord with the lowest X, Y and Z coordinates, in that order of evaluation.
	 * i.e. If something has a lower X but higher Y/Z coordinates, it will still be the reference.
	 * If something has the same X but a lower Y coordinate, it will be the reference. Etc.
	 */
	protected CoordTriplet referenceCoord;

	/**
	 * Minimum bounding box coordinate. Blocks do not necessarily exist at this coord if your machine
	 * is not a cube/rectangular prism.
	 */
	private CoordTriplet minimumCoord;

	/**
	 * Maximum bounding box coordinate. Blocks do not necessarily exist at this coord if your machine
	 * is not a cube/rectangular prism.
	 */
	private CoordTriplet maximumCoord;
	
	/** 
	 * Set to true when adding/removing blocks to the controller.
	 * If true, the controller will check to see if the machine
	 * should be assembled/disassembled on the next tick.
	 */
	private boolean blocksHaveChangedThisFrame;
	
	/**
	 * Set to true when all blocks unloading this frame are unloading due to
	 * chunk unloading.
	 */
	private boolean chunksHaveUnloaded;
	
	protected MultiblockControllerBase(World world) {
		// Multiblock stuff
		worldObj = world;
		connectedBlocks = new CopyOnWriteArraySet<CoordTriplet>(); // We need this for thread-safety
		referenceCoord = null;
		assemblyState = AssemblyState.Disassembled;

		minimumCoord = new CoordTriplet(0,0,0);
		maximumCoord = new CoordTriplet(0,0,0);
		
		blocksHaveChangedThisFrame = false;
		chunksHaveUnloaded = false;
	}

	/**
	 * Call when the save delegate block finishes loading its chunk.
	 * Immediately call attachBlock after this, or risk your multiblock
	 * being destroyed!
	 * @param savedData The NBT tag containing this controller's data.
	 */
	public void restore(NBTTagCompound savedData) {
		this.readFromNBT(savedData);
		MultiblockRegistry.register(this);
	}
	
	/**
	 * Check if a block is being tracked by this machine.
	 * @param blockCoord Coordinate to check.
	 * @return True if the tile entity at blockCoord is being tracked by this machine, false otherwise.
	 */
	public boolean hasBlock(CoordTriplet blockCoord) {
		return connectedBlocks.contains(blockCoord);
	}
	
	/**
	 * Call this to attach a block to this machine. Generally, you want to call this when
	 * the block is added to the world.
	 * @param part The part representing the block to attach to this machine.
	 */
	public void attachBlock(IMultiblockPart part) {
		IMultiblockPart candidate;
		CoordTriplet coord = part.getWorldLocation();
		boolean firstBlock = this.connectedBlocks.isEmpty();

		// No need to re-add a block
		if(connectedBlocks.contains(coord)) {
			return;
		}

		connectedBlocks.add(coord);
		part.onAttached(this);
		this.onBlockAdded(part);
		this.worldObj.markBlockForUpdate(coord.x, coord.y, coord.z);
		
		if(firstBlock) {
			MultiblockRegistry.register(this);
		}

		if(this.referenceCoord == null) {
			referenceCoord = coord;
			part.becomeMultiblockSaveDelegate();
		}
		else if(coord.compareTo(referenceCoord) < 0) {
			TileEntity te = this.worldObj.getBlockTileEntity(referenceCoord.x, referenceCoord.y, referenceCoord.z);
			((IMultiblockPart)te).forfeitMultiblockSaveDelegate();
			
			referenceCoord = coord;
			part.becomeMultiblockSaveDelegate();
		}
		
		blocksHaveChangedThisFrame = true;
	}

	/**
	 * Called when a new part is added to the machine. Good time to register things into lists.
	 * @param newPart The part being added.
	 */
	protected abstract void onBlockAdded(IMultiblockPart newPart);

	/**
	 * Called when a part is removed from the machine. Good time to clean up lists.
	 * @param oldPart The part being removed.
	 */
	protected abstract void onBlockRemoved(IMultiblockPart oldPart);
	
	/**
	 * Called when a machine is assembled from a disassembled state.
	 */
	protected abstract void onMachineAssembled();
	
	/**
	 * Called when a machine is restored to the assembled state from a paused state.
	 */
	protected abstract void onMachineRestored();

	/**
	 * Called when a machine is paused from an assembled state
	 * This generally only happens due to chunk-loads and other "system" events.
	 */
	protected abstract void onMachinePaused();
	
	/**
	 * Called when a machine is disassembled from an assembled state.
	 * This happens due to user or in-game actions (e.g. explosions)
	 */
	protected abstract void onMachineDisassembled();
	
	/**
	 * Call to detach a block from this machine. Generally, this should be called
	 * when the tile entity is being released, e.g. on block destruction.
	 * @param part The part to detach from this machine.
	 * @param chunkUnloading Is this entity detaching due to the chunk unloading? If true, the multiblock will be paused instead of broken.
	 */
	public void detachBlock(IMultiblockPart part, boolean chunkUnloading) {
		_detachBlock(part, chunkUnloading);
		
		// If we've lost blocks while disassembled, split up our machine. Can result in up to 6 new TEs.
		if(!chunkUnloading && this.assemblyState == AssemblyState.Disassembled) {
			this.revisitBlocks();
		}
	}

	/**
	 * Internal helper that can be safely called for internal use
	 * Does not trigger fission.
	 * @param part The part to detach from this machine.
	 * @param chunkUnloading Is this entity detaching due to the chunk unloading? If true, the multiblock will be paused instead of broken.
	 */
	private void _detachBlock(IMultiblockPart part, boolean chunkUnloading) {
		CoordTriplet coord = part.getWorldLocation();
		if(chunkUnloading) {
			if(this.assemblyState == AssemblyState.Assembled) {
				this.assemblyState = AssemblyState.Paused;
				this.pauseMachine();
			}
		}

		if(connectedBlocks.contains(coord)) {
			part.onDetached(this);
		}
		
		while(connectedBlocks.contains(coord))
		{
			connectedBlocks.remove(coord);
			this.onBlockRemoved(part);

			if(referenceCoord != null && referenceCoord.equals(coord)) {
				part.forfeitMultiblockSaveDelegate();
				referenceCoord = null;
			}
		}

		if(connectedBlocks.isEmpty()) {
			// Destroy/unregister
			MultiblockRegistry.unregister(this);
			return;
		}

		if(!blocksHaveChangedThisFrame && chunkUnloading) {
			// If the first change this frame is a chunk unload, set to true.
			this.chunksHaveUnloaded = true;
		}
		else if(this.chunksHaveUnloaded) {
			// If we get multiple unloads in a frame, any one of them being false flips this false too.
			this.chunksHaveUnloaded = chunkUnloading;
		}

		blocksHaveChangedThisFrame = true;

		// Find new save delegate if we need to.
		if(referenceCoord == null) {
			// ConnectedBlocks can be empty due to chunk unloading. This is OK. We'll die next frame.
			if(!this.connectedBlocks.isEmpty()) {
				for(CoordTriplet connectedCoord : connectedBlocks) {
					TileEntity te = this.worldObj.getBlockTileEntity(connectedCoord.x, connectedCoord.y, connectedCoord.z);
					if(te == null) { continue; } // Chunk unload has removed this block. It'll get hit soon. Ignore it.
					
					if(referenceCoord == null) {
						referenceCoord = connectedCoord;
					}
					else if(connectedCoord.compareTo(referenceCoord) < 0) {
						referenceCoord = connectedCoord;
					}
				}
			}

			if(referenceCoord != null) {
				TileEntity te = this.worldObj.getBlockTileEntity(referenceCoord.x, referenceCoord.y, referenceCoord.z);
				((IMultiblockPart)te).becomeMultiblockSaveDelegate();
			}
		}
	}

	/**
	 * Helper method so we don't check for a whole machine until we have enough blocks
	 * to actually assemble it. This isn't as simple as xmax*ymax*zmax for non-cubic machines
	 * or for machines with hollow/complex interiors.
	 * @return The minimum number of blocks connected to the machine for it to be assembled.
	 */
	protected abstract int getMinimumNumberOfBlocksForAssembledMachine();

	/**
	 * @return True if the machine is "whole" and should be assembled. False otherwise.
	 */
	protected boolean isMachineWhole() {
		if(connectedBlocks.size() >= getMinimumNumberOfBlocksForAssembledMachine()) {
			// Now we run a simple check on each block within that volume.
			// Any block deviating = NO DEAL SIR
			TileEntity te;
			IMultiblockPart part;
			boolean dealbreaker = false;
			for(int x = minimumCoord.x; !dealbreaker && x <= maximumCoord.x; x++) {
				for(int y = minimumCoord.y; !dealbreaker && y <= maximumCoord.y; y++) {
					for(int z = minimumCoord.z; !dealbreaker && z <= maximumCoord.z; z++) {
						// Okay, figure out what sort of block this should be.
						
						te = this.worldObj.getBlockTileEntity(x, y, z);
						if(te != null && te instanceof IMultiblockPart) {
							part = (IMultiblockPart)te;
						}
						else {
							part = null;
						}
						
						// Validate block type against both part-level and material-level validators.
						int extremes = 0;
						if(x == minimumCoord.x) { extremes++; }
						if(y == minimumCoord.y) { extremes++; }
						if(z == minimumCoord.z) { extremes++; }
						
						if(x == maximumCoord.x) { extremes++; }
						if(y == maximumCoord.y) { extremes++; }
						if(z == maximumCoord.z) { extremes++; }
						
						if(extremes >= 2) {
							if(part != null && !part.isGoodForFrame()) {
								dealbreaker = true;
							}
							else if(part == null && !isBlockGoodForFrame(this.worldObj, x, y, z)) {
								dealbreaker = true;
							}
						}
						else if(extremes == 1) {
							if(y == maximumCoord.y) {
								if(part != null && !part.isGoodForTop()) {
									dealbreaker = true;
								}
								else if(part == null & !isBlockGoodForTop(this.worldObj, x, y, z)) {
									dealbreaker = true;
								}
							}
							else if(y == minimumCoord.y) {
								if(part != null && !part.isGoodForBottom()) {
									dealbreaker = true;
								}
								else if(part == null & !isBlockGoodForBottom(this.worldObj, x, y, z)) {
									dealbreaker = true;
								}
							}
							else {
								// Side
								if(part != null && !part.isGoodForSides()) {
									dealbreaker = true;
								}
								else if(part == null & !isBlockGoodForSides(this.worldObj, x, y, z)) {
									dealbreaker = true;
								}
							}
						}
						else {
							if(part != null && !part.isGoodForInterior()) {
								dealbreaker = true;
							}
							else if(part == null & !isBlockGoodForInterior(this.worldObj, x, y, z)) {
								dealbreaker = true;
							}
						}
					}
				}
			}
			
			return !dealbreaker;
		}
		return false;
	}
	
	/**
	 * Check if the machine is whole or not.
	 * If the machine was not whole, but now is, assemble the machine.
	 * If the machine was whole, but no longer is, disassemble the machine.
	 */
	protected void checkIfMachineIsWhole() {
		AssemblyState oldState = this.assemblyState;
		boolean isWhole = isMachineWhole();
		
		if(isWhole) {
			// This will alter assembly state
			this.assemblyState = AssemblyState.Assembled;
			assembleMachine(oldState);
		}
		else if(oldState == AssemblyState.Assembled) {
			// This will alter assembly state
			this.assemblyState = AssemblyState.Disassembled;
			disassembleMachine();
		}
		// Else Paused, do nothing
	}
	
	/**
	 * Called when a machine becomes "whole" and should begin
	 * functioning as a game-logically finished machine.
	 * Calls onMachineAssembled on all attached parts.
	 */
	private void assembleMachine(AssemblyState oldState) {
		TileEntity te;
		for(CoordTriplet coord : connectedBlocks) {
			te = this.worldObj.getBlockTileEntity(coord.x, coord.y, coord.z);
			if(te instanceof IMultiblockPart) {
				((IMultiblockPart)te).onMachineAssembled();
			}
		}
		
		if(oldState == assemblyState.Paused) {
			onMachineRestored();
		}
		else {
			onMachineAssembled();
		}
	}
	
	/**
	 * Called when the machine needs to be disassembled.
	 * It is not longer "whole" and should not be functional, usually
	 * as a result of a block being removed.
	 * Calls onMachineBroken on all attached parts.
	 */
	private void disassembleMachine() {
		TileEntity te;
		for(CoordTriplet coord : connectedBlocks) {
			te = this.worldObj.getBlockTileEntity(coord.x, coord.y, coord.z);
			if(te instanceof IMultiblockPart) {
				((IMultiblockPart)te).onMachineBroken();
			}
		}
		
		onMachineDisassembled();
	}
	
	/**
	 * Called when the machine is paused, generally due to chunk unloads or merges.
	 * This should perform any machine-halt cleanup logic, but not change any user
	 * settings.
	 * Calls onMachineBroken on all attached parts.
	 */
	private void pauseMachine() {
		TileEntity te;
		for(CoordTriplet coord : connectedBlocks) {
			te = this.worldObj.getBlockTileEntity(coord.x, coord.y, coord.z);
			if(te instanceof IMultiblockPart) {
				((IMultiblockPart)te).onMachineBroken();
			}
		}
		
		onMachinePaused();
	}

	/**
	 * Called before other machines are merged into this one.
	 */
	public void beginMerging() { }

	/**
	 * Merge another controller into this controller.
	 * Acquire all of the other controller's blocks and attach them
	 * to this machine.
	 * 
	 * NOTE: endMerging MUST be called after 1 or more merge calls!
	 * 
	 * @param other The controller to merge into this one.
	 * {@link erogenousbeef.core.multiblock.MultiblockControllerBase#endMerging()}
	 */
	public void merge(MultiblockControllerBase other) {
		if(this.referenceCoord.compareTo(other.referenceCoord) >= 0) {
			throw new IllegalArgumentException("The controller with the lowest minimum-coord value must consume the one with the higher coords");
		}

		TileEntity te;
		Set<CoordTriplet> blocksToAcquire = new CopyOnWriteArraySet<CoordTriplet>(other.connectedBlocks);

		// releases all blocks and references gently so they can be incorporated into another multiblock
		other.onMergedIntoOtherController(this);
		
		IMultiblockPart acquiredPart;
		for(CoordTriplet coord : blocksToAcquire) {
			// By definition, none of these can be the minimum block.
			this.connectedBlocks.add(coord);
			te = this.worldObj.getBlockTileEntity(coord.x, coord.y, coord.z);
			acquiredPart = (IMultiblockPart)te;
			acquiredPart.onMergedIntoOtherMultiblock(this);
			this.onBlockAdded(acquiredPart);
		}
	}
	
	/**
	 * Called when this machine is consumed by another controller.
	 * Essentially, forcibly tear down this object.
	 * @param otherController The controller consuming this controller.
	 */
	private void onMergedIntoOtherController(MultiblockControllerBase otherController) {
		this.pauseMachine();
		
		if(referenceCoord != null) {
			TileEntity te = this.worldObj.getBlockTileEntity(referenceCoord.x, referenceCoord.y, referenceCoord.z);
			((IMultiblockPart)te).forfeitMultiblockSaveDelegate();
			this.referenceCoord = null;
		}

		this.connectedBlocks.clear();
		MultiblockRegistry.unregister(this);
		
		this.onMachineMerge(otherController);
	}
	
	/**
	 * Callback. Called after this machine is consumed by another controller.
	 * This means all blocks have been stripped out of this object and
	 * handed over to the other controller.
	 * @param otherMachine The machine consuming this controller.
	 */
	protected abstract void onMachineMerge(MultiblockControllerBase otherMachine);
	
	/**
	 * Called after all multiblock machine merges have been completed for
	 * this machine.
	 */
	public void endMerging() {
		this.recalculateMinMaxCoords();
	}
	
	/**
	 * The update loop! Implement the game logic you would like to execute
	 * on every world tick here. Note that this only executes on the server,
	 * so you will need to send updates to the client manually.
	 */
	public final void updateMultiblockEntity() {
		if(this.connectedBlocks.isEmpty()) {
			MultiblockRegistry.unregister(this);
			return;
		}
		
		if(this.blocksHaveChangedThisFrame) {
			// Assemble/break machine if we have to
			this.recalculateMinMaxCoords();
			checkIfMachineIsWhole();
			this.blocksHaveChangedThisFrame = false;
		}
		
		if(this.assemblyState == AssemblyState.Assembled) {
			if(update()) {
				// If our chunks are all loaded, then save them, because we've changed stuff.
				if(this.worldObj.checkChunksExist(minimumCoord.x, minimumCoord.y, minimumCoord.z, maximumCoord.x, maximumCoord.y, maximumCoord.z)) {
					int minChunkX = minimumCoord.x >> 4;
					int minChunkZ = minimumCoord.z >> 4;
					int maxChunkX = maximumCoord.x >> 4;
					int maxChunkZ = maximumCoord.z >> 4;
					
					for(int x = minChunkX; x <= maxChunkX; x++) {
						for(int z = minChunkZ; z <= maxChunkZ; z++) {
							// Ensure that we save our data, even if the our save delegate is in has no TEs.
							Chunk chunkToSave = this.worldObj.getChunkFromChunkCoords(x, z);
							chunkToSave.setChunkModified();
						}
					}
				}
			}
		}
	}
	
	/**
	 * The update loop! Use this similarly to a TileEntity's update loop.
	 * You do not need to call your superclass' update() if you're directly
	 * derived from MultiblockControllerBase. This is a callback.
	 * Note that this will only be called when the machine is assembled.
	 * @return True if the multiblock should save data, i.e. its internal game state has changed. False otherwise.
	 */
	protected abstract boolean update();
	
	/**
	 * Visits all blocks via a breadth-first walk of neighbors from the
	 * reference coordinate. If any blocks remain unvisited after this
	 * method is called, they are orphans and are split off the main
	 * machine.
	 */
	private void revisitBlocks() {
		TileEntity te;
		// Ensure that our current reference coord is valid. If not, invalidate it.
		if(referenceCoord != null && this.worldObj.getBlockTileEntity(referenceCoord.x, referenceCoord.y, referenceCoord.z) == null) {
			referenceCoord = null;
		}
		
		// Reset visitations and find the minimum coordinate
		for(CoordTriplet c: connectedBlocks) {
			te = this.worldObj.getBlockTileEntity(c.x, c.y, c.z);
			if(te == null) { continue; } // This happens during chunk unload. Consider it valid, move on.

			((IMultiblockPart)te).setUnvisited();
			
			if(referenceCoord == null) {
				referenceCoord = c;
			}
			else if(c.compareTo(referenceCoord) < 0) {
				referenceCoord = c;
			}
		}
		
		if(referenceCoord == null) {
			// There are no valid parts remaining. This is due to a chunk unload. Halt.
			return;
		}

		// Now visit all connected parts, breadth-first, starting from reference coord.
		LinkedList<IMultiblockPart> partsToCheck = new LinkedList<IMultiblockPart>();
		IMultiblockPart[] nearbyParts = null;
		IMultiblockPart part = (IMultiblockPart)this.worldObj.getBlockTileEntity(referenceCoord.x, referenceCoord.y, referenceCoord.z);

		partsToCheck.add(part);
		while(!partsToCheck.isEmpty()) {
			part = partsToCheck.removeFirst();
			part.setVisited();

			nearbyParts = part.getNeighboringParts();
			for(IMultiblockPart nearbyPart : nearbyParts) {
				// Ignore different machines
				if(nearbyPart.getMultiblockController() != this) {
					continue;
				}

				if(!nearbyPart.isVisited()) {
					nearbyPart.setVisited();
					partsToCheck.add(nearbyPart);
				}
			}
		}
		
		// First, remove any blocks that are still disconnected.
		List<IMultiblockPart> orphans = new LinkedList<IMultiblockPart>();
		for(CoordTriplet c : connectedBlocks) {
			part = (IMultiblockPart)this.worldObj.getBlockTileEntity(c.x, c.y, c.z);
			if(!part.isVisited()) {
				orphans.add(part);
			}
		}
		
		// Remove all orphaned parts. i.e. Actually orphan them.
		for(IMultiblockPart orphan : orphans) {
			this._detachBlock(orphan, false);
		}
		
		// Now go through and start up as many new machines as possible.
		for(IMultiblockPart orphan : orphans) {
			if(!orphan.isConnected()) {
				// Creating a new multiblock should capture all other orphans connected to this orphan.
				orphan.onOrphaned();
			}
		}
	}
	
	// Validation helpers
	/**
	 * The "frame" consists of the outer edges of the machine, plus the corners.
	 * 
	 * @param world World object for the world in which this controller is located.
	 * @param x X coordinate of the block being tested
	 * @param y Y coordinate of the block being tested
	 * @param z Z coordinate of the block being tested
	 * @return True if this block can be used as part of the frame.
	 */
	protected boolean isBlockGoodForFrame(World world, int x, int y, int z) {
		return false;
	}

	/**
	 * The top consists of the top face, minus the edges.
	 * @param world World object for the world in which this controller is located.
	 * @param x X coordinate of the block being tested
	 * @param y Y coordinate of the block being tested
	 * @param z Z coordinate of the block being tested
	 * @return True if this block can be used as part of the top face.
	 */
	protected boolean isBlockGoodForTop(World world, int x, int y, int z) {
		return false;
	}
	
	/**
	 * The bottom consists of the bottom face, minus the edges.
	 * @param world World object for the world in which this controller is located.
	 * @param x X coordinate of the block being tested
	 * @param y Y coordinate of the block being tested
	 * @param z Z coordinate of the block being tested
	 * @return True if this block can be used as part of the bottom face.
	 */
	protected boolean isBlockGoodForBottom(World world, int x, int y, int z) {
		return false;
	}
	
	/**
	 * The sides consists of the N/E/S/W-facing faces, minus the edges.
	 * @param world World object for the world in which this controller is located.
	 * @param x X coordinate of the block being tested
	 * @param y Y coordinate of the block being tested
	 * @param z Z coordinate of the block being tested
	 * @return True if this block can be used as part of the sides.
	 */
	protected boolean isBlockGoodForSides(World world, int x, int y, int z) {
		return false;
	}
	
	/**
	 * The interior is any block that does not touch blocks outside the machine.
	 * @param world World object for the world in which this controller is located.
	 * @param x X coordinate of the block being tested
	 * @param y Y coordinate of the block being tested
	 * @param z Z coordinate of the block being tested
	 * @return True if this block can be used as part of the sides.
	 */
	protected boolean isBlockGoodForInterior(World world, int x, int y, int z) {
		return false;
	}
	
	/**
	 * @return The reference coordinate, the block with the lowest x, y, z coordinates, evaluated in that order.
	 */
	public CoordTriplet getReferenceCoord() { return referenceCoord; }
	
	/**
	 * @return The number of blocks connected to this controller.
	 */
	public int getNumConnectedBlocks() { return connectedBlocks.size(); }

	public abstract void writeToNBT(NBTTagCompound data);
	public abstract void readFromNBT(NBTTagCompound data);
	
	private void recalculateMinMaxCoords() {
		minimumCoord.x = minimumCoord.y = minimumCoord.z = Integer.MAX_VALUE;
		maximumCoord.x = maximumCoord.y = maximumCoord.z = Integer.MIN_VALUE;

		for(CoordTriplet coord : connectedBlocks) {
			if(coord.x < minimumCoord.x) { minimumCoord.x = coord.x; }
			if(coord.x > maximumCoord.x) { maximumCoord.x = coord.x; } 
			if(coord.y < minimumCoord.y) { minimumCoord.y = coord.y; }
			if(coord.y > maximumCoord.y) { maximumCoord.y = coord.y; } 
			if(coord.z < minimumCoord.z) { minimumCoord.z = coord.z; }
			if(coord.z > maximumCoord.z) { maximumCoord.z = coord.z; } 
		}
	}
	
	/**
	 * @return The minimum bounding-box coordinate containing this machine's blocks.
	 */
	public CoordTriplet getMinimumCoord() { return minimumCoord.copy(); }

	/**
	 * @return The maximum bounding-box coordinate containing this machine's blocks.
	 */
	public CoordTriplet getMaximumCoord() { return maximumCoord.copy(); }

	/**
	 * Called when the save delegate's tile entity is being asked for its description packet
	 * @param tag A fresh compound tag to write your multiblock data into
	 */
	public abstract void formatDescriptionPacket(NBTTagCompound data);

	/**
	 * Called when the save delegate's tile entity receiving a description packet
	 * @param tag A compound tag containing multiblock data to import
	 */
	public abstract void decodeDescriptionPacket(NBTTagCompound data);
}
