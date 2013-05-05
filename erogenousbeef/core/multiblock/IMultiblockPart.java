package erogenousbeef.core.multiblock;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.world.World;
import erogenousbeef.core.common.CoordTriplet;

public interface IMultiblockPart {
	public static final int INVALID_DISTANCE = Integer.MAX_VALUE;
	
	/*
	 * Is this block connected to a multiblock controller?
	 * @return True if this block is connected to a multiblock controller. False otherwise.
	 */
	public boolean isConnected();
	
	/*
	 * Returns the attached multiblock controller for this tile entity.
	 * @return 
	 */
	public MultiblockControllerBase getMultiblockController();
	
	/*
	 * Returns the location of this tile entity in the world, in CoordTriplet form.
	 * @return A CoordTriplet with its x,y,z members set to the location of this tile entity in the world.
	 */
	public CoordTriplet getWorldLocation();
	
	// Multiblock connection-logic callbacks
	
	/*
	 * Called after this block has been attached to a new multiblock controller.
	 * @param newController The new multiblock controller to which this tile entity is attached.
	 */
	public void onAttached(MultiblockControllerBase newController);
	
	/*
	 * Called after this block has been detached from a multiblock controller.
	 * @param multiblockController The multiblock controller that no longer controls this tile entity.
	 */
	public void onDetached(MultiblockControllerBase multiblockController);
	
	// Multiblock fuse/split helper methods. Here there be dragons.
	/*
	 * Creates a new Multiblock Controller and attaches this tile entity to it.
	 * Generally, you should not override this method.
	 */
	public void createNewMultiblock();
	
	/*
	 * Factory method. Creates a new multiblock controller and returns it.
	 * Does not attach this tile entity to it.
	 * Override this in your game code!
	 * @return A new Multiblock Controller, derived from MultiblockControllerBase.
	 */
	public MultiblockControllerBase getNewMultiblockControllerObject();
	
	/*
	 * Called when this block is merged from its current controller into a new controller.
	 * A special case of attach/detach, done here for efficiency to avoid triggering
	 * lots of recalculation logic.
	 */
	public void onMergedIntoOtherMultiblock(MultiblockControllerBase newController);

	// Multiblock connection data access.
	// You generally shouldn't toy with these!
	// They're for use by Multiblock Controllers.
	
	/*
	 * Set the graph-traversal distance from the reference block to this block.
	 * @param newDistance The new current distance from the reference block to this block.
	 */
	public void setDistance(int newDistance);
	
	/*
	 * Get the graph-traversal distance from the reference block to this block.
	 * @return The graph-traversal (walk) distance from the reference block to this block.
	 */
	public int getDistanceFromReferenceCoord();
	
	/*
	 * Called when this block becomes the designated block for saving data and
	 * transmitting data across the wire.
	 */
	public void becomeMultiblockSaveDelegate();
	
	/*
	 * Called when this block is no longer the designated block for saving data
	 * and transmitting data across the wire.
	 */
	public void forfeitMultiblockSaveDelegate();
	
	/*
	 * Returns an array containing references to neighboring IMultiblockPart tile entities.
	 * Primarily a utility method. Only works after tileentity construction, so it cannot be used in
	 * MultiblockControllerBase::attachBlock
	 * @return An array of references to neighboring IMultiblockPart tile entities.
	 */
	public IMultiblockPart[] getNeighboringParts();

	// Multiblock data communication
	// Most of the time you can ignore this.
	/*
	 * Dispatches a packet across the wire and marks this block for a world update.
	 * The default implementation simply calls getDescriptionPacket().
	 * Override this if you wish to do fancy updating logic.
	 */
	public void sendUpdatePacket();
	
	// Multiblock business-logic callbacks - implement these!
	/*
	 * Called when a machine is fully assembled from a broken state. Use this to set metadata
	 * if you want to change your blocks' appearance, as well as initialize any specialized
	 * logic in your tile entities.
	 * Note that, for non-square machines, the min/max coordinates may not actually be part
	 * of the machine! They form an outer bounding box for the whole machine itself.
	 * @param machineMinCoords The minimum x,y,z coordinates present in the machine.
	 * @param machineMaxCoords the maximum x,y,z coordinates present in the machine.
	 */
	public void onMachineAssembled(CoordTriplet machineMinCoords, CoordTriplet machineMaxCoords);
	
	/*
	 * Called when the machine is broken, generally due to the removal of a block.
	 */
	public void onMachineBroken();
	
	/*
	 * Called when the user activates the machine. This is not called by default, but is included
	 * as most machines have this game-logical concept.
	 */
	public void onMachineActivated();

	/*
	 * Called when the user deactivates the machine. This is not called by default, but is included
	 * as most machines have this game-logical concept.
	 */
	public void onMachineDeactivated();

	// Multiblock Validation Helpers
	
	/*
	 * @return True if this block can be used as a piece of the machine's frame. (Outer edges)
	 */
	public boolean isGoodForFrame();
	
	/*
	 * @return True if this block can be used on the north, east, south or west faces of the machine,
	 * inside of the frame.
	 */
	public boolean isGoodForSides();
	
	/*
	 * @return True if this block can be used on the top face of the machine, inside of the frame.
	 */
	public boolean isGoodForTop();
	
	/*
	 * @return True if this block can be used on the bottom face of the machine, inside of the frame.
	 */
	public boolean isGoodForBottom();
	
	/*
	 * @return True if this block can be used inside the machine; not on any faces or the frame.
	 */
	public boolean isGoodForInterior();

	// Block events
	/*
	 * Should be called by the block when it receives an onBlockAdded event to an IMultiblockPart tile entity. 
	 */
	public void onBlockAdded(World world, int x, int y, int z);
}
