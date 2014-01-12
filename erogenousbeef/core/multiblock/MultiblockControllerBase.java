package erogenousbeef.core.multiblock;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import cpw.mods.fml.common.FMLLog;
import erogenousbeef.core.common.CoordTriplet;

/**
 * This class contains the base logic for "multiblock controllers". Conceptually, they are
 * meta-TileEntities. They govern the logic for an associated group of TileEntities.
 * 
 * Subordinate TileEntities implement the IMultiblockPart class and, generally, should not have an update() loop.
 */
public abstract class MultiblockControllerBase {
	public static final short DIMENSION_UNBOUNDED = -1;

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
	 * Set to true whenever a part is removed from this controller.
	 */
	private boolean shouldCheckForDisconnections;
	
	/**
	 * Set whenever we validate the multiblock
	 */
	private MultiblockValidationException lastValidationException;
	
	protected boolean debugMode;
	
	protected MultiblockControllerBase(World world) {
		// Multiblock stuff
		worldObj = world;
		connectedBlocks = new HashSet<CoordTriplet>();
		referenceCoord = null;
		assemblyState = AssemblyState.Disassembled;

		minimumCoord = new CoordTriplet(0,0,0);
		maximumCoord = new CoordTriplet(0,0,0);

		shouldCheckForDisconnections = true;
		lastValidationException = null;
		
		debugMode = false;
	}

	public void setDebugMode(boolean active) {
		debugMode = active;
	}
	
	public boolean isDebugMode() { return debugMode; }
	
	/**
	 * Call when a block with cached save-delegate data is added to the multiblock.
	 * The part will be notified that the data has been used after this call completes.
	 * @param part The NBT tag containing this controller's data.
	 */
	public abstract void onAttachedPartWithMultiblockData(IMultiblockPart part, NBTTagCompound data);
	
	/**
	 * Check if a block is being tracked by this machine.
	 * @param blockCoord Coordinate to check.
	 * @return True if the tile entity at blockCoord is being tracked by this machine, false otherwise.
	 */
	public boolean hasBlock(CoordTriplet blockCoord) {
		return connectedBlocks.contains(blockCoord);
	}
	
	/**
	 * Attach a new part to this machine.
	 * @param part The part to add.
	 */
	public void attachBlock(IMultiblockPart part) {
		IMultiblockPart candidate;
		CoordTriplet coord = part.getWorldLocation();

		// No need to re-add a block
		if(connectedBlocks.contains(coord)) {
			FMLLog.warning("[%s] Controller %s is double-adding a block @ %s. This is unusual. If you encounter odd behavior, please tear down the machine and rebuild it.", (worldObj.isRemote?"CLIENT":"SERVER"), hashCode(), coord);
		}

		connectedBlocks.add(coord);
		part.onAttached(this);
		this.onBlockAdded(part);
		
		if(part.hasMultiblockSaveData()) {
			NBTTagCompound savedData = part.getMultiblockSaveData();
			onAttachedPartWithMultiblockData(part, savedData);
			part.onMultiblockDataAssimilated();
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
		else {
			part.forfeitMultiblockSaveDelegate();
		}
		
		MultiblockRegistry.addDirtyController(worldObj, this);
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
	 * Callback whenever a part is removed (or will very shortly be removed) from a controller.
	 * Do housekeeping/callbacks.
	 * @param part The part being removed.
	 */
	private void onDetachBlock(IMultiblockPart part) {
		// Strip out this part
		part.onDetached(this);
		this.onBlockRemoved(part);
		part.forfeitMultiblockSaveDelegate();

		shouldCheckForDisconnections = true;
	}
	
	/**
	 * Call to detach a block from this machine. Generally, this should be called
	 * when the tile entity is being released, e.g. on block destruction.
	 * @param part The part to detach from this machine.
	 * @param chunkUnloading Is this entity detaching due to the chunk unloading? If true, the multiblock will be paused instead of broken.
	 */
	public void detachBlock(IMultiblockPart part, boolean chunkUnloading) {
		CoordTriplet coord = part.getWorldLocation();
		if(chunkUnloading && this.assemblyState == AssemblyState.Assembled) {
			this.assemblyState = AssemblyState.Paused;
			this.onMachinePaused();
		}

		// Strip out this part
		onDetachBlock(part);
		connectedBlocks.remove(coord);

		if(referenceCoord != null && referenceCoord.equals(coord)) {
			referenceCoord = null;
		}

		if(connectedBlocks.isEmpty()) {
			// Destroy/unregister
			MultiblockRegistry.addDeadController(this.worldObj, this);
			return;
		}

		MultiblockRegistry.addDirtyController(this.worldObj,  this);

		// Find new save delegate if we need to.
		if(referenceCoord == null) {
			IChunkProvider chunkProvider = worldObj.getChunkProvider();
			TileEntity theChosenOne = null;
			for(CoordTriplet connectedCoord : connectedBlocks) {
				if(!chunkProvider.chunkExists(connectedCoord.getChunkX(), connectedCoord.getChunkZ())) {
					// Chunk is unloading, skip this coord to prevent chunk thrashing
					continue;
				}

				// Check if TE has been removed for some reason, if so, we'll detach it soon.
				TileEntity te = this.worldObj.getBlockTileEntity(connectedCoord.x, connectedCoord.y, connectedCoord.z);
				if(te == null) { continue; }

				if(referenceCoord == null || connectedCoord.compareTo(referenceCoord) < 0) {
					referenceCoord = connectedCoord;
					theChosenOne = te;
				}
			}

			if(referenceCoord != null && theChosenOne != null) {
				((IMultiblockPart)theChosenOne).becomeMultiblockSaveDelegate();
			}
			// Else, wtf?
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
	 * Returns the maximum X dimension size of the machine, or -1 (DIMENSION_UNBOUNDED) to disable
	 * dimension checking in X. (This is not recommended.)
	 * @return The maximum X dimension size of the machine, or -1 
	 */
	protected abstract int getMaximumXSize();

	/**
	 * Returns the maximum Z dimension size of the machine, or -1 (DIMENSION_UNBOUNDED) to disable
	 * dimension checking in X. (This is not recommended.)
	 * @return The maximum Z dimension size of the machine, or -1 
	 */
	protected abstract int getMaximumZSize();

	/**
	 * Returns the maximum Y dimension size of the machine, or -1 (DIMENSION_UNBOUNDED) to disable
	 * dimension checking in X. (This is not recommended.)
	 * @return The maximum Y dimension size of the machine, or -1 
	 */
	protected abstract int getMaximumYSize();
	
	/**
	 * Returns the minimum X dimension size of the machine. Must be at least 1, because nothing else makes sense.
	 * @return The minimum X dimension size of the machine
	 */
	protected int getMinimumXSize() { return 1; }

	/**
	 * Returns the minimum Y dimension size of the machine. Must be at least 1, because nothing else makes sense.
	 * @return The minimum Y dimension size of the machine
	 */
	protected int getMinimumYSize() { return 1; }

	/**
	 * Returns the minimum Z dimension size of the machine. Must be at least 1, because nothing else makes sense.
	 * @return The minimum Z dimension size of the machine
	 */
	protected int getMinimumZSize() { return 1; }
	
	
	/**
	 * @return An exception representing the last error encountered when trying to assemble this
	 * multiblock, or null if there is no error.
	 */
	public MultiblockValidationException getLastValidationException() { return lastValidationException; }
	
	/**
	 * @return True if the machine is "whole" and should be assembled. False otherwise.
	 */
	protected boolean isMachineWhole() throws MultiblockValidationException {
		if(connectedBlocks.size() < getMinimumNumberOfBlocksForAssembledMachine()) {
			throw new MultiblockValidationException("Machine is too small.");
		}
		
		// Quickly check for exceeded dimensions
		int deltaX = maximumCoord.x - minimumCoord.x + 1;
		int deltaY = maximumCoord.y - minimumCoord.y + 1;
		int deltaZ = maximumCoord.z - minimumCoord.z + 1;
		
		int maxX = getMaximumXSize();
		int maxY = getMaximumYSize();
		int maxZ = getMaximumZSize();
		int minX = getMinimumXSize();
		int minY = getMinimumYSize();
		int minZ = getMinimumZSize();
		
		if(maxX > 0 && deltaX > maxX) { throw new MultiblockValidationException(String.format("Machine is too large, it may be at most %d blocks in the X dimension", maxX)); }
		if(maxY > 0 && deltaY > maxY) { throw new MultiblockValidationException(String.format("Machine is too large, it may be at most %d blocks in the Y dimension", maxY)); }
		if(maxZ > 0 && deltaZ > maxZ) { throw new MultiblockValidationException(String.format("Machine is too large, it may be at most %d blocks in the Z dimension", maxZ)); }
		if(deltaX < minX) { throw new MultiblockValidationException(String.format("Machine is too small, it must be at least %d blocks in the X dimension", minX)); }
		if(deltaY < minY) { throw new MultiblockValidationException(String.format("Machine is too small, it must be at least %d blocks in the Y dimension", minY)); }
		if(deltaZ < minZ) { throw new MultiblockValidationException(String.format("Machine is too small, it must be at least %d blocks in the Z dimension", minZ)); }

		// Now we run a simple check on each block within that volume.
		// Any block deviating = NO DEAL SIR
		TileEntity te;
		IMultiblockPart part;
		for(int x = minimumCoord.x; x <= maximumCoord.x; x++) {
			for(int y = minimumCoord.y; y <= maximumCoord.y; y++) {
				for(int z = minimumCoord.z; z <= maximumCoord.z; z++) {
					// Okay, figure out what sort of block this should be.
					
					te = this.worldObj.getBlockTileEntity(x, y, z);
					if(te instanceof IMultiblockPart) {
						part = (IMultiblockPart)te;
					}
					else {
						// This is permitted so that we can incorporate certain non-multiblock parts inside interiors
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
						if(part != null) {
							part.isGoodForFrame();
						}
						else {
							isBlockGoodForFrame(this.worldObj, x, y, z);
						}
					}
					else if(extremes == 1) {
						if(y == maximumCoord.y) {
							if(part != null) {
								part.isGoodForTop();
							}
							else {
								isBlockGoodForTop(this.worldObj, x, y, z);
							}
						}
						else if(y == minimumCoord.y) {
							if(part != null) {
								part.isGoodForBottom();
							}
							else {
								isBlockGoodForBottom(this.worldObj, x, y, z);
							}
						}
						else {
							// Side
							if(part != null) {
								part.isGoodForSides();
							}
							else {
								isBlockGoodForSides(this.worldObj, x, y, z);
							}
						}
					}
					else {
						if(part != null) {
							part.isGoodForInterior();
						}
						else {
							isBlockGoodForInterior(this.worldObj, x, y, z);
						}
					}
				}
			}
		}
		
		return true;
	}
	
	/**
	 * Check if the machine is whole or not.
	 * If the machine was not whole, but now is, assemble the machine.
	 * If the machine was whole, but no longer is, disassemble the machine.
	 * @return 
	 */
	public void checkIfMachineIsWhole() {
		AssemblyState oldState = this.assemblyState;
		boolean isWhole;
		lastValidationException = null;
		try {
			isWhole = isMachineWhole();
		} catch (MultiblockValidationException e) {
			lastValidationException = e;
			isWhole = false;
		}
		
		if(isWhole) {
			// This will alter assembly state
			assembleMachine(oldState);
		}
		else if(oldState == AssemblyState.Assembled) {
			// This will alter assembly state
			disassembleMachine();
			if(isDebugMode()) {
				FMLLog.info("[%s] Machine %d is disassembling. Check above here for stacktraces indicating why this reactor broke.", worldObj.isRemote?"CLIENT":"SERVER", hashCode());
			}
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
		// No chunk safety checks because these things should all be in loaded chunks already
		for(CoordTriplet coord : connectedBlocks) {
			te = this.worldObj.getBlockTileEntity(coord.x, coord.y, coord.z);
			if(te instanceof IMultiblockPart) {
				((IMultiblockPart)te).onMachineAssembled(this);
			}
		}
		
		this.assemblyState = AssemblyState.Assembled;
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
		IChunkProvider chunkProvider = worldObj.getChunkProvider();
		for(CoordTriplet coord : connectedBlocks) {
			if(!chunkProvider.chunkExists(coord.getChunkX(), coord.getChunkZ())) {
				// Chunk is already unloaded, don't fetch the TE.
				// This happens on SMP servers when players log out.
				continue;
			}
			
			te = this.worldObj.getBlockTileEntity(coord.x, coord.y, coord.z);
			if(te instanceof IMultiblockPart) {
				((IMultiblockPart)te).onMachineBroken();
			}
		}
		
		this.assemblyState = AssemblyState.Disassembled;
		onMachineDisassembled();
	}
	
	/**
	 * Assimilate another controller into this controller.
	 * Acquire all of the other controller's blocks and attach them
	 * to this one.
	 * 
	 * @param other The controller to merge into this one.
	 */
	public void assimilate(MultiblockControllerBase other) {
		if(other.referenceCoord != null && this.referenceCoord.compareTo(other.referenceCoord) >= 0) {
			throw new IllegalArgumentException("The controller with the lowest minimum-coord value must consume the one with the higher coords");
		}

		TileEntity te;
		Set<CoordTriplet> blocksToAcquire = new CopyOnWriteArraySet<CoordTriplet>(other.connectedBlocks);

		// releases all blocks and references gently so they can be incorporated into another multiblock
		other._onAssimilated(this);
		
		IMultiblockPart acquiredPart;
		for(CoordTriplet coord : blocksToAcquire) {
			// By definition, none of these can be the minimum block.
			te = this.worldObj.getBlockTileEntity(coord.x, coord.y, coord.z);
			if(te instanceof IMultiblockPart) {
				acquiredPart = (IMultiblockPart)te;
				this.connectedBlocks.add(coord);
				acquiredPart.onAssimilated(this);
				this.onBlockAdded(acquiredPart);
			}
		}

		this.onAssimilate(other);
		other.onAssimilated(this);
	}
	
	/**
	 * Called when this machine is consumed by another controller.
	 * Essentially, forcibly tear down this object.
	 * @param otherController The controller consuming this controller.
	 */
	private void _onAssimilated(MultiblockControllerBase otherController) {
		if(referenceCoord != null) {
			if(worldObj.getChunkProvider().chunkExists(referenceCoord.getChunkX(), referenceCoord.getChunkZ())) {
				TileEntity te = this.worldObj.getBlockTileEntity(referenceCoord.x, referenceCoord.y, referenceCoord.z);
				if(te instanceof IMultiblockPart) {
					((IMultiblockPart)te).forfeitMultiblockSaveDelegate();
				}
			}
			this.referenceCoord = null;
		}

		this.connectedBlocks.clear();
	}
	
	/**
	 * Callback. Called after this controller assimilates all the blocks
	 * from another controller.
	 * Use this to absorb that controller's game data.
	 * @param assimilated The controller whose uniqueness was added to our own.
	 */
	protected abstract void onAssimilate(MultiblockControllerBase assimilated);
	
	/**
	 * Callback. Called after this controller is assimilated into another controller.
	 * All blocks have been stripped out of this object and handed over to the
	 * other controller.
	 * This is intended primarily for cleanup.
	 * @param assimilator The controller which has assimilated this controller.
	 */
	protected abstract void onAssimilated(MultiblockControllerBase assimilator);
	
	/**
	 * Driver for the update loop. If the machine is assembled, runs
	 * the game logic update method.
	 * @see erogenousbeef.core.multiblock.MultiblockControllerBase#update()
	 */
	public final void updateMultiblockEntity() {
		if(connectedBlocks.isEmpty()) {
			// This shouldn't happen, but just in case...
			MultiblockRegistry.addDeadController(this.worldObj, this);
			return;
		}
		
		if(this.assemblyState != AssemblyState.Assembled) {
			// Not assembled - don't run game logic
			return;
		}

		if(worldObj.isRemote) {
			updateClient();
		}
		else if(updateServer()) {
			// If this returns true, the server has changed its internal data. 
			// If our chunks are loaded (they should be), we must mark our chunks as dirty.
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
		// Else: Server, but no need to save data.
	}
	
	/**
	 * The server-side update loop! Use this similarly to a TileEntity's update loop.
	 * You do not need to call your superclass' update() if you're directly
	 * derived from MultiblockControllerBase. This is a callback.
	 * Note that this will only be called when the machine is assembled.
	 * @return True if the multiblock should save data, i.e. its internal game state has changed. False otherwise.
	 */
	protected abstract boolean updateServer();
	
	/**
	 * Client-side update loop. Generally, this shouldn't do anything, but if you want
	 * to do some interpolation or something, do it here.
	 */
	protected abstract void updateClient();
	
	// Validation helpers
	/**
	 * The "frame" consists of the outer edges of the machine, plus the corners.
	 * 
	 * @param world World object for the world in which this controller is located.
	 * @param x X coordinate of the block being tested
	 * @param y Y coordinate of the block being tested
	 * @param z Z coordinate of the block being tested
	 * @throws MultiblockValidationException if the tested block is not allowed on the machine's frame
	 */
	protected void isBlockGoodForFrame(World world, int x, int y, int z) throws MultiblockValidationException {
		throw new MultiblockValidationException(String.format("%d, %d, %d - Block is not valid for use in the machine's interior", x, y, z));
	}

	/**
	 * The top consists of the top face, minus the edges.
	 * @param world World object for the world in which this controller is located.
	 * @param x X coordinate of the block being tested
	 * @param y Y coordinate of the block being tested
	 * @param z Z coordinate of the block being tested
	 * @throws MultiblockValidationException if the tested block is not allowed on the machine's top face
	 */
	protected void isBlockGoodForTop(World world, int x, int y, int z) throws MultiblockValidationException {
		throw new MultiblockValidationException(String.format("%d, %d, %d - Block is not valid for use in the machine's interior", x, y, z));
	}
	
	/**
	 * The bottom consists of the bottom face, minus the edges.
	 * @param world World object for the world in which this controller is located.
	 * @param x X coordinate of the block being tested
	 * @param y Y coordinate of the block being tested
	 * @param z Z coordinate of the block being tested
	 * @throws MultiblockValidationException if the tested block is not allowed on the machine's bottom face
	 */
	protected void isBlockGoodForBottom(World world, int x, int y, int z) throws MultiblockValidationException {
		throw new MultiblockValidationException(String.format("%d, %d, %d - Block is not valid for use in the machine's interior", x, y, z));
	}
	
	/**
	 * The sides consists of the N/E/S/W-facing faces, minus the edges.
	 * @param world World object for the world in which this controller is located.
	 * @param x X coordinate of the block being tested
	 * @param y Y coordinate of the block being tested
	 * @param z Z coordinate of the block being tested
	 * @throws MultiblockValidationException if the tested block is not allowed on the machine's side faces
	 */
	protected void isBlockGoodForSides(World world, int x, int y, int z) throws MultiblockValidationException {
		throw new MultiblockValidationException(String.format("%d, %d, %d - Block is not valid for use in the machine's interior", x, y, z));
	}
	
	/**
	 * The interior is any block that does not touch blocks outside the machine.
	 * @param world World object for the world in which this controller is located.
	 * @param x X coordinate of the block being tested
	 * @param y Y coordinate of the block being tested
	 * @param z Z coordinate of the block being tested
	 * @throws MultiblockValidationException if the tested block is not allowed in the machine's interior
	 */
	protected void isBlockGoodForInterior(World world, int x, int y, int z) throws MultiblockValidationException {
		throw new MultiblockValidationException(String.format("%d, %d, %d - Block is not valid for use in the machine's interior", x, y, z));
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

	/**
	 * Force this multiblock to recalculate its minimum and maximum coordinates
	 * from the list of connected parts.
	 */
	public void recalculateMinMaxCoords() {
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

	/**
	 * @return True if this controller has no associated blocks, false otherwise
	 */
	public boolean isEmpty() {
		return this.connectedBlocks.isEmpty();
	}

	/**
	 * Tests whether this multiblock should consume the other multiblock
	 * and become the new multiblock master when the two multiblocks
	 * are adjacent. Assumes both multiblocks are the same type.
	 * @param otherController The other multiblock controller.
	 * @return True if this multiblock should consume the other, false otherwise.
	 */
	public boolean shouldConsume(MultiblockControllerBase otherController) {
		if(!otherController.getClass().equals(getClass())) {
			throw new IllegalArgumentException("Attempting to merge two multiblocks with different master classes - this should never happen!");
		}
		
		if(otherController == this) { return false; } // Don't be silly, don't eat yourself.
		
		CoordTriplet myCoord = getReferenceCoord();
		CoordTriplet theirCoord = otherController.getReferenceCoord();
		
		int res = myCoord.compareTo(theirCoord);
		if(res < 0) { return true; }
		else if(res > 0) { return false; }
		else {
			FMLLog.severe("My Controller (%d): size (%d), coords: %s", hashCode(), connectedBlocks.size(), java.util.Arrays.toString(connectedBlocks.toArray()));
			FMLLog.severe("Other Controller (%d): size (%d), coords: %s", otherController.hashCode(), otherController.connectedBlocks.size(), java.util.Arrays.toString(otherController.connectedBlocks.toArray()));
			throw new IllegalArgumentException("[" + (worldObj.isRemote?"CLIENT":"SERVER") + "] Two controllers with the same reference coord - this should never happen!"); 
		}
	}

	/**
	 * Called when this machine may need to check for blocks that are no
	 * longer physically connected to the reference coordinate.
	 * @return
	 */
	public Set<IMultiblockPart> checkForDisconnections() {
		if(!this.shouldCheckForDisconnections) {
			return null;
		}
		
		if(this.isEmpty()) {
			return null;
		}
		
		// We've run the checks from here on out.
		shouldCheckForDisconnections = false;
		
		TileEntity te;
		IChunkProvider chunkProvider = worldObj.getChunkProvider();
		// Ensure that our current reference coord is valid. If not, invalidate it.
		if(referenceCoord != null) {
			if(!chunkProvider.chunkExists(referenceCoord.getChunkX(), referenceCoord.getChunkZ()) ||
				worldObj.getBlockTileEntity(referenceCoord.x, referenceCoord.y, referenceCoord.z) == null) {
				referenceCoord = null;
			}
		}
		
		// Reset visitations and find the minimum coordinate
		Set<CoordTriplet> deadCoords = new HashSet<CoordTriplet>();
		IMultiblockPart part = null;
		
		int originalSize = connectedBlocks.size();

		for(CoordTriplet c: connectedBlocks) {
			// This happens during chunk unload.
			if(!chunkProvider.chunkExists(c.x >> 4, c.z >> 4)) {
				deadCoords.add(c);
				continue;
			}

			te = this.worldObj.getBlockTileEntity(c.x, c.y, c.z);
			if(!(te instanceof IMultiblockPart)) {
				// This happens during chunk unload. Consider it valid, move on.
				deadCoords.add(c);
				continue;
			}
			
			part = (IMultiblockPart)te;
			part.setUnvisited();
			
			if(referenceCoord == null) {
				referenceCoord = c;
			}
			else if(c.compareTo(referenceCoord) < 0) {
				referenceCoord = c;
			}
		}
		
		connectedBlocks.removeAll(deadCoords);
		deadCoords.clear();
		
		if(referenceCoord == null || isEmpty()) {
			// There are no valid parts remaining. The entire multiblock was unloaded during a chunk unload. Halt.
			return null;
		}
		
		IMultiblockPart referencePart = (IMultiblockPart)worldObj.getBlockTileEntity(referenceCoord.x, referenceCoord.y, referenceCoord.z);
		
		// Now visit all connected parts, breadth-first, starting from reference coord's part
		LinkedList<IMultiblockPart> partsToCheck = new LinkedList<IMultiblockPart>();
		IMultiblockPart[] nearbyParts = null;
		partsToCheck.add(referencePart);
		int visitedParts = 0;
		
		while(!partsToCheck.isEmpty()) {
			part = partsToCheck.removeFirst();
			part.setVisited();
			visitedParts++;

			nearbyParts = part.getNeighboringParts(); // Chunk-safe on server, but not on client
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
		
		// Finally, remove all parts that remain disconnected.
		Set<IMultiblockPart> removedParts = new HashSet<IMultiblockPart>();
		IMultiblockPart orphanCandidate;
		
		for(CoordTriplet c : connectedBlocks) {
			te = worldObj.getBlockTileEntity(c.x, c.y, c.z);
			if(!(te instanceof IMultiblockPart)) {
				// Weird chunk problems?
				deadCoords.add(c);
				continue;
			}
			
			orphanCandidate = (IMultiblockPart)te;
			if (!orphanCandidate.isVisited()) {
				deadCoords.add(c);
				orphanCandidate.onOrphaned(this, originalSize, visitedParts);
				onDetachBlock(orphanCandidate);
				removedParts.add(orphanCandidate);
			}
		}

		// Trim any blocks that were invalid, or were removed.
		this.connectedBlocks.removeAll(deadCoords);
		
		// Cleanup. Not necessary, really.
		deadCoords.clear();
		
		return removedParts;
	}

	/**
	 * Detach all parts. Return a set of all parts which still
	 * have a valid tile entity. Chunk-safe.
	 * @return A set of all parts which still have a valid tile entity.
	 */
	public Set<IMultiblockPart> detachAllBlocks() {
		Set<IMultiblockPart> detachedParts = new HashSet<IMultiblockPart>();

		if(worldObj == null) { return detachedParts; }
		
		IChunkProvider chunkProvider = worldObj.getChunkProvider();
		TileEntity te;
		IMultiblockPart part;
		for(CoordTriplet c : connectedBlocks) {
			if(chunkProvider.chunkExists(c.getChunkX(), c.getChunkZ())) {
				te = worldObj.getBlockTileEntity(c.x, c.y, c.z);
				if(te instanceof IMultiblockPart) {
					part = (IMultiblockPart)te;
					onDetachBlock(part);
					detachedParts.add(part);
				}
			}
		}
		
		connectedBlocks.clear();
		return detachedParts;
	}

	/**
	 * Called from a part that wishes to store data from this controller when it gets orphaned.
	 * Generally, this data will be read back in during onAddedPartWithMultiblockData().
	 * @param newOrphan The part being orphaned.
	 * @param oldSize The size of the controller before detaching orphans.
	 * @param newSize The size of the controller after detaching orphans.
	 * @param dataContainer An NBT Compound Tag into which to write data.
	 */
	public abstract void getOrphanData(IMultiblockPart newOrphan, int oldSize, int newSize, NBTTagCompound dataContainer);

	/**
	 * @return True if this multiblock machine is considered assembled and ready to go.
	 */
	public boolean isAssembled() {
		return this.assemblyState == AssemblyState.Assembled;
	}
	
}