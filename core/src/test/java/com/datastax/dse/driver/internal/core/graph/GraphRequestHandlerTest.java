/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.dse.driver.internal.core.graph;

import static com.datastax.dse.driver.internal.core.graph.GraphProtocol.GRAPHSON_2_0;
import static com.datastax.dse.driver.internal.core.graph.GraphProtocol.GRAPHSON_3_0;
import static com.datastax.dse.driver.internal.core.graph.GraphProtocol.GRAPH_BINARY_1_0;
import static com.datastax.oss.driver.Assertions.assertThat;
import static com.datastax.oss.driver.api.core.type.codec.TypeCodecs.BIGINT;
import static com.datastax.oss.driver.api.core.type.codec.TypeCodecs.TEXT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.datastax.dse.driver.api.core.DseProtocolVersion;
import com.datastax.dse.driver.api.core.config.DseDriverOption;
import com.datastax.dse.driver.api.core.data.geometry.Point;
import com.datastax.dse.driver.api.core.graph.BatchGraphStatement;
import com.datastax.dse.driver.api.core.graph.DseGraph;
import com.datastax.dse.driver.api.core.graph.FluentGraphStatement;
import com.datastax.dse.driver.api.core.graph.GraphNode;
import com.datastax.dse.driver.api.core.graph.GraphResultSet;
import com.datastax.dse.driver.api.core.graph.GraphStatement;
import com.datastax.dse.driver.api.core.graph.ScriptGraphStatement;
import com.datastax.dse.driver.internal.core.context.DseDriverContext;
import com.datastax.dse.driver.internal.core.graph.binary.GraphBinaryModule;
import com.datastax.dse.protocol.internal.request.RawBytesQuery;
import com.datastax.dse.protocol.internal.request.query.DseQueryOptions;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverExecutionProfile;
import com.datastax.oss.driver.api.core.tracker.RequestTracker;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.datastax.oss.driver.internal.core.cql.RequestHandlerTestHarness;
import com.datastax.oss.driver.internal.core.metadata.DefaultNode;
import com.datastax.oss.driver.internal.core.metrics.NodeMetricUpdater;
import com.datastax.oss.driver.shaded.guava.common.collect.ImmutableList;
import com.datastax.oss.driver.shaded.guava.common.collect.ImmutableMap;
import com.datastax.oss.protocol.internal.Frame;
import com.datastax.oss.protocol.internal.Message;
import com.datastax.oss.protocol.internal.ProtocolConstants;
import com.datastax.oss.protocol.internal.request.Query;
import com.datastax.oss.protocol.internal.response.result.ColumnSpec;
import com.datastax.oss.protocol.internal.response.result.DefaultRows;
import com.datastax.oss.protocol.internal.response.result.RawType;
import com.datastax.oss.protocol.internal.response.result.RowsMetadata;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;
import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.regex.Pattern;
import org.apache.tinkerpop.gremlin.driver.ser.binary.GraphBinaryReader;
import org.apache.tinkerpop.gremlin.driver.ser.binary.GraphBinaryWriter;
import org.apache.tinkerpop.gremlin.driver.ser.binary.TypeSerializerRegistry;
import org.apache.tinkerpop.gremlin.process.remote.traversal.DefaultRemoteTraverser;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedVertex;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedVertexProperty;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@RunWith(DataProviderRunner.class)
public class GraphRequestHandlerTest {

  private static final Pattern LOG_PREFIX_PER_REQUEST = Pattern.compile("test-graph\\|\\d*\\|\\d*");

  @Mock DefaultNode node;

  @Mock protected NodeMetricUpdater nodeMetricUpdater1;

  @Before
  public void setup() {
    MockitoAnnotations.initMocks(this);
    when(node.getMetricUpdater()).thenReturn(nodeMetricUpdater1);
  }

  GraphBinaryModule createGraphBinaryModule(DseDriverContext context) {
    TypeSerializerRegistry registry = GraphBinaryModule.createDseTypeSerializerRegistry(context);
    return new GraphBinaryModule(new GraphBinaryReader(registry), new GraphBinaryWriter(registry));
  }

  @Test
  @UseDataProvider("bytecodeEnabledGraphProtocols")
  public void should_create_query_message_from_script_statement(GraphProtocol graphProtocol)
      throws IOException {
    // initialization
    GraphRequestHandlerTestHarness harness = GraphRequestHandlerTestHarness.builder().build();
    ScriptGraphStatement graphStatement =
        ScriptGraphStatement.newInstance("mockQuery")
            .setQueryParam("p1", 1L)
            .setQueryParam("p2", Uuids.random());

    GraphBinaryModule module = createGraphBinaryModule(harness.getContext());

    // when
    DriverExecutionProfile executionProfile =
        GraphConversions.resolveExecutionProfile(graphStatement, harness.getContext());

    Message m =
        GraphConversions.createMessageFromGraphStatement(
            graphStatement, graphProtocol, executionProfile, harness.getContext(), module);

    // checks
    assertThat(m).isInstanceOf(Query.class);
    Query q = ((Query) m);
    assertThat(q.query).isEqualTo("mockQuery");
    assertThat(q.options.positionalValues)
        .containsExactly(serialize(graphStatement.getQueryParams(), graphProtocol, module));
    assertThat(q.options.namedValues).isEmpty();
  }

  @Test
  @UseDataProvider("bytecodeEnabledGraphProtocols")
  public void should_create_query_message_from_fluent_statement(GraphProtocol graphProtocol)
      throws IOException {
    // initialization
    GraphRequestHandlerTestHarness harness = GraphRequestHandlerTestHarness.builder().build();
    GraphTraversal traversalTest =
        DseGraph.g.V().has("person", "name", "marko").has("p1", 1L).has("p2", Uuids.random());
    GraphStatement<?> graphStatement = FluentGraphStatement.newInstance(traversalTest);

    GraphBinaryModule module = createGraphBinaryModule(harness.getContext());

    // when
    DriverExecutionProfile executionProfile =
        GraphConversions.resolveExecutionProfile(graphStatement, harness.getContext());

    Message m =
        GraphConversions.createMessageFromGraphStatement(
            graphStatement, graphProtocol, executionProfile, harness.getContext(), module);

    Map<String, ByteBuffer> createdCustomPayload =
        GraphConversions.createCustomPayload(
            graphStatement, graphProtocol, executionProfile, harness.getContext(), module);

    // checks
    assertThat(m).isInstanceOf(RawBytesQuery.class);
    testQueryRequestAndPayloadContents(
        ((RawBytesQuery) m),
        createdCustomPayload,
        GraphConversions.bytecodeToSerialize(graphStatement),
        graphProtocol,
        module);
  }

  @Test
  @UseDataProvider("bytecodeEnabledGraphProtocols")
  public void should_create_query_message_from_batch_statement(GraphProtocol graphProtocol)
      throws IOException {
    // initialization
    GraphRequestHandlerTestHarness harness = GraphRequestHandlerTestHarness.builder().build();
    List<GraphTraversal> traversalsTest =
        ImmutableList.of(
            // randomly testing some complex data types. Complete suite of data types test is in
            // GraphBinaryDataTypesTest
            DseGraph.g
                .addV("person")
                .property("p1", 2.3f)
                .property("p2", LocalDateTime.now(ZoneOffset.UTC)),
            DseGraph.g
                .addV("software")
                .property("p3", new BigInteger("123456789123456789123456789123456789"))
                .property("p4", ImmutableList.of(Point.fromCoordinates(30.4, 25.63746284))));
    GraphStatement<?> graphStatement =
        BatchGraphStatement.builder().addTraversals(traversalsTest).build();

    GraphBinaryModule module = createGraphBinaryModule(harness.getContext());

    // when
    DriverExecutionProfile executionProfile =
        GraphConversions.resolveExecutionProfile(graphStatement, harness.getContext());

    Message m =
        GraphConversions.createMessageFromGraphStatement(
            graphStatement, graphProtocol, executionProfile, harness.getContext(), module);

    Map<String, ByteBuffer> createdCustomPayload =
        GraphConversions.createCustomPayload(
            graphStatement, graphProtocol, executionProfile, harness.getContext(), module);

    // checks
    assertThat(m).isInstanceOf(RawBytesQuery.class);
    testQueryRequestAndPayloadContents(
        ((RawBytesQuery) m),
        createdCustomPayload,
        GraphConversions.bytecodeToSerialize(graphStatement),
        graphProtocol,
        module);
  }

  private static ByteBuffer serialize(
      Object value, GraphProtocol graphProtocol, GraphBinaryModule graphBinaryModule)
      throws IOException {

    ByteBuf nettyBuf = graphBinaryModule.serialize(value);
    ByteBuffer nioBuffer = ByteBufUtil.toByteBuffer(nettyBuf);
    nettyBuf.release();
    return graphProtocol.isGraphBinary()
        ? nioBuffer
        : GraphSONUtils.serializeToByteBuffer(value, graphProtocol);
  }

  private void testQueryRequestAndPayloadContents(
      RawBytesQuery q,
      Map<String, ByteBuffer> customPayload,
      Object traversalTest,
      GraphProtocol graphProtocol,
      GraphBinaryModule module)
      throws IOException {
    if (graphProtocol.isGraphBinary()) {
      assertThat(q.query).isEqualTo(GraphConversions.EMPTY_STRING_QUERY);
      assertThat(customPayload).containsKey(GraphConversions.GRAPH_BINARY_QUERY_OPTION_KEY);
      ByteBuffer encodedQuery = customPayload.get(GraphConversions.GRAPH_BINARY_QUERY_OPTION_KEY);
      assertThat(encodedQuery).isNotNull();
      assertThat(encodedQuery).isEqualTo(serialize(traversalTest, graphProtocol, module));
    } else {
      assertThat(q.query).isEqualTo(serialize(traversalTest, graphProtocol, module).array());
      assertThat(customPayload).doesNotContainKey(GraphConversions.GRAPH_BINARY_QUERY_OPTION_KEY);
    }
  }

  @Test
  public void should_set_correct_query_options_from_graph_statement() throws IOException {
    // initialization
    GraphRequestHandlerTestHarness harness = GraphRequestHandlerTestHarness.builder().build();
    GraphStatement<?> graphStatement =
        ScriptGraphStatement.newInstance("mockQuery").setQueryParam("name", "value");
    GraphProtocol subProtocol = GraphProtocol.GRAPHSON_2_0;

    GraphBinaryModule module = createGraphBinaryModule(harness.getContext());

    // when
    DriverExecutionProfile executionProfile =
        GraphConversions.resolveExecutionProfile(graphStatement, harness.getContext());
    Message m =
        GraphConversions.createMessageFromGraphStatement(
            graphStatement, subProtocol, executionProfile, harness.getContext(), module);

    // checks
    Query query = ((Query) m);
    DseQueryOptions options = ((DseQueryOptions) query.options);
    assertThat(options.consistency)
        .isEqualTo(
            DefaultConsistencyLevel.valueOf(
                    executionProfile.getString(DefaultDriverOption.REQUEST_CONSISTENCY))
                .getProtocolCode());
    // set by the mock timestamp generator
    assertThat(options.defaultTimestamp).isEqualTo(-9223372036854775808L);
    assertThat(options.positionalValues)
        .isEqualTo(
            ImmutableList.of(
                GraphSONUtils.serializeToByteBuffer(
                    ImmutableMap.of("name", "value"), subProtocol)));

    m =
        GraphConversions.createMessageFromGraphStatement(
            graphStatement.setTimestamp(2L),
            subProtocol,
            executionProfile,
            harness.getContext(),
            module);
    query = ((Query) m);
    options = ((DseQueryOptions) query.options);
    assertThat(options.defaultTimestamp).isEqualTo(2L);
  }

  @Test
  public void should_create_payload_from_config_options() {
    // initialization
    GraphRequestHandlerTestHarness harness = GraphRequestHandlerTestHarness.builder().build();
    GraphStatement<?> graphStatement =
        ScriptGraphStatement.newInstance("mockQuery").setExecutionProfileName("test-graph");
    GraphProtocol subProtocol = GraphProtocol.GRAPHSON_2_0;

    GraphBinaryModule module = createGraphBinaryModule(harness.getContext());

    // when
    DriverExecutionProfile executionProfile =
        GraphConversions.resolveExecutionProfile(graphStatement, harness.getContext());

    Map<String, ByteBuffer> requestPayload =
        GraphConversions.createCustomPayload(
            graphStatement, subProtocol, executionProfile, harness.getContext(), module);

    // checks
    Mockito.verify(executionProfile).getString(DseDriverOption.GRAPH_TRAVERSAL_SOURCE, null);
    Mockito.verify(executionProfile).getString(DseDriverOption.GRAPH_NAME, null);
    Mockito.verify(executionProfile).getBoolean(DseDriverOption.GRAPH_IS_SYSTEM_QUERY, false);
    Mockito.verify(executionProfile).getDuration(DseDriverOption.GRAPH_TIMEOUT, null);
    Mockito.verify(executionProfile).getString(DseDriverOption.GRAPH_READ_CONSISTENCY_LEVEL, null);
    Mockito.verify(executionProfile).getString(DseDriverOption.GRAPH_WRITE_CONSISTENCY_LEVEL, null);

    assertThat(requestPayload.get(GraphConversions.GRAPH_SOURCE_OPTION_KEY))
        .isEqualTo(TEXT.encode("a", harness.getContext().getProtocolVersion()));
    assertThat(requestPayload.get(GraphConversions.GRAPH_RESULTS_OPTION_KEY))
        .isEqualTo(
            TEXT.encode(subProtocol.toInternalCode(), harness.getContext().getProtocolVersion()));
    assertThat(requestPayload.get(GraphConversions.GRAPH_NAME_OPTION_KEY))
        .isEqualTo(TEXT.encode("mockGraph", harness.getContext().getProtocolVersion()));
    assertThat(requestPayload.get(GraphConversions.GRAPH_LANG_OPTION_KEY))
        .isEqualTo(TEXT.encode("gremlin-groovy", harness.getContext().getProtocolVersion()));
    assertThat(requestPayload.get(GraphConversions.GRAPH_TIMEOUT_OPTION_KEY))
        .isEqualTo(BIGINT.encode(2L, harness.getContext().getProtocolVersion()));
    assertThat(requestPayload.get(GraphConversions.GRAPH_READ_CONSISTENCY_LEVEL_OPTION_KEY))
        .isEqualTo(TEXT.encode("LOCAL_TWO", harness.getContext().getProtocolVersion()));
    assertThat(requestPayload.get(GraphConversions.GRAPH_WRITE_CONSISTENCY_LEVEL_OPTION_KEY))
        .isEqualTo(TEXT.encode("LOCAL_THREE", harness.getContext().getProtocolVersion()));
  }

  @Test
  public void should_create_payload_from_statement_options() {
    // initialization
    GraphRequestHandlerTestHarness harness = GraphRequestHandlerTestHarness.builder().build();
    GraphStatement<?> graphStatement =
        ScriptGraphStatement.builder("mockQuery")
            .setGraphName("mockGraph")
            .setTraversalSource("a")
            .setTimeout(Duration.ofMillis(2))
            .setReadConsistencyLevel(DefaultConsistencyLevel.TWO)
            .setWriteConsistencyLevel(DefaultConsistencyLevel.THREE)
            .setSystemQuery(false)
            .build();
    GraphProtocol subProtocol = GraphProtocol.GRAPHSON_2_0;

    GraphBinaryModule module = createGraphBinaryModule(harness.getContext());

    // when
    DriverExecutionProfile executionProfile =
        GraphConversions.resolveExecutionProfile(graphStatement, harness.getContext());

    Map<String, ByteBuffer> requestPayload =
        GraphConversions.createCustomPayload(
            graphStatement, subProtocol, executionProfile, harness.getContext(), module);

    // checks
    Mockito.verify(executionProfile, never())
        .getString(DseDriverOption.GRAPH_TRAVERSAL_SOURCE, null);
    Mockito.verify(executionProfile, never()).getString(DseDriverOption.GRAPH_NAME, null);
    Mockito.verify(executionProfile, never())
        .getBoolean(DseDriverOption.GRAPH_IS_SYSTEM_QUERY, false);
    Mockito.verify(executionProfile, never()).getDuration(DseDriverOption.GRAPH_TIMEOUT, null);
    Mockito.verify(executionProfile, never())
        .getString(DseDriverOption.GRAPH_READ_CONSISTENCY_LEVEL, null);
    Mockito.verify(executionProfile, never())
        .getString(DseDriverOption.GRAPH_WRITE_CONSISTENCY_LEVEL, null);

    assertThat(requestPayload.get(GraphConversions.GRAPH_SOURCE_OPTION_KEY))
        .isEqualTo(TEXT.encode("a", harness.getContext().getProtocolVersion()));
    assertThat(requestPayload.get(GraphConversions.GRAPH_RESULTS_OPTION_KEY))
        .isEqualTo(
            TEXT.encode(subProtocol.toInternalCode(), harness.getContext().getProtocolVersion()));
    assertThat(requestPayload.get(GraphConversions.GRAPH_NAME_OPTION_KEY))
        .isEqualTo(TEXT.encode("mockGraph", harness.getContext().getProtocolVersion()));
    assertThat(requestPayload.get(GraphConversions.GRAPH_LANG_OPTION_KEY))
        .isEqualTo(TEXT.encode("gremlin-groovy", harness.getContext().getProtocolVersion()));
    assertThat(requestPayload.get(GraphConversions.GRAPH_TIMEOUT_OPTION_KEY))
        .isEqualTo(BIGINT.encode(2L, harness.getContext().getProtocolVersion()));
    assertThat(requestPayload.get(GraphConversions.GRAPH_READ_CONSISTENCY_LEVEL_OPTION_KEY))
        .isEqualTo(TEXT.encode("TWO", harness.getContext().getProtocolVersion()));
    assertThat(requestPayload.get(GraphConversions.GRAPH_WRITE_CONSISTENCY_LEVEL_OPTION_KEY))
        .isEqualTo(TEXT.encode("THREE", harness.getContext().getProtocolVersion()));
  }

  @Test
  public void should_not_set_graph_name_on_system_queries() {
    // initialization
    GraphRequestHandlerTestHarness harness = GraphRequestHandlerTestHarness.builder().build();
    GraphStatement<?> graphStatement =
        ScriptGraphStatement.newInstance("mockQuery").setSystemQuery(true);
    GraphProtocol subProtocol = GraphProtocol.GRAPHSON_2_0;

    GraphBinaryModule module = createGraphBinaryModule(harness.getContext());

    // when
    DriverExecutionProfile executionProfile =
        GraphConversions.resolveExecutionProfile(graphStatement, harness.getContext());

    Map<String, ByteBuffer> requestPayload =
        GraphConversions.createCustomPayload(
            graphStatement, subProtocol, executionProfile, harness.getContext(), module);

    // checks
    assertThat(requestPayload.get(GraphConversions.GRAPH_NAME_OPTION_KEY)).isNull();
    assertThat(requestPayload.get(GraphConversions.GRAPH_SOURCE_OPTION_KEY)).isNull();
  }

  @Test
  @UseDataProvider("bytecodeEnabledGraphProtocols")
  public void should_return_results_for_statements(GraphProtocol graphProtocol) throws IOException {
    DseDriverContext mockContext = Mockito.mock(DseDriverContext.class);
    GraphBinaryModule module = createGraphBinaryModule(mockContext);

    GraphPagingSupportChecker graphPagingSupportChecker = mock(GraphPagingSupportChecker.class);
    when(graphPagingSupportChecker.isPagingEnabled(any(), any())).thenReturn(false);

    GraphRequestAsyncProcessor p =
        Mockito.spy(new GraphRequestAsyncProcessor(mockContext, graphPagingSupportChecker));
    when(p.getGraphBinaryModule()).thenReturn(module);

    Vertex v =
        DetachedVertex.build()
            .setId(1)
            .setLabel("person")
            .addProperty(
                DetachedVertexProperty.build()
                    .setId(11)
                    .setLabel("name")
                    .setValue("marko")
                    .create())
            .create();

    RequestHandlerTestHarness harness =
        GraphRequestHandlerTestHarness.builder()
            .withGraphProtocolForTestConfig(graphProtocol.toInternalCode())
            // ideally we would be able to provide a function here to
            // produce results instead of a static predefined response.
            // Function to which we would pass the harness instance or a (mocked)DriverContext.
            // Since that's not possible in the RequestHandlerTestHarness API at the moment, we
            // have to use another DseDriverContext and GraphBinaryModule here,
            // instead of reusing the one in the harness' DriverContext
            .withResponse(node, defaultDseFrameOf(singleGraphRow(graphProtocol, v, module)))
            .build();

    GraphStatement graphStatement =
        ScriptGraphStatement.newInstance("mockQuery").setExecutionProfileName("test-graph");
    GraphResultSet grs =
        new GraphRequestSyncProcessor(p)
            .process(graphStatement, harness.getSession(), harness.getContext(), "test-graph");

    List<GraphNode> nodes = grs.all();
    assertThat(nodes.size()).isEqualTo(1);

    GraphNode node = nodes.get(0);
    assertThat(node.isVertex()).isTrue();

    Vertex vRead = node.asVertex();
    assertThat(vRead.label()).isEqualTo("person");
    assertThat(vRead.id()).isEqualTo(1);
    if (!graphProtocol.isGraphBinary()) {
      // GraphBinary does not encode properties regardless of whether they are present in the
      // parent element or not :/
      assertThat(v.property("name").id()).isEqualTo(11);
      assertThat(v.property("name").value()).isEqualTo("marko");
    }
  }

  @DataProvider
  public static Object[][] bytecodeEnabledGraphProtocols() {
    return new Object[][] {{GRAPHSON_2_0}, {GRAPHSON_3_0}, {GRAPH_BINARY_1_0}};
  }

  @Test
  public void should_invoke_request_tracker() throws IOException {
    DseDriverContext mockContext = Mockito.mock(DseDriverContext.class);
    GraphBinaryModule module = createGraphBinaryModule(mockContext);

    GraphRequestAsyncProcessor p =
        Mockito.spy(new GraphRequestAsyncProcessor(mockContext, new GraphPagingSupportChecker()));
    when(p.getGraphBinaryModule()).thenReturn(module);

    Vertex v =
        DetachedVertex.build()
            .setId(1)
            .setLabel("person")
            .addProperty(
                DetachedVertexProperty.build()
                    .setId(11)
                    .setLabel("name")
                    .setValue("marko")
                    .create())
            .create();

    RequestHandlerTestHarness harness =
        GraphRequestHandlerTestHarness.builder()
            .withResponse(
                node, defaultDseFrameOf(singleGraphRow(GraphProtocol.GRAPHSON_2_0, v, module)))
            .build();

    RequestTracker requestTracker = mock(RequestTracker.class);
    when(harness.getContext().getRequestTracker()).thenReturn(requestTracker);

    GraphStatement graphStatement = ScriptGraphStatement.newInstance("mockQuery");
    GraphPagingSupportChecker graphPagingSupportChecker = mock(GraphPagingSupportChecker.class);
    when(graphPagingSupportChecker.isPagingEnabled(any(), any())).thenReturn(false);
    GraphResultSet grs =
        new GraphRequestSyncProcessor(
                new GraphRequestAsyncProcessor(
                    (DseDriverContext) harness.getContext(), graphPagingSupportChecker))
            .process(graphStatement, harness.getSession(), harness.getContext(), "test-graph");

    List<GraphNode> nodes = grs.all();
    assertThat(nodes.size()).isEqualTo(1);

    GraphNode graphNode = nodes.get(0);
    assertThat(graphNode.isVertex()).isTrue();

    Vertex actual = graphNode.asVertex();
    assertThat(actual.label()).isEqualTo("person");
    assertThat(actual.id()).isEqualTo(1);
    assertThat(actual.property("name").id()).isEqualTo(11);
    assertThat(actual.property("name").value()).isEqualTo("marko");

    verify(requestTracker)
        .onSuccess(
            eq(graphStatement),
            anyLong(),
            any(DriverExecutionProfile.class),
            eq(node),
            matches(LOG_PREFIX_PER_REQUEST));
    verifyNoMoreInteractions(requestTracker);
  }

  private static Frame defaultDseFrameOf(Message responseMessage) {
    return Frame.forResponse(
        DseProtocolVersion.DSE_V2.getCode(),
        0,
        null,
        Frame.NO_PAYLOAD,
        Collections.emptyList(),
        responseMessage);
  }

  // Returns a single row, with a single "message" column containing the value
  // given in parameter serialized according to the protocol
  private static Message singleGraphRow(
      GraphProtocol graphProtocol, Object value, GraphBinaryModule module) throws IOException {
    RowsMetadata metadata =
        new RowsMetadata(
            ImmutableList.of(
                new ColumnSpec(
                    "ks",
                    "table",
                    "gremlin",
                    0,
                    graphProtocol.isGraphBinary()
                        ? RawType.PRIMITIVES.get(ProtocolConstants.DataType.BLOB)
                        : RawType.PRIMITIVES.get(ProtocolConstants.DataType.VARCHAR))),
            null,
            new int[] {},
            null);
    Queue<List<ByteBuffer>> data = new ArrayDeque<>();

    data.add(
        ImmutableList.of(
            serialize(
                graphProtocol.isGraphBinary()
                    // GraphBinary returns results directly inside a Traverser
                    ? new DefaultRemoteTraverser<>(value, 1)
                    : ImmutableMap.of("result", value),
                graphProtocol,
                module)));
    return new DefaultRows(metadata, data);
  }
}
