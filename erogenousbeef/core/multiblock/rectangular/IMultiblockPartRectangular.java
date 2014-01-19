package erogenousbeef.core.multiblock.rectangular;

import net.minecraftforge.common.ForgeDirection;
import erogenousbeef.core.multiblock.IMultiblockPart;
import erogenousbeef.core.multiblock.MultiblockValidationException;

public interface IMultiblockPartRectangular extends IMultiblockPart {

	// Positional data
	/**
	 * @return This part's position on the multiblock, if the multiblock is assembled. Returns UNKNOWN if the multiblock is disassembled.
	 */
	public PartPosition getPartPosition();

	/**
	 * @return The outwards direction for this part, if this part is on an external face. If not on a face, returns UNKNOWN.
	 */
	public ForgeDirection getOutwardsDir();
	
	// Multiblock Validation Helpers
	
	/**
	 * Ensures this block can be used as a piece of the machine's frame. (Outer edges)
	 * @throws A MultiblockValidationException indicating why this part was not OK for the frame.
	 */
	public void isGoodForFrame() throws MultiblockValidationException;
	
	/**
	 * Ensures this block can be used on the north, east, south or west faces of the machine,
	 * inside of the frame.
	 * @throws A MultiblockValidationException indicating why this part was not OK for the side faces.
	 */
	public void isGoodForSides() throws MultiblockValidationException;
	
	/**
	 * Ensures this block can be used on the top face of the machine, inside of the frame.
	 * @throws A MultiblockValidationException indicating why this part was not OK for the top face.
	 */
	public void isGoodForTop() throws MultiblockValidationException;
	
	/**
	 * Ensures this block can be used on the bottom face of the machine, inside of the frame.
	 * @throws A MultiblockValidationException indicating why this part was not OK for the bottom face.
	 */
	public void isGoodForBottom() throws MultiblockValidationException;
	
	/**
	 * Ensures this block can be used inside the machine.
	 * @throws A MultiblockValidationException indicating why this part was not OK for the interior.
	 */
	public void isGoodForInterior() throws MultiblockValidationException;
}
