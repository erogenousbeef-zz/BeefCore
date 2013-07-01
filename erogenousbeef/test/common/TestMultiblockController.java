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
		return 26;
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
		System.out.println(String.format("Machine %d PAUSED", hashCode()));
	}
	
	@Override
	protected void onMachineAssembled() {
		System.out.println(String.format("Machine %d ASSEMBLED", hashCode()));
	}
	
	@Override
	protected void onMachineDisassembled() {
		System.out.println(String.format("Machine %d DISASSEMBLED", hashCode()));
	}

	@Override
	protected void onMachineRestored() {
		System.out.println(String.format("Machine %d RESTORED", hashCode()));
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
	
	@Override
	protected boolean isBlockGoodForInterior(World world, int x, int y, int z) {
		return true;
	}
	
}
