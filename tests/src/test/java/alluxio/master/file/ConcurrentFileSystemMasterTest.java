/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.master.file;

import alluxio.AlluxioURI;
import alluxio.AuthenticatedUserRule;
import alluxio.Constants;
import alluxio.LocalAlluxioClusterResource;
import alluxio.PropertyKey;
import alluxio.client.WriteType;
import alluxio.client.file.FileSystem;
import alluxio.client.file.URIStatus;
import alluxio.client.file.options.CreateDirectoryOptions;
import alluxio.client.file.options.CreateFileOptions;
import alluxio.client.file.options.ListStatusOptions;
import alluxio.collections.ConcurrentHashSet;
import alluxio.exception.FileDoesNotExistException;
import alluxio.exception.InvalidPathException;
import alluxio.master.file.meta.PersistenceState;
import alluxio.security.authentication.AuthenticatedClientUser;
import alluxio.underfs.UnderFileSystemRegistry;
import alluxio.underfs.sleepfs.SleepingUnderFileSystemFactory;
import alluxio.underfs.sleepfs.SleepingUnderFileSystemOptions;
import alluxio.util.CommonUtils;
import alluxio.wire.LoadMetadataType;

import com.google.common.base.Throwables;
import com.google.common.io.Files;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tests to validate the concurrency in {@link FileSystemMaster}. These tests all use a local
 * path as the under storage system.
 *
 * The tests validate the correctness of concurrent operations, ie. no corrupted/partial state is
 * exposed, through a series of concurrent operations followed by verification of the final
 * state, or inspection of the in-progress state as the operations are carried out.
 *
 * The tests also validate that operations are concurrent by injecting a short sleep in the
 * critical code path. Tests will timeout if the critical section is performed serially.
 */
public class ConcurrentFileSystemMasterTest {
  private static final String TEST_USER = "test";
  private static final int CONCURRENCY_FACTOR = 50;
  /** Duration to sleep during the rename call to show the benefits of concurrency. */
  private static final long SLEEP_MS = Constants.SECOND_MS;
  /** Timeout for the concurrent test after which we will mark the test as failed. */
  private static final long LIMIT_MS = SLEEP_MS * CONCURRENCY_FACTOR / 10;
  /**
   * Options to mark a created file as persisted. Note that this does not actually persist the
   * file but flag the file to be treated as persisted, which will invoke ufs operations.
   */
  private static CreateFileOptions sCreatePersistedFileOptions =
      CreateFileOptions.defaults().setWriteType(WriteType.THROUGH);
  private static CreateDirectoryOptions sCreatePersistedDirOptions =
      CreateDirectoryOptions.defaults().setWriteType(WriteType.THROUGH);
  private static CreateDirectoryOptions sCreateDirectoryOptions =
      CreateDirectoryOptions.defaults().setRecursive(true);

  private static SleepingUnderFileSystemFactory sSleepingUfsFactory;

  private FileSystem mFileSystem;

  private String mLocalUfsPath = Files.createTempDir().getAbsolutePath();

  private enum UnaryOperation {
    CREATE,
    DELETE,
    GET_FILE_INFO,
    LIST_STATUS
  }

  @Rule
  public AuthenticatedUserRule mAuthenticatedUser = new AuthenticatedUserRule(TEST_USER);

  @Rule
  public LocalAlluxioClusterResource mLocalAlluxioClusterResource =
      new LocalAlluxioClusterResource.Builder().setProperty(PropertyKey.UNDERFS_ADDRESS,
          "sleep://" + mLocalUfsPath).setProperty(PropertyKey
          .USER_FILE_MASTER_CLIENT_THREADS, CONCURRENCY_FACTOR).build();

  // Must be done in beforeClass so execution is before rules
  @BeforeClass
  public static void beforeClass() throws Exception {
    // Register sleeping ufs with slow rename
    SleepingUnderFileSystemOptions options = new SleepingUnderFileSystemOptions();
    sSleepingUfsFactory = new SleepingUnderFileSystemFactory(options);
    options.setRenameFileMs(SLEEP_MS).setRenameDirectoryMs(SLEEP_MS)
        .setDeleteFileMs(SLEEP_MS).setDeleteDirectoryMs(SLEEP_MS)
        .setMkdirsMs(SLEEP_MS).setIsDirectoryMs(SLEEP_MS);
    UnderFileSystemRegistry.register(sSleepingUfsFactory);
  }

  @AfterClass
  public static void afterClass() throws Exception {
    UnderFileSystemRegistry.unregister(sSleepingUfsFactory);
  }

  @Before
  public void before() {
    mFileSystem = FileSystem.Factory.get();
  }

  /**
   * Uses the integer suffix of a path to determine order. Paths without integer suffixes will be
   * ordered last.
   */
  private class IntegerSuffixedPathComparator implements Comparator<URIStatus> {
    @Override
    public int compare(URIStatus o1, URIStatus o2) {
      return extractIntegerSuffix(o1.getName()) - extractIntegerSuffix(o2.getName());
    }

    private int extractIntegerSuffix(String name) {
      Pattern p = Pattern.compile("\\D*(\\d+$)");
      Matcher m = p.matcher(name);
      if (m.matches()) {
        return Integer.parseInt(m.group(1));
      } else {
        return Integer.MAX_VALUE;
      }
    }
  }

  /**
   * Tests concurrent renames within the root do not block on each other.
   */
  @Test
  public void rootConcurrentRename() throws Exception {
    final int numThreads = CONCURRENCY_FACTOR;
    AlluxioURI[] srcs = new AlluxioURI[numThreads];
    AlluxioURI[] dsts = new AlluxioURI[numThreads];

    for (int i = 0; i < numThreads; i++) {
      srcs[i] = new AlluxioURI("/file" + i);
      mFileSystem.createFile(srcs[i], sCreatePersistedFileOptions).close();
      dsts[i] = new AlluxioURI("/renamed" + i);
    }

    int errors = concurrentRename(srcs, dsts);

    Assert.assertEquals("More than 0 errors: " + errors, 0, errors);
    List<URIStatus> files = mFileSystem.listStatus(new AlluxioURI("/"));
    Collections.sort(files, new IntegerSuffixedPathComparator());
    for (int i = 0; i < numThreads; i++) {
      Assert.assertEquals(dsts[i].getName(), files.get(i).getName());
    }
    Assert.assertEquals(numThreads, files.size());
  }

  @Test
  public void concurrentCreate() throws Exception {
    final int numThreads = CONCURRENCY_FACTOR;
    // 7 nested components to create (2 seconds each).
    final long limitMs = 14 * SLEEP_MS * CONCURRENCY_FACTOR / 10;
    AlluxioURI[] paths = new AlluxioURI[numThreads];

    for (int i = 0; i < numThreads; i++) {
      paths[i] =
          new AlluxioURI("/existing/path/dir/shared_dir/t_" + i + "/sub_dir1/sub_dir2/file" + i);
    }
    int errors = concurrentUnaryOperation(UnaryOperation.CREATE, paths, limitMs);
    Assert.assertEquals("More than 0 errors: " + errors, 0, errors);
  }

  @Test
  public void concurrentCreateExistingDir() throws Exception {
    final int numThreads = CONCURRENCY_FACTOR;
    // 7 nested components to create (2 seconds each).
    final long limitMs = 14 * SLEEP_MS * CONCURRENCY_FACTOR / 10;
    AlluxioURI[] paths = new AlluxioURI[numThreads];

    // Create the existing path with MUST_CACHE, so subsequent creates have to persist the dirs.
    mFileSystem.createDirectory(new AlluxioURI("/existing/path/dir/"),
        CreateDirectoryOptions.defaults().setRecursive(true).setWriteType(WriteType.CACHE_THROUGH));

    for (int i = 0; i < numThreads; i++) {
      paths[i] =
          new AlluxioURI("/existing/path/dir/shared_dir/t_" + i + "/sub_dir1/sub_dir2/file" + i);
    }
    int errors = concurrentUnaryOperation(UnaryOperation.CREATE, paths, limitMs);
    Assert.assertEquals("More than 0 errors: " + errors, 0, errors);
  }

  @Test
  public void concurrentCreateNonPersistedDir() throws Exception {
    final int numThreads = CONCURRENCY_FACTOR;
    // 7 nested components to create (2 seconds each).
    final long limitMs = 14 * SLEEP_MS * CONCURRENCY_FACTOR / 10;
    AlluxioURI[] paths = new AlluxioURI[numThreads];

    // Create the existing path with MUST_CACHE, so subsequent creates have to persist the dirs.
    mFileSystem.createDirectory(new AlluxioURI("/existing/path/dir/"),
        CreateDirectoryOptions.defaults().setRecursive(true).setWriteType(WriteType.MUST_CACHE));

    for (int i = 0; i < numThreads; i++) {
      paths[i] =
          new AlluxioURI("/existing/path/dir/shared_dir/t_" + i + "/sub_dir1/sub_dir2/file" + i);
    }
    int errors = concurrentUnaryOperation(UnaryOperation.CREATE, paths, limitMs);
    Assert.assertEquals("More than 0 errors: " + errors, 0, errors);
  }

  @Test
  public void concurrentLoadFileMetadata() throws Exception {
    runLoadMetadata(null, false, true, false);
  }

  @Test
  public void concurrentLoadFileMetadataExistingDir() throws Exception {
    runLoadMetadata(WriteType.CACHE_THROUGH, false, true, false);
  }

  @Test
  public void concurrentLoadFileMetadataNonPersistedDir() throws Exception {
    runLoadMetadata(WriteType.MUST_CACHE, false, true, false);
  }

  @Test
  public void concurrentLoadSameFileMetadata() throws Exception {
    runLoadMetadata(null, true, true, false);
  }

  @Test
  public void concurrentLoadSameFileMetadataExistingDir() throws Exception {
    runLoadMetadata(WriteType.CACHE_THROUGH, true, true, false);
  }

  @Test
  public void concurrentLoadSameFileMetadataNonPersistedDir() throws Exception {
    runLoadMetadata(WriteType.MUST_CACHE, true, true, false);
  }

  @Test
  public void concurrentLoadDirMetadata() throws Exception {
    runLoadMetadata(null, false, false, false);
  }

  @Test
  public void concurrentLoadDirMetadataExistingDir() throws Exception {
    runLoadMetadata(WriteType.CACHE_THROUGH, false, false, false);
  }

  @Test
  public void concurrentLoadDirMetadataNonPersistedDir() throws Exception {
    runLoadMetadata(WriteType.MUST_CACHE, false, false, false);
  }

  @Test
  public void concurrentLoadSameDirMetadata() throws Exception {
    runLoadMetadata(null, true, false, false);
  }

  @Test
  public void concurrentLoadSameDirMetadataExistingDir() throws Exception {
    runLoadMetadata(WriteType.CACHE_THROUGH, true, false, false);
  }

  @Test
  public void concurrentLoadSameDirMetadataNonPersistedDir() throws Exception {
    runLoadMetadata(WriteType.MUST_CACHE, true, false, false);
  }

  @Test
  public void concurrentListDirs() throws Exception {
    runLoadMetadata(null, false, false, true);
  }

  @Test
  public void concurrentListDirsExistingDir() throws Exception {
    runLoadMetadata(WriteType.CACHE_THROUGH, false, false, true);
  }

  @Test
  public void concurrentListDirsNonPersistedDir() throws Exception {
    runLoadMetadata(WriteType.MUST_CACHE, false, false, true);
  }

  @Test
  public void concurrentListFiles() throws Exception {
    runLoadMetadata(null, false, true, true);
  }

  @Test
  public void concurrentListFilesExistingDir() throws Exception {
    runLoadMetadata(WriteType.CACHE_THROUGH, false, true, true);
  }

  @Test
  public void concurrentListFilesNonPersistedDir() throws Exception {
    runLoadMetadata(WriteType.MUST_CACHE, false, true, true);
  }

  /**
   * Tests concurrent deletes within the root do not block on each other.
   */
  @Test
  public void rootConcurrentDelete() throws Exception {
    final int numThreads = CONCURRENCY_FACTOR;
    AlluxioURI[] paths = new AlluxioURI[numThreads];

    for (int i = 0; i < numThreads; i++) {
      paths[i] = new AlluxioURI("/file" + i);
      mFileSystem.createFile(paths[i], sCreatePersistedFileOptions).close();
    }

    int errors = concurrentUnaryOperation(UnaryOperation.DELETE, paths, LIMIT_MS);

    Assert.assertEquals("More than 0 errors: " + errors, 0, errors);
    List<URIStatus> files = mFileSystem.listStatus(new AlluxioURI("/"));
    Assert.assertEquals(0, files.size());
  }

  /**
   * Tests concurrent renames within a folder do not block on each other.
   */
  @Test
  public void folderConcurrentRename() throws Exception {
    final int numThreads = CONCURRENCY_FACTOR;
    AlluxioURI[] srcs = new AlluxioURI[numThreads];
    AlluxioURI[] dsts = new AlluxioURI[numThreads];

    AlluxioURI dir = new AlluxioURI("/dir");

    mFileSystem.createDirectory(dir);

    for (int i = 0; i < numThreads; i++) {
      srcs[i] = dir.join("/file" + i);
      mFileSystem.createFile(srcs[i], sCreatePersistedFileOptions).close();
      dsts[i] = dir.join("/renamed" + i);
    }
    int errors = concurrentRename(srcs, dsts);

    Assert.assertEquals("More than 0 errors: " + errors, 0, errors);
    List<URIStatus> files = mFileSystem.listStatus(new AlluxioURI("/dir"));
    Collections.sort(files, new IntegerSuffixedPathComparator());
    for (int i = 0; i < numThreads; i++) {
      Assert.assertEquals(dsts[i].getName(), files.get(i).getName());
    }
    Assert.assertEquals(numThreads, files.size());
  }

  /**
   * Tests concurrent deletes within a folder do not block on each other.
   */
  @Test
  public void folderConcurrentDelete() throws Exception {
    final int numThreads = CONCURRENCY_FACTOR;
    AlluxioURI[] paths = new AlluxioURI[numThreads];
    AlluxioURI dir = new AlluxioURI("/dir");
    mFileSystem.createDirectory(dir);

    for (int i = 0; i < numThreads; i++) {
      paths[i] = dir.join("/file" + i);
      mFileSystem.createFile(paths[i], sCreatePersistedFileOptions).close();
    }
    int errors = concurrentUnaryOperation(UnaryOperation.DELETE, paths, LIMIT_MS);

    Assert.assertEquals("More than 0 errors: " + errors, 0, errors);
    List<URIStatus> files = mFileSystem.listStatus(dir);
    Assert.assertEquals(0, files.size());
  }

  /**
   * Tests concurrent deletes with shared prefix do not block on each other.
   */
  @Test
  public void prefixConcurrentDelete() throws Exception {
    final int numThreads = CONCURRENCY_FACTOR;
    AlluxioURI[] paths = new AlluxioURI[numThreads];
    AlluxioURI dir1 = new AlluxioURI("/dir1");
    mFileSystem.createDirectory(dir1);
    AlluxioURI dir2 = new AlluxioURI("/dir1/dir2");
    mFileSystem.createDirectory(dir2);
    AlluxioURI dir3 = new AlluxioURI("/dir1/dir2/dir3");
    mFileSystem.createDirectory(dir3);

    for (int i = 0; i < numThreads; i++) {
      if (i % 3 == 0) {
        paths[i] = dir1.join("/file" + i);
      } else if (i % 3 == 1) {
        paths[i] = dir2.join("/file" + i);
      } else {
        paths[i] = dir3.join("/file" + i);
      }
      mFileSystem.createFile(paths[i], sCreatePersistedFileOptions).close();
    }
    int errors = concurrentUnaryOperation(UnaryOperation.DELETE, paths, LIMIT_MS);

    Assert.assertEquals("More than 0 errors: " + errors, 0, errors);
    List<URIStatus> files = mFileSystem.listStatus(dir1);
    // Should only contain a single directory
    Assert.assertEquals(1, files.size());
    Assert.assertEquals("dir2", files.get(0).getName());
    files = mFileSystem.listStatus(dir2);
    // Should only contain a single directory
    Assert.assertEquals(1, files.size());
    Assert.assertEquals("dir3", files.get(0).getName());
    files = mFileSystem.listStatus(dir3);
    Assert.assertEquals(0, files.size());
  }

  /**
   * Tests that many threads concurrently renaming the same file will only succeed once.
   */
  @Test
  public void sameFileConcurrentRename() throws Exception {
    int numThreads = CONCURRENCY_FACTOR;
    final AlluxioURI[] srcs = new AlluxioURI[numThreads];
    final AlluxioURI[] dsts = new AlluxioURI[numThreads];
    for (int i = 0; i < numThreads; i++) {
      srcs[i] = new AlluxioURI("/file");
      dsts[i] = new AlluxioURI("/renamed" + i);
    }

    // Create the one source file
    mFileSystem.createFile(srcs[0], sCreatePersistedFileOptions).close();

    int errors = concurrentRename(srcs, dsts);

    // We should get an error for all but 1 rename
    Assert.assertEquals(numThreads - 1, errors);

    List<URIStatus> files = mFileSystem.listStatus(new AlluxioURI("/"));

    // Only one renamed file should exist
    Assert.assertEquals(1, files.size());
    Assert.assertTrue(files.get(0).getName().startsWith("renamed"));
  }

  /**
   * Tests that many threads concurrently deleting the same file will only succeed once.
   */
  @Test
  public void sameFileConcurrentDelete() throws Exception {
    int numThreads = CONCURRENCY_FACTOR;
    final AlluxioURI[] paths = new AlluxioURI[numThreads];
    for (int i = 0; i < numThreads; i++) {
      paths[i] = new AlluxioURI("/file");
    }
    // Create the single file
    mFileSystem.createFile(paths[0], sCreatePersistedFileOptions).close();

    int errors = concurrentUnaryOperation(UnaryOperation.DELETE, paths, LIMIT_MS);

    // We should get an error for all but 1 delete
    Assert.assertEquals(numThreads - 1, errors);

    List<URIStatus> files = mFileSystem.listStatus(new AlluxioURI("/"));
    Assert.assertEquals(0, files.size());
  }

  /**
   * Tests that many threads concurrently renaming the same directory will only succeed once.
   */
  @Test
  public void sameDirConcurrentRename() throws Exception {
    int numThreads = CONCURRENCY_FACTOR;
    final AlluxioURI[] srcs = new AlluxioURI[numThreads];
    final AlluxioURI[] dsts = new AlluxioURI[numThreads];
    for (int i = 0; i < numThreads; i++) {
      srcs[i] = new AlluxioURI("/dir");
      dsts[i] = new AlluxioURI("/renamed" + i);
    }

    // Create the one source directory
    mFileSystem.createDirectory(srcs[0]);
    mFileSystem.createFile(new AlluxioURI("/dir/file"), sCreatePersistedFileOptions).close();

    int errors = concurrentRename(srcs, dsts);

    // We should get an error for all but 1 rename
    Assert.assertEquals(numThreads - 1, errors);
    // Only one renamed dir should exist
    List<URIStatus> existingDirs = mFileSystem.listStatus(new AlluxioURI("/"));
    Assert.assertEquals(1, existingDirs.size());
    Assert.assertTrue(existingDirs.get(0).getName().startsWith("renamed"));
    // The directory should contain the file
    List<URIStatus> dirChildren =
        mFileSystem.listStatus(new AlluxioURI(existingDirs.get(0).getPath()));
    Assert.assertEquals(1, dirChildren.size());
  }

  /**
   * Tests that many threads concurrently deleting the same directory will only succeed once.
   */
  @Test
  public void sameDirConcurrentDelete() throws Exception {
    int numThreads = CONCURRENCY_FACTOR;
    final AlluxioURI[] paths = new AlluxioURI[numThreads];
    for (int i = 0; i < numThreads; i++) {
      paths[i] = new AlluxioURI("/dir");
    }
    // Create the single directory
    mFileSystem.createDirectory(paths[0], sCreatePersistedDirOptions);

    int errors = concurrentUnaryOperation(UnaryOperation.DELETE, paths, LIMIT_MS);

    // We should get an error for all but 1 delete
    Assert.assertEquals(numThreads - 1, errors);
    List<URIStatus> dirs = mFileSystem.listStatus(new AlluxioURI("/"));
    Assert.assertEquals(0, dirs.size());
  }

  /**
   * Tests renaming files concurrently to the same destination will only succeed once.
   */
  @Test
  public void sameDstConcurrentRename() throws Exception {
    int numThreads = CONCURRENCY_FACTOR;
    final AlluxioURI[] srcs = new AlluxioURI[numThreads];
    final AlluxioURI[] dsts = new AlluxioURI[numThreads];
    for (int i = 0; i < numThreads; i++) {
      srcs[i] = new AlluxioURI("/file" + i);
      mFileSystem.createFile(srcs[i], sCreatePersistedFileOptions).close();
      dsts[i] = new AlluxioURI("/renamed");
    }

    int errors = concurrentRename(srcs, dsts);

    // We should get an error for all but 1 rename.
    Assert.assertEquals(numThreads - 1, errors);

    List<URIStatus> files = mFileSystem.listStatus(new AlluxioURI("/"));
    // Store file names in a set to ensure the names are all unique.
    Set<String> renamedFiles = new HashSet<>();
    Set<String> originalFiles = new HashSet<>();
    for (URIStatus file : files) {
      if (file.getName().startsWith("renamed")) {
        renamedFiles.add(file.getName());
      }
      if (file.getName().startsWith("file")) {
        originalFiles.add(file.getName());
      }
    }
    // One renamed file should exist, and numThreads - 1 original source files
    Assert.assertEquals(numThreads, files.size());
    Assert.assertEquals(1, renamedFiles.size());
    Assert.assertEquals(numThreads - 1, originalFiles.size());
  }

  /**
   * Tests renaming files concurrently from one directory to another succeeds.
   */
  @Test
  public void twoDirConcurrentRename() throws Exception {
    int numThreads = CONCURRENCY_FACTOR;
    final AlluxioURI[] srcs = new AlluxioURI[numThreads];
    final AlluxioURI[] dsts = new AlluxioURI[numThreads];
    AlluxioURI dir1 = new AlluxioURI("/dir1");
    AlluxioURI dir2 = new AlluxioURI("/dir2");
    mFileSystem.createDirectory(dir1);
    mFileSystem.createDirectory(dir2);
    for (int i = 0; i < numThreads; i++) {
      srcs[i] = dir1.join("file" + i);
      mFileSystem.createFile(srcs[i], sCreatePersistedFileOptions).close();
      dsts[i] = dir2.join("renamed" + i);
    }

    int errors = concurrentRename(srcs, dsts);

    // We should get no errors
    Assert.assertEquals(0, errors);

    List<URIStatus> dir1Files = mFileSystem.listStatus(dir1);
    List<URIStatus> dir2Files = mFileSystem.listStatus(dir2);

    Assert.assertEquals(0, dir1Files.size());
    Assert.assertEquals(numThreads, dir2Files.size());

    Collections.sort(dir2Files, new IntegerSuffixedPathComparator());
    for (int i = 0; i < numThreads; i++) {
      Assert.assertEquals(dsts[i].getName(), dir2Files.get(i).getName());
    }
  }

  /**
   * Tests renaming files concurrently from and to two directories succeeds.
   */
  @Test
  public void acrossDirConcurrentRename() throws Exception {
    int numThreads = CONCURRENCY_FACTOR;
    final AlluxioURI[] srcs = new AlluxioURI[numThreads];
    final AlluxioURI[] dsts = new AlluxioURI[numThreads];
    AlluxioURI dir1 = new AlluxioURI("/dir1");
    AlluxioURI dir2 = new AlluxioURI("/dir2");
    mFileSystem.createDirectory(dir1);
    mFileSystem.createDirectory(dir2);
    for (int i = 0; i < numThreads; i++) {
      // Dir1 has even files, dir2 has odd files.
      if (i % 2 == 0) {
        srcs[i] = dir1.join("file" + i);
        dsts[i] = dir2.join("renamed" + i);
      } else {
        srcs[i] = dir2.join("file" + i);
        dsts[i] = dir1.join("renamed" + i);
      }
      mFileSystem.createFile(srcs[i], sCreatePersistedFileOptions).close();
    }

    int errors = concurrentRename(srcs, dsts);

    // We should get no errors.
    Assert.assertEquals(0, errors);

    List<URIStatus> dir1Files = mFileSystem.listStatus(dir1);
    List<URIStatus> dir2Files = mFileSystem.listStatus(dir2);

    Assert.assertEquals(numThreads / 2, dir1Files.size());
    Assert.assertEquals(numThreads / 2, dir2Files.size());

    Collections.sort(dir1Files, new IntegerSuffixedPathComparator());
    for (int i = 1; i < numThreads; i += 2) {
      Assert.assertEquals(dsts[i].getName(), dir1Files.get(i / 2).getName());
    }

    Collections.sort(dir2Files, new IntegerSuffixedPathComparator());
    for (int i = 0; i < numThreads; i += 2) {
      Assert.assertEquals(dsts[i].getName(), dir2Files.get(i / 2).getName());
    }
  }

  /**
   * Tests renaming files concurrently under directories with a shared path prefix.
   */
  @Test
  public void sharedPrefixDirConcurrentRename() throws Exception {
    int numThreads = CONCURRENCY_FACTOR;
    final AlluxioURI[] srcs = new AlluxioURI[numThreads];
    final AlluxioURI[] dsts = new AlluxioURI[numThreads];
    AlluxioURI dir1 = new AlluxioURI("/root/dir1");
    AlluxioURI dir2 = new AlluxioURI("/root/parent/dir2");
    AlluxioURI dst = new AlluxioURI("/dst");
    mFileSystem.createDirectory(dir1, sCreateDirectoryOptions);
    mFileSystem.createDirectory(dir2, sCreateDirectoryOptions);
    mFileSystem.createDirectory(dst, sCreateDirectoryOptions);
    for (int i = 0; i < numThreads; i++) {
      // Dir1 has even files, dir2 has odd files.
      srcs[i] = i % 2 == 0 ? dir1.join("file" + i) : dir2.join("file" + i);
      dsts[i] = dst.join("renamed" + i);
      mFileSystem.createFile(srcs[i], sCreatePersistedFileOptions).close();
    }

    int errors = concurrentRename(srcs, dsts);

    // We should get no errors.
    Assert.assertEquals(0, errors);

    List<URIStatus> dir1Files = mFileSystem.listStatus(dir1);
    List<URIStatus> dir2Files = mFileSystem.listStatus(dir2);
    List<URIStatus> dstFiles = mFileSystem.listStatus(dst);

    Assert.assertEquals(0, dir1Files.size());
    Assert.assertEquals(0, dir2Files.size());
    Assert.assertEquals(numThreads, dstFiles.size());

    Collections.sort(dstFiles, new IntegerSuffixedPathComparator());
    for (int i = 0; i < numThreads; i++) {
      Assert.assertEquals(dsts[i].getName(), dstFiles.get(i).getName());
    }
  }

  /**
   * Helper for renaming a list of paths concurrently. Assumes the srcs are already created and
   * dsts do not exist. Enforces that the run time of this method is not greater than twice the
   * sleep time (to infer concurrent operations). Injects an artificial sleep time to the
   * sleeping under file system and resets it after the renames are complete.
   *
   * @param src list of source paths
   * @param dst list of destination paths
   * @return how many errors occurred
   */
  private int concurrentRename(final AlluxioURI[] src, final AlluxioURI[] dst)
      throws Exception {
    final int numFiles = src.length;
    final CyclicBarrier barrier = new CyclicBarrier(numFiles);
    List<Thread> threads = new ArrayList<>(numFiles);
    // If there are exceptions, we will store them here.
    final ConcurrentHashSet<Throwable> errors = new ConcurrentHashSet<>();
    Thread.UncaughtExceptionHandler exceptionHandler = new Thread.UncaughtExceptionHandler() {
      public void uncaughtException(Thread th, Throwable ex) {
        errors.add(ex);
      }
    };
    for (int i = 0; i < numFiles; i++) {
      final int iteration = i;
      Thread t = new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            AuthenticatedClientUser.set(TEST_USER);
            barrier.await();
            mFileSystem.rename(src[iteration], dst[iteration]);
          } catch (Exception e) {
            Throwables.propagate(e);
          }
        }
      });
      t.setUncaughtExceptionHandler(exceptionHandler);
      threads.add(t);
    }
    Collections.shuffle(threads);
    long startMs = CommonUtils.getCurrentMs();
    for (Thread t : threads) {
      t.start();
    }
    for (Thread t : threads) {
      t.join();
    }
    long durationMs = CommonUtils.getCurrentMs() - startMs;
    Assert.assertTrue("Execution duration " + durationMs + " took longer than expected " + LIMIT_MS,
        durationMs < LIMIT_MS);
    return errors.size();
  }

  private int concurrentUnaryOperation(final UnaryOperation operation, final AlluxioURI[] paths,
      final long limitMs) throws Exception {
    final int numFiles = paths.length;
    final CyclicBarrier barrier = new CyclicBarrier(numFiles);
    List<Thread> threads = new ArrayList<>(numFiles);
    // If there are exceptions, we will store them here.
    final ConcurrentHashSet<Throwable> errors = new ConcurrentHashSet<>();
    Thread.UncaughtExceptionHandler exceptionHandler = new Thread.UncaughtExceptionHandler() {
      public void uncaughtException(Thread th, Throwable ex) {
        errors.add(ex);
      }
    };
    for (int i = 0; i < numFiles; i++) {
      final int iteration = i;
      Thread t = new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            AuthenticatedClientUser.set(TEST_USER);
            barrier.await();
            switch (operation) {
              case CREATE:
                mFileSystem.createFile(paths[iteration], sCreatePersistedFileOptions).close();
                break;
              case DELETE:
                mFileSystem.delete(paths[iteration]);
                break;
              case GET_FILE_INFO:
                mFileSystem.getStatus(paths[iteration]);
                break;
              case LIST_STATUS:
                mFileSystem.listStatus(paths[iteration]);
                break;
              default: throw new IllegalArgumentException("'operation' is not a valid operation.");
            }

          } catch (Exception e) {
            Throwables.propagate(e);
          }
        }
      });
      t.setUncaughtExceptionHandler(exceptionHandler);
      threads.add(t);
    }
    Collections.shuffle(threads);
    long startMs = CommonUtils.getCurrentMs();
    for (Thread t : threads) {
      t.start();
    }
    for (Thread t : threads) {
      t.join();
    }
    long durationMs = CommonUtils.getCurrentMs() - startMs;
    Assert.assertTrue("Execution duration " + durationMs + " took longer than expected " + limitMs,
        durationMs < limitMs);
    return errors.size();
  }

  /**
   * Runs load metadata tests.
   *
   * @param writeType the {@link WriteType} to create ancestors, if not null
   * @param useSinglePath if true, threads will only use a single path
   * @param createFiles if true, will create files at the bottom of the tree, directories otherwise
   * @param listParentDir if true, will list the parent dir to load the metadata
   * @throws Exception if an error occurs
   */
  private void runLoadMetadata(WriteType writeType, boolean useSinglePath, boolean createFiles,
      boolean listParentDir) throws Exception {
    int numThreads = CONCURRENCY_FACTOR;
    // 2 nested components to create.
    long limitMs = 2 * SLEEP_MS * 2;

    int uniquePaths = useSinglePath ? 1 : numThreads;

    if (listParentDir) {
      // Loading direct children needs to load each child, so reduce the branching factor.
      uniquePaths = 10;
      limitMs = (2 + uniquePaths) * SLEEP_MS * 2;
    }

    // Create UFS files outside of Alluxio.
    new File(mLocalUfsPath + "/existing/path/").mkdirs();
    for (int i = 0; i < uniquePaths; i++) {
      if (createFiles) {
        FileWriter fileWriter = new FileWriter(mLocalUfsPath + "/existing/path/last_" + i);
        fileWriter.write("test");
        fileWriter.close();
      } else {
        new File(mLocalUfsPath + "/existing/path/last_" + i).mkdirs();
      }
    }

    if (writeType != null) {
      // create inodes in Alluxio
      mFileSystem.createDirectory(new AlluxioURI("/existing/path/"),
          CreateDirectoryOptions.defaults().setRecursive(true).setWriteType(writeType));
    }

    // Generate path names for threads.
    AlluxioURI[] paths = new AlluxioURI[numThreads];
    int fileId = 0;
    for (int i = 0; i < numThreads; i++) {
      if (listParentDir) {
        paths[i] = new AlluxioURI("/existing/path/");
      } else {
        paths[i] = new AlluxioURI("/existing/path/last_" + ((fileId++) % uniquePaths));
      }
    }

    int errors = 0;
    if (listParentDir) {
      errors = concurrentUnaryOperation(UnaryOperation.LIST_STATUS, paths, limitMs);
    } else {
      errors = concurrentUnaryOperation(UnaryOperation.GET_FILE_INFO, paths, limitMs);
    }
    Assert.assertEquals("More than 0 errors: " + errors, 0, errors);

    ListStatusOptions listOptions = ListStatusOptions.defaults().setLoadMetadataType(
        LoadMetadataType.Never);

    List<URIStatus> files = mFileSystem.listStatus(new AlluxioURI("/"), listOptions);
    Assert.assertEquals(1, files.size());
    Assert.assertEquals("existing", files.get(0).getName());
    Assert.assertEquals(PersistenceState.PERSISTED,
        PersistenceState.valueOf(files.get(0).getPersistenceState()));

    files = mFileSystem.listStatus(new AlluxioURI("/existing"), listOptions);
    Assert.assertEquals(1, files.size());
    Assert.assertEquals("path", files.get(0).getName());
    Assert.assertEquals(PersistenceState.PERSISTED,
        PersistenceState.valueOf(files.get(0).getPersistenceState()));

    files = mFileSystem.listStatus(new AlluxioURI("/existing/path/"), listOptions);
    Assert.assertEquals(uniquePaths, files.size());
    Collections.sort(files, new IntegerSuffixedPathComparator());
    for (int i = 0; i < uniquePaths; i++) {
      Assert.assertEquals("last_" + i, files.get(i).getName());
      Assert.assertEquals(PersistenceState.PERSISTED,
          PersistenceState.valueOf(files.get(i).getPersistenceState()));
    }
  }

  /**
   * Tests that getFileInfo (read operation) either returns the correct file info or fails if it
   * has been renamed while the operation was waiting for the file lock.
   */
  @Test
  public void consistentGetFileInfo() throws Exception {
    final int iterations = CONCURRENCY_FACTOR;
    final AlluxioURI file = new AlluxioURI("/file");
    final AlluxioURI dst = new AlluxioURI("/dst");
    final CyclicBarrier barrier = new CyclicBarrier(2);
    // If there are exceptions, we will store them here.
    final ConcurrentHashSet<Throwable> errors = new ConcurrentHashSet<>();
    Thread.UncaughtExceptionHandler exceptionHandler = new Thread.UncaughtExceptionHandler() {
      public void uncaughtException(Thread th, Throwable ex) {
        errors.add(ex);
      }
    };
    for (int i = 0; i < iterations; i++) {
      // Don't want sleeping ufs behavior, so do not write to ufs
      mFileSystem.createFile(file, CreateFileOptions.defaults().setWriteType(WriteType.MUST_CACHE))
          .close();
      Thread renamer = new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            AuthenticatedClientUser.set(TEST_USER);
            barrier.await();
            mFileSystem.rename(file, dst);
            mFileSystem.delete(dst);
          } catch (Exception e) {
            Assert.fail(e.getMessage());
          }
        }
      });
      renamer.setUncaughtExceptionHandler(exceptionHandler);
      Thread reader = new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            AuthenticatedClientUser.set(TEST_USER);
            barrier.await();
            URIStatus status = mFileSystem.getStatus(file);
            // If the uri status is successfully obtained, then the path should match
            Assert.assertEquals(file.getName(), status.getName());
          } catch (InvalidPathException | FileDoesNotExistException e) {
            // InvalidPathException - if the file is renamed while the thread waits for the lock.
            // FileDoesNotExistException - if the file is fully renamed before the getFileInfo call.
          } catch (Exception e) {
            Assert.fail(e.getMessage());
          }
        }
      });
      reader.setUncaughtExceptionHandler(exceptionHandler);
      renamer.start();
      reader.start();
      renamer.join();
      reader.join();
      Assert.assertTrue("Errors detected: " + errors, errors.isEmpty());
    }
  }
}
