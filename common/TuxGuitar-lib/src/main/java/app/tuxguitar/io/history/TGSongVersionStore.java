package app.tuxguitar.io.history;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;
import java.util.TreeMap;

import app.tuxguitar.io.base.TGFileFormatUtils;

/**
 * Numbered full-file snapshots of a song file, kept in a hidden sidecar directory
 * next to the song itself.
 *
 * <p>For {@code /path/mysong.tg} the sidecar is {@code /path/.mysong.tg.versions}
 * holding {@code manifest.properties} plus one {@code NNNN.tg} per previous save.
 * A snapshot numbered {@code n} is the content the song file had right before it
 * was overwritten for the {@code n}-th time, so after a save the live file is
 * version {@code currentVersion} and snapshots {@code 1..currentVersion-1} are
 * the earlier saves.</p>
 *
 * <p>Reads never write and never fail on a missing or corrupt manifest: the version
 * list and the current version are then derived from the snapshot files present.
 * The manifest is normalized and rewritten by {@link #snapshotBeforeOverwrite(File)},
 * which is the only method that writes.</p>
 *
 * <p>This class is plain {@code java.io}/{@code java.nio}: no UI, no {@code TGContext},
 * no {@code java.time} (the Android build targets API 24 without desugaring).</p>
 */
public class TGSongVersionStore {

	public static final String VERSIONS_DIR_PREFIX = ".";

	public static final String VERSIONS_DIR_SUFFIX = ".versions";

	public static final String MANIFEST_FILE_NAME = "manifest.properties";

	public static final String CURRENT_VERSION_KEY = "currentVersion";

	private static final String MANIFEST_TEMP_FILE_NAME = MANIFEST_FILE_NAME + ".tmp";

	private static final String MANIFEST_COMMENT = "TuxGuitar song version history";

	private static final String VERSION_KEY_PREFIX = "v.";

	private static final String VERSION_NUMBER_FORMAT = "%04d";

	private static final String TIMESTAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss";

	/**
	 * The hidden sibling directory that holds the history of the given song file.
	 */
	public static File versionsDir(File songFile) {
		File parent = songFile.getAbsoluteFile().getParentFile();
		return new File(parent, VERSIONS_DIR_PREFIX + songFile.getName() + VERSIONS_DIR_SUFFIX);
	}

	/**
	 * The file holding the given previous state of the song.
	 */
	public static File snapshotFile(File songFile, int version) {
		return new File(versionsDir(songFile), formatVersionName(version) + TGFileFormatUtils.DEFAULT_EXTENSION);
	}

	/**
	 * Version number the next save of the given song will produce. Returns 1 when the
	 * song has never been snapshotted.
	 */
	public static int findCurrentVersion(File songFile) {
		return load(versionsDir(songFile)).currentVersion;
	}

	/**
	 * Previous saves of the given song, ordered by ascending version number. Entries
	 * whose snapshot file is missing are skipped, so every returned version can be
	 * restored.
	 */
	public static List<TGSongVersion> listVersions(File songFile) {
		List<TGSongVersion> versions = new ArrayList<TGSongVersion>();
		for (Map.Entry<Integer, String> entry : load(versionsDir(songFile)).versions.entrySet()) {
			if( snapshotFile(songFile, entry.getKey()).isFile() ) {
				versions.add(new TGSongVersion(entry.getKey(), entry.getValue()));
			}
		}
		Collections.sort(versions);
		return versions;
	}

	/**
	 * Copies the given song file into the sidecar as its next previous version, before
	 * the caller overwrites it. Does nothing when the song file does not exist yet,
	 * which is the case of a first save.
	 *
	 * @return the version number the following save will produce
	 * @throws IOException if the snapshot or the manifest could not be written, in which
	 *                     case the caller must not proceed to overwrite the song file
	 */
	public static int snapshotBeforeOverwrite(File songFile) throws IOException {
		if( !songFile.isFile() ) {
			return findCurrentVersion(songFile);
		}

		File versionsDir = versionsDir(songFile);
		Files.createDirectories(versionsDir.toPath());

		Manifest manifest = load(versionsDir);
		// guard against a manifest that lags behind the snapshots actually on disk
		int version = Math.max(manifest.currentVersion, findMaxSnapshotNumber(versionsDir) + 1);
		Files.copy(songFile.toPath(), snapshotFile(songFile, version).toPath(), StandardCopyOption.REPLACE_EXISTING);

		manifest.currentVersion = version + 1;
		manifest.versions.put(Integer.valueOf(version), now());
		save(versionsDir, manifest);

		return manifest.currentVersion;
	}

	/**
	 * Replaces the given song file with one of its previous versions. Later snapshots
	 * are left untouched: history stays append-only.
	 */
	public static void restore(File songFile, int version) throws IOException {
		File snapshot = snapshotFile(songFile, version);
		if( !snapshot.isFile() ) {
			throw new IOException("Missing song version snapshot: " + snapshot.getAbsolutePath());
		}
		Files.copy(snapshot.toPath(), songFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
	}

	private static String formatVersionName(int version) {
		return String.format(VERSION_NUMBER_FORMAT, Integer.valueOf(version));
	}

	private static int findMaxSnapshotNumber(File versionsDir) {
		int max = 0;
		for (File snapshot : findSnapshotFiles(versionsDir)) {
			int number = findSnapshotNumber(snapshot);
			if( number > max ) {
				max = number;
			}
		}
		return max;
	}

	private static List<File> findSnapshotFiles(File versionsDir) {
		List<File> snapshots = new ArrayList<File>();
		File[] files = versionsDir.listFiles();
		if( files == null ) {
			return snapshots;
		}
		String extension = TGFileFormatUtils.DEFAULT_EXTENSION;
		for (File file : files) {
			if( file.isFile() && file.getName().endsWith(extension) ) {
				snapshots.add(file);
			}
		}
		return snapshots;
	}

	private static int findSnapshotNumber(File snapshot) {
		String name = snapshot.getName();
		String extension = TGFileFormatUtils.DEFAULT_EXTENSION;
		try {
			return Integer.parseInt(name.substring(0, name.length() - extension.length()));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static Manifest load(File versionsDir) {
		Manifest manifest = readManifest(versionsDir);
		if( manifest == null ) {
			manifest = deriveFromSnapshots(versionsDir);
		}
		return manifest;
	}

	private static Manifest readManifest(File versionsDir) {
		File file = new File(versionsDir, MANIFEST_FILE_NAME);
		if( !file.isFile() ) {
			return null;
		}
		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
			properties.load(reader);
		} catch (IOException e) {
			return null;
		} catch (IllegalArgumentException e) {
			// malformed unicode escape
			return null;
		}

		Manifest manifest = new Manifest();
		try {
			manifest.currentVersion = Integer.parseInt(properties.getProperty(CURRENT_VERSION_KEY, "1").trim());
			if( manifest.currentVersion < 1 ) {
				return null;
			}
		} catch (NumberFormatException e) {
			return null;
		}

		for (String key : properties.stringPropertyNames()) {
			if( key.startsWith(VERSION_KEY_PREFIX) ) {
				try {
					int number = Integer.parseInt(key.substring(VERSION_KEY_PREFIX.length()).trim());
					if( number >= 1 ) {
						manifest.versions.put(Integer.valueOf(number), properties.getProperty(key));
					}
				} catch (NumberFormatException e) {
					// ignore malformed entry
				}
			}
		}
		return manifest;
	}

	private static Manifest deriveFromSnapshots(File versionsDir) {
		Manifest manifest = new Manifest();
		for (File snapshot : findSnapshotFiles(versionsDir)) {
			int number = findSnapshotNumber(snapshot);
			if( number > 0 ) {
				manifest.versions.put(Integer.valueOf(number), formatTimestamp(snapshot.lastModified()));
			}
		}
		manifest.currentVersion = findMaxSnapshotNumber(versionsDir) + 1;
		return manifest;
	}

	private static void save(File versionsDir, Manifest manifest) throws IOException {
		Path target = new File(versionsDir, MANIFEST_FILE_NAME).toPath();
		Path temporary = new File(versionsDir, MANIFEST_TEMP_FILE_NAME).toPath();

		Properties properties = new Properties();
		properties.setProperty(CURRENT_VERSION_KEY, Integer.toString(manifest.currentVersion));
		for (Map.Entry<Integer, String> entry : manifest.versions.entrySet()) {
			properties.setProperty(VERSION_KEY_PREFIX + entry.getKey(), entry.getValue());
		}
		try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
			properties.store(writer, MANIFEST_COMMENT);
		}

		try {
			Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static String now() {
		return formatTimestamp(System.currentTimeMillis());
	}

	private static String formatTimestamp(long time) {
		SimpleDateFormat format = new SimpleDateFormat(TIMESTAMP_PATTERN);
		format.setTimeZone(TimeZone.getTimeZone("UTC"));
		return format.format(new Date(time));
	}

	private static class Manifest {

		int currentVersion = 1;

		final TreeMap<Integer, String> versions = new TreeMap<Integer, String>();
	}
}
