package com.termux.app.ubuntu;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tests of the links of the shared storage.
 *
 * <p>The links are created in the home directory of the application, which is the home directory
 * of the guest, so that {@code ~/storage} reaches the files of the device from inside Ubuntu.
 */
public class UbuntuStorageTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    /** The links point to the shared storage and to its public directories. */
    @Test
    public void createsTheLinksOfTheSharedStorage() throws IOException {
        File storageDir = temporaryFolder.newFolder("home", "storage");
        File shared = temporaryFolder.newFolder("emulated", "0");
        new File(shared, "Download").mkdirs();

        int linked = UbuntuStorage.setupStorageSymlinks(storageDir, shared.getAbsolutePath());

        Assert.assertTrue("the links are created", linked >= 7);
        Assert.assertEquals("the shared storage is linked",
            shared.getAbsolutePath(), Files.readSymbolicLink(new File(storageDir, "shared").toPath()).toString());
        Assert.assertEquals("the downloads are linked",
            new File(shared, "Download").getAbsolutePath(),
            Files.readSymbolicLink(new File(storageDir, "downloads").toPath()).toString());
        Assert.assertTrue("the link is usable", new File(storageDir, "shared").isDirectory());
    }

    /** A second run does not destroy the links and does not fail. */
    @Test
    public void keepsTheExistingLinks() throws IOException {
        File storageDir = temporaryFolder.newFolder("home", "storage");
        File shared = temporaryFolder.newFolder("emulated", "0");

        UbuntuStorage.setupStorageSymlinks(storageDir, shared.getAbsolutePath());
        int linked = UbuntuStorage.setupStorageSymlinks(storageDir, shared.getAbsolutePath());

        Assert.assertTrue("the links are still usable", linked >= 7);
        Assert.assertEquals("the shared storage is still linked",
            shared.getAbsolutePath(), Files.readSymbolicLink(new File(storageDir, "shared").toPath()).toString());
    }

    /** A broken link is repaired. */
    @Test
    public void repairsABrokenLink() throws IOException {
        File storageDir = temporaryFolder.newFolder("home", "storage");
        File shared = temporaryFolder.newFolder("emulated", "0");
        File target = temporaryFolder.newFolder("target");
        File link = new File(storageDir, "shared");
        Files.createSymbolicLink(link.toPath(), target.toPath());

        UbuntuStorage.setupStorageSymlinks(storageDir, shared.getAbsolutePath());

        Assert.assertEquals("the broken link points to the shared storage again",
            shared.getAbsolutePath(), Files.readSymbolicLink(link.toPath()).toString());
    }

    /** The files of the user are never deleted, only the links are replaced. */
    @Test
    public void doesNotDeleteTheFilesOfTheUser() throws IOException {
        File storageDir = temporaryFolder.newFolder("home", "storage");
        File shared = temporaryFolder.newFolder("emulated", "0");
        File userFile = new File(storageDir, "notes.txt");
        Files.write(userFile.toPath(), "keep me".getBytes("UTF-8"));

        UbuntuStorage.setupStorageSymlinks(storageDir, shared.getAbsolutePath());

        Assert.assertTrue("the file of the user is still there", userFile.isFile());
        Assert.assertEquals("the content is unchanged", "keep me",
            new String(Files.readAllBytes(userFile.toPath()), "UTF-8"));
    }

    /** A link that points to another directory is replaced. */
    @Test
    public void replacesALinkThatPointsToAnotherDirectory() throws IOException {
        File storageDir = temporaryFolder.newFolder("home", "storage");
        File shared = temporaryFolder.newFolder("emulated", "0");
        File other = temporaryFolder.newFolder("other");
        File link = new File(storageDir, "shared");
        Files.createSymbolicLink(link.toPath(), other.toPath());

        UbuntuStorage.setupStorageSymlinks(storageDir, shared.getAbsolutePath());

        Assert.assertEquals("the link points to the shared storage",
            shared.getAbsolutePath(), Files.readSymbolicLink(link.toPath()).toString());
    }

    /** Without the access to the shared storage no link is created. */
    @Test
    public void createsNoLinkWithoutTheSharedStorage() throws IOException {
        File storageDir = temporaryFolder.newFolder("home", "storage");

        int linked = UbuntuStorage.setupStorageSymlinks(storageDir, null);

        Assert.assertEquals("no link is created", 0, linked);
        Assert.assertFalse("the directory stays empty",
            new File(storageDir, "shared").exists());
    }

    /** The directory of the links is created when it is missing. */
    @Test
    public void createsTheDirectoryOfTheLinks() throws IOException {
        File storageDir = new File(new File(temporaryFolder.getRoot(), "missing"), "storage");
        File shared = temporaryFolder.newFolder("emulated", "0");

        int linked = UbuntuStorage.setupStorageSymlinks(storageDir, shared.getAbsolutePath());

        Assert.assertTrue("the directory is created", storageDir.isDirectory());
        Assert.assertTrue("the links are created", linked >= 7);
    }

    /** A path that cannot be used is reported and does not stop the other links. */
    @Test
    public void reportsALinkThatCannotBeCreated() throws IOException {
        File storageDir = temporaryFolder.newFolder("home", "storage");
        File shared = temporaryFolder.newFolder("emulated", "0");
        new File(shared, "Download").mkdirs();
        // A regular file cannot be replaced by a link.
        Path blocked = new File(storageDir, "shared").toPath();
        Files.write(blocked, "not a link".getBytes("UTF-8"));

        UbuntuStorage.setupStorageSymlinks(storageDir, shared.getAbsolutePath());

        // The file was not deleted, the other links were created nevertheless.
        Assert.assertTrue("the file is still there", new File(storageDir, "shared").isFile());
        Assert.assertTrue("the other links are usable", new File(storageDir, "downloads").isDirectory());
    }
}
