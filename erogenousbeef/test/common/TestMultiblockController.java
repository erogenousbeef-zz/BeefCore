package erogenousbeef.test.common;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import erogenousbeef.core.multiblock.IMultiblockPart;
import erogenousbeef.core.multiblock.MultiblockControllerBase;

public class TestMultiblockController extends MultiblockControllerBase {

	protected static int nextOrdinal = 0;
	public int ordinal;
	
	public TestMultiblockController(World world) {
		super(world);
		if(world.isRemote) {
			ordinal = -1;
		}
		else {
			ordinal = nextOrdinal++;
		}
	}

	@Override
	protected void onBlockAdded(IMultiblockPart newPart) {
	}

	@Override
	protected void onBlockRemoved(IMultiblockPart oldPart) {
	}

	@Override
	protected int getMinimumNumberOfBlocksForAssembledMachine() {
		return 0;
	}

	@Override
	protected void onMachineMerge(MultiblockControllerBase otherMachine) {
	}

	@Override
	public void detachBlock(IMultiblockPart part, boolean chunkUnloading) {
		System.out.println("Controller " + Integer.toString(ordinal) + (worldObj.isRemote ? " (client)" : " (server)") + " detaching block at " + part.getWorldLocation().toString());
		super.detachBlock(part, chunkUnloading);
	}
	
	@Override
	public void writeToNBT(NBTTagCompound data) {
	}

	@Override
	public void readFromNBT(NBTTagCompound data) {
	}

	@Override
	public void formatDescriptionPacket(NBTTagCompound data) {
		data.setInteger("ordinal", ordinal);
	}

	@Override
	public void decodeDescriptionPacket(NBTTagCompound data) {
		if(data.hasKey("ordinal")) {
			ordinal = data.getInteger("ordinal");
		}
	}
	
	@Override
	protected void onMachinePaused() {
		System.out.println("Machine PAUSED");
	}
	
	@Override
	protected void onMachineAssembled() {
		System.out.println("Machine ASSEMBLED");
	}
	
	@Override
	protected void onMachineDisassembled() {
		System.out.println("Machine DISASSEMBLED");
	}

	@Override
	protected void onMachineRestored() {
		System.out.println("Machine RESTORED");
	}

	@Override
	protected boolean update() {
		return false;
	}

	@Override
	protected int getMaximumXSize() {
		return 16;
	}

	@Override
	protected int getMaximumZSize() {
		return 16;
	}

	@Override
	protected int getMaximumYSize() {
		return 16;
	}
	
}
