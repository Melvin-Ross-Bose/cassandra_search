/*
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package org.apache.cassandra.db;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

import org.junit.Assert;
import org.junit.Test;

import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.Util;
import org.apache.cassandra.config.Config;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.commitlog.CommitLog;
import org.apache.cassandra.db.commitlog.CommitLogDescriptor;
import org.apache.cassandra.db.commitlog.CommitLogSegmentManager;
import org.apache.cassandra.db.commitlog.ReplayPosition;
import org.apache.cassandra.db.commitlog.CommitLogSegment;
import org.apache.cassandra.db.composites.CellName;
import org.apache.cassandra.db.composites.CellNameType;
import org.apache.cassandra.db.filter.NamesQueryFilter;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.gms.Gossiper;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.service.CassandraDaemon;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.JVMStabilityInspector;
import org.apache.cassandra.utils.KillerForTests;

import static org.apache.cassandra.utils.ByteBufferUtil.bytes;

public class CommitLogTest extends SchemaLoader
{
    @Test
    public void testRecoveryWithEmptyLog() throws Exception
    {
        CommitLog.instance.recover(new File[]{ tmpFile() });
    }

    @Test
    public void testRecoveryWithShortLog() throws Exception
    {
        // force EOF while reading log
        testRecoveryWithBadSizeArgument(100, 10);
    }

    @Test
    public void testRecoveryWithShortSize() throws Exception
    {
        testRecovery(new byte[2]);
    }

    @Test
    public void testRecoveryWithShortCheckSum() throws Exception
    {
        testRecovery(new byte[6]);
    }

    @Test
    public void testRecoveryWithGarbageLog() throws Exception
    {
        byte[] garbage = new byte[100];
        (new java.util.Random()).nextBytes(garbage);
        testRecovery(garbage);
    }

    @Test
    public void testRecoveryWithBadSizeChecksum() throws Exception
    {
        Checksum checksum = new CRC32();
        checksum.update(100);
        testRecoveryWithBadSizeArgument(100, 100, ~checksum.getValue());
    }

    @Test
    public void testRecoveryWithZeroSegmentSizeArgument() throws Exception
    {
        // many different combinations of 4 bytes (garbage) will be read as zero by readInt()
        testRecoveryWithBadSizeArgument(0, 10); // zero size, but no EOF
    }

    @Test
    public void testRecoveryWithNegativeSizeArgument() throws Exception
    {
        // garbage from a partial/bad flush could be read as a negative size even if there is no EOF
        testRecoveryWithBadSizeArgument(-10, 10); // negative size, but no EOF
    }

    @Test
    public void testDontDeleteIfDirty() throws Exception
    {
        CommitLog.instance.resetUnsafe();
        // Roughly 32 MB mutation
        Mutation rm = new Mutation("Keyspace1", bytes("k"));
        rm.add("Standard1", Util.cellname("c1"), ByteBuffer.allocate(DatabaseDescriptor.getCommitLogSegmentSize()/4), 0);

        // Adding it 5 times
        CommitLog.instance.add(rm);
        CommitLog.instance.add(rm);
        CommitLog.instance.add(rm);
        CommitLog.instance.add(rm);
        CommitLog.instance.add(rm);

        // Adding new mutation on another CF
        Mutation rm2 = new Mutation("Keyspace1", bytes("k"));
        rm2.add("Standard2", Util.cellname("c1"), ByteBuffer.allocate(4), 0);
        CommitLog.instance.add(rm2);

        assert CommitLog.instance.activeSegments() == 2 : "Expecting 2 segments, got " + CommitLog.instance.activeSegments();

        UUID cfid2 = rm2.getColumnFamilyIds().iterator().next();
        CommitLog.instance.discardCompletedSegments(cfid2, CommitLog.instance.getContext());

        // Assert we still have both our segment
        assert CommitLog.instance.activeSegments() == 2 : "Expecting 2 segments, got " + CommitLog.instance.activeSegments();
    }

    @Test
    public void testDeleteIfNotDirty() throws Exception
    {
        DatabaseDescriptor.getCommitLogSegmentSize();
        CommitLog.instance.resetUnsafe();
        // Roughly 32 MB mutation
        Mutation rm = new Mutation("Keyspace1", bytes("k"));
        rm.add("Standard1", Util.cellname("c1"), ByteBuffer.allocate((DatabaseDescriptor.getCommitLogSegmentSize()/4) - 1), 0);

        // Adding it twice (won't change segment)
        CommitLog.instance.add(rm);
        CommitLog.instance.add(rm);

        assert CommitLog.instance.activeSegments() == 1 : "Expecting 1 segment, got " + CommitLog.instance.activeSegments();

        // "Flush": this won't delete anything
        UUID cfid1 = rm.getColumnFamilyIds().iterator().next();
        CommitLog.instance.sync(true);
        CommitLog.instance.discardCompletedSegments(cfid1, CommitLog.instance.getContext());

        assert CommitLog.instance.activeSegments() == 1 : "Expecting 1 segment, got " + CommitLog.instance.activeSegments();

        // Adding new mutation on another CF, large enough (including CL entry overhead) that a new segment is created
        Mutation rm2 = new Mutation("Keyspace1", bytes("k"));
        rm2.add("Standard2", Util.cellname("c1"), ByteBuffer.allocate((DatabaseDescriptor.getCommitLogSegmentSize()/2) - 100), 0);
        CommitLog.instance.add(rm2);
        // also forces a new segment, since each entry-with-overhead is just under half the CL size
        CommitLog.instance.add(rm2);
        CommitLog.instance.add(rm2);

        assert CommitLog.instance.activeSegments() == 3 : "Expecting 3 segments, got " + CommitLog.instance.activeSegments();


        // "Flush" second cf: The first segment should be deleted since we
        // didn't write anything on cf1 since last flush (and we flush cf2)

        UUID cfid2 = rm2.getColumnFamilyIds().iterator().next();
        CommitLog.instance.discardCompletedSegments(cfid2, CommitLog.instance.getContext());

        // Assert we still have both our segment
        assert CommitLog.instance.activeSegments() == 1 : "Expecting 1 segment, got " + CommitLog.instance.activeSegments();
    }

    private static int getMaxRecordDataSize(String keyspace, ByteBuffer key, String table, CellName column)
    {
        Mutation rm = new Mutation("Keyspace1", bytes("k"));
        rm.add("Standard1", Util.cellname("c1"), ByteBuffer.allocate(0), 0);

        int max = (DatabaseDescriptor.getCommitLogSegmentSize() / 2);
        max -= CommitLogSegment.ENTRY_OVERHEAD_SIZE; // log entry overhead
        return max - (int) Mutation.serializer.serializedSize(rm, MessagingService.current_version);
    }

    private static int getMaxRecordDataSize()
    {
        return getMaxRecordDataSize("Keyspace1", bytes("k"), "Standard1", Util.cellname("c1"));
    }

    // CASSANDRA-3615
    @Test
    public void testEqualRecordLimit() throws Exception
    {
        CommitLog.instance.resetUnsafe();

        Mutation rm = new Mutation("Keyspace1", bytes("k"));
        rm.add("Standard1", Util.cellname("c1"), ByteBuffer.allocate(getMaxRecordDataSize()), 0);
        CommitLog.instance.add(rm);
    }

    @Test
    public void testExceedRecordLimit() throws Exception
    {
        CommitLog.instance.resetUnsafe();
        try
        {
            Mutation rm = new Mutation("Keyspace1", bytes("k"));
            rm.add("Standard1", Util.cellname("c1"), ByteBuffer.allocate(1 + getMaxRecordDataSize()), 0);
            CommitLog.instance.add(rm);
            throw new AssertionError("mutation larger than limit was accepted");
        }
        catch (IllegalArgumentException e)
        {
            // IAE is thrown on too-large mutations
        }
    }

    protected void testRecoveryWithBadSizeArgument(int size, int dataSize) throws Exception
    {
        Checksum checksum = new CRC32();
        checksum.update(size);
        testRecoveryWithBadSizeArgument(size, dataSize, checksum.getValue());
    }

    protected void testRecoveryWithBadSizeArgument(int size, int dataSize, long checksum) throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dout = new DataOutputStream(out);
        dout.writeInt(size);
        dout.writeLong(checksum);
        dout.write(new byte[dataSize]);
        dout.close();
        testRecovery(out.toByteArray());
    }

    protected File tmpFile() throws IOException
    {
        File logFile = File.createTempFile("CommitLog-" + CommitLogDescriptor.current_version + "-", ".log");
        logFile.deleteOnExit();
        assert logFile.length() == 0;
        return logFile;
    }

    protected void testRecovery(byte[] logData) throws Exception
    {
        File logFile = tmpFile();
        try (OutputStream lout = new FileOutputStream(logFile))
        {
            lout.write(logData);
            //statics make it annoying to test things correctly
            CommitLog.instance.recover(new File[]{ logFile }); //CASSANDRA-1119 / CASSANDRA-1179 throw on failure*/
        }
    }

    @Test
    public void testVersions()
    {
        Assert.assertTrue(CommitLogDescriptor.isValid("CommitLog-1340512736956320000.log"));
        Assert.assertTrue(CommitLogDescriptor.isValid("CommitLog-2-1340512736956320000.log"));
        Assert.assertFalse(CommitLogDescriptor.isValid("CommitLog--1340512736956320000.log"));
        Assert.assertFalse(CommitLogDescriptor.isValid("CommitLog--2-1340512736956320000.log"));
        Assert.assertFalse(CommitLogDescriptor.isValid("CommitLog-2-1340512736956320000-123.log"));

        Assert.assertEquals(1340512736956320000L, CommitLogDescriptor.fromFileName("CommitLog-2-1340512736956320000.log").id);

        Assert.assertEquals(MessagingService.current_version, new CommitLogDescriptor(1340512736956320000L).getMessagingVersion());
        String newCLName = "CommitLog-" + CommitLogDescriptor.current_version + "-1340512736956320000.log";
        Assert.assertEquals(MessagingService.current_version, CommitLogDescriptor.fromFileName(newCLName).getMessagingVersion());
    }

    @Test
    public void testCommitFailurePolicy_stop() throws ConfigurationException
    {
        CassandraDaemon daemon = new CassandraDaemon();
        daemon.completeSetup(); //startup must be completed, otherwise commit log failure must kill JVM regardless of failure policy
        StorageService.instance.registerDaemon(daemon);

        // Need storage service active so stop policy can shutdown gossip
        StorageService.instance.initServer();
        Assert.assertTrue(Gossiper.instance.isEnabled());

        Config.CommitFailurePolicy oldPolicy = DatabaseDescriptor.getCommitFailurePolicy();
        try
        {
            DatabaseDescriptor.setCommitFailurePolicy(Config.CommitFailurePolicy.stop);
            CommitLog.handleCommitError("Test stop error", new Throwable());
            Assert.assertFalse(Gossiper.instance.isEnabled());
        }
        finally
        {
            DatabaseDescriptor.setCommitFailurePolicy(oldPolicy);
        }
    }

    @Test
    public void testCommitFailurePolicy_die()
    {
        CassandraDaemon daemon = new CassandraDaemon();
        daemon.completeSetup(); //startup must be completed, otherwise commit log failure must kill JVM regardless of failure policy
        StorageService.instance.registerDaemon(daemon);

        KillerForTests killerForTests = new KillerForTests();
        JVMStabilityInspector.Killer originalKiller = JVMStabilityInspector.replaceKiller(killerForTests);
        Config.CommitFailurePolicy oldPolicy = DatabaseDescriptor.getCommitFailurePolicy();
        try
        {
            DatabaseDescriptor.setCommitFailurePolicy(Config.CommitFailurePolicy.die);
            CommitLog.handleCommitError("Testing die policy", new Throwable());
            Assert.assertTrue(killerForTests.wasKilled());
            Assert.assertFalse(killerForTests.wasKilledQuietly()); //only killed quietly on startup failure
        }
        finally
        {
            DatabaseDescriptor.setCommitFailurePolicy(oldPolicy);
            JVMStabilityInspector.replaceKiller(originalKiller);
        }
    }

    @Test
    public void testCommitFailurePolicy_mustDieIfNotStartedUp()
    {
        //startup was not completed successfuly (since method completeSetup() was not called)
        CassandraDaemon daemon = new CassandraDaemon();
        StorageService.instance.registerDaemon(daemon);

        KillerForTests killerForTests = new KillerForTests();
        JVMStabilityInspector.Killer originalKiller = JVMStabilityInspector.replaceKiller(killerForTests);
        Config.CommitFailurePolicy oldPolicy = DatabaseDescriptor.getCommitFailurePolicy();
        try
        {
            //even though policy is ignore, JVM must die because Daemon has not finished initializing
            DatabaseDescriptor.setCommitFailurePolicy(Config.CommitFailurePolicy.ignore);
            CommitLog.handleCommitError("Testing die policy", new Throwable());
            Assert.assertTrue(killerForTests.wasKilled());
            Assert.assertTrue(killerForTests.wasKilledQuietly()); //killed quietly due to startup failure
        }
        finally
        {
            DatabaseDescriptor.setCommitFailurePolicy(oldPolicy);
            JVMStabilityInspector.replaceKiller(originalKiller);
        }
    }

    @Test
    public void testCommitLogFailureBeforeInitialization_mustKillJVM() throws Exception
    {
        //startup was not completed successfuly (since method completeSetup() was not called)
        CassandraDaemon daemon = new CassandraDaemon();
        StorageService.instance.registerDaemon(daemon);

        //let's make the commit log directory non-writable
        File commitLogDir = new File(DatabaseDescriptor.getCommitLogLocation());
        commitLogDir.setWritable(false);

        KillerForTests killerForTests = new KillerForTests();
        JVMStabilityInspector.Killer originalKiller = JVMStabilityInspector.replaceKiller(killerForTests);
        Config.CommitFailurePolicy oldPolicy = DatabaseDescriptor.getCommitFailurePolicy();
        try
        {
            DatabaseDescriptor.setCommitFailurePolicy(Config.CommitFailurePolicy.ignore);

            //now let's create a commit log segment manager and wait for it to fail
            new CommitLogSegmentManager();

            //busy wait since commitlogsegmentmanager spawns another thread
            int retries = 0;
            while (!killerForTests.wasKilled() && retries++ < 5)
                Thread.sleep(10);

            //since failure was before CassandraDaemon startup, the JVM must be killed
            Assert.assertTrue(killerForTests.wasKilled());
            Assert.assertTrue(killerForTests.wasKilledQuietly()); //killed quietly due to startup failure
        }
        finally
        {
            DatabaseDescriptor.setCommitFailurePolicy(oldPolicy);
            JVMStabilityInspector.replaceKiller(originalKiller);
            commitLogDir.setWritable(true);
        }
    }

    @Test
    public void testCommitLogFailureAfterInitialization_mustRespectFailurePolicy() throws Exception
    {
        //startup was not completed successfuly (since method completeSetup() was not called)
        CassandraDaemon daemon = new CassandraDaemon();
        daemon.completeSetup(); //startup must be completed, otherwise commit log failure must kill JVM regardless of failure policy
        StorageService.instance.registerDaemon(daemon);

        //let's make the commit log directory non-writable
        File commitLogDir = new File(DatabaseDescriptor.getCommitLogLocation());
        commitLogDir.setWritable(false);

        KillerForTests killerForTests = new KillerForTests();
        JVMStabilityInspector.Killer originalKiller = JVMStabilityInspector.replaceKiller(killerForTests);
        Config.CommitFailurePolicy oldPolicy = DatabaseDescriptor.getCommitFailurePolicy();
        try
        {
            DatabaseDescriptor.setCommitFailurePolicy(Config.CommitFailurePolicy.ignore);

            //now let's create a commit log segment manager and wait for it to fail
            new CommitLogSegmentManager();

            //wait commit log segment manager thread to execute
            Thread.sleep(50);

            //error policy is set to IGNORE, so JVM must not be killed if error ocurs after startup
            Assert.assertFalse(killerForTests.wasKilled());
        }
        finally
        {
            DatabaseDescriptor.setCommitFailurePolicy(oldPolicy);
            JVMStabilityInspector.replaceKiller(originalKiller);
            commitLogDir.setWritable(true);
        }
    }

    @Test
    public void testTruncateWithoutSnapshot()  throws ExecutionException, InterruptedException
    {
        CommitLog.instance.resetUnsafe();
        boolean prev = DatabaseDescriptor.isAutoSnapshot();
        DatabaseDescriptor.setAutoSnapshot(false);
        ColumnFamilyStore cfs1 = Keyspace.open("Keyspace1").getColumnFamilyStore("Standard1");
        ColumnFamilyStore cfs2 = Keyspace.open("Keyspace1").getColumnFamilyStore("Standard2");

        final Mutation rm1 = new Mutation("Keyspace1", bytes("k"));
        rm1.add("Standard1", Util.cellname("c1"), ByteBuffer.allocate(100), 0);
        rm1.apply();
        cfs1.truncateBlocking();
        DatabaseDescriptor.setAutoSnapshot(prev);
        final Mutation rm2 = new Mutation("Keyspace1", bytes("k"));
        rm2.add("Standard2", Util.cellname("c1"), ByteBuffer.allocate(DatabaseDescriptor.getCommitLogSegmentSize() / 4), 0);

        for (int i = 0 ; i < 5 ; i++)
            CommitLog.instance.add(rm2);

        Assert.assertEquals(2, CommitLog.instance.activeSegments());
        ReplayPosition position = CommitLog.instance.getContext();
        for (Keyspace ks : Keyspace.system())
            for (ColumnFamilyStore syscfs : ks.getColumnFamilyStores())
                CommitLog.instance.discardCompletedSegments(syscfs.metadata.cfId, position);
        CommitLog.instance.discardCompletedSegments(cfs2.metadata.cfId, position);
        Assert.assertEquals(1, CommitLog.instance.activeSegments());
    }

    @Test
    public void testTruncateWithoutSnapshotNonDurable()  throws ExecutionException, InterruptedException
    {
        CommitLog.instance.resetUnsafe();
        boolean prevAutoSnapshot = DatabaseDescriptor.isAutoSnapshot();
        DatabaseDescriptor.setAutoSnapshot(false);
        Keyspace notDurableKs = Keyspace.open("NoCommitlogSpace");
        Assert.assertFalse(notDurableKs.getMetadata().durableWrites);
        ColumnFamilyStore cfs = notDurableKs.getColumnFamilyStore("Standard1");
        CellNameType type = notDurableKs.getColumnFamilyStore("Standard1").getComparator();
        Mutation rm;
        DecoratedKey dk = Util.dk("key1");

        // add data
        rm = new Mutation("NoCommitlogSpace", dk.getKey());
        rm.add("Standard1", Util.cellname("Column1"), ByteBufferUtil.bytes("abcd"), 0);
        rm.apply();

        ReadCommand command = new SliceByNamesReadCommand("NoCommitlogSpace", dk.getKey(), "Standard1", System.currentTimeMillis(), new NamesQueryFilter(FBUtilities.singleton(Util.cellname("Column1"), type)));
        Row row = command.getRow(notDurableKs);
        Cell col = row.cf.getColumn(Util.cellname("Column1"));
        Assert.assertEquals(col.value(), ByteBuffer.wrap("abcd".getBytes()));
        cfs.truncateBlocking();
        DatabaseDescriptor.setAutoSnapshot(prevAutoSnapshot);
        row = command.getRow(notDurableKs);
        Assert.assertEquals(null, row.cf);
    }
}
