package erogenousbeef.core.multiblock;

import java.util.LinkedList;
import java.util.List;

import cpw.mods.fml.common.network.PacketDispatcher;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import erogenousbeef.core.common.CoordTriplet;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.INetworkManager;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.Packet132TileEntityData;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

public class MultiblockTileEntityBase extends TileEntity implements IMultiblockPart {
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

	// Multiblock Base Code
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
						if(connectionTarget == null || connectionTarget.getMultiblockController().getMinimumCoord().compareTo(remotePart.getMultiblockController().getMinimumCoord()) > 0) {
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
				CoordTriplet hostLoc = this.controller.getMinimumCoord();
				this.controller.beginMerging();
				for(MultiblockControllerBase controllerToMerge : controllers) {
					CoordTriplet mergeLoc = controllerToMerge.getMinimumCoord();
					this.controller.merge(controllerToMerge);
				}
				this.controller.endMerging();
			}
		}
		else {
			this.createNewMultiblock();
		}
	}
	
	public void onNeighborConnectedToMaster(World world, MultiblockControllerBase multiblockMaster) {
		if(this.controller == multiblockMaster) {
			// Re-connect notification. Ignore.
			return;
		}
		
		// Are we moving to a new master?
		if(this.controller != null) {
			detachSelf();
		}
		
		attachSelf(world, multiblockMaster);
	}
	
	protected void attachSelf(World world, MultiblockControllerBase newController) {
		// holy shit we're good to go
		this.controller = newController;
		this.controller.attachBlock(this);
	}
	
	protected void detachSelf() {
		if(this.controller != null) {
			this.controller.detachBlock(this);
			this.controller = null;
		}
	}

	// Overrides from TileEntity
	@Override
	public boolean canUpdate() { return false; }
	
	@Override
	public void invalidate() {
		super.invalidate();
		
		detachSelf();
	}
	
	@Override
	public void validate() {
		super.validate();
		
		if(this.cachedMultiblockData != null) {
			// We need to create a new multiblock BUT we cannot check the world yet.
			// So we do something stupid and special.
			MultiblockControllerBase newController = new MultiblockControllerBase(this.worldObj);
			newController.loadAndCacheInitialBlock(this.getWorldLocation(), this.cachedMultiblockData);
			this.cachedMultiblockData = null;
		}
	}
	
	protected void formatDescriptionPacket(NBTTagCompound packetData) {
		packetData.setInteger("distance", this.distance);
	}
	
	protected void decodeDescriptionPacket(NBTTagCompound packetData) {
		if(packetData.hasKey("distance")) {
			this.distance = packetData.getInteger("distance");
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
	
	// IMultiblockPart
	
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
	public boolean isGoodForFrame() {
		return true;
	}

	@Override
	public boolean isGoodForSides() {
		return true;
	}

	@Override
	public boolean isGoodForTop() {
		return true;
	}

	@Override
	public boolean isGoodForBottom() {
		return true;
	}

	@Override
	public boolean isGoodForInterior() {
		return false;
	}

	@Override
	public void onMachineAssembled(CoordTriplet machineMinCoords,
			CoordTriplet machineMaxCoords) {
	}

	@Override
	public void onMachineBroken() {
	}

	@Override
	public void onMachineActivated() {
	}

	@Override
	public void onMachineDeactivated() {
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

	// Things to override in most implementations
	@Override
	public MultiblockControllerBase getNewMultiblockControllerObject() {
		return new MultiblockControllerBase(this.worldObj);
	}

	@Override
	public void sendUpdatePacket() {
		this.worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
		PacketDispatcher.sendPacketToAllAround(xCoord, yCoord, zCoord, 50, worldObj.provider.dimensionId, getDescriptionPacket());
	}
	
	
	// Overrides from base TileEntity methods
	
	@Override
	public void readFromNBT(NBTTagCompound data) {
		super.readFromNBT(data);
		
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
	
	// Private Helpers
	private CoordTriplet[] getNeighborCoords() {
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
