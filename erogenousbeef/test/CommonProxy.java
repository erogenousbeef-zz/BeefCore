package erogenousbeef.test;

import cpw.mods.fml.common.registry.TickRegistry;
import cpw.mods.fml.relauncher.Side;
import erogenousbeef.core.multiblock.MultiblockTickHandler;

public class CommonProxy {

	public void preInit() {
		
	}
	
	public void init() {
		TestMod.registerTileEntities();
		TickRegistry.registerTickHandler(new MultiblockTickHandler(), Side.SERVER);
	}
	
}
