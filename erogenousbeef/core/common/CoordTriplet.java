package erogenousbeef.core.common;

import net.minecraftforge.common.ForgeDirection;

public class CoordTriplet implements Comparable {
	public int x, y, z;
	public CoordTriplet(int x, int y, int z) {
		this.x = x;
		this.y = y;
		this.z = z;
	}
	
	public boolean equals(Object other) {
		if(other == null)
		{ return false; }
		else if(other instanceof CoordTriplet) {
			CoordTriplet otherTriplet = (CoordTriplet)other;
			return this.x == otherTriplet.x && this.y == otherTriplet.y && this.z == otherTriplet.z;
		}
		else {
			return false;
		}
	}
	
	public boolean equals(int x, int y, int z) {
		return this.x == x && this.y == y && this.z == z;
	}
	
	// Suggested implementation from NetBeans 7.1
	public int hashCode() {
		int hash = 7;
		hash = 71 * hash + this.x;
		hash = 71 * hash + this.y;
		hash = 71 * hash + this.z;
		return hash;
	}
	
	public ForgeDirection getDirectionFromSourceCoords(int x, int y, int z) {
		if(this.x < x) { return ForgeDirection.WEST; }
		else if(this.x > x) { return ForgeDirection.EAST; }
		else if(this.y < y) { return ForgeDirection.DOWN; }
		else if(this.y > y) { return ForgeDirection.UP; }
		else if(this.z < z) { return ForgeDirection.SOUTH; }
		else if(this.z > z) { return ForgeDirection.NORTH; }
		else { return ForgeDirection.UNKNOWN; }
	}
	
	public ForgeDirection getOppositeDirectionFromSourceCoords(int x, int y, int z) {
		if(this.x < x) { return ForgeDirection.EAST; }
		else if(this.x > x) { return ForgeDirection.WEST; }
		else if(this.y < y) { return ForgeDirection.UP; }
		else if(this.y > y) { return ForgeDirection.DOWN; }
		else if(this.z < z) { return ForgeDirection.NORTH; }
		else if(this.z > z) { return ForgeDirection.SOUTH; }
		else { return ForgeDirection.UNKNOWN; }
		
	}

	public CoordTriplet copy() {
		return new CoordTriplet(x, y, z);
	}

	@Override
	public int compareTo(Object o) {
		if(o instanceof CoordTriplet) {
			CoordTriplet other = (CoordTriplet)o;
			if(this.x < other.x) { return -1; }
			else if(this.x > other.x) { return 1; }
			else if(this.y < other.y) { return -1; }
			else if(this.y > other.y) { return 1; }
			else if(this.z < other.z) { return -1; }
			else if(this.z > other.z) { return 1; }
			else { return 0; }
		}
		return 0;
	}
}
