package com.feijimiao.xianyuassistant.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.command.CommandDescriptor;
import com.feijimiao.xianyuassistant.command.CommandRegistry;
import com.feijimiao.xianyuassistant.command.ParamSpec;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP JSON-RPC interoperability regression tests.
 *
 * <p>The batch coverage follows the public MCP integration patterns reviewed
 * in DoLovya/xianyu-mcp-server; no third-party source code is copied here.</p>
 */
class McpServerControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CommandRegistry commandRegistry;
    private RbacService rbacService;
    private McpServerController controller;

    @BeforeEach
    void setUp() {
        commandRegistry = mock(CommandRegistry.class);
        rbacService = mock(RbacService.class);
        controller = new McpServerController(commandRegistry, objectMapper, rbacService);
        when(rbacService.hasAnyPermission(7L, "commands:read")).thenReturn(true);
        when(rbacService.hasAnyPermission(7L, "commands:execute")).thenReturn(true);
        UserContext.set(7L, "operator");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void initializeReturnsProtocolAndServerInfo() throws Exception {
        JsonNode response = controller.handle("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                """);

        assertEquals("2.0", response.path("jsonrpc").asText());
        assertEquals(1, response.path("id").asInt());
        assertEquals("2025-03-26", response.path("result").path("protocolVersion").asText());
        assertEquals("xianyu-assistant", response.path("result").path("serverInfo").path("name").asText());
    }

    @Test
    void toolsListRequiresReadPermission() {
        when(rbacService.hasAnyPermission(7L, "commands:read")).thenReturn(false);

        JsonNode response = controller.handle("""
                {"jsonrpc":"2.0","id":5,"method":"tools/list"}
                """);

        assertEquals(-32003, response.path("error").path("code").asInt());
        verify(commandRegistry, never()).list();
    }

    @Test
    void toolsCallRequiresExecutePermission() {
        when(rbacService.hasAnyPermission(7L, "commands:execute")).thenReturn(false);

        JsonNode response = controller.handle("""
                {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"list_accounts","arguments":{}}}
                """);

        assertEquals(-32003, response.path("error").path("code").asInt());
        verify(commandRegistry, never()).execute("list_accounts", Map.of());
    }

    @Test
    void toolsListExposesTypedRequiredParameters() throws Exception {
        when(commandRegistry.list()).thenReturn(List.of(new CommandDescriptor(
                "list_goods",
                "查询商品",
                List.of(new ParamSpec("accountId", "number", true, "账号 ID")),
                true)));

        JsonNode response = controller.handle("""
                {"jsonrpc":"2.0","id":"tools","method":"tools/list"}
                """);

        JsonNode tool = response.path("result").path("tools").get(0);
        assertEquals("list_goods", tool.path("name").asText());
        assertEquals("number", tool.path("inputSchema").path("properties").path("accountId").path("type").asText());
        assertEquals("accountId", tool.path("inputSchema").path("required").get(0).asText());
    }

    @Test
    void batchRequestsReturnOneResponsePerItem() throws Exception {
        when(commandRegistry.list()).thenReturn(List.of());

        JsonNode response = controller.handle("""
                [
                  {"jsonrpc":"2.0","id":1,"method":"initialize"},
                  {"jsonrpc":"2.0","id":2,"method":"tools/list"},
                  {"jsonrpc":"2.0","id":3,"method":"unknown"}
                ]
                """);

        assertTrue(response.isArray());
        assertEquals(3, response.size());
        assertEquals(1, response.get(0).path("id").asInt());
        assertEquals(2, response.get(1).path("id").asInt());
        assertEquals(-32601, response.get(2).path("error").path("code").asInt());
    }

    @Test
    void internalExceptionsAreRedactedFromJsonRpcResponses() throws Exception {
        when(commandRegistry.list()).thenThrow(new IllegalStateException("jdbc://secret-host/internal"));
        JsonNode listResponse = controller.handle("""
                {"jsonrpc":"2.0","id":9,"method":"tools/list"}
                """);

        when(commandRegistry.execute("explode", Map.of()))
                .thenThrow(new IllegalStateException("token=private-secret"));
        JsonNode callResponse = controller.handle("""
                {"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"explode","arguments":{}}}
                """);

        assertEquals("Internal server error", listResponse.path("error").path("message").asText());
        assertFalse(listResponse.path("error").path("message").asText().contains("secret-host"));
        assertEquals("Command execution failed",
                callResponse.path("result").path("content").get(0).path("text").asText());
        assertFalse(callResponse.path("result").path("content").get(0).path("text").asText().contains("private-secret"));
    }

    @Test
    void toolsCallSerializesSuccessfulResultAndRejectsMalformedRequests() throws Exception {
        when(commandRegistry.execute("list_accounts", Map.of())).thenReturn(Map.of("success", true));

        JsonNode success = controller.handle("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"list_accounts","arguments":{}}}
                """);
        JsonNode malformed = controller.handle("[]");

        assertFalse(success.path("result").path("isError").asBoolean());
        assertTrue(success.path("result").path("content").get(0).path("text").asText().contains("success"));
        assertEquals(-32600, malformed.path("error").path("code").asInt());
    }

    @Test
    void rejectsRequestsWithMissingOrUnsupportedJsonRpcVersion() {
        JsonNode missingVersion = controller.handle("""
                {"id":11,"method":"initialize"}
                """);
        JsonNode unsupportedVersion = controller.handle("""
                {"jsonrpc":"1.0","id":12,"method":"initialize"}
                """);

        assertEquals(-32600, missingVersion.path("error").path("code").asInt());
        assertEquals(-32600, unsupportedVersion.path("error").path("code").asInt());
    }
}
