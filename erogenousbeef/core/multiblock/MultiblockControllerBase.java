package erogenousbeef.core.multiblock;

import java.util.LinkedList;
import java.util.List;

import net.minecraft.block.material.Material;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import erogenousbeef.core.common.CoordTriplet;

/**
 * This class contains the base logic for "multiblock controllers". You can think of them
 * as meta-TileEntities. They govern the logic for an associated group of TileEntities.
 * 
 * Subordinate TileEntities implement the IMultiblockPart class and, generally, should not have an update() loop.
 */
public abstract class MultiblockControllerBase {
	// Multiblock stuff - do not mess with
	private World worldObj;
	private boolean isWholeMachine;
	private LinkedList<CoordTriplet> connectedBlocks;
	
	/**
	 * The coordinate of the block through which we communicate over the network,
	 * and to/from which controllers save/load data.
	 * Generally, this should also be the reference coordinate.
	 */
	private CoordTriplet saveDelegate;
	
	/** This is a deterministically-picked coordinate that identifies this
	 * multiblock uniquely in its dimension.
	 * Currently, this is the coord with the lowest X, Y and Z coordinates, in that order of evaluation.
	 * i.e. If something has a lower X but higher Y/Z coordinates, it will still be the reference.
	 * If something has the same X but a lower Y coordinate, it will be the reference. Etc.
	 */
	private CoordTriplet referenceCoord;

	 /**
	  * A cached "initial block". On the first tick recieved by this controller,
	  * if this is non-null, this coordinate will be checked for an IMachinePart tile entity.
	  * If one exists, it will be added to this controller.
	  * This is a trick to allow machines to create themselves during world loading without
	  * all the blocks having to load their states at once.
	  */
	private CoordTriplet cachedBlock;

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
	
	protected MultiblockControllerBase(World world) {
		// Multiblock stuff
		worldObj = world;
		isWholeMachine = false;
		connectedBlocks = new LinkedList<CoordTriplet>();
		saveDelegate = null;
		referenceCoord = null;
		cachedBlock = null;
		
		minimumCoord = new CoordTriplet(0,0,0);
		maximumCoord = new CoordTriplet(0,0,0);
		
		blocksHaveChangedThisFrame = false;
	}

	/**
	 * Call when the save delegate block loads.
	 * @param initialBlock The coordinate of the block which is loading data into this controller.
	 * @param savedData The NBT tag containing this controller's data.
	 */
	public void loadAndCacheInitialBlock(CoordTriplet initialBlock, NBTTagCompound savedData) {
		this.readFromNBT(savedData);
		cachedBlock = initialBlock;
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
		CoordTriplet coord;
		boolean firstBlock = this.connectedBlocks.isEmpty();

		// Spider out into the world, consuming all unconnected blocks we touch.
		LinkedList<IMultiblockPart> partsToCheck = new LinkedList<IMultiblockPart>();
		partsToCheck.add(part);
		while(!partsToCheck.isEmpty()) {
			candidate = partsToCheck.removeFirst();
			coord = candidate.getWorldLocation();
			
			// Already got it? Ignore.
			if(connectedBlocks.contains(coord)) {
				continue;
			}
			
			if(!candidate.isConnected()) {
				// Not connected? Add it.
				connectedBlocks.add(coord);
				candidate.onAttached(this);
				
				IMultiblockPart[] newParts = candidate.getNeighboringParts();
				for(IMultiblockPart p : newParts) { partsToCheck.addLast(p); }
			}
			// Fusion is handled in block placement.
		}

		if(firstBlock) {
			MultiblockRegistry.register(this);
		}

		blocksHaveChangedThisFrame = true;
		this.recalculateDistances();
		this.recalculateMinMaxCoords();
		
		if(this.saveDelegate == null) {
			saveDelegate = referenceCoord;
			TileEntity te = this.worldObj.getBlockTileEntity(saveDelegate.x, saveDelegate.y, saveDelegate.z);
			((IMultiblockPart)te).becomeMultiblockSaveDelegate();
		}
		else if(this.referenceCoord.compareTo(this.saveDelegate) < 0) {
			// Swap save delegate to new minimum coord.
			TileEntity te = this.worldObj.getBlockTileEntity(saveDelegate.x, saveDelegate.y, saveDelegate.z);
			((IMultiblockPart)te).forfeitMultiblockSaveDelegate();

			saveDelegate = referenceCoord;
			te = this.worldObj.getBlockTileEntity(saveDelegate.x, saveDelegate.y, saveDelegate.z);			
			((IMultiblockPart)te).becomeMultiblockSaveDelegate();
		}
	}
	
	/**
	 * Call to detach a block from this machine. Generally, this should be called
	 * when the tile entity is being released, e.g. on block destruction.
	 * @param part The part to detach from this machine.
	 */
	public void detachBlock(IMultiblockPart part) {
		CoordTriplet coord = part.getWorldLocation();
		
		while(connectedBlocks.contains(coord))
		{
			connectedBlocks.remove(coord);
			
			if(saveDelegate.equals(coord)) {
				part.forfeitMultiblockSaveDelegate();
				// Shite.
				saveDelegate = null;
			}
			
			if(referenceCoord.equals(coord)) {
				// SHITE!
				referenceCoord = null;
			}
		}

		boolean wasDetachedAlready = part.getDistanceFromReferenceCoord() == IMultiblockPart.INVALID_DISTANCE;
		
		part.onDetached(this);
		
		if(connectedBlocks.isEmpty()) {
			// Destroy/unregister
			MultiblockRegistry.unregister(this);
			return;
		}

		blocksHaveChangedThisFrame = true;
		this.recalculateMinMaxCoords();
		
		// Was this block already known disconnected?
		if(wasDetachedAlready) {
			// Yup, but it was already known disconnected.
			return;
		}
		
		// Recalculate all distances from the minimum coord.
		this.recalculateDistances();
		
		// Perform fission. Can result in up to 6 new TEs.
		doFission();
		
		// Find new save delegate if we need to.
		if(saveDelegate == null) {
			saveDelegate = referenceCoord;
			TileEntity te = this.worldObj.getBlockTileEntity(saveDelegate.x, saveDelegate.y, saveDelegate.z);
			((IMultiblockPart)te).becomeMultiblockSaveDelegate();
		}
	}

	/**
	 * Helper function. Check for orphans and create new machines until there are no more orphaned blocks.
	 */
	private void doFission() {
		// First, remove any blocks that are still disconnected.
		IMultiblockPart part;
		List<IMultiblockPart> orphans = new LinkedList<IMultiblockPart>();
		for(CoordTriplet c : connectedBlocks) {
			part = (IMultiblockPart)this.worldObj.getBlockTileEntity(c.x, c.y, c.z);
			if(part.getDistanceFromReferenceCoord() == IMultiblockPart.INVALID_DISTANCE) {
				orphans.add(part);
			}
		}
		
		// Remove all orphaned parts. i.e. Actually orphan them.
		for(IMultiblockPart orphan : orphans) {
			this.detachBlock(orphan);
		}
		
		// Now go through and start up as many new machines as possible.
		for(IMultiblockPart orphan : orphans) {
			if(!orphan.isConnected()) {
				// Creating a new multiblock should capture all other orphans connected to this orphan.
				orphan.createNewMultiblock();
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

	// TODO: Move this implementation up to the game logic level.
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
		boolean wasWholeMachine = this.isWholeMachine;
		this.isWholeMachine = isMachineWhole();
		
		if(!wasWholeMachine && isWholeMachine) {
			assembleMachine();
		}
		else if(wasWholeMachine && !isWholeMachine) {
			disassembleMachine();
		}
	}
	
	/**
	 * Called when a machine becomes "whole" and should begin
	 * functioning as a game-logically finished machine.
	 * Calls onMachineAssembled on all attached parts.
	 */
	protected void assembleMachine() {
		TileEntity te;
		for(CoordTriplet coord : connectedBlocks) {
			te = this.worldObj.getBlockTileEntity(coord.x, coord.y, coord.z);
			if(te instanceof IMultiblockPart) {
				((IMultiblockPart)te).onMachineAssembled();
			}
		}
	}
	
	/**
	 * Called when the machine needs to be disassembled.
	 * It is not longer "whole" and should not be functional, usually
	 * as a result of a block being removed.
	 * Called onMachineBroken on all attached parts.
	 */
	protected void disassembleMachine() {
		TileEntity te;
		for(CoordTriplet coord : connectedBlocks) {
			te = this.worldObj.getBlockTileEntity(coord.x, coord.y, coord.z);
			if(te instanceof IMultiblockPart) {
				((IMultiblockPart)te).onMachineBroken();
			}
		}
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
		List<CoordTriplet> blocksToAcquire = (LinkedList<CoordTriplet>)other.connectedBlocks.clone();

		// releases all blocks and references gently so they can be incorporated into another multiblock
		other.onConsumedByOtherController();
		
		for(CoordTriplet coord : blocksToAcquire) {
			// By definition, none of these can be the minimum block.
			this.connectedBlocks.add(coord);
			te = this.worldObj.getBlockTileEntity(coord.x, coord.y, coord.z);
			((IMultiblockPart)te).onMergedIntoOtherMultiblock(this);
		}
	}
	
	/**
	 * Called when this machine is consumed by another controller.
	 * Essentially, forcibly tear down this object.
	 */
	private void onConsumedByOtherController() {
		this.disassembleMachine();
		this.referenceCoord = null;
		TileEntity te = this.worldObj.getBlockTileEntity(saveDelegate.x, saveDelegate.y, saveDelegate.z);
		((IMultiblockPart)te).forfeitMultiblockSaveDelegate();
		this.saveDelegate = null;
		this.connectedBlocks.clear();
		MultiblockRegistry.unregister(this);
	}

	/**
	 * Called after all multiblock machine merges have been completed for
	 * this machine.
	 */
	public void endMerging() {
		this.recalculateDistances();
	}
	
	// TODO: Change this so that people can safely override a business-logic callback
	// and don't need to call super.
	/**
	 * The update loop! Implement the game logic you would like to execute
	 * on every world tick here.
	 * Make damned sure to call super.updateMultiblockEntity()!
	 */
	public void updateMultiblockEntity() {
		if(cachedBlock != null) {
			// First frame
			TileEntity te = this.worldObj.getBlockTileEntity(cachedBlock.x, cachedBlock.y, cachedBlock.z);
			cachedBlock = null;
			assert(te instanceof IMultiblockPart);
			
			// This will spider out and reconnect to all the blocks loaded into the world
			// on the first frame.
			this.attachBlock((IMultiblockPart)te);
		}
		
		if(this.connectedBlocks.isEmpty()) {
			MultiblockRegistry.unregister(this);
			return;
		}
		
		if(this.blocksHaveChangedThisFrame) {
			// Assemble/break machine if we have to
			checkIfMachineIsWhole();
			this.blocksHaveChangedThisFrame = false;
		}
	}

	/**
	 * Recalculates the walk distance from the reference coordinate to
	 * all other connected blocks.
	 */
	protected void recalculateDistances() {
		TileEntity te;
		// Reset all distances and find the minimum coordinate
		for(CoordTriplet c: connectedBlocks) {
			te = this.worldObj.getBlockTileEntity(c.x, c.y, c.z);
			((IMultiblockPart)te).setDistance(IMultiblockPart.INVALID_DISTANCE);
			
			if(referenceCoord == null) {
				referenceCoord = c;
			}
			else if(c.compareTo(referenceCoord) < 0) {
				referenceCoord = c;
			}
		}
		
		IMultiblockPart part = (IMultiblockPart)this.worldObj.getBlockTileEntity(referenceCoord.x, referenceCoord.y, referenceCoord.z);
		part.setDistance(0);

		LinkedList<IMultiblockPart> partsToCheck = new LinkedList<IMultiblockPart>();
		IMultiblockPart[] nearbyParts = part.getNeighboringParts();
		for(IMultiblockPart nearbyPart : nearbyParts) {
			assert(nearbyPart.getMultiblockController() == this);
			if(nearbyPart.getDistanceFromReferenceCoord() == IMultiblockPart.INVALID_DISTANCE) {
				partsToCheck.add(nearbyPart);
			}
		}
		
		// Do a breadth-first search of neighboring parts.
		// Any parts not yet calculated will be added to the queue.
		int minimumNearbyDistance;
		while(!partsToCheck.isEmpty()) {
			part = partsToCheck.removeFirst();
			assert(part.getMultiblockController() == this);
			assert(part.getDistanceFromReferenceCoord() == IMultiblockPart.INVALID_DISTANCE);
			
			minimumNearbyDistance = IMultiblockPart.INVALID_DISTANCE;
			
			nearbyParts = part.getNeighboringParts();
			for(IMultiblockPart nearbyPart : nearbyParts) {
				assert(nearbyPart.getMultiblockController() == this);
				minimumNearbyDistance = Math.min(minimumNearbyDistance, nearbyPart.getDistanceFromReferenceCoord());
				
				if(nearbyPart.getDistanceFromReferenceCoord() == IMultiblockPart.INVALID_DISTANCE) {
					partsToCheck.add(nearbyPart);
				}
			}
			
			part.setDistance(minimumNearbyDistance+1);
		}
		
		for(CoordTriplet coord : connectedBlocks) {
			part = (IMultiblockPart)this.worldObj.getBlockTileEntity(coord.x, coord.y, coord.z);
			part.sendUpdatePacket();
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
	 * @return The coordinate of the save-delegate block.
	 */
	public CoordTriplet getDelegateLocation() { return saveDelegate; }
	
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
}
