package app.tuxguitar.io.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;

public class TestTGSongVersionStore {

	// This module builds with surefire's JUnit3/POJO provider, which does not run the
	// JUnit 5 lifecycle. Each test therefore creates and removes its own directory.

	private static File createTempDir() {
		try {
			return Files.createTempDirectory("tuxguitar-song-version-store").toFile();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static void deleteDirectory(File directory) {
		File[] files = directory.listFiles();
		if( files != null ) {
			for (File file : files) {
				if( file.isDirectory() ) {
					deleteDirectory(file);
				} else {
					file.delete();
				}
			}
		}
		directory.delete();
	}

	private static File createSongFile(File dir, String name, String content) throws IOException {
		File songFile = new File(dir, name);
		writeContent(songFile, content);
		return songFile;
	}

	private static void writeContent(File file, String content) throws IOException {
		Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
	}

	private static String readContent(File file) throws IOException {
		return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
	}

	@Test
	public void testVersionsDirIsHiddenSibling() throws IOException {
		File tempDir = createTempDir();
		try {
			File songFile = createSongFile(tempDir, "mysong.tg", "first");

			File versionsDir = TGSongVersionStore.versionsDir(songFile);

			assertEquals(new File(tempDir, ".mysong.tg.versions"), versionsDir);
			assertEquals(tempDir, versionsDir.getParentFile());
			assertTrue(versionsDir.getName().startsWith("."), "the sidecar name must be dot-prefixed");
		} finally {
			deleteDirectory(tempDir);
		}
	}

	@Test
	public void testSnapshotFileNaming() throws IOException {
		File tempDir = createTempDir();
		try {
			File songFile = createSongFile(tempDir, "mysong.tg", "first");
			File versionsDir = TGSongVersionStore.versionsDir(songFile);

			assertEquals(new File(versionsDir, "0001.tg"), TGSongVersionStore.snapshotFile(songFile, 1));
			assertEquals(new File(versionsDir, "0012.tg"), TGSongVersionStore.snapshotFile(songFile, 12));
			assertEquals(new File(versionsDir, "12345.tg"), TGSongVersionStore.snapshotFile(songFile, 12345));
		} finally {
			deleteDirectory(tempDir);
		}
	}

	@Test
	public void testFirstSaveTakesNoSnapshot() throws IOException {
		File tempDir = createTempDir();
		try {
			// a first save creates the song file, there is nothing to snapshot yet
			File songFile = new File(tempDir, "new.tg");
			assertFalse(songFile.exists());
			assertEquals(1, TGSongVersionStore.findCurrentVersion(songFile));

			int currentVersion = TGSongVersionStore.snapshotBeforeOverwrite(songFile);

			assertEquals(1, currentVersion);
			assertFalse(TGSongVersionStore.versionsDir(songFile).exists(), "no sidecar must be created for a first save");
			assertTrue(TGSongVersionStore.listVersions(songFile).isEmpty());
		} finally {
			deleteDirectory(tempDir);
		}
	}

	@Test
	public void testOverwriteSnapshotsPreviousContent() throws IOException {
		File tempDir = createTempDir();
		try {
			File songFile = createSongFile(tempDir, "mysong.tg", "first");

			int currentVersion = TGSongVersionStore.snapshotBeforeOverwrite(songFile);
			writeContent(songFile, "second");

			assertEquals(2, currentVersion);
			assertEquals("first", readContent(TGSongVersionStore.snapshotFile(songFile, 1)));
			assertEquals("second", readContent(songFile));

			List<TGSongVersion> versions = TGSongVersionStore.listVersions(songFile);
			assertEquals(1, versions.size());
			assertEquals(1, versions.get(0).getNumber());
			assertFalse(versions.get(0).getTimestamp().isEmpty());
		} finally {
			deleteDirectory(tempDir);
		}
	}

	@Test
	public void testConsecutiveSavesAreNumbered() throws IOException {
		File tempDir = createTempDir();
		try {
			File songFile = createSongFile(tempDir, "v1.tg", "v1");

			for (int i = 2; i <= 4; i++) {
				TGSongVersionStore.snapshotBeforeOverwrite(songFile);
				writeContent(songFile, "v" + i);
			}

			assertEquals(4, TGSongVersionStore.findCurrentVersion(songFile));
			assertEquals("v1", readContent(TGSongVersionStore.snapshotFile(songFile, 1)));
			assertEquals("v2", readContent(TGSongVersionStore.snapshotFile(songFile, 2)));
			assertEquals("v3", readContent(TGSongVersionStore.snapshotFile(songFile, 3)));
			assertFalse(TGSongVersionStore.snapshotFile(songFile, 4).exists(), "the current version is not a snapshot");

			List<TGSongVersion> versions = TGSongVersionStore.listVersions(songFile);
			assertEquals(3, versions.size());
			assertEquals(1, versions.get(0).getNumber());
			assertEquals(2, versions.get(1).getNumber());
			assertEquals(3, versions.get(2).getNumber());
		} finally {
			deleteDirectory(tempDir);
		}
	}

	@Test
	public void testVersionNumberPaddingGrows() throws IOException {
		File tempDir = createTempDir();
		try {
			File songFile = createSongFile(tempDir, "many.tg", "content-0");

			for (int i = 1; i <= 12; i++) {
				TGSongVersionStore.snapshotBeforeOverwrite(songFile);
				writeContent(songFile, "content-" + i);
			}

			assertEquals(13, TGSongVersionStore.findCurrentVersion(songFile));
			assertEquals("content-0", readContent(TGSongVersionStore.snapshotFile(songFile, 1)));
			assertTrue(new File(TGSongVersionStore.versionsDir(songFile), "0010.tg").isFile());
			assertTrue(new File(TGSongVersionStore.versionsDir(songFile), "0012.tg").isFile());
		} finally {
			deleteDirectory(tempDir);
		}
	}

	@Test
	public void testRestoreIsByteExact() throws IOException {
		File tempDir = createTempDir();
		try {
			File songFile = createSongFile(tempDir, "restore.tg", "original content");
			String original = readContent(songFile);

			TGSongVersionStore.snapshotBeforeOverwrite(songFile);
			writeContent(songFile, "much later content");

			TGSongVersionStore.restore(songFile, 1);

			assertEquals(original, readContent(songFile));
		} finally {
			deleteDirectory(tempDir);
		}
	}

	@Test
	public void testRestoreKeepsLaterSnapshots() throws IOException {
		File tempDir = createTempDir();
		try {
			File songFile = createSongFile(tempDir, "keep.tg", "first");
			TGSongVersionStore.snapshotBeforeOverwrite(songFile);
			writeContent(songFile, "second");
			TGSongVersionStore.snapshotBeforeOverwrite(songFile);
			writeContent(songFile, "third");

			TGSongVersionStore.restore(songFile, 1);

			assertEquals("first", readContent(songFile));
			assertEquals("second", readContent(TGSongVersionStore.snapshotFile(songFile, 2)));
			assertEquals(2, TGSongVersionStore.listVersions(songFile).size());
		} finally {
			deleteDirectory(tempDir);
		}
	}

	@Test
	public void testRestoreOfMissingVersionFails() throws IOException {
		File tempDir = createTempDir();
		try {
			File songFile = createSongFile(tempDir, "missing.tg", "first");

			assertThrows(IOException.class, () -> TGSongVersionStore.restore(songFile, 1));
		} finally {
			deleteDirectory(tempDir);
		}
	}

	@Test
	public void testMissingManifestIsDerivedFromSnapshots() throws IOException {
		File tempDir = createTempDir();
		try {
			File songFile = createSongFile(tempDir, "healed.tg", "first");
			TGSongVersionStore.snapshotBeforeOverwrite(songFile);
			writeContent(songFile, "second");
			TGSongVersionStore.snapshotBeforeOverwrite(songFile);
			writeContent(songFile, "third");

			File manifest = new File(TGSongVersionStore.versionsDir(songFile), "manifest.properties");
			assertTrue(manifest.isFile());
			assertTrue(manifest.delete());

			assertEquals(3, TGSongVersionStore.findCurrentVersion(songFile));
			List<TGSongVersion> versions = TGSongVersionStore.listVersions(songFile);
			assertEquals(2, versions.size());
			assertEquals(1, versions.get(0).getNumber());
			assertEquals(2, versions.get(1).getNumber());
		} finally {
			deleteDirectory(tempDir);
		}
	}

	@Test
	public void testManifestIsRewrittenAfterSelfHeal() throws IOException {
		File tempDir = createTempDir();
		try {
			File songFile = createSongFile(tempDir, "repaired.tg", "first");
			TGSongVersionStore.snapshotBeforeOverwrite(songFile);
			writeContent(songFile, "second");

			File manifest = new File(TGSongVersionStore.versionsDir(songFile), "manifest.properties");
			assertTrue(manifest.delete());

			TGSongVersionStore.snapshotBeforeOverwrite(songFile);

			assertEquals(3, TGSongVersionStore.findCurrentVersion(songFile));
			assertTrue(manifest.isFile(), "the manifest must be recreated on the next save");
			assertEquals("second", readContent(TGSongVersionStore.snapshotFile(songFile, 2)));
		} finally {
			deleteDirectory(tempDir);
		}
	}

	@Test
	public void testCorruptManifestIsIgnored() throws IOException {
		File tempDir = createTempDir();
		try {
			File songFile = createSongFile(tempDir, "corrupt.tg", "first");
			TGSongVersionStore.snapshotBeforeOverwrite(songFile);
			writeContent(songFile, "second");

			File manifest = new File(TGSongVersionStore.versionsDir(songFile), "manifest.properties");
			writeContent(manifest, "currentVersion=not-a-number\n");

			assertEquals(2, TGSongVersionStore.findCurrentVersion(songFile));
			assertEquals(1, TGSongVersionStore.listVersions(songFile).size());
		} finally {
			deleteDirectory(tempDir);
		}
	}

	@Test
	public void testMalformedUnicodeManifestIsIgnored() throws IOException {
		File tempDir = createTempDir();
		try {
			File songFile = createSongFile(tempDir, "unicode.tg", "first");
			TGSongVersionStore.snapshotBeforeOverwrite(songFile);
			writeContent(songFile, "second");

			// Properties.load throws IllegalArgumentException for a bad unicode escape
			File manifest = new File(TGSongVersionStore.versionsDir(songFile), "manifest.properties");
			writeContent(manifest, "currentVersion=2\nv.1=\\u00zz\n");

			assertEquals(2, TGSongVersionStore.findCurrentVersion(songFile));
			assertEquals(1, TGSongVersionStore.listVersions(songFile).size());
		} finally {
			deleteDirectory(tempDir);
		}
	}

	@Test
	public void testStaleManifestDoesNotOverwriteSnapshot() throws IOException {
		File tempDir = createTempDir();
		try {
			File songFile = createSongFile(tempDir, "stale.tg", "first");
			TGSongVersionStore.snapshotBeforeOverwrite(songFile);
			writeContent(songFile, "second");
			TGSongVersionStore.snapshotBeforeOverwrite(songFile);
			writeContent(songFile, "third");

			// a manifest claiming an older current version must not clobber a snapshot
			File manifest = new File(TGSongVersionStore.versionsDir(songFile), "manifest.properties");
			writeContent(manifest, "currentVersion=1\n");

			int currentVersion = TGSongVersionStore.snapshotBeforeOverwrite(songFile);

			assertEquals(4, currentVersion);
			assertEquals("third", readContent(TGSongVersionStore.snapshotFile(songFile, 3)));
			assertEquals("second", readContent(TGSongVersionStore.snapshotFile(songFile, 2)));
		} finally {
			deleteDirectory(tempDir);
		}
	}

	@Test
	public void testMissingSnapshotIsNotListed() throws IOException {
		File tempDir = createTempDir();
		try {
			File songFile = createSongFile(tempDir, "partial.tg", "first");
			TGSongVersionStore.snapshotBeforeOverwrite(songFile);
			writeContent(songFile, "second");
			TGSongVersionStore.snapshotBeforeOverwrite(songFile);
			writeContent(songFile, "third");

			assertTrue(TGSongVersionStore.snapshotFile(songFile, 1).delete());

			List<TGSongVersion> versions = TGSongVersionStore.listVersions(songFile);
			assertEquals(1, versions.size());
			assertEquals(2, versions.get(0).getNumber());
		} finally {
			deleteDirectory(tempDir);
		}
	}

	@Test
	public void testVersionCompare() {
		assertEquals(0, new TGSongVersion(1, "a").compareTo(new TGSongVersion(1, "b")));
		assertTrue(new TGSongVersion(2, "a").compareTo(new TGSongVersion(1, "a")) > 0);
		assertTrue(new TGSongVersion(1, "a").compareTo(new TGSongVersion(2, "a")) < 0);
	}
}
