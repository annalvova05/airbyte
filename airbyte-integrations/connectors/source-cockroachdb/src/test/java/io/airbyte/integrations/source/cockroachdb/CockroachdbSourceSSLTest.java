///*
// * MIT License
// *
// * Copyright (c) 2020 Airbyte
// *
// * Permission is hereby granted, free of charge, to any person obtaining a copy
// * of this software and associated documentation files (the "Software"), to deal
// * in the Software without restriction, including without limitation the rights
// * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// * copies of the Software, and to permit persons to whom the Software is
// * furnished to do so, subject to the following conditions:
// *
// * The above copyright notice and this permission notice shall be included in all
// * copies or substantial portions of the Software.
// *
// * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// * SOFTWARE.
// */
//
//package io.airbyte.integrations.source.cockroachdb;
//
//import static org.junit.jupiter.api.Assertions.assertEquals;
//import static org.junit.jupiter.api.Assertions.assertFalse;
//import static org.junit.jupiter.api.Assertions.assertTrue;
//
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.node.ObjectNode;
//import com.google.common.collect.ImmutableMap;
//import com.google.common.collect.Lists;
//import com.google.common.collect.Sets;
//import io.airbyte.commons.io.IOs;
//import io.airbyte.commons.json.Jsons;
//import io.airbyte.commons.string.Strings;
//import io.airbyte.commons.util.MoreIterators;
//import io.airbyte.db.Database;
//import io.airbyte.db.Databases;
//import io.airbyte.protocol.models.AirbyteCatalog;
//import io.airbyte.protocol.models.AirbyteMessage;
//import io.airbyte.protocol.models.AirbyteMessage.Type;
//import io.airbyte.protocol.models.AirbyteRecordMessage;
//import io.airbyte.protocol.models.AirbyteStream;
//import io.airbyte.protocol.models.CatalogHelpers;
//import io.airbyte.protocol.models.ConfiguredAirbyteCatalog;
//import io.airbyte.protocol.models.Field;
//import io.airbyte.protocol.models.JsonSchemaPrimitive;
//import io.airbyte.protocol.models.SyncMode;
//import io.airbyte.test.utils.CockroachDBContainerHelper;
//import io.airbyte.test.utils.PostgreSQLContainerHelper;
//import java.math.BigDecimal;
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
//import java.util.Optional;
//import java.util.Set;
//import java.util.stream.Collectors;
//import org.jooq.SQLDialect;
//import org.junit.jupiter.api.AfterAll;
//import org.junit.jupiter.api.BeforeAll;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.testcontainers.containers.CockroachContainer;
//import org.testcontainers.utility.DockerImageName;
//import org.testcontainers.utility.MountableFile;
//
//class CockroachdbSourceSSLTest {
//
//  private static final String SCHEMA_NAME = "public";
//  private static final String STREAM_NAME = "id_and_name";
//  private static final AirbyteCatalog CATALOG = new AirbyteCatalog().withStreams(List.of(
//      CatalogHelpers.createAirbyteStream(
//          STREAM_NAME,
//          SCHEMA_NAME,
//          Field.of("id", JsonSchemaPrimitive.NUMBER),
//          Field.of("name", JsonSchemaPrimitive.STRING),
//          Field.of("power", JsonSchemaPrimitive.NUMBER))
//          .withSupportedSyncModes(Lists.newArrayList(SyncMode.FULL_REFRESH, SyncMode.INCREMENTAL))
//          .withSourceDefinedPrimaryKey(List.of(List.of("id"))),
//      CatalogHelpers.createAirbyteStream(
//          STREAM_NAME + "2",
//          SCHEMA_NAME,
//          Field.of("id", JsonSchemaPrimitive.NUMBER),
//          Field.of("name", JsonSchemaPrimitive.STRING),
//          Field.of("power", JsonSchemaPrimitive.NUMBER))
//          .withSupportedSyncModes(Lists.newArrayList(SyncMode.FULL_REFRESH, SyncMode.INCREMENTAL)),
//      CatalogHelpers.createAirbyteStream(
//          "names",
//          SCHEMA_NAME,
//          Field.of("first_name", JsonSchemaPrimitive.STRING),
//          Field.of("last_name", JsonSchemaPrimitive.STRING),
//          Field.of("power", JsonSchemaPrimitive.NUMBER))
//          .withSupportedSyncModes(Lists.newArrayList(SyncMode.FULL_REFRESH, SyncMode.INCREMENTAL))
//          .withSourceDefinedPrimaryKey(List.of(List.of("first_name"), List.of("last_name")))));
//  private static final ConfiguredAirbyteCatalog CONFIGURED_CATALOG = CatalogHelpers.toDefaultConfiguredCatalog(CATALOG);
//  private static final Set<AirbyteMessage> ASCII_MESSAGES = Sets.newHashSet(
//      createRecord(STREAM_NAME, map("id", new BigDecimal("1.0"), "name", "goku", "power", null)),
//      createRecord(STREAM_NAME, map("id", new BigDecimal("2.0"), "name", "vegeta", "power", 9000.1)),
//      createRecord(STREAM_NAME, map("id", null, "name", "piccolo", "power", null)));
//
//  private static CockroachContainer PSQL_DB;
//
//  private String dbName;
//
//  @BeforeAll
//  static void init() {
//    PSQL_DB = new CockroachContainer(DockerImageName.parse("marcosmarxm/postgres-ssl:dev").asCompatibleSubstituteFor("postgres"))
//        .withCommand("postgres -c ssl=on -c ssl_cert_file=/var/lib/postgresql/server.crt -c ssl_key_file=/var/lib/postgresql/server.key");
//    PSQL_DB.start();
//  }
//
//  @BeforeEach
//  void setup() throws Exception {
//    dbName = Strings.addRandomSuffix("db", "_", 10).toLowerCase();
//
//    final String initScriptName = "init_" + dbName.concat(".sql");
//    final String tmpFilePath = IOs.writeFileToRandomTmpDir(initScriptName, "CREATE DATABASE " + dbName + ";");
//    CockroachDBContainerHelper.runSqlScript(MountableFile.forHostPath(tmpFilePath), PSQL_DB);
//
//    final JsonNode config = getConfig(PSQL_DB, dbName);
//    final Database database = getDatabaseFromConfig(config);
//    database.query(ctx -> {
//      ctx.fetch("CREATE TABLE id_and_name(id NUMERIC(20, 10), name VARCHAR(200), power double precision, PRIMARY KEY (id));");
//      ctx.fetch("CREATE INDEX i1 ON id_and_name (id);");
//      ctx.fetch("INSERT INTO id_and_name (id, name, power) VALUES (1,'goku', 'Infinity'),  (2, 'vegeta', 9000.1), ('NaN', 'piccolo', '-Infinity');");
//
//      ctx.fetch("CREATE TABLE id_and_name2(id NUMERIC(20, 10), name VARCHAR(200), power double precision);");
//      ctx.fetch("INSERT INTO id_and_name2 (id, name, power) VALUES (1,'goku', 'Infinity'),  (2, 'vegeta', 9000.1), ('NaN', 'piccolo', '-Infinity');");
//
//      ctx.fetch("CREATE TABLE names(first_name VARCHAR(200), last_name VARCHAR(200), power double precision, PRIMARY KEY (first_name, last_name));");
//      ctx.fetch(
//          "INSERT INTO names (first_name, last_name, power) VALUES ('san', 'goku', 'Infinity'),  ('prince', 'vegeta', 9000.1), ('piccolo', 'junior', '-Infinity');");
//      return null;
//    });
//    database.close();
//  }
//
//  private Database getDatabaseFromConfig(JsonNode config) {
//    return Databases.createDatabase(
//        config.get("username").asText(),
//        config.get("password").asText(),
//        String.format("jdbc:postgresql://%s:%s/%s?sslmode=require",
//            config.get("host").asText(),
//            config.get("port").asText(),
//            config.get("database").asText()),
//        "org.postgresql.Driver",
//        SQLDialect.POSTGRES);
//  }
//
//  private JsonNode getConfig(CockroachContainer psqlDb, String dbName) {
//    return Jsons.jsonNode(ImmutableMap.builder()
//        .put("host", psqlDb.getHost())
//        .put("port", psqlDb.getFirstMappedPort())
//        .put("database", dbName)
//        .put("username", psqlDb.getUsername())
//        .put("password", psqlDb.getPassword())
//        .put("ssl", true)
//        .build());
//  }
//
//  private JsonNode getConfig(CockroachContainer psqlDb) {
//    return getConfig(psqlDb, psqlDb.getDatabaseName());
//  }
//
//  @AfterAll
//  static void cleanUp() {
//    PSQL_DB.close();
//  }
//
//  private static void setEmittedAtToNull(Iterable<AirbyteMessage> messages) {
//    for (AirbyteMessage actualMessage : messages) {
//      if (actualMessage.getRecord() != null) {
//        actualMessage.getRecord().setEmittedAt(null);
//      }
//    }
//  }
//
//  @Test
//  void testDiscoverWithPk() throws Exception {
//    final AirbyteCatalog actual = new CockroachdbSource().discover(getConfig(PSQL_DB, dbName));
//    actual.getStreams().forEach(actualStream -> {
//      final Optional<AirbyteStream> expectedStream =
//          CATALOG.getStreams().stream().filter(stream -> stream.getName().equals(actualStream.getName())).findAny();
//      assertTrue(expectedStream.isPresent());
//      assertEquals(expectedStream.get(), actualStream);
//    });
//  }
//
//  @Test
//  void testReadSuccess() throws Exception {
//    final ConfiguredAirbyteCatalog configuredCatalog =
//        CONFIGURED_CATALOG.withStreams(CONFIGURED_CATALOG.getStreams().stream().filter(s -> s.getStream().getName().equals(STREAM_NAME))
//            .collect(Collectors.toList()));
//
//    final Set<AirbyteMessage> actualMessages = MoreIterators.toSet(new CockroachdbSource().read(getConfig(PSQL_DB, dbName), configuredCatalog, null));
//    setEmittedAtToNull(actualMessages);
//
//    assertEquals(ASCII_MESSAGES, actualMessages);
//  }
//
//  @Test
//  void testIsCdc() {
//    final JsonNode config = getConfig(PSQL_DB, dbName);
//
//    assertFalse(CockroachdbSource.isCdc(config));
//
//    ((ObjectNode) config).set("replication_method", Jsons.jsonNode(ImmutableMap.of(
//        "replication_slot", "slot",
//        "publication", "ab_pub")));
//    assertTrue(CockroachdbSource.isCdc(config));
//  }
//
//  private static AirbyteMessage createRecord(String stream, Map<Object, Object> data) {
//    return new AirbyteMessage().withType(Type.RECORD)
//        .withRecord(new AirbyteRecordMessage().withData(Jsons.jsonNode(data)).withStream(stream).withNamespace(SCHEMA_NAME));
//  }
//
//  private static Map<Object, Object> map(Object... entries) {
//    if (entries.length % 2 != 0) {
//      throw new IllegalArgumentException("Entries must have even length");
//    }
//
//    return new HashMap<>() {
//
//      {
//        for (int i = 0; i < entries.length; i++) {
//          put(entries[i++], entries[i]);
//        }
//      }
//
//    };
//  }
//
//}