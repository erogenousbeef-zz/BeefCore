package erogenousbeef.core.multiblock;

import java.util.LinkedList;
import java.util.List;

import net.minecraft.block.material.Material;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import erogenousbeef.core.common.CoordTriplet;

public class MultiblockControllerBase {
	// Multiblock stuff
	private World worldObj;
	private boolean isWholeMachine;

	private LinkedList<CoordTriplet> connectedBlocks;
	private CoordTriplet saveDelegate; // Also the network communication delegate
	
	// This is the "reference coord", a deterministically-picked coordinate that identifies this
	// multiblock uniquely in its dimension.
	// Currently, this is the coord with the lowest X, Y and Z coordinates, in that order of evaluation.
	// i.e. If something has a lower X but higher Y/Z coordinates, it will still be the reference.
	// If something has the same X but a lower Y coordinate, it will be the reference. Etc.
	private CoordTriplet referenceCoord;
	
	private CoordTriplet cachedBlock;
	
	public MultiblockControllerBase(World world) {
		// Multiblock stuff
		worldObj = world;
		isWholeMachine = false;
		connectedBlocks = new LinkedList<CoordTriplet>();
		saveDelegate = null;
		referenceCoord = null;
		cachedBlock = null;

	}

	public void loadAndCacheInitialBlock(CoordTriplet initialBlock, NBTTagCompound savedData) {
		this.readFromNBT(savedData);
		cachedBlock = initialBlock;
		MultiblockRegistry.register(this);
	}
	
	public int getNumConnectedBlocks() { return connectedBlocks.size(); }
	
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

		this.recalculateDistances();
		
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
		
		checkMachineWholeness(this.worldObj, false);
	}
	
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
		
		// Was this block already known disconnected?
		if(wasDetachedAlready) {
			// Yup, but it was already known disconnected.
			return;
		}
		
		// Recalculate all distances from the minimum coord.
		this.recalculateDistances();
		
		// Perform fission. Can result in up to 6 new TEs.
		doFission(coord);
		
		// Break machine if we have to.
		checkMachineWholeness(this.worldObj, true);
		
		// Find new save delegate if we need to.
		if(saveDelegate == null) {
			saveDelegate = referenceCoord;
			TileEntity te = this.worldObj.getBlockTileEntity(saveDelegate.x, saveDelegate.y, saveDelegate.z);
			((IMultiblockPart)te).becomeMultiblockSaveDelegate();
		}
	}

	private void doFission(CoordTriplet center) {
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

	private void getMinMaxCoords(CoordTriplet minCoord, CoordTriplet maxCoord) {
		// Find min/max coordinates
		for(CoordTriplet coord : connectedBlocks) {
			if(coord.x < minCoord.x) { minCoord.x = coord.x; }
			if(coord.x > maxCoord.x) { maxCoord.x = coord.x; } 
			if(coord.y < minCoord.y) { minCoord.y = coord.y; }
			if(coord.y > maxCoord.y) { maxCoord.y = coord.y; } 
			if(coord.z < minCoord.z) { minCoord.z = coord.z; }
			if(coord.z > maxCoord.z) { maxCoord.z = coord.z; } 
		}
	}
	
	private void checkMachineWholeness(World world, boolean breakMachineIfNeeded) {
		boolean isWhole = false;
		CoordTriplet minCoord = new CoordTriplet(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
		CoordTriplet maxCoord = new CoordTriplet(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
		
		// For a 3x3 cube, the smallest outline is 20 blocks
		if(connectedBlocks.size() >= 20) {
			getMinMaxCoords(minCoord, maxCoord);
			
			// Now we run a simple check on each block within that volume.
			// Any block deviating = NO DEAL SIR
			TileEntity te;
			IMultiblockPart part;
			boolean dealbreaker = false;
			for(int x = minCoord.x; !dealbreaker && x <= maxCoord.x; x++) {
				for(int y = minCoord.y; !dealbreaker && y <= maxCoord.y; y++) {
					for(int z = minCoord.z; !dealbreaker && z <= maxCoord.z; z++) {
						// Okay, figure out what sort of block this should be.
						
						te = world.getBlockTileEntity(x, y, z);
						if(te != null && te instanceof IMultiblockPart) {
							part = (IMultiblockPart)te;
						}
						else {
							part = null;
						}
						
						// Validate block type against both part-level and material-level validators.
						int extremes = 0;
						if(x == minCoord.x) { extremes++; }
						if(y == minCoord.y) { extremes++; }
						if(z == minCoord.z) { extremes++; }
						
						if(x == maxCoord.x) { extremes++; }
						if(y == maxCoord.y) { extremes++; }
						if(z == maxCoord.z) { extremes++; }
						
						if(extremes >= 2) {
							if(part != null && !part.isGoodForFrame()) {
								dealbreaker = true;
							}
							else if(part == null && !isBlockGoodForFrame(world, x, y, z)) {
								dealbreaker = true;
							}
						}
						else if(extremes == 1) {
							if(y == maxCoord.y) {
								if(part != null && !part.isGoodForTop()) {
									dealbreaker = true;
								}
								else if(part == null & !isBlockGoodForTop(world, x, y, z)) {
									dealbreaker = true;
								}
							}
							else if(y == minCoord.y) {
								if(part != null && !part.isGoodForBottom()) {
									dealbreaker = true;
								}
								else if(part == null & !isBlockGoodForBottom(world, x, y, z)) {
									dealbreaker = true;
								}
							}
							else {
								// Side
								if(part != null && !part.isGoodForSides()) {
									dealbreaker = true;
								}
								else if(part == null & !isBlockGoodForSides(world, x, y, z)) {
									dealbreaker = true;
								}
							}
						}
						else {
							if(part != null && !part.isGoodForInterior()) {
								dealbreaker = true;
							}
							else if(part == null & !isBlockGoodForInterior(world, x, y, z)) {
								dealbreaker = true;
							}
						}
					}
				}
			}
			
			if(!dealbreaker) {
				isWhole = true;
				assembleMachine(world, minCoord, maxCoord);
			}
			else if(breakMachineIfNeeded) {
				disassembleMachine(world, minCoord, maxCoord);
			}
		}
		else if(breakMachineIfNeeded) {
			getMinMaxCoords(minCoord, maxCoord);
			disassembleMachine(world, minCoord, maxCoord);
		}
		
		this.isWholeMachine = isWhole;
	}
	
	private void assembleMachine(World world, CoordTriplet minCoord, CoordTriplet maxCoord) {
		TileEntity te;
		for(CoordTriplet coord : connectedBlocks) {
			te = world.getBlockTileEntity(coord.x, coord.y, coord.z);
			if(te instanceof IMultiblockPart) {
				((IMultiblockPart)te).onMachineAssembled(minCoord, maxCoord);
			}
		}
	}
	
	private void disassembleMachine(World world, CoordTriplet minCoord, CoordTriplet maxCoord) {
		TileEntity te;
		for(CoordTriplet coord : connectedBlocks) {
			te = world.getBlockTileEntity(coord.x, coord.y, coord.z);
			if(te instanceof IMultiblockPart) {
				((IMultiblockPart)te).onMachineBroken();
			}
		}
	}
	
	public void beginMerging() {
		// ???
	}
	
	// endMerging() MUST be called after 1 or more merge() calls.
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
	
	private void onConsumedByOtherController() {
		CoordTriplet minCoord = new CoordTriplet(0,0,0);
		CoordTriplet maxCoord = new CoordTriplet(0,0,0);
		this.getMinMaxCoords(minCoord, maxCoord);
		this.disassembleMachine(this.worldObj, minCoord, maxCoord);
		this.referenceCoord = null;
		TileEntity te = this.worldObj.getBlockTileEntity(saveDelegate.x, saveDelegate.y, saveDelegate.z);
		((IMultiblockPart)te).forfeitMultiblockSaveDelegate();
		this.saveDelegate = null;
		this.connectedBlocks.clear();
		MultiblockRegistry.unregister(this);
	}

	public void endMerging() {
		this.recalculateDistances();
	}
	
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
		}
	}

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
	
	// Static validation helpers
	private static boolean isBlockGoodForFrame(World world, int x, int y, int z) {
		return false;
	}

	// Any casing block or control rod, plus glass
	private static boolean isBlockGoodForTop(World world, int x, int y, int z) {
		return world.getBlockMaterial(x, y, z) == net.minecraft.block.material.Material.glass;
	}
	
	// Casing and glass only
	private static boolean isBlockGoodForBottom(World world, int x, int y, int z) {
		return 	world.getBlockMaterial(x, y, z) == net.minecraft.block.material.Material.glass;
		
	}
	
	// Casing and parts, but no control rods, plus glass.
	private static boolean isBlockGoodForSides(World world, int x, int y, int z) {
		return world.getBlockMaterial(x, y, z) == net.minecraft.block.material.Material.glass;
	}
	
	// Yellorium fuel rods, water and air.
	private static boolean isBlockGoodForInterior(World world, int x, int y, int z) {
		Material material = world.getBlockMaterial(x, y, z);
		if(material == net.minecraft.block.material.MaterialLiquid.water ||
			material == net.minecraft.block.material.Material.air) {
			return true;
		}
		return false;
	}
	
	public CoordTriplet getDelegateLocation() { return saveDelegate; }
	public CoordTriplet getMinimumCoord() { return referenceCoord; }

	public void writeToNBT(NBTTagCompound data) {
		// TODO Auto-generated method stub
		
	}

	public void readFromNBT(NBTTagCompound data) {
		// TODO Auto-generated method stub
		
	}
}
