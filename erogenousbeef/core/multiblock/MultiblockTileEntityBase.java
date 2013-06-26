package erogenousbeef.core.multiblock;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import cpw.mods.fml.common.network.PacketDispatcher;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import erogenousbeef.core.common.CoordTriplet;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.INetworkManager;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.Packet132TileEntityData;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;

/**
 * Base logic class for Multiblock-connected tile entities. Most multiblock machines
 * should derive from this and implement their game logic in certain abstract methods.
 */
public abstract class MultiblockTileEntityBase extends TileEntity implements IMultiblockPart {
	private MultiblockControllerBase controller;
	private byte visited;
	private static final byte kUnvisited = 0;
	private static final byte kVisited = 1;
	
	
	private boolean saveMultiblockData;
	private NBTTagCompound cachedMultiblockData;
	private boolean paused;

	public MultiblockTileEntityBase() {
		super();
		controller = null;
		visited = kUnvisited;
		saveMultiblockData = false;
		paused = false;
		cachedMultiblockData = null;
	}

	///// Multiblock Connection Base Logic
	
	/**
	 * Remember to call super.onBlockAdded() if you derive from this! This performs important
	 * checks to fuse multiple controllers that are now logically conjoined by the new block.
	 * {@link erogenousbeef.core.multiblock.IMultiblockPart#onBlockAdded(net.minecraft.world.World, int, int, int)}
	 */
	@Override
	public void onBlockAdded(World world, int x, int y, int z) {
		IMultiblockPart[] partsToCheck = getNeighboringParts();
		
		TileEntity remoteTE;
		IMultiblockPart remotePart;
		IMultiblockPart connectionTarget = null;
		CoordTriplet targetCoord = null;

		List<MultiblockControllerBase> controllers = new LinkedList<MultiblockControllerBase>();

		// Check all adjacent loaded blocks for controllers. If they're all the same controller,
		// attach via the "closest" one. Otherwise, attach to the closest one and then
		// merge all controllers.
		for(IMultiblockPart neighborPart : partsToCheck) {
			if(neighborPart.isConnected()) {
				CoordTriplet coord = neighborPart.getWorldLocation();
				if(!controllers.contains(neighborPart.getMultiblockController())) {
					if(connectionTarget == null || connectionTarget.getMultiblockController().getReferenceCoord().compareTo(neighborPart.getMultiblockController().getReferenceCoord()) > 0) {
						// Different machine controller, better target. Or first controller encountered.
						connectionTarget = neighborPart;
						targetCoord = coord;
					}
					controllers.add(neighborPart.getMultiblockController());						
				}
				else {
					// We've already encountered this one, so first check if it's the same machine.
					if(neighborPart.getMultiblockController() != connectionTarget.getMultiblockController()) {
						// Okay, we need to see if this is a "better" connection candidate;
						// That is, it's also a compatible machine AND this part's controller
						// has a refcoord smaller than the existing target.
						if(targetCoord == null) {
							targetCoord = coord;
							connectionTarget = neighborPart;
						}
						else if(coord.compareTo(targetCoord) < 0) {
							// We found a "better" target.
							targetCoord = coord;
							connectionTarget = neighborPart;
						}
						// Else, current target is better, continue using it.
					}
					// Else, it's a machine that we've already decided not to connect to.
				}
			}
		} // End search for connection target

		if(connectionTarget != null) {
			controllers.remove(connectionTarget.getMultiblockController());
			connectionTarget.getMultiblockController().attachBlock(this);

			if(controllers.size() > 0) {
				// Oh shit it's merge time
				CoordTriplet hostLoc = this.controller.getReferenceCoord();
				this.controller.beginMerging();
				for(MultiblockControllerBase controllerToMerge : controllers) {
					CoordTriplet mergeLoc = controllerToMerge.getReferenceCoord();
					this.controller.merge(controllerToMerge);
				}
				this.controller.endMerging();
			}
		}
		else {
			this.createNewMultiblock();
		}
	}
	
	///// Overrides from base TileEntity methods
	
	@Override
	public void readFromNBT(NBTTagCompound data) {
		super.readFromNBT(data);
		
		// We can't directly initialize a multiblock controller yet, so we cache the data here until
		// we receive a validate() call, which creates the controller and hands off the cached data.
		if(data.hasKey("multiblockData")) {
			this.cachedMultiblockData = data.getCompoundTag("multiblockData");
		}
	}
	
	@Override
	public void writeToNBT(NBTTagCompound data) {
		super.writeToNBT(data);
		
		if(this.saveMultiblockData) {
			NBTTagCompound multiblockData = new NBTTagCompound();
			this.controller.writeToNBT(multiblockData);
			data.setCompoundTag("multiblockData", multiblockData);
		}
	}
		
	/*
	 * Generally, TileEntities that are part of a multiblock should not subscribe to updates
	 * from the main game loop. Instead, you should have lists of TileEntities which need to
	 * be notified during an update() in your Controller and perform callbacks from there.
	 * @see net.minecraft.tileentity.TileEntity#canUpdate()
	 */
	@Override
	public boolean canUpdate() { return false; }
	
	/*
	 * Called when a block is removed.
	 * @see net.minecraft.tileentity.TileEntity#invalidate()
	 */
	@Override
	public void invalidate() {
		super.invalidate();
		
		detachSelf(false);
	}
	
	// Called -immediately- when a chunk is unloaded.
	// Quickly detach from the controller.
	@Override
	public void onChunkUnloaded() {
		detachSelf(true);
	}
	
	@Override
	public void onChunkUnload() {
		super.onChunkUnload();
		
		// On the client, this can be relied upon, I hope.
		if(this.worldObj.isRemote) {
			detachSelf(true);
		}
	}

	// This is actually called from a special hacked chunk-load event detector, because
	// minecraft won't tell us this directly. :(
	@Override
	public void onChunkLoad() {
		if(this.cachedMultiblockData != null) {
			// We need to create a new multiblock BUT we cannot check the world yet.
			// So we do something stupid and special.
			MultiblockControllerBase newController = getNewMultiblockControllerObject();
			newController.restore(this.cachedMultiblockData);
			this.cachedMultiblockData = null;
			newController.attachBlock(this); // This should grab any other connected blocks in the chunk
		}
		else {
			if(!this.isConnected()) {
				// Ignore blocks that are already connected
				this.onBlockAdded(this.worldObj, xCoord, yCoord, zCoord);
			}
		}
	}
	
	/*
	 * This is called when a block is being marked as valid by the chunk, but has not yet fully
	 * been placed into the world's TileEntity cache. this.worldObj, xCoord, yCoord and zCoord have
	 * been initialized, but any attempts to read data about the world can cause infinite loops -
	 * if you call getTileEntity on this TileEntity's coordinate from within validate(), you will
	 * blow your call stack.
	 * 
	 * TL;DR: Here there be dragons.
	 * @see net.minecraft.tileentity.TileEntity#validate()
	 */
	@Override
	public void validate() {
		super.validate();
		
		if(!this.worldObj.isRemote) {
			MultiblockRegistry.registerPart(this.worldObj, ChunkCoordIntPair.chunkXZ2Int(xCoord >> 4, zCoord >> 4), this);
			
			if(!this.worldObj.getChunkProvider().chunkExists(xCoord >> 4, zCoord >> 4)) {
				boolean priority = this.cachedMultiblockData != null;
				MultiblockRegistry.onPartLoaded(this.worldObj, ChunkCoordIntPair.chunkXZ2Int(xCoord >> 4, zCoord >> 4), this, priority);
			}
		}
	}
	
	// Network Communication
	@Override
	public Packet getDescriptionPacket() {
		NBTTagCompound packetData = new NBTTagCompound();
		formatDescriptionPacket(packetData);
		return new Packet132TileEntityData(xCoord, yCoord, zCoord, 0, packetData);
	}
	
	@Override
	public void onDataPacket(INetworkManager network, Packet132TileEntityData packet) {
		decodeDescriptionPacket(packet.customParam1);
	}
	
	///// Things to override in most implementations (IMultiblockPart)

	@Override
	public void sendUpdatePacket() {
		this.worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
	}
	
	/*
	 * Override this to easily modify the description packet's data without having
	 * to worry about sending the packet itself.
	 */
	protected void formatDescriptionPacket(NBTTagCompound packetData) {
		packetData.setByte("visited", this.visited);
		if(this.isMultiblockSaveDelegate()) {
			NBTTagCompound tag = new NBTTagCompound();
			getMultiblockController().formatDescriptionPacket(tag);
			packetData.setCompoundTag("multiblockData", tag);
		}
	}
	
	/*
	 * Override this to easily read in data from a TileEntity's description packet.
	 */
	protected void decodeDescriptionPacket(NBTTagCompound packetData) {
		if(packetData.hasKey("visited")) {
			this.visited = packetData.getByte("visited");
		}
		else {
			this.setUnvisited();
		}

		if(packetData.hasKey("multiblockData")) {
			NBTTagCompound tag = packetData.getCompoundTag("multiblockData");
			if(isConnected()) {
				getMultiblockController().decodeDescriptionPacket(tag);
			}
			else {
				if(this.worldObj.isRemote) {
					if(!this.isConnected()) {
						// If a client receives a desc packet and is not yet connected, forcibly connect
						onBlockAdded(worldObj, xCoord, yCoord, zCoord);
					}
					getMultiblockController().decodeDescriptionPacket(tag);
				}
				else {
					this.cachedMultiblockData = tag;
				}
			}
		}
		
		// Ensure that client blocks are always connected,
		// since the server doesn't do an "onBlockAdded" callback.
		// TODO: Try removing this.
		if(!this.isConnected() && this.worldObj.isRemote) {
			onBlockAdded(worldObj, xCoord, yCoord, zCoord);
		}
	}

	@Override
	public abstract MultiblockControllerBase getNewMultiblockControllerObject();
	
	///// Validation Helpers (IMultiblockPart)
	
	@Override
	public abstract boolean isGoodForFrame();

	@Override
	public abstract boolean isGoodForSides();

	@Override
	public abstract boolean isGoodForTop();

	@Override
	public abstract boolean isGoodForBottom();

	@Override
	public abstract boolean isGoodForInterior();

	///// Game logic callbacks (IMultiblockPart)
	
	@Override
	public abstract void onMachineAssembled();

	@Override
	public abstract void onMachineBroken();

	@Override
	public abstract void onMachineActivated();

	@Override
	public abstract void onMachineDeactivated();

	///// Miscellaneous multiblock-assembly callbacks and support methods (IMultiblockPart)
	
	@Override
	public boolean isConnected() {
		return (controller != null);
	}

	@Override
	public MultiblockControllerBase getMultiblockController() {
		return controller;
	}

	@Override
	public CoordTriplet getWorldLocation() {
		return new CoordTriplet(this.xCoord, this.yCoord, this.zCoord);
	}
	
	@Override
	public void becomeMultiblockSaveDelegate() {
		this.saveMultiblockData = true;
	}

	@Override
	public void forfeitMultiblockSaveDelegate() {
		this.saveMultiblockData = false;
	}
	
	@Override
	public boolean isMultiblockSaveDelegate() { return this.saveMultiblockData; }

	@Override
	public void setUnvisited() {
		this.visited = kUnvisited;
	}
	
	@Override
	public void setVisited() {
		this.visited = kVisited;
	}
	
	@Override
	public boolean isVisited() {
		return this.visited != kUnvisited;
	}

	@Override
	public void onMergedIntoOtherMultiblock(MultiblockControllerBase newController) {
		assert(this.controller != newController);
		this.controller = newController;
	}
	
	@Override
	public void onAttached(MultiblockControllerBase newController) {
		this.controller = newController;
	}
	
	@Override
	public void onDetached(MultiblockControllerBase oldController) {
		if(this.controller == null) {
			throw new IllegalArgumentException("Detaching but the current controller is already null!");
		}
		else if(this.controller != oldController) {
			throw new IllegalArgumentException("Detaching from wrong controller, old @ " + oldController.hashCode() + " current @ " + this.controller.hashCode());
		}

		this.controller = null;
	}

	@Override
	public void createNewMultiblock() {
		MultiblockControllerBase newController = getNewMultiblockControllerObject();
		newController.attachBlock(this);
	}

	@Override
	public IMultiblockPart[] getNeighboringParts() {
		CoordTriplet[] neighbors = new CoordTriplet[] {
				new CoordTriplet(this.xCoord-1, this.yCoord, this.zCoord),
				new CoordTriplet(this.xCoord, this.yCoord-1, this.zCoord),
				new CoordTriplet(this.xCoord, this.yCoord, this.zCoord-1),
				new CoordTriplet(this.xCoord, this.yCoord, this.zCoord+1),
				new CoordTriplet(this.xCoord, this.yCoord+1, this.zCoord),
				new CoordTriplet(this.xCoord+1, this.yCoord, this.zCoord)
		};

		TileEntity te;
		List<IMultiblockPart> neighborParts = new ArrayList<IMultiblockPart>();
		for(CoordTriplet neighbor : neighbors) {
			if(!this.worldObj.getChunkProvider().chunkExists(neighbor.x >> 4, neighbor.z >> 4)) {
				// Chunk not loaded, skip it.
				continue;
			}

			te = this.worldObj.getBlockTileEntity(neighbor.x, neighbor.y, neighbor.z);
			if(te instanceof IMultiblockPart) {
				neighborParts.add((IMultiblockPart)te);
			}
		}
		IMultiblockPart[] tmp = new IMultiblockPart[neighborParts.size()];
		return neighborParts.toArray(tmp);
	}
	
	@Override
	public void onOrphaned() {
		if(this.isConnected()) {
			// Well, we're not REALLY an orphan.
			return;
		}
		
		createNewMultiblock();

		// Now for fun. Add all neighbors and DFS out into the world.
		Queue<IMultiblockPart> partsToCheck = new LinkedList<IMultiblockPart>();

		// Add all unconnected neighbors in loaded chunks
		IMultiblockPart[] neighborParts = getNeighboringParts();
		for(IMultiblockPart neighborPart : neighborParts) {
			if(!neighborPart.isConnected()) {
				partsToCheck.add(neighborPart);
			}
		}

		IMultiblockPart part;
		while(!partsToCheck.isEmpty()) {
			part = partsToCheck.remove();
			if(part.isConnected()) {
				// Ignore connected parts that aren't us
				continue;
			}
			
			// We're already connected by virtue of the new controller
			this.controller.attachBlock(part);
			
			// Add all unconnected neighbors of this part
			neighborParts = part.getNeighboringParts();
			for(IMultiblockPart neighbor : neighborParts) {
				if(!neighbor.isConnected()) {
					partsToCheck.add(neighbor);
				}
			}
		}
	}

	///// Private/Protected Logic Helpers

	/*
	 * Attaches this block to the specified controller. Assigns the controller
	 * member and calls attachBlock on the controller.
	 */
	protected void attachSelf(World world, MultiblockControllerBase newController) {
		// holy shit we're good to go
		this.controller = newController;
		this.controller.attachBlock(this);
	}
	
	/*
	 * Detaches this block from its controller. Calls detachBlock() and clears the controller member.
	 */
	protected void detachSelf(boolean chunkUnloading) {
		if(this.controller != null) {
			this.controller.detachBlock(this, chunkUnloading);
			this.controller = null;
		}
	}
}
