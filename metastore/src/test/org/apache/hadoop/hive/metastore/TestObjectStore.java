/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.metastore;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.common.metrics.MetricsTestUtils;
import org.apache.hadoop.hive.common.metrics.common.MetricsConstant;
import org.apache.hadoop.hive.common.metrics.common.MetricsFactory;
import org.apache.hadoop.hive.common.metrics.metrics2.CodahaleMetrics;
import org.apache.hadoop.hive.common.metrics.metrics2.MetricsReporting;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.api.BooleanColumnStatsData;
import org.apache.hadoop.hive.metastore.api.ColumnStatistics;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsData;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsDesc;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsObj;
import org.apache.hadoop.hive.metastore.api.CurrentNotificationEventId;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.FileMetadataExprType;
import org.apache.hadoop.hive.metastore.api.Function;
import org.apache.hadoop.hive.metastore.api.HiveObjectPrivilege;
import org.apache.hadoop.hive.metastore.api.HiveObjectRef;
import org.apache.hadoop.hive.metastore.api.Index;
import org.apache.hadoop.hive.metastore.api.InvalidInputException;
import org.apache.hadoop.hive.metastore.api.InvalidObjectException;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.NotificationEvent;
import org.apache.hadoop.hive.metastore.api.NotificationEventRequest;
import org.apache.hadoop.hive.metastore.api.NotificationEventResponse;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.PrincipalType;
import org.apache.hadoop.hive.metastore.api.PrivilegeBag;
import org.apache.hadoop.hive.metastore.api.PrivilegeGrantInfo;
import org.apache.hadoop.hive.metastore.api.Role;
import org.apache.hadoop.hive.metastore.api.SerDeInfo;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.client.builder.DatabaseBuilder;
import org.apache.hadoop.hive.metastore.client.builder.HiveObjectPrivilegeBuilder;
import org.apache.hadoop.hive.metastore.client.builder.HiveObjectRefBuilder;
import org.apache.hadoop.hive.metastore.client.builder.PartitionBuilder;
import org.apache.hadoop.hive.metastore.client.builder.PrivilegeGrantInfoBuilder;
import org.apache.hadoop.hive.metastore.client.builder.TableBuilder;
import org.apache.hadoop.hive.metastore.messaging.EventMessage;
import org.apache.hadoop.hive.ql.io.sarg.SearchArgument;
import org.apache.hadoop.hive.serde.serdeConstants;
import org.apache.hadoop.hive.serde2.typeinfo.PrimitiveTypeInfo;
import org.apache.thrift.TException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.Mockito;

import javax.jdo.Query;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.Executors.newFixedThreadPool;

public class TestObjectStore {
  private ObjectStore objectStore = null;
  private HiveConf conf;

  private static final String DB1 = "testobjectstoredb1";
  private static final String DB2 = "testobjectstoredb2";
  private static final String TABLE1 = "testobjectstoretable1";
  private static final String KEY1 = "testobjectstorekey1";
  private static final String KEY2 = "testobjectstorekey2";
  private static final String OWNER = "testobjectstoreowner";
  private static final String USER1 = "testobjectstoreuser1";
  private static final String ROLE1 = "testobjectstorerole1";
  private static final String ROLE2 = "testobjectstorerole2";

  public static class MockPartitionExpressionProxy implements PartitionExpressionProxy {
    @Override
    public String convertExprToFilter(byte[] expr) throws MetaException {
      return null;
    }

    @Override
    public boolean filterPartitionsByExpr(List<String> partColumnNames,
        List<PrimitiveTypeInfo> partColumnTypeInfos, byte[] expr,
        String defaultPartitionName, List<String> partitionNames)
        throws MetaException {
      return false;
    }

    @Override
    public FileMetadataExprType getMetadataType(String inputFormat) {
      return null;
    }

    @Override
    public SearchArgument createSarg(byte[] expr) {
      return null;
    }

    @Override
    public FileFormatProxy getFileFormatProxy(FileMetadataExprType type) {
      return null;
    }
  }

  @Before
  public void setUp() throws Exception {
    conf = new HiveConf();
    conf.setVar(HiveConf.ConfVars.METASTORE_EXPRESSION_PROXY_CLASS, MockPartitionExpressionProxy.class.getName());

    objectStore = new ObjectStore();
    objectStore.setConf(conf);
    dropAllStoreObjects(objectStore);
  }

  @After
  public void tearDown() {
    // Clear the SSL system properties before each test.
    System.clearProperty(ObjectStore.TRUSTSTORE_PATH_KEY);
    System.clearProperty(ObjectStore.TRUSTSTORE_PASSWORD_KEY);
    System.clearProperty(ObjectStore.TRUSTSTORE_TYPE_KEY);
  }

  /**
   * Test notification operations
   */
  @Test
  public void testNotificationOps() throws InterruptedException {
    final int NO_EVENT_ID = 0;
    final int FIRST_EVENT_ID = 1;
    final int SECOND_EVENT_ID = 2;

    NotificationEvent event =
        new NotificationEvent(0, 0, EventMessage.EventType.CREATE_DATABASE.toString(), "");
    NotificationEventResponse eventResponse;
    CurrentNotificationEventId eventId;

    // Verify that there is no notifications available yet
    eventId = objectStore.getCurrentNotificationEventId();
    Assert.assertEquals(NO_EVENT_ID, eventId.getEventId());

    // Verify that addNotificationEvent() updates the NotificationEvent with the new event ID
    objectStore.addNotificationEvent(event);
    Assert.assertEquals(FIRST_EVENT_ID, event.getEventId());
    objectStore.addNotificationEvent(event);
    Assert.assertEquals(SECOND_EVENT_ID, event.getEventId());

    // Verify that objectStore fetches the latest notification event ID
    eventId = objectStore.getCurrentNotificationEventId();
    Assert.assertEquals(SECOND_EVENT_ID, eventId.getEventId());

    // Verify that getNextNotification() returns all events
    eventResponse = objectStore.getNextNotification(new NotificationEventRequest());
    Assert.assertEquals(2, eventResponse.getEventsSize());
    Assert.assertEquals(FIRST_EVENT_ID, eventResponse.getEvents().get(0).getEventId());
    Assert.assertEquals(SECOND_EVENT_ID, eventResponse.getEvents().get(1).getEventId());

    // Verify that getNextNotification(last) returns events after a specified event
    eventResponse = objectStore.getNextNotification(new NotificationEventRequest(FIRST_EVENT_ID));
    Assert.assertEquals(1, eventResponse.getEventsSize());
    Assert.assertEquals(SECOND_EVENT_ID, eventResponse.getEvents().get(0).getEventId());

    // Verify that getNextNotification(last) returns zero events if there are no more notifications available
    eventResponse = objectStore.getNextNotification(new NotificationEventRequest(SECOND_EVENT_ID));
    Assert.assertEquals(0, eventResponse.getEventsSize());

    // Verify that cleanNotificationEvents() cleans up all old notifications
    Thread.sleep(1);
    objectStore.cleanNotificationEvents(1);
    eventResponse = objectStore.getNextNotification(new NotificationEventRequest());
    Assert.assertEquals(0, eventResponse.getEventsSize());
  }

  /**
   * Test metastore configuration property METASTORE_MAX_EVENT_RESPONSE
   */
  @Test
  public void testMaxEventResponse() throws InterruptedException, MetaException {
    NotificationEvent event = new NotificationEvent(0, 0, EventMessage.EventType.CREATE_DATABASE.toString(), "");
    HiveConf.setIntVar(conf, HiveConf.ConfVars.METASTORE_MAX_EVENT_RESPONSE, 1);
    ObjectStore objs = new ObjectStore();
    objs.setConf(conf);
    // Verify if METASTORE_MAX_EVENT_RESPONSE will limit number of events to respond
    for (int i = 0; i < 3; i++) {
      objs.addNotificationEvent(event);
    }
    NotificationEventResponse eventResponse = objs.getNextNotification(new NotificationEventRequest());
    Assert.assertEquals(1, eventResponse.getEventsSize());
  }

  /**
   * Test concurrent updates to notifications.<p>
   *
   * The test uses N threads to concurrently add M notifications.
   * It assumes that no other thread modifies nodifications, but there are no assumptions
   * about the initial state of notification table.<p>
   *
   * The following assertions are verified:
   * <ul>
   *   <li>Correct number of events are added in the table</li>
   *   <li>There are no duplicate events</li>
   *   <li>There are no holes</li>
   *   <li>Events returned by getNextNotification() have all the new events in increasing order</li>
   * </ul>
   *
   * @throws ExecutionException
   * @throws InterruptedException
   */
  @Ignore(
      "This test is here to allow testing with other databases like mysql / postgres etc\n"
          + " with  user changes to the code. This cannot be run on apache derby because of\n"
          + " https://db.apache.org/derby/docs/10.10/devguide/cdevconcepts842385.html"
  )
  @Test
  public void testConcurrentNotifications() throws ExecutionException, InterruptedException {

    final int NUM_THREADS = 4;
    final int NUM_EVENTS = 200;
    // Barrier is used to ensure that all threads start race at the same time
    final CyclicBarrier barrier = new CyclicBarrier(NUM_THREADS);
    ExecutorService executor = newFixedThreadPool(NUM_THREADS);

    final HiveConf conf = new HiveConf();
    conf.setVar(HiveConf.ConfVars.METASTORE_EXPRESSION_PROXY_CLASS, MockPartitionExpressionProxy.class.getName());

    /*
     * To tun these tests on real DB you need to
     * - make sure NOTIFICATION_SEQUENCE schema is initialized
     * - Uncomment the following settings and fill appropriate values for your setup.
     * You also need to add test dependency on mysql-connector driver.
     */
    // conf.setVar(HiveConf.ConfVars.METASTORE_CONNECTION_DRIVER,  "com.mysql.jdbc.Driver");
    // conf.setVar(HiveConf.ConfVars.METASTORECONNECTURLKEY,
    //   "jdbc:mysql://<HOST>:3306/<DB>");
    // conf.setVar(HiveConf.ConfVars.METASTORE_CONNECTION_USER_NAME, "<USER>");
    // conf.setVar(HiveConf.ConfVars.METASTOREPWD, "<PASSWORD>");

    // We can't rely on this.objectSTore because we are not using derby
    ObjectStore myStore = new ObjectStore();
    myStore.setConf(conf);

    // Get current notification value. We assume that no one else modifies notifications
    long currentEventId = myStore.getCurrentNotificationEventId().getEventId();

    // Add NUM_EVENTS notifications and return list if notification IDs added
    Callable<List<Long>> addNotifications = new Callable<List<Long>>() {
      @Override
      public List<Long> call() throws Exception {
        // We need thread-local object store
        ObjectStore store = new ObjectStore();
        store.setConf(conf);
        List<Long> result = new ArrayList<>(NUM_EVENTS);
        NotificationEvent event =
            new NotificationEvent(0, 0, EventMessage.EventType.CREATE_DATABASE.toString(), "");

        // Prepare for the race
        barrier.await();
        // Fun part begins
        for (int i = 1; i < NUM_EVENTS; i++) {
          store.addNotificationEvent(event);
          long evId = store.getCurrentNotificationEventId().getEventId();
          // Make sure events do not jump backwards
          Assert.assertTrue(evId >= event.getEventId());
          result.add(event.getEventId());
        }
        return result;
      }
    };

    List<Future<List<Long>>> results = new ArrayList<>(NUM_THREADS);

    // Submit work for all threads
    for (int i = 0; i < NUM_THREADS; i++) {
      results.add(executor.submit(addNotifications));
    }

    // Collect all results in a map which counts number of times each notification ID is used.
    // Later we verify that each count is one
    Map<Long, Integer> ids = new HashMap<>();
    for (Future<List<Long>> r: results) {
      List<Long> values = r.get();
      for (Long value: values) {
        Integer oldVal = ids.get(value);
        Assert.assertNull(oldVal);
        if (oldVal == null) {
          ids.put(value, 1);
        } else {
          ids.put(value, oldVal + 1);
        }
      }
    }

    // By now all the async work is complete, so we can safely shut down the executor
    executor.shutdownNow();

    // Get latest notification ID
    long lastEventId = myStore.getCurrentNotificationEventId().getEventId();

    Assert.assertEquals(NUM_THREADS * (NUM_EVENTS - 1), lastEventId - currentEventId);
    for(long evId = currentEventId + 1; evId <= lastEventId; evId++) {
      Integer count = ids.get(evId);
      Assert.assertNotNull(count);
      Assert.assertEquals(1L, count.longValue());
    }

    // Certify that all notifications returned from getNextNotification() are present
    // and properly ordered.
    NotificationEventResponse eventResponse =
        myStore.getNextNotification(new NotificationEventRequest(currentEventId));
    long prevId = currentEventId;
    for (NotificationEvent e: eventResponse.getEvents()) {
      Assert.assertEquals(prevId + 1, e.getEventId());
      prevId = e.getEventId();
      Integer count = ids.get(e.getEventId());
      Assert.assertNotNull(count);
      Assert.assertEquals(1L, count.longValue());
    }
    Assert.assertEquals(prevId, lastEventId);
  }

  /**
   * Test the concurrent drop of same partition would leak transaction.
   * https://issues.apache.org/jira/browse/HIVE-16839
   *
   * Note: the leak happens during a race condition, this test case tries
   * to simulate the race condition on best effort, it have two threads trying
   * to drop the same set of partitions
   */
  @Test
  public void testConcurrentDropPartitions() throws MetaException, InvalidObjectException {
    Database db1 = new DatabaseBuilder()
        .setName(DB1)
        .setDescription("description")
        .setLocation("locationurl")
        .build();
    objectStore.createDatabase(db1);
    StorageDescriptor sd = createFakeSd("location");
    HashMap<String, String> tableParams = new HashMap<>();
    tableParams.put("EXTERNAL", "false");
    FieldSchema partitionKey1 = new FieldSchema("Country", serdeConstants.STRING_TYPE_NAME, "");
    FieldSchema partitionKey2 = new FieldSchema("State", serdeConstants.STRING_TYPE_NAME, "");
    Table tbl1 =
        new Table(TABLE1, DB1, "owner", 1, 2, 3, sd, Arrays.asList(partitionKey1, partitionKey2),
            tableParams, null, null, "MANAGED_TABLE");
    objectStore.createTable(tbl1);
    HashMap<String, String> partitionParams = new HashMap<>();
    partitionParams.put("PARTITION_LEVEL_PRIVILEGE", "true");

    // Create some partitions
    List<List<String>> partNames = new LinkedList<>();
    for (char c = 'A'; c < 'Z'; c++) {
      String name = "" + c;
      partNames.add(Arrays.asList(name, name));
    }
    for (List<String> n : partNames) {
      Partition p = new Partition(n, DB1, TABLE1, 111, 111, sd, partitionParams);
      objectStore.addPartition(p);
    }

    int numThreads = 2;
    ExecutorService executorService = Executors.newFixedThreadPool(numThreads);
    for (int i = 0; i < numThreads; i++) {
      executorService.execute(
          () -> {
            for (List<String> p : partNames) {
              try {
                objectStore.dropPartition(DB1, TABLE1, p);
                System.out.println("Dropping partition: " + p.get(0));
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            }
          }
      );
    }

    executorService.shutdown();
    try {
      executorService.awaitTermination(30, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      Assert.assertTrue("Got interrupted.", false);
    }
    Assert.assertTrue("Expect no active transactions.", !objectStore.isActiveTransaction());
  }

  private StorageDescriptor createFakeSd(String location) {
    return new StorageDescriptor(null, location, null, null, false, 0,
        new SerDeInfo("SerDeName", "serializationLib", null), null, null, null);
  }

  /**
   * Test database operations
   */
  @Test
  public void testDatabaseOps() throws MetaException, InvalidObjectException, NoSuchObjectException {
    Database db1 = new Database(DB1, "description", "locationurl", null);
    Database db2 = new Database(DB2, "description", "locationurl", null);
    objectStore.createDatabase(db1);
    objectStore.createDatabase(db2);

    List<String> databases = objectStore.getAllDatabases();
    Assert.assertEquals(2, databases.size());
    Assert.assertEquals(DB1, databases.get(0));
    Assert.assertEquals(DB2, databases.get(1));

    objectStore.dropDatabase(DB1);
    databases = objectStore.getAllDatabases();
    Assert.assertEquals(1, databases.size());
    Assert.assertEquals(DB2, databases.get(0));

    objectStore.dropDatabase(DB2);
  }

  /**
   * Test table operations
   */
  @Test
  public void testTableOps() throws MetaException, InvalidObjectException, NoSuchObjectException, InvalidInputException {
    Database db1 = new Database(DB1, "description", "locationurl", null);
    objectStore.createDatabase(db1);
    StorageDescriptor sd = new StorageDescriptor(null, "location", null, null, false, 0, new SerDeInfo("SerDeName", "serializationLib", null), null, null, null);
    HashMap<String,String> params = new HashMap<String,String>();
    params.put("EXTERNAL", "false");
    Table tbl1 = new Table(TABLE1, DB1, "owner", 1, 2, 3, sd, null, params, "viewOriginalText", "viewExpandedText", "MANAGED_TABLE");
    objectStore.createTable(tbl1);

    List<String> tables = objectStore.getAllTables(DB1);
    Assert.assertEquals(1, tables.size());
    Assert.assertEquals(TABLE1, tables.get(0));

    Table newTbl1 = new Table("new" + TABLE1, DB1, "owner", 1, 2, 3, sd, null, params, "viewOriginalText", "viewExpandedText", "MANAGED_TABLE");

    // Change different fields and verify they were altered
    newTbl1.setOwner("role1");
    newTbl1.setOwnerType(PrincipalType.ROLE);

    objectStore.alterTable(DB1, TABLE1, newTbl1);
    tables = objectStore.getTables(DB1, "new*");
    Assert.assertEquals(1, tables.size());
    Assert.assertEquals("new" + TABLE1, tables.get(0));

    // Verify fields were altered during the alterTable operation
    Table alteredTable = objectStore.getTable(DB1, "new" + TABLE1);
    Assert.assertEquals("Owner of table was not altered", newTbl1.getOwner(), alteredTable.getOwner());
    Assert.assertEquals("Owner type of table was not altered", newTbl1.getOwnerType(), alteredTable.getOwnerType());

    objectStore.dropTable(DB1, "new" + TABLE1);
    tables = objectStore.getAllTables(DB1);
    Assert.assertEquals(0, tables.size());

    objectStore.dropDatabase(DB1);
  }

  /**
   * Tests partition operations
   */
  @Test
  public void testPartitionOps() throws MetaException, InvalidObjectException, NoSuchObjectException, InvalidInputException {
    Database db1 = new Database(DB1, "description", "locationurl", null);
    objectStore.createDatabase(db1);
    StorageDescriptor sd = new StorageDescriptor(null, "location", null, null, false, 0, new SerDeInfo("SerDeName", "serializationLib", null), null, null, null);
    HashMap<String,String> tableParams = new HashMap<String,String>();
    tableParams.put("EXTERNAL", "false");
    FieldSchema partitionKey1 = new FieldSchema("Country", serdeConstants.STRING_TYPE_NAME, "");
    FieldSchema partitionKey2 = new FieldSchema("State", serdeConstants.STRING_TYPE_NAME, "");
    Table tbl1 = new Table(TABLE1, DB1, "owner", 1, 2, 3, sd, Arrays.asList(partitionKey1, partitionKey2), tableParams, "viewOriginalText", "viewExpandedText", "MANAGED_TABLE");
    objectStore.createTable(tbl1);
    HashMap<String, String> partitionParams = new HashMap<String, String>();
    partitionParams.put("PARTITION_LEVEL_PRIVILEGE", "true");
    List<String> value1 = Arrays.asList("US", "CA");
    Partition part1 = new Partition(value1, DB1, TABLE1, 111, 111, sd, partitionParams);
    objectStore.addPartition(part1);
    List<String> value2 = Arrays.asList("US", "MA");
    Partition part2 = new Partition(value2, DB1, TABLE1, 222, 222, sd, partitionParams);
    objectStore.addPartition(part2);

    Deadline.startTimer("getPartition");
    List<Partition> partitions = objectStore.getPartitions(DB1, TABLE1, 10);
    Assert.assertEquals(2, partitions.size());
    Assert.assertEquals(111, partitions.get(0).getCreateTime());
    Assert.assertEquals(222, partitions.get(1).getCreateTime());

    int numPartitions  = objectStore.getNumPartitionsByFilter(DB1, TABLE1, "");
    Assert.assertEquals(partitions.size(), numPartitions);

    numPartitions  = objectStore.getNumPartitionsByFilter(DB1, TABLE1, "country = \"US\"");
    Assert.assertEquals(2, numPartitions);

    objectStore.dropPartition(DB1, TABLE1, value1);
    partitions = objectStore.getPartitions(DB1, TABLE1, 10);
    Assert.assertEquals(1, partitions.size());
    Assert.assertEquals(222, partitions.get(0).getCreateTime());

    objectStore.dropPartition(DB1, TABLE1, value2);
    objectStore.dropTable(DB1, TABLE1);
    objectStore.dropDatabase(DB1);
  }

  /**
   * Checks if the JDO cache is able to handle directSQL partition drops in one session.
   * @throws MetaException
   * @throws InvalidObjectException
   * @throws NoSuchObjectException
   * @throws SQLException
   */
  @Test
  public void testDirectSQLDropPartitionsCacheInSession()
      throws TException {
    createPartitionedTable(false, false);
    // query the partitions with JDO
    Deadline.startTimer("getPartition");
    List<Partition> partitions = objectStore.getPartitionsInternal(DB1, TABLE1,
        10, false, true);
    Assert.assertEquals(3, partitions.size());

    // drop partitions with directSql
    objectStore.dropPartitionsInternal(DB1, TABLE1,
        Arrays.asList("test_part_col=a0", "test_part_col=a1"), true, false);

    // query the partitions with JDO, checking the cache is not causing any problem
    partitions = objectStore.getPartitionsInternal(DB1, TABLE1,
        10, false, true);
    Assert.assertEquals(1, partitions.size());
  }

  /**
   * Checks if the JDO cache is able to handle directSQL partition drops cross sessions.
   * @throws MetaException
   * @throws InvalidObjectException
   * @throws NoSuchObjectException
   * @throws SQLException
   */
  @Test
  public void testDirectSQLDropPartitionsCacheCrossSession()
      throws TException {
    ObjectStore objectStore2 = new ObjectStore();
    objectStore2.setConf(objectStore.getConf());

    createPartitionedTable(false, false);
    // query the partitions with JDO in the 1st session
    Deadline.startTimer("getPartition");
    List<Partition> partitions = objectStore.getPartitionsInternal(DB1, TABLE1,
        10, false, true);
    Assert.assertEquals(3, partitions.size());

    // query the partitions with JDO in the 2nd session
    partitions = objectStore2.getPartitionsInternal(DB1, TABLE1,10,
        false, true);
    Assert.assertEquals(3, partitions.size());

    // drop partitions with directSql in the 1st session
    objectStore.dropPartitionsInternal(DB1, TABLE1,
        Arrays.asList("test_part_col=a0", "test_part_col=a1"), true, false);

    // query the partitions with JDO in the 2nd session, checking the cache is not causing any
    // problem
    partitions = objectStore2.getPartitionsInternal(DB1, TABLE1,
        10, false, true);
    Assert.assertEquals(1, partitions.size());
  }

  /**
   * Checks if the directSQL partition drop removes every connected data from the RDBMS tables.
   * @throws MetaException
   * @throws InvalidObjectException
   * @throws NoSuchObjectException
   * @throws SQLException
   */
  @Test
  public void testDirectSQLDropPartitionsCleanup() throws TException,
      SQLException {

    createPartitionedTable(true, true);

    // Check, that every table in the expected state before the drop
    checkBackendTableSize("PARTITIONS", 3);
    checkBackendTableSize("PART_PRIVS", 3);
    checkBackendTableSize("PART_COL_PRIVS", 3);
    checkBackendTableSize("PART_COL_STATS", 3);
    checkBackendTableSize("PARTITION_PARAMS", 3);
    checkBackendTableSize("PARTITION_KEY_VALS", 3);
    checkBackendTableSize("SD_PARAMS", 3);
    checkBackendTableSize("BUCKETING_COLS", 3);
    checkBackendTableSize("SKEWED_COL_NAMES", 3);
    checkBackendTableSize("SDS", 4); // Table has an SDS
    checkBackendTableSize("SORT_COLS", 3);
    checkBackendTableSize("SERDE_PARAMS", 3);
    checkBackendTableSize("SERDES", 4); // Table has a serde

    // drop the partitions
    Deadline.startTimer("dropPartitions");
    objectStore.dropPartitionsInternal(DB1, TABLE1,
        Arrays.asList("test_part_col=a0", "test_part_col=a1", "test_part_col=a2"), true, false);

    // Check, if every data is dropped connected to the partitions
    checkBackendTableSize("PARTITIONS", 0);
    checkBackendTableSize("PART_PRIVS", 0);
    checkBackendTableSize("PART_COL_PRIVS", 0);
    checkBackendTableSize("PART_COL_STATS", 0);
    checkBackendTableSize("PARTITION_PARAMS", 0);
    checkBackendTableSize("PARTITION_KEY_VALS", 0);
    checkBackendTableSize("SD_PARAMS", 0);
    checkBackendTableSize("BUCKETING_COLS", 0);
    checkBackendTableSize("SKEWED_COL_NAMES", 0);
    checkBackendTableSize("SDS", 1); // Table has an SDS
    checkBackendTableSize("SORT_COLS", 0);
    checkBackendTableSize("SERDE_PARAMS", 0);
    checkBackendTableSize("SERDES", 1); // Table has a serde
  }

  /**
   * Creates DB1 database, TABLE1 table with 3 partitions
   * @param withPrivileges Should we create privileges as well
   * @param withStatistics Should we create statitics as well
   * @throws MetaException
   * @throws InvalidObjectException
   */
  private void createPartitionedTable(boolean withPrivileges, boolean withStatistics) throws TException {
    Database db1 = new DatabaseBuilder()
        .setName(DB1)
        .setDescription("description")
        .setLocation("locationurl")
        .build();
    objectStore.createDatabase(db1);
    Table tbl1 =
        new TableBuilder()
            .setDbName(DB1)
            .setTableName(TABLE1)
            .addCol("test_col1", "int")
            .addCol("test_col2", "int")
            .addPartCol("test_part_col", "int")
            .addCol("test_bucket_col", "int", "test bucket col comment")
            .addCol("test_skewed_col", "int", "test skewed col comment")
            .addCol("test_sort_col", "int", "test sort col comment")
            .build();
    objectStore.createTable(tbl1);

    PrivilegeBag privilegeBag = new PrivilegeBag();

    // Create partitions for the partitioned table
    for(int i=0; i < 3; i++) {
      Partition part = new PartitionBuilder()
          .fromTable(tbl1)
          .addValue("a" + i)
          .addSerdeParam("serdeParam", "serdeParamValue")
          .addStorageDescriptorParam("sdParam", "sdParamValue")
          .addBucketCol("test_bucket_col")
          .addSkewedColName("test_skewed_col")
          .addSortCol("test_sort_col", 1)
          .build();
      objectStore.addPartition(part);

      if (withPrivileges) {
        HiveObjectRef partitionReference = new HiveObjectRefBuilder().buildPartitionReference(part);
        HiveObjectRef partitionColumnReference = new HiveObjectRefBuilder()
            .buildPartitionColumnReference(tbl1, "test_part_col", part.getValues());
        PrivilegeGrantInfo privilegeGrantInfo = new PrivilegeGrantInfoBuilder()
            .setPrivilege("a")
            .build();
        HiveObjectPrivilege partitionPriv = new HiveObjectPrivilegeBuilder()
            .setHiveObjectRef(partitionReference)
            .setPrincipleName("a")
            .setPrincipalType(PrincipalType.USER)
            .setGrantInfo(privilegeGrantInfo)
            .build();
        privilegeBag.addToPrivileges(partitionPriv);
        HiveObjectPrivilege partitionColPriv = new HiveObjectPrivilegeBuilder()
            .setHiveObjectRef(partitionColumnReference)
            .setPrincipleName("a")
            .setPrincipalType(PrincipalType.USER)
            .setGrantInfo(privilegeGrantInfo)
            .build();
        privilegeBag.addToPrivileges(partitionColPriv);
      }

      if (withStatistics) {
        ColumnStatistics stats = new ColumnStatistics();
        ColumnStatisticsDesc desc = new ColumnStatisticsDesc();
        desc.setDbName(tbl1.getDbName());
        desc.setTableName(tbl1.getTableName());
        desc.setPartName("test_part_col=a" + i);
        stats.setStatsDesc(desc);

        List<ColumnStatisticsObj> statsObjList = new ArrayList<>(1);
        stats.setStatsObj(statsObjList);

        ColumnStatisticsData data = new ColumnStatisticsData();
        BooleanColumnStatsData boolStats = new BooleanColumnStatsData();
        boolStats.setNumTrues(0);
        boolStats.setNumFalses(0);
        boolStats.setNumNulls(0);
        data.setBooleanStats(boolStats);

        ColumnStatisticsObj partStats = new ColumnStatisticsObj("test_part_col", "int", data);
        statsObjList.add(partStats);

        objectStore.updatePartitionColumnStatistics(stats, part.getValues());
      }
    }

    if (withPrivileges) {
      objectStore.grantPrivileges(privilegeBag);
    }
  }

  /**
   * Checks if the HMS backend db row number is as expected. If they are not, an
   * {@link AssertionError} is thrown.
   * @param tableName The table in which we count the rows
   * @param size The expected row number
   * @throws SQLException If there is a problem connecting to / querying the backend DB
   */
  private void checkBackendTableSize(String tableName, int size) throws SQLException {
    String connectionStr = HiveConf.getVar(objectStore.getConf(), HiveConf.ConfVars.METASTORECONNECTURLKEY);
    Connection conn = DriverManager.getConnection(connectionStr);
    Statement stmt = conn.createStatement();

    ResultSet rs = stmt.executeQuery("SELECT COUNT(1) FROM " + tableName);
    rs.next();
    Assert.assertEquals(tableName + " table should contain " + size + " rows", size,
        rs.getLong(1));
  }

  /**
   * Test master keys operation
   */
  @Test
  public void testMasterKeyOps() throws MetaException, NoSuchObjectException {
    int id1 = objectStore.addMasterKey(KEY1);
    int id2 = objectStore.addMasterKey(KEY2);

    String[] keys = objectStore.getMasterKeys();
    Assert.assertEquals(2, keys.length);
    Assert.assertEquals(KEY1, keys[0]);
    Assert.assertEquals(KEY2, keys[1]);

    objectStore.updateMasterKey(id1, "new" + KEY1);
    objectStore.updateMasterKey(id2, "new" + KEY2);
    keys = objectStore.getMasterKeys();
    Assert.assertEquals(2, keys.length);
    Assert.assertEquals("new" + KEY1, keys[0]);
    Assert.assertEquals("new" + KEY2, keys[1]);

    objectStore.removeMasterKey(id1);
    keys = objectStore.getMasterKeys();
    Assert.assertEquals(1, keys.length);
    Assert.assertEquals("new" + KEY2, keys[0]);

    objectStore.removeMasterKey(id2);
  }

  /**
   * Test role operation
   */
  @Test
  public void testRoleOps() throws InvalidObjectException, MetaException, NoSuchObjectException {
    objectStore.addRole(ROLE1, OWNER);
    objectStore.addRole(ROLE2, OWNER);
    List<String> roles = objectStore.listRoleNames();
    Assert.assertEquals(2, roles.size());
    Assert.assertEquals(ROLE2, roles.get(1));
    Role role1 = objectStore.getRole(ROLE1);
    Assert.assertEquals(OWNER, role1.getOwnerName());
    objectStore.grantRole(role1, USER1, PrincipalType.USER, OWNER, PrincipalType.ROLE, true);
    objectStore.revokeRole(role1, USER1, PrincipalType.USER, false);
    objectStore.removeRole(ROLE1);
  }

  @Test
  public void testDirectSqlErrorMetrics() throws Exception {
    HiveConf conf = new HiveConf();
    conf.setBoolVar(HiveConf.ConfVars.HIVE_SERVER2_METRICS_ENABLED, true);
    conf.setVar(HiveConf.ConfVars.HIVE_METRICS_REPORTER, MetricsReporting.JSON_FILE.name()
        + "," + MetricsReporting.JMX.name());

    MetricsFactory.init(conf);
    CodahaleMetrics metrics = (CodahaleMetrics) MetricsFactory.getInstance();

    objectStore.new GetDbHelper("foo", null, true, true) {
      @Override
      protected Database getSqlResult(ObjectStore.GetHelper<Database> ctx) throws MetaException {
        return null;
      }

      @Override
      protected Database getJdoResult(ObjectStore.GetHelper<Database> ctx) throws MetaException,
          NoSuchObjectException {
        return null;
      }
    }.run(false);

    String json = metrics.dumpJson();
    MetricsTestUtils.verifyMetricsJson(json, MetricsTestUtils.COUNTER,
        MetricsConstant.DIRECTSQL_ERRORS, "");

    objectStore.new GetDbHelper("foo", null, true, true) {
      @Override
      protected Database getSqlResult(ObjectStore.GetHelper<Database> ctx) throws MetaException {
        throw new RuntimeException();
      }

      @Override
      protected Database getJdoResult(ObjectStore.GetHelper<Database> ctx) throws MetaException,
          NoSuchObjectException {
        return null;
      }
    }.run(false);

    json = metrics.dumpJson();
    MetricsTestUtils.verifyMetricsJson(json, MetricsTestUtils.COUNTER,
        MetricsConstant.DIRECTSQL_ERRORS, 1);
  }

  public static void dropAllStoreObjects(RawStore store) throws MetaException, InvalidObjectException, InvalidInputException {
    try {
      Deadline.registerIfNot(100000);
      List<Function> funcs = store.getAllFunctions();
      for (Function func : funcs) {
        store.dropFunction(func.getDbName(), func.getFunctionName());
      }
      List<String> dbs = store.getAllDatabases();
      for (int i = 0; i < dbs.size(); i++) {
        String db = dbs.get(i);
        List<String> tbls = store.getAllTables(db);
        for (String tbl : tbls) {
          List<Index> indexes = store.getIndexes(db, tbl, 100);
          for (Index index : indexes) {
            store.dropIndex(db, tbl, index.getIndexName());
          }
        }
        for (String tbl : tbls) {
          Deadline.startTimer("getPartition");
          List<Partition> parts = store.getPartitions(db, tbl, 100);
          for (Partition part : parts) {
            store.dropPartition(db, tbl, part.getValues());
          }
          store.dropTable(db, tbl);
        }
        store.dropDatabase(db);
      }
      List<String> roles = store.listRoleNames();
      for (String role : roles) {
        store.removeRole(role);
      }
    } catch (NoSuchObjectException e) {
    }
  }

  @Test
  public void testQueryCloseOnError() throws Exception {
    ObjectStore spy = Mockito.spy(objectStore);
    spy.getAllDatabases();
    spy.getAllFunctions();
    spy.getAllTables(DB1);
    spy.getPartitionCount();
    Mockito.verify(spy, Mockito.times(3))
        .rollbackAndCleanup(Mockito.anyBoolean(), Mockito.<Query>anyObject());
  }

  /**
   * Test the SSL configuration parameters to ensure that they modify the Java system properties correctly.
   */
  @Test
  public void testSSLPropertiesAreSet() {
    setAndCheckSSLProperties(true, "/tmp/truststore.p12", "password", "pkcs12");
  }

  /**
   * Test the property {@link HiveConf.ConfVars#METASTORE_DBACCESS_USE_SSL} to ensure that it correctly
   * toggles whether or not the SSL configuration parameters will be set. Effectively, this is testing whether
   * SSL can be turned on/off correctly.
   */
  @Test
  public void testUseSSLProperty() {
    setAndCheckSSLProperties(false, "/tmp/truststore.jks", "password", "jks");
  }

  /**
   * Test that the deprecated property {@link HiveConf.ConfVars#METASTORE_DBACCESS_SSL_PROPS} is overwritten by the
   * HiveConf.ConfVars#METASTORE_DBACCESS_SSL_* properties if both are set.
   *
   * This is not an ideal scenario. It is highly recommend to only set the HiveConf.ConfVars#METASTORE_DBACCESS_SSL_* properties.
   */
  @Test
  public void testDeprecatedConfigIsOverwritten() {
    // Different from the values in the safe config
    HiveConf.setVar(conf, HiveConf.ConfVars.METASTORE_DBACCESS_SSL_PROPS,
        ObjectStore.TRUSTSTORE_PATH_KEY + "=/tmp/truststore.p12," + ObjectStore.TRUSTSTORE_PASSWORD_KEY + "=pwd," +
            ObjectStore.TRUSTSTORE_TYPE_KEY + "=pkcs12");;

    // Safe config
    setAndCheckSSLProperties(true, "/tmp/truststore.jks", "password", "jks");
  }

  /**
   * Test that providing an empty truststore path and truststore password will not throw an exception.
   */
  @Test
  public void testEmptyTrustStoreProps() {
    setAndCheckSSLProperties(true, "", "", "jks");
  }

  /**
   * Helper method for setting and checking the SSL configuration parameters.
   * @param useSSL whether or not SSL is enabled
   * @param trustStorePath truststore path, corresponding to the value for {@link HiveConf.ConfVars#METASTORE_DBACCESS_SSL_TRUSTSTORE_PATH}
   * @param trustStorePassword truststore password, corresponding to the value for {@link HiveConf.ConfVars#METASTORE_DBACCESS_SSL_TRUSTSTORE_PASSWORD}
   * @param trustStoreType truststore type, corresponding to the value for {@link HiveConf.ConfVars#METASTORE_DBACCESS_SSL_TRUSTSTORE_TYPE}
   */
  private void setAndCheckSSLProperties(boolean useSSL, String trustStorePath, String trustStorePassword, String trustStoreType) {
    HiveConf.setBoolVar(conf, HiveConf.ConfVars.METASTORE_DBACCESS_USE_SSL, useSSL);
    HiveConf.setVar(conf, HiveConf.ConfVars.METASTORE_DBACCESS_SSL_TRUSTSTORE_PATH, trustStorePath);
    HiveConf.setVar(conf, HiveConf.ConfVars.METASTORE_DBACCESS_SSL_TRUSTSTORE_PASSWORD, trustStorePassword);
    HiveConf.setVar(conf, HiveConf.ConfVars.METASTORE_DBACCESS_SSL_TRUSTSTORE_TYPE, trustStoreType);
    objectStore.setConf(conf); // Calls configureSSL()

    // Check that the Java system values correspond to the values that we set
    // Check that the properties were set correctly
    checkSSLProperty(useSSL, ObjectStore.TRUSTSTORE_PATH_KEY, trustStorePath);
    checkSSLProperty(useSSL, ObjectStore.TRUSTSTORE_PASSWORD_KEY, trustStorePassword);
    checkSSLProperty(useSSL, ObjectStore.TRUSTSTORE_TYPE_KEY, trustStoreType);
  }

  /**
   * Helper method to check whether the Java system properties were set correctly in {@link ObjectStore#configureSSL(Configuration)}
   * @param useSSL whether or not SSL is enabled
   * @param key Java system property key
   * @param value Java system property value indicated by the key
   */
  private void checkSSLProperty(boolean useSSL, String key, String value) {
    if (useSSL && !value.isEmpty()) {
      Assert.assertEquals(value, System.getProperty(key));
    } else {
      Assert.assertNull(System.getProperty(key));
    }
  }
}
