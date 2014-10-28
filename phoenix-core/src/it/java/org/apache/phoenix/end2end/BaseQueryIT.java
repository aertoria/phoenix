/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.end2end;

import static org.apache.phoenix.util.TestUtil.TEST_PROPERTIES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.hbase.index.write.IndexWriterUtils;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.util.ByteUtil;
import org.apache.phoenix.util.PhoenixRuntime;
import org.apache.phoenix.util.PropertiesUtil;
import org.apache.phoenix.util.ReadOnlyProps;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;



/**
 * 
 * Basic tests for Phoenix JDBC implementation
 *
 * 
 * @since 0.1
 */

@Category(ClientManagedTimeTest.class)
@RunWith(Parameterized.class)
public abstract class BaseQueryIT extends BaseClientManagedTimeIT {
    protected static final String tenantId = getOrganizationId();
    protected static final String ATABLE_INDEX_NAME = "ATABLE_IDX";
    protected static final long BATCH_SIZE = 3;
    
    @BeforeClass
    @Shadower(classBeingShadowed = BaseClientManagedTimeIT.class)
    public static void doSetup() throws Exception {
        Map<String,String> props = getDefaultProps();
        props.put(QueryServices.QUEUE_SIZE_ATTRIB, Integer.toString(5000));
        props.put(IndexWriterUtils.HTABLE_THREAD_KEY, Integer.toString(100));
        // Make a small batch size to test multiple calls to reserve sequences
        props.put(QueryServices.SEQUENCE_CACHE_SIZE_ATTRIB, Long.toString(BATCH_SIZE));
        // Must update config before starting server
        setUpTestDriver(new ReadOnlyProps(props.entrySet().iterator()));
    }
    
    protected long ts;
    protected Date date;
    private String indexDDL;
    
    public BaseQueryIT(String indexDDL) {
        this.indexDDL = indexDDL;
    }
    
    @Before
    public void initTable() throws Exception {
         ts = nextTimestamp();
        initATableValues(tenantId, getDefaultSplits(tenantId), date=new Date(System.currentTimeMillis()), ts);
        if (indexDDL != null && indexDDL.length() > 0) {
            Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
            props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts));
            Connection conn = DriverManager.getConnection(getUrl(), props);
            conn.createStatement().execute(indexDDL);
        }
    }
    
    @Parameters(name="{0}")
    public static Collection<Object> data() {
        List<Object> testCases = Lists.newArrayList();
        testCases.add(new String[] { "CREATE INDEX " + ATABLE_INDEX_NAME + " ON aTable (a_integer DESC) INCLUDE ("
                + "    A_STRING, " + "    B_STRING, " + "    A_DATE)" });
        testCases.add(new String[] { "CREATE INDEX " + ATABLE_INDEX_NAME + " ON aTable (a_integer, a_string) INCLUDE ("
                + "    B_STRING, " + "    A_DATE)" });
        testCases.add(new String[] { "CREATE INDEX " + ATABLE_INDEX_NAME + " ON aTable (a_integer) INCLUDE ("
                + "    A_STRING, " + "    B_STRING, " + "    A_DATE)" });
        testCases.add(new String[] { "" });
        return testCases;
    }
    
    protected void assertValueEqualsResultSet(ResultSet rs, List<Object> expectedResults) throws SQLException {
        List<List<Object>> nestedExpectedResults = Lists.newArrayListWithExpectedSize(expectedResults.size());
        for (Object expectedResult : expectedResults) {
            nestedExpectedResults.add(Arrays.asList(expectedResult));
        }
        assertValuesEqualsResultSet(rs, nestedExpectedResults); 
    }

    /**
     * Asserts that we find the expected values in the result set. We don't know the order, since we don't always
     * have an order by and we're going through indexes, but we assert that each expected result occurs once as
     * expected (in any order).
     */
    protected void assertValuesEqualsResultSet(ResultSet rs, List<List<Object>> expectedResults) throws SQLException {
        int expectedCount = expectedResults.size();
        int count = 0;
        List<List<Object>> actualResults = Lists.newArrayList();
        List<Object> errorResult = null;
        while (rs.next() && errorResult == null) {
            List<Object> result = Lists.newArrayList();
            for (int i = 0; i < rs.getMetaData().getColumnCount(); i++) {
                result.add(rs.getObject(i+1));
            }
            if (!expectedResults.contains(result)) {
                errorResult = result;
            }
            actualResults.add(result);
            count++;
        }
        assertTrue("Could not find " + errorResult + " in expected results: " + expectedResults + " with actual results: " + actualResults, errorResult == null);
        assertEquals(count, expectedCount);
    }
    
    protected void assertOneOfValuesEqualsResultSet(ResultSet rs, List<List<Object>>... expectedResultsArray) throws SQLException {
        List<List<Object>> results = Lists.newArrayList();
        while (rs.next()) {
            List<Object> result = Lists.newArrayList();
            for (int i = 0; i < rs.getMetaData().getColumnCount(); i++) {
                result.add(rs.getObject(i+1));
            }
            results.add(result);
        }
        for (int j = 0; j < expectedResultsArray.length; j++) {
                List<List<Object>> expectedResults = expectedResultsArray[j];
                Set<List<Object>> expectedResultsSet = Sets.newHashSet(expectedResults);
                int count = 0;
                boolean brokeEarly = false;
                for (List<Object> result : results) {
                    if (!expectedResultsSet.contains(result)) {
                        brokeEarly = true;
                        break;
                    }
                    count++;
                }
                if (!brokeEarly && count == expectedResults.size()) {
                    return;
                }
        }
        fail("Unable to find " + results + " in " + Arrays.asList(expectedResultsArray));
    }

    protected static boolean compare(CompareOp op, ImmutableBytesWritable lhsOutPtr, ImmutableBytesWritable rhsOutPtr) {
        int compareResult = Bytes.compareTo(lhsOutPtr.get(), lhsOutPtr.getOffset(), lhsOutPtr.getLength(), rhsOutPtr.get(), rhsOutPtr.getOffset(), rhsOutPtr.getLength());
        return ByteUtil.compare(op, compareResult);
    }
    
    
    private static AtomicInteger runCount = new AtomicInteger(0);
    protected static int nextRunCount() {
        return runCount.getAndAdd(1);
    }
}
