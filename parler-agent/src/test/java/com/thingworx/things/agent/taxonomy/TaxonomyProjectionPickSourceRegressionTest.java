package com.thingworx.things.agent.taxonomy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

/**
 * Offline guard: {@code createTaxonomyProjectionPick} must always pass a
 * (possibly 0-row) {@code propertyNames} EntityList — never omit the parameter (wide QIT path).
 * Cannot invoke {@code QueryEntitiesExecutor} in JUnit without ThingWorx {@code ProviderConfig}.
 */
class TaxonomyProjectionPickSourceRegressionTest {

  @Test
  void createTaxonomyProjectionPick_alwaysBuildsPropertyNamesTable() throws Exception {
    Path src = queryEntitiesExecutorSource();
    String text = Files.readString(src, StandardCharsets.UTF_8);
    int start = text.indexOf("static ColumnPick createTaxonomyProjectionPick");
    assertTrue(start >= 0, "createTaxonomyProjectionPick missing");
    int end = text.indexOf("static Long inferTotalWhenPlatformOmitsCount", start);
    assertTrue(end > start, "method body boundary missing");
    String body = text.substring(start, end);
    assertTrue(
        body.contains("InfoTable propTable = buildEntityListTable(props)"),
        "must always build propertyNames table");
    assertFalse(
        body.contains("propTable = null"),
        "must not null-out propertyNames for empty props");
  }

  private static Path queryEntitiesExecutorSource() {
    String root = System.getProperty("user.dir");
    Path direct =
        Paths.get(
            root,
            "src/main/java/com/thingworx/things/agent/tools/QueryEntitiesExecutor.java");
    if (Files.isRegularFile(direct)) {
      return direct;
    }
    return Paths.get(
        root,
        "parler-agent/src/main/java/com/thingworx/things/agent/tools/QueryEntitiesExecutor.java");
  }
}
