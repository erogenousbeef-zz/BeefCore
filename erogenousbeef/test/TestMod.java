package erogenousbeef.test;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import cpw.mods.fml.client.registry.RenderingRegistry;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.Init;
import cpw.mods.fml.common.Mod.Instance;
import cpw.mods.fml.common.Mod.PreInit;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.registry.GameRegistry;
import erogenousbeef.test.client.RendererMultiblockTester;
import erogenousbeef.test.common.BlockMultiblockTester;
import erogenousbeef.test.common.ItemBlockMultiblockTester;
import erogenousbeef.test.common.TileEntityMultiblockTester;

@Mod(modid = TestMod.CHANNEL, name = TestMod.NAME, version=TestMod.VERSION, dependencies="", acceptedMinecraftVersions="[1.5.1,)")
public class TestMod {
	public static final String CHANNEL = "TestMod";
	public static final String NAME = "Beef's Testing Mod";
	public static final String VERSION = "1.0";

	public static final String RESOURCE_PATH = "/mods/testmod/";
	public static final String TEXTURE_DIRECTORY = RESOURCE_PATH;

	public static final CreativeTabs TAB = new CreativeTabTest(CreativeTabs.getNextID(), CHANNEL);
	public static final String TEXTURE_NAME_PREFIX = "testmod:";
	
	@Instance("TestMod")
	public static TestMod instance;
	
	@SidedProxy(clientSide="erogenousbeef.test.ClientProxy", serverSide= "erogenousbeef.test.CommonProxy")
	public static CommonProxy proxy;

	private static boolean INITIALIZED = false;

	public static Block blockMultiblockTester;
	
	@PreInit
	public void preInit(FMLPreInitializationEvent evt) {
		proxy.preInit();
		
		// Register blocks
		if(blockMultiblockTester == null) {
			blockMultiblockTester = new BlockMultiblockTester(4000, Material.iron);
			GameRegistry.registerBlock(blockMultiblockTester, ItemBlockMultiblockTester.class, "MultiblockTester");
		}
	}
	
	@Init
	public void load(FMLInitializationEvent evt) {
		proxy.init();
		register();
	}
	
	private void register() {
		if(!INITIALIZED) {
			// Register other stuff here
		}
		INITIALIZED = true;
	}
	
	public static void registerTileEntities() {
		GameRegistry.registerTileEntity(TileEntityMultiblockTester.class, "BeefTestMultiblockTester");
	}
}
