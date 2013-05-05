package erogenousbeef.core.multiblock;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

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
