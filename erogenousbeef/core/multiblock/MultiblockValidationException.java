package erogenousbeef.core.multiblock;

/**
 * An exception thrown when trying to validate a multiblock. Requires a string describing why the multiblock
 * could not assemble.
 * @author Erogenous Beef
 */
public class MultiblockValidationException extends Exception {

	public MultiblockValidationException(String reason) {
		super(reason);
	}

	public MultiblockValidationException(String reason, Throwable arg1) {
		super(reason, arg1);
	}

	public MultiblockValidationException(String reason, Throwable arg1,
			boolean arg2, boolean arg3) {
		super(reason, arg1, arg2, arg3);
	}
}
