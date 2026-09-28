package app.tuxguitar.io.history;

/**
 * A single previous state of a song file, as stored in the sidecar history.
 *
 * @see TGSongVersionStore
 */
public class TGSongVersion implements Comparable<TGSongVersion> {

	private int number;
	private String timestamp;

	public TGSongVersion(int number, String timestamp) {
		this.number = number;
		this.timestamp = timestamp;
	}

	public int getNumber() {
		return this.number;
	}

	public String getTimestamp() {
		return this.timestamp;
	}

	public int compareTo(TGSongVersion version) {
		if( version == null ) {
			return 1;
		}
		return (this.number == version.getNumber() ? 0 : (this.number > version.getNumber() ? 1 : -1));
	}

	@Override
	public String toString() {
		return ("#" + this.number + " (" + this.timestamp + ")");
	}
}
