package erogenousbeef.core.multiblock;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/*
 * This is a very simple static singleton registry class, used to send ticks to active multiblocks.
 * Register when your multiblock is created and unregister it when it loses its last connected block.
 */
public class MultiblockRegistry {
	private static List<MultiblockControllerBase> controllers = new LinkedList<MultiblockControllerBase>();
	
	public static void tick() {
		for(MultiblockControllerBase reactor : controllers) {
			reactor.updateMultiblockEntity();
		}
	}
	
	public static void register(MultiblockControllerBase reactor) {
		if(!controllers.contains(reactor)) {
			controllers.add(reactor);
		}
		else {
			// TODO: Log a warning
		}
	}
	
	public static void unregister(MultiblockControllerBase reactor) {
		if(controllers.contains(reactor)) {
			controllers.remove(reactor);
		}
		else {
			// TODO: Log a warning
		}
	}	
}
