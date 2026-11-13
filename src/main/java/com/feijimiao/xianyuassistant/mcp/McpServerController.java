package com.feijimiao.xianyuassistant.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.feijimiao.xianyuassistant.command.CommandDescriptor;
import com.feijimiao.xianyuassistant.command.CommandRegistry;
import com.feijimiao.xianyuassistant.command.ParamSpec;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 轻量 MCP（Model Context Protocol）服务端点。
 * 基于 JSON-RPC 2.0 over HTTP，实现 initialize / tools/list / tools/call 三个方法。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class McpServerController {

    private static final String JSONRPC = "2.0";
    private static final String PROTOCOL_VERSION = "2025-03-26";
    private static final String SERVER_NAME = "xianyu-assistant";
    @Value("${app.version:3.5.24}")
    private String serverVersion;

    private final CommandRegistry commandRegistry;
    private final ObjectMapper objectMapper;
    private final RbacService rbacService;

    @PostMapping(value = "/api/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonNode handle(@RequestBody(required = false) String rawBody) {
        JsonNode request;
        try {
            request = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            log.warn("MCP 请求解析失败: {}", e.getMessage());
            return errorResponse(null, -32700, "Parse error");
        }
        if (request == null) {
            return errorResponse(null, -32700, "Parse error: invalid request");
        }

        if (request.isArray()) {
            if (request.isEmpty()) {
                return errorResponse(null, -32600, "Invalid Request: empty batch");
            }
            ArrayNode responses = objectMapper.createArrayNode();
            for (JsonNode item : request) {
                responses.add(handleSingleRequest(item));
            }
            return responses;
        }

        return handleSingleRequest(request);
    }

    private JsonNode handleSingleRequest(JsonNode request) {
        if (request == null || !request.isObject()) {
            return errorResponse(null, -32600, "Invalid Request");
        }
        try {
            JsonNode id = request.get("id");
            if (!"2.0".equals(request.path("jsonrpc").asText())
                    || !request.path("method").isTextual()) {
                return errorResponse(id, -32600, "Invalid Request");
            }
            String method = request.path("method").asText("");
            switch (method) {
                case "initialize" -> {
                    return resultResponse(id, initializeResult());
                }
                case "tools/list" -> {
                    if (!hasPermission("commands:read")) {
                        return errorResponse(id, -32003, "Permission denied: commands:read");
                    }
                    return resultResponse(id, toolsListResult());
                }
                case "tools/call" -> {
                    if (!hasPermission("commands:execute")) {
                        return errorResponse(id, -32003, "Permission denied: commands:execute");
                    }
                    return toolsCall(id, request.path("params"));
                }
                default -> {
                    return errorResponse(id, -32601, "Method not found: " + method);
                }
            }
        } catch (Exception e) {
            log.error("MCP 请求处理异常: method={}", request.path("method").asText(""), e);
            return errorResponse(request.get("id"), -32603, "Internal server error");
        }
    }

    private boolean hasPermission(String permission) {
        return rbacService.hasAnyPermission(UserContext.getUserId(), permission);
    }

    // ==================== JSON-RPC 响应构造 ====================

    private ObjectNode resultResponse(JsonNode id, ObjectNode result) {
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("jsonrpc", JSONRPC);
        resp.set("id", id == null ? NullNode.getInstance() : id);
        resp.set("result", result);
        return resp;
    }

    private ObjectNode errorResponse(JsonNode id, int code, String message) {
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("jsonrpc", JSONRPC);
        resp.set("id", id == null ? NullNode.getInstance() : id);
        ObjectNode error = resp.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return resp;
    }

    // ==================== initialize ====================

    private ObjectNode initializeResult() {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode capabilities = result.putObject("capabilities");
        ObjectNode tools = capabilities.putObject("tools");
        tools.put("listChanged", false);
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", serverVersion);
        return result;
    }

    // ==================== tools/list ====================

    private ObjectNode toolsListResult() {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        List<CommandDescriptor> descriptors = commandRegistry.list();
        for (CommandDescriptor d : descriptors) {
            ObjectNode tool = tools.addObject();
            tool.put("name", d.name());
            tool.put("description", d.description());
            tool.set("inputSchema", toInputSchema(d.params()));
        }
        return result;
    }

    private ObjectNode toInputSchema(List<ParamSpec> params) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        for (ParamSpec p : params) {
            ObjectNode prop = properties.putObject(p.name());
            prop.put("type", mapParamType(p.type()));
            if (p.description() != null && !p.description().isBlank()) {
                prop.put("description", p.description());
            }
            if (p.required()) {
                required.add(p.name());
            }
        }
        return schema;
    }

    private String mapParamType(String type) {
        return switch (type == null ? "" : type) {
            case "list" -> "array";
            case "boolean" -> "boolean";
            case "number" -> "number";
            default -> "string";
        };
    }

    // ==================== tools/call ====================

    private JsonNode toolsCall(JsonNode id, JsonNode params) {
        if (params == null || !params.isObject()) {
            return errorResponse(id, -32602, "Invalid params");
        }
        String name = params.path("name").asText("");
        if (name.isBlank()) {
            return errorResponse(id, -32602, "Invalid params: missing tool name");
        }

        JsonNode arguments = params.get("arguments");
        Map<String, Object> args = null;
        if (arguments != null && !arguments.isNull()) {
            try {
                args = objectMapper.convertValue(arguments,
                        objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            } catch (Exception e) {
                return errorResponse(id, -32602, "Invalid params: arguments is not an object");
            }
        }

        ObjectNode result = objectMapper.createObjectNode();
        Map<String, Object> executeResult;
        try {
            executeResult = commandRegistry.execute(name, args);
            result.put("isError", false);
        } catch (Exception e) {
            log.error("MCP tools/call 命令执行失败: name={}", name, e);
            result.put("isError", true);
            ArrayNode content = result.putArray("content");
            content.addObject().put("type", "text").put("text", "Command execution failed");
            return resultResponse(id, result);
        }

        String text;
        try {
            text = objectMapper.writeValueAsString(executeResult);
        } catch (Exception e) {
            text = "{\"success\":false}";
        }
        ArrayNode content = result.putArray("content");
        content.addObject().put("type", "text").put("text", text);
        return resultResponse(id, result);
    }
}
