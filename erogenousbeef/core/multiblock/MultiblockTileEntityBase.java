package erogenousbeef.core.multiblock;

import java.util.LinkedList;
import java.util.List;

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
import net.minecraft.world.World;

/**
 * Base logic class for Multiblock-connected tile entities. Most multiblock machines
 * should derive from this and implement their game logic in certain abstract methods.
 */
public abstract class MultiblockTileEntityBase extends TileEntity implements IMultiblockPart {
	private MultiblockControllerBase controller;
	private int distance;
	private boolean saveMultiblockData;
	private NBTTagCompound cachedMultiblockData;

	public MultiblockTileEntityBase() {
		super();
		controller = null;
		distance = IMultiblockPart.INVALID_DISTANCE;
		saveMultiblockData = false;
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
		CoordTriplet[] coordsToCheck = getNeighborCoords();
		
		TileEntity remoteTE;
		IMultiblockPart remotePart;
		IMultiblockPart connectionTarget = null;

		List<MultiblockControllerBase> controllers = new LinkedList<MultiblockControllerBase>();

		// Check all adjacent blocks for controllers. If they're all the same controller,
		// attach via the "closest" one. Otherwise, attach to the closest one and then
		// merge all controllers.
		for(CoordTriplet coord : coordsToCheck) {
			remoteTE = world.getBlockTileEntity(coord.x, coord.y, coord.z);
			
			if(remoteTE != null && remoteTE instanceof IMultiblockPart) {
				remotePart = (IMultiblockPart)remoteTE;
				if(remotePart.isConnected()) {
					if(!controllers.contains(remotePart.getMultiblockController())) {
						if(connectionTarget == null || connectionTarget.getMultiblockController().getReferenceCoord().compareTo(remotePart.getMultiblockController().getReferenceCoord()) > 0) {
							// Different machine controller, better target. Or first controller encountered.
							connectionTarget = remotePart;
						}
						controllers.add(remotePart.getMultiblockController());						
					}
					else {
						// We've already encountered this one, so first check if it's the same machine.
						if(remotePart.getMultiblockController() == connectionTarget.getMultiblockController()) {
							// It is, so we need to see if this is a closer connection.
							if(remotePart.isConnected() && (connectionTarget == null || connectionTarget.getDistanceFromReferenceCoord() > remotePart.getDistanceFromReferenceCoord()))
							{
								connectionTarget = remotePart;
							}
						}
						// Else, it's a machine that we've already decided not to connect to.
						// IT WILL BECOME PART OF US LATER.
					}
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
		
		detachSelf();
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
		
		if(this.cachedMultiblockData != null) {
			// We need to create a new multiblock BUT we cannot check the world yet.
			// So we do something stupid and special.
			MultiblockControllerBase newController = getNewMultiblockControllerObject();
			newController.loadAndCacheInitialBlock(this.getWorldLocation(), this.cachedMultiblockData);
			this.cachedMultiblockData = null;
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
		PacketDispatcher.sendPacketToAllAround(xCoord, yCoord, zCoord, 50, worldObj.provider.dimensionId, getDescriptionPacket());
	}
	
	/*
	 * Override this to easily modify the description packet's data without having
	 * to worry about sending the packet itself.
	 */
	protected void formatDescriptionPacket(NBTTagCompound packetData) {
		packetData.setInteger("distance", this.distance);
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
		if(packetData.hasKey("distance")) {
			this.distance = packetData.getInteger("distance");
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
	public int getDistanceFromReferenceCoord() {
		return distance;
	}
	
	@Override
	public void setDistance(int newDistance) {
		this.distance = newDistance;
	}

	@Override
	public void onMergedIntoOtherMultiblock(MultiblockControllerBase newController) {
		assert(this.controller != newController);
		this.controller = newController;
		this.distance = IMultiblockPart.INVALID_DISTANCE;
	}
	
	@Override
	public void onAttached(MultiblockControllerBase newController) {
		this.controller = newController;
	}
	
	@Override
	public void onDetached(MultiblockControllerBase oldController) {
		assert(this.controller == oldController);
		this.controller = null;
		this.distance = IMultiblockPart.INVALID_DISTANCE;
	}

	@Override
	public void createNewMultiblock() {
		MultiblockControllerBase newController = getNewMultiblockControllerObject();
		newController.attachBlock(this);
	}

	@Override
	public IMultiblockPart[] getNeighboringParts() {
		CoordTriplet[] neighbors = getNeighborCoords();
		TileEntity te;
		List<IMultiblockPart> neighborParts = new LinkedList<IMultiblockPart>();
		for(CoordTriplet neighbor : neighbors) {
			te = this.worldObj.getBlockTileEntity(neighbor.x, neighbor.y, neighbor.z);
			if(te != null && te instanceof IMultiblockPart) {
				neighborParts.add((IMultiblockPart)te);
			}
		}
		IMultiblockPart[] tmp = new IMultiblockPart[neighborParts.size()];
		return neighborParts.toArray(tmp);
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
	protected void detachSelf() {
		if(this.controller != null) {
			this.controller.detachBlock(this);
			this.controller = null;
		}
	}
	
	/*
	 * Get a list containing the six coordinates neighboring this one.
	 */
	protected CoordTriplet[] getNeighborCoords() {
		// It's important that these are in sorted order. MinX-MinY-MinZ-MaxZ-MaxY-MaxX
		return new CoordTriplet[] {
				new CoordTriplet(this.xCoord-1, this.yCoord, this.zCoord),
				new CoordTriplet(this.xCoord, this.yCoord-1, this.zCoord),
				new CoordTriplet(this.xCoord, this.yCoord, this.zCoord-1),
				new CoordTriplet(this.xCoord, this.yCoord, this.zCoord+1),
				new CoordTriplet(this.xCoord, this.yCoord+1, this.zCoord),
				new CoordTriplet(this.xCoord+1, this.yCoord, this.zCoord)
		};		
	}
}
