package com.devflow.team.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI contract verification tests for Phase 2.6 (Teams).
 *
 * <p>Validates that the Springdoc OpenAPI engine exposes all 12 Teams API endpoints,
 * adheres to canonical URI conventions, publishes accurate request and response schemas,
 * enforces required parameters, maps error responses to the canonical {@code ErrorResponse} schema,
 * documents payload examples, and preserves global schema integrity without regressions.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.api-docs.path=/v3/api-docs"
})
@DisplayName("Teams OpenAPI Contract Verification")
class TeamOpenApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode fetchOpenApiSpec() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getOperation(JsonNode root, String path, String method) {
        JsonNode pathNode = root.path("paths").path(path);
        assertThat(pathNode.isMissingNode()).as("Path not found: %s", path).isFalse();
        JsonNode op = pathNode.path(method.toLowerCase());
        assertThat(op.isMissingNode()).as("Operation %s not found on path: %s", method, path).isFalse();
        return op;
    }

    // ── 1. Endpoint Registration & Operation IDs ──────────────────────────────

    @Nested
    @DisplayName("1. Endpoint Registration & Operations")
    class EndpointRegistrationTests {

        @Test
        @DisplayName("All 12 Teams endpoints are registered with correct HTTP verbs and operation IDs")
        void allTwelveTeamsEndpointsAreRegistered() throws Exception {
            JsonNode root = fetchOpenApiSpec();
            JsonNode paths = root.path("paths");
            assertThat(paths.isMissingNode()).isFalse();

            // Workspace-scoped collection: POST & GET
            String wsTeamsPath = "/api/v1/organizations/{organizationId}/workspaces/{workspaceId}/teams";
            assertThat(paths.path(wsTeamsPath).path("post").path("operationId").asText()).isEqualTo("createTeam");
            assertThat(paths.path(wsTeamsPath).path("get").path("operationId").asText()).isEqualTo("listWorkspaceTeams");

            // Workspace-scoped item: GET
            String wsTeamItemPath = "/api/v1/organizations/{organizationId}/workspaces/{workspaceId}/teams/{teamId}";
            assertThat(paths.path(wsTeamItemPath).path("get").path("operationId").asText()).isEqualTo("getTeamInWorkspace");

            // Resource-scoped team item: GET & PATCH
            String teamItemPath = "/api/v1/teams/{teamId}";
            assertThat(paths.path(teamItemPath).path("get").path("operationId").asText()).isEqualTo("getTeam");
            assertThat(paths.path(teamItemPath).path("patch").path("operationId").asText()).isEqualTo("updateTeam");

            // Archive team: POST
            String archivePath = "/api/v1/teams/{teamId}/archive";
            assertThat(paths.path(archivePath).path("post").path("operationId").asText()).isEqualTo("archiveTeam");

            // Team members collection: POST & GET
            String membersPath = "/api/v1/teams/{teamId}/members";
            assertThat(paths.path(membersPath).path("post").path("operationId").asText()).isEqualTo("addTeamMember");
            assertThat(paths.path(membersPath).path("get").path("operationId").asText()).isEqualTo("listTeamMembers");

            // Remove member: DELETE
            String memberItemPath = "/api/v1/teams/{teamId}/members/{userId}";
            assertThat(paths.path(memberItemPath).path("delete").path("operationId").asText()).isEqualTo("removeTeamMember");

            // Leave team: POST
            String leavePath = "/api/v1/teams/{teamId}/leave";
            assertThat(paths.path(leavePath).path("post").path("operationId").asText()).isEqualTo("leaveTeam");

            // Team lead: GET & PUT
            String leadPath = "/api/v1/teams/{teamId}/lead";
            assertThat(paths.path(leadPath).path("get").path("operationId").asText()).isEqualTo("getTeamLead");
            assertThat(paths.path(leadPath).path("put").path("operationId").asText()).isEqualTo("transferTeamLeadership");
        }

        @Test
        @DisplayName("All 12 operations declare Bearer authentication requirement")
        void allTeamsOperationsRequireBearerAuth() throws Exception {
            JsonNode root = fetchOpenApiSpec();
            JsonNode paths = root.path("paths");

            List<String> teamPaths = List.of(
                    "/api/v1/organizations/{organizationId}/workspaces/{workspaceId}/teams",
                    "/api/v1/organizations/{organizationId}/workspaces/{workspaceId}/teams/{teamId}",
                    "/api/v1/teams/{teamId}",
                    "/api/v1/teams/{teamId}/archive",
                    "/api/v1/teams/{teamId}/members",
                    "/api/v1/teams/{teamId}/members/{userId}",
                    "/api/v1/teams/{teamId}/leave",
                    "/api/v1/teams/{teamId}/lead"
            );

            for (String path : teamPaths) {
                JsonNode pathNode = paths.path(path);
                Iterator<Map.Entry<String, JsonNode>> fields = pathNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    String method = entry.getKey();
                    if (Set.of("get", "post", "put", "patch", "delete").contains(method)) {
                        JsonNode security = entry.getValue().path("security");
                        assertThat(security.isArray()).isTrue();
                        boolean hasBearerAuth = false;
                        for (JsonNode secItem : security) {
                            if (secItem.has("bearerAuth")) {
                                hasBearerAuth = true;
                                break;
                            }
                        }
                        assertThat(hasBearerAuth)
                                .as("Operation %s %s must require bearerAuth", method.toUpperCase(), path)
                                .isTrue();
                    }
                }
            }
        }

        @Test
        @DisplayName("Operation IDs across the specification are strictly unique")
        void noDuplicateOperationIdsAcrossSpec() throws Exception {
            JsonNode root = fetchOpenApiSpec();
            JsonNode paths = root.path("paths");

            Set<String> operationIds = new HashSet<>();
            List<String> duplicates = new ArrayList<>();

            Iterator<JsonNode> pathNodes = paths.elements();
            while (pathNodes.hasNext()) {
                JsonNode pathNode = pathNodes.next();
                Iterator<Map.Entry<String, JsonNode>> operations = pathNode.fields();
                while (operations.hasNext()) {
                    Map.Entry<String, JsonNode> op = operations.next();
                    if (Set.of("get", "post", "put", "patch", "delete").contains(op.getKey())) {
                        String opId = op.getValue().path("operationId").asText();
                        if (opId != null && !opId.isBlank()) {
                            if (!operationIds.add(opId)) {
                                duplicates.add(opId);
                            }
                        }
                    }
                }
            }

            assertThat(duplicates)
                    .as("Expected no duplicate operation IDs in OpenAPI specification")
                    .isEmpty();
        }
    }

    // ── 2. Successful Response Codes & Schemas ─────────────────────────────────

    @Nested
    @DisplayName("2. Successful Response Contracts")
    class SuccessResponseContractTests {

        @Test
        @DisplayName("Creation endpoints return 201 Created with single item response schema")
        void creationEndpointsReturn201WithSchema() throws Exception {
            JsonNode root = fetchOpenApiSpec();

            // POST /workspaces/{workspaceId}/teams -> 201 TeamResponse
            JsonNode createTeamResp = getOperation(root, "/api/v1/organizations/{organizationId}/workspaces/{workspaceId}/teams", "post")
                    .path("responses").path("201");
            assertThat(createTeamResp.path("description").asText()).isEqualTo("Team created successfully");
            assertThat(createTeamResp.path("content").path("application/json").path("schema").path("$ref").asText())
                    .isEqualTo("#/components/schemas/TeamResponse");

            // POST /teams/{teamId}/members -> 201 TeamMemberResponse
            JsonNode addMemberResp = getOperation(root, "/api/v1/teams/{teamId}/members", "post")
                    .path("responses").path("201");
            assertThat(addMemberResp.path("description").asText()).isEqualTo("Team member added successfully");
            assertThat(addMemberResp.path("content").path("application/json").path("schema").path("$ref").asText())
                    .isEqualTo("#/components/schemas/TeamMemberResponse");
        }

        @Test
        @DisplayName("Item retrieval and mutation endpoints return 200 OK with correct response schemas")
        void singleItemEndpointsReturn200WithSchema() throws Exception {
            JsonNode root = fetchOpenApiSpec();

            // GET /workspaces/{workspaceId}/teams/{teamId} -> 200 TeamResponse
            JsonNode getWsTeam = getOperation(root, "/api/v1/organizations/{organizationId}/workspaces/{workspaceId}/teams/{teamId}", "get")
                    .path("responses").path("200");
            assertThat(getWsTeam.path("content").path("application/json").path("schema").path("$ref").asText())
                    .isEqualTo("#/components/schemas/TeamResponse");

            // GET /teams/{teamId} -> 200 TeamResponse
            JsonNode getTeam = getOperation(root, "/api/v1/teams/{teamId}", "get")
                    .path("responses").path("200");
            assertThat(getTeam.path("content").path("application/json").path("schema").path("$ref").asText())
                    .isEqualTo("#/components/schemas/TeamResponse");

            // PATCH /teams/{teamId} -> 200 TeamResponse
            JsonNode updateTeam = getOperation(root, "/api/v1/teams/{teamId}", "patch")
                    .path("responses").path("200");
            assertThat(updateTeam.path("content").path("application/json").path("schema").path("$ref").asText())
                    .isEqualTo("#/components/schemas/TeamResponse");

            // POST /teams/{teamId}/archive -> 200 TeamResponse
            JsonNode archiveTeam = getOperation(root, "/api/v1/teams/{teamId}/archive", "post")
                    .path("responses").path("200");
            assertThat(archiveTeam.path("content").path("application/json").path("schema").path("$ref").asText())
                    .isEqualTo("#/components/schemas/TeamResponse");

            // GET /teams/{teamId}/lead -> 200 TeamLeadResponse
            JsonNode getLead = getOperation(root, "/api/v1/teams/{teamId}/lead", "get")
                    .path("responses").path("200");
            assertThat(getLead.path("content").path("application/json").path("schema").path("$ref").asText())
                    .isEqualTo("#/components/schemas/TeamLeadResponse");

            // PUT /teams/{teamId}/lead -> 200 TeamLeadResponse
            JsonNode transferLead = getOperation(root, "/api/v1/teams/{teamId}/lead", "put")
                    .path("responses").path("200");
            assertThat(transferLead.path("content").path("application/json").path("schema").path("$ref").asText())
                    .isEqualTo("#/components/schemas/TeamLeadResponse");
        }

        @Test
        @DisplayName("Collection listing endpoints return 200 OK with array response schemas")
        void collectionEndpointsReturn200WithArraySchema() throws Exception {
            JsonNode root = fetchOpenApiSpec();

            // GET /workspaces/{workspaceId}/teams -> 200 Array[TeamResponse]
            JsonNode listTeams = getOperation(root, "/api/v1/organizations/{organizationId}/workspaces/{workspaceId}/teams", "get")
                    .path("responses").path("200");
            JsonNode listTeamsSchema = listTeams.path("content").path("application/json").path("schema");
            assertThat(listTeamsSchema.path("type").asText()).isEqualTo("array");
            assertThat(listTeamsSchema.path("items").path("$ref").asText())
                    .isEqualTo("#/components/schemas/TeamResponse");

            // GET /teams/{teamId}/members -> 200 Array[TeamMemberResponse]
            JsonNode listMembers = getOperation(root, "/api/v1/teams/{teamId}/members", "get")
                    .path("responses").path("200");
            JsonNode listMembersSchema = listMembers.path("content").path("application/json").path("schema");
            assertThat(listMembersSchema.path("type").asText()).isEqualTo("array");
            assertThat(listMembersSchema.path("items").path("$ref").asText())
                    .isEqualTo("#/components/schemas/TeamMemberResponse");
        }

        @Test
        @DisplayName("Void action endpoints return 204 No Content with empty response body")
        void voidActionEndpointsReturn204WithoutBody() throws Exception {
            JsonNode root = fetchOpenApiSpec();

            // DELETE /teams/{teamId}/members/{userId} -> 204 No Content
            JsonNode removeMember = getOperation(root, "/api/v1/teams/{teamId}/members/{userId}", "delete")
                    .path("responses").path("204");
            assertThat(removeMember.path("description").asText()).isEqualTo("Team member removed successfully");
            assertThat(removeMember.path("content").isMissingNode() || removeMember.path("content").isEmpty()).isTrue();

            // POST /teams/{teamId}/leave -> 204 No Content
            JsonNode leaveTeam = getOperation(root, "/api/v1/teams/{teamId}/leave", "post")
                    .path("responses").path("204");
            assertThat(leaveTeam.path("description").asText()).isEqualTo("Successfully left the team");
            assertThat(leaveTeam.path("content").isMissingNode() || leaveTeam.path("content").isEmpty()).isTrue();
        }
    }

    // ── 3. Error Response Codes & Error Schema ─────────────────────────────────

    @Nested
    @DisplayName("3. Error Response Contracts")
    class ErrorResponseContractTests {

        @Test
        @DisplayName("Error response codes match actual controller and GlobalExceptionHandler mappings")
        void errorResponseCodesMatchHandlerBehavior() throws Exception {
            JsonNode root = fetchOpenApiSpec();

            // 1. createTeam: 400, 401, 403, 404, 409
            JsonNode ctResp = getOperation(root, "/api/v1/organizations/{organizationId}/workspaces/{workspaceId}/teams", "post").path("responses");
            assertThat(ctResp.has("400")).isTrue();
            assertThat(ctResp.has("401")).isTrue();
            assertThat(ctResp.has("403")).isTrue();
            assertThat(ctResp.has("404")).isTrue();
            assertThat(ctResp.has("409")).isTrue();

            // 2. listWorkspaceTeams: 401, 403, 404
            JsonNode ltResp = getOperation(root, "/api/v1/organizations/{organizationId}/workspaces/{workspaceId}/teams", "get").path("responses");
            assertThat(ltResp.has("401")).isTrue();
            assertThat(ltResp.has("403")).isTrue();
            assertThat(ltResp.has("404")).isTrue();

            // 3. getTeam: 401, 403, 404
            JsonNode gtResp = getOperation(root, "/api/v1/teams/{teamId}", "get").path("responses");
            assertThat(gtResp.has("401")).isTrue();
            assertThat(gtResp.has("403")).isTrue();
            assertThat(gtResp.has("404")).isTrue();

            // 4. updateTeam: 400, 401, 403, 404, 409
            JsonNode utResp = getOperation(root, "/api/v1/teams/{teamId}", "patch").path("responses");
            assertThat(utResp.has("400")).isTrue();
            assertThat(utResp.has("401")).isTrue();
            assertThat(utResp.has("403")).isTrue();
            assertThat(utResp.has("404")).isTrue();
            assertThat(utResp.has("409")).isTrue();

            // 5. archiveTeam: 400, 401, 403, 404
            JsonNode atResp = getOperation(root, "/api/v1/teams/{teamId}/archive", "post").path("responses");
            assertThat(atResp.has("400")).isTrue();
            assertThat(atResp.has("401")).isTrue();
            assertThat(atResp.has("403")).isTrue();
            assertThat(atResp.has("404")).isTrue();

            // 6. addTeamMember: 400, 401, 403, 404, 409
            JsonNode amResp = getOperation(root, "/api/v1/teams/{teamId}/members", "post").path("responses");
            assertThat(amResp.has("400")).isTrue();
            assertThat(amResp.has("401")).isTrue();
            assertThat(amResp.has("403")).isTrue();
            assertThat(amResp.has("404")).isTrue();
            assertThat(amResp.has("409")).isTrue();

            // 7. removeTeamMember: 400, 401, 403, 404
            JsonNode rmResp = getOperation(root, "/api/v1/teams/{teamId}/members/{userId}", "delete").path("responses");
            assertThat(rmResp.has("400")).isTrue();
            assertThat(rmResp.has("401")).isTrue();
            assertThat(rmResp.has("403")).isTrue();
            assertThat(rmResp.has("404")).isTrue();

            // 8. leaveTeam: 400, 401, 403, 404
            JsonNode lvResp = getOperation(root, "/api/v1/teams/{teamId}/leave", "post").path("responses");
            assertThat(lvResp.has("400")).isTrue();
            assertThat(lvResp.has("401")).isTrue();
            assertThat(lvResp.has("403")).isTrue();
            assertThat(lvResp.has("404")).isTrue();

            // 9. transferTeamLeadership: 400, 401, 403, 404
            JsonNode tlResp = getOperation(root, "/api/v1/teams/{teamId}/lead", "put").path("responses");
            assertThat(tlResp.has("400")).isTrue();
            assertThat(tlResp.has("401")).isTrue();
            assertThat(tlResp.has("403")).isTrue();
            assertThat(tlResp.has("404")).isTrue();
        }

        @Test
        @DisplayName("All error responses reference the canonical ErrorResponse schema")
        void allErrorResponsesReferenceCanonicalErrorSchema() throws Exception {
            JsonNode root = fetchOpenApiSpec();
            JsonNode paths = root.path("paths");

            List<String> teamPaths = List.of(
                    "/api/v1/organizations/{organizationId}/workspaces/{workspaceId}/teams",
                    "/api/v1/organizations/{organizationId}/workspaces/{workspaceId}/teams/{teamId}",
                    "/api/v1/teams/{teamId}",
                    "/api/v1/teams/{teamId}/archive",
                    "/api/v1/teams/{teamId}/members",
                    "/api/v1/teams/{teamId}/members/{userId}",
                    "/api/v1/teams/{teamId}/leave",
                    "/api/v1/teams/{teamId}/lead"
            );

            for (String path : teamPaths) {
                JsonNode pathNode = paths.path(path);
                Iterator<Map.Entry<String, JsonNode>> ops = pathNode.fields();
                while (ops.hasNext()) {
                    Map.Entry<String, JsonNode> opEntry = ops.next();
                    String method = opEntry.getKey();
                    if (!Set.of("get", "post", "put", "patch", "delete").contains(method)) {
                        continue;
                    }
                    JsonNode responses = opEntry.getValue().path("responses");
                    for (String errorCode : List.of("400", "401", "403", "404", "409")) {
                        if (responses.has(errorCode)) {
                            JsonNode errResp = responses.path(errorCode);
                            JsonNode schemaNode = errResp.path("content").path("application/json").path("schema");
                            assertThat(schemaNode.path("$ref").asText())
                                    .as("Endpoint %s %s [%s] must reference ErrorResponse schema", method.toUpperCase(), path, errorCode)
                                    .isEqualTo("#/components/schemas/ErrorResponse");
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("ErrorResponse schema defines the canonical GlobalExceptionHandler structure")
        void errorResponseSchemaStructureMatchesHandler() throws Exception {
            JsonNode root = fetchOpenApiSpec();
            JsonNode errorSchema = root.path("components").path("schemas").path("ErrorResponse");
            assertThat(errorSchema.isMissingNode()).isFalse();

            assertThat(errorSchema.path("type").asText()).isEqualTo("object");
            JsonNode props = errorSchema.path("properties");

            // Canonical 5 fields: timestamp, status, error, message, path
            assertThat(props.has("timestamp")).isTrue();
            assertThat(props.path("timestamp").path("type").asText()).isEqualTo("string");
            assertThat(props.path("timestamp").path("format").asText()).isEqualTo("date-time");

            assertThat(props.has("status")).isTrue();
            assertThat(props.path("status").path("type").asText()).isEqualTo("integer");

            assertThat(props.has("error")).isTrue();
            assertThat(props.path("error").path("type").asText()).isEqualTo("string");

            assertThat(props.has("message")).isTrue();
            assertThat(props.path("message").path("type").asText()).isEqualTo("string");

            assertThat(props.has("path")).isTrue();
            assertThat(props.path("path").path("type").asText()).isEqualTo("string");

            // Required fields
            JsonNode required = errorSchema.path("required");
            assertThat(required.isArray()).isTrue();
            List<String> reqFields = new ArrayList<>();
            required.forEach(node -> reqFields.add(node.asText()));
            assertThat(reqFields).containsExactlyInAnyOrder("timestamp", "status", "error", "message", "path");
        }
    }

    // ── 4. Request Body Schemas & Examples ────────────────────────────────────

    @Nested
    @DisplayName("4. Request Body Schemas & Examples")
    class RequestBodyContractTests {

        @Test
        @DisplayName("Request bodies on mutating endpoints reference correct schemas and are marked required")
        void mutatingEndpointsDeclareRequiredRequestBodies() throws Exception {
            JsonNode root = fetchOpenApiSpec();

            // POST /workspaces/{workspaceId}/teams -> CreateTeamRequest
            JsonNode createTeamBody = getOperation(root, "/api/v1/organizations/{organizationId}/workspaces/{workspaceId}/teams", "post")
                    .path("requestBody");
            assertThat(createTeamBody.path("required").asBoolean()).isTrue();
            assertThat(createTeamBody.path("content").path("application/json").path("schema").path("$ref").asText())
                    .isEqualTo("#/components/schemas/CreateTeamRequest");

            // PATCH /teams/{teamId} -> UpdateTeamRequest
            JsonNode updateTeamBody = getOperation(root, "/api/v1/teams/{teamId}", "patch")
                    .path("requestBody");
            assertThat(updateTeamBody.path("required").asBoolean()).isTrue();
            assertThat(updateTeamBody.path("content").path("application/json").path("schema").path("$ref").asText())
                    .isEqualTo("#/components/schemas/UpdateTeamRequest");

            // POST /teams/{teamId}/members -> AddTeamMemberRequest
            JsonNode addMemberBody = getOperation(root, "/api/v1/teams/{teamId}/members", "post")
                    .path("requestBody");
            assertThat(addMemberBody.path("required").asBoolean()).isTrue();
            assertThat(addMemberBody.path("content").path("application/json").path("schema").path("$ref").asText())
                    .isEqualTo("#/components/schemas/AddTeamMemberRequest");

            // PUT /teams/{teamId}/lead -> TransferTeamLeadershipRequest
            JsonNode transferLeadBody = getOperation(root, "/api/v1/teams/{teamId}/lead", "put")
                    .path("requestBody");
            assertThat(transferLeadBody.path("required").asBoolean()).isTrue();
            assertThat(transferLeadBody.path("content").path("application/json").path("schema").path("$ref").asText())
                    .isEqualTo("#/components/schemas/TransferTeamLeadershipRequest");
        }

        @Test
        @DisplayName("Request schemas define validation constraints and example values")
        void requestSchemasDefineConstraintsAndExamples() throws Exception {
            JsonNode root = fetchOpenApiSpec();
            JsonNode schemas = root.path("components").path("schemas");

            // CreateTeamRequest: name required, maxLength 100, example "Core Backend"
            JsonNode ctr = schemas.path("CreateTeamRequest");
            assertThat(ctr.path("required").toString()).contains("name");
            assertThat(ctr.path("properties").path("name").path("maxLength").asInt()).isEqualTo(100);
            assertThat(ctr.path("properties").path("name").path("example").asText()).isEqualTo("Core Backend");
            assertThat(ctr.path("properties").path("description").path("maxLength").asInt()).isEqualTo(500);

            // UpdateTeamRequest: name maxLength 100, example "Platform Engineering"
            JsonNode utr = schemas.path("UpdateTeamRequest");
            assertThat(utr.path("properties").path("name").path("maxLength").asInt()).isEqualTo(100);
            assertThat(utr.path("properties").path("name").path("example").asText()).isEqualTo("Platform Engineering");

            // AddTeamMemberRequest: userId required, UUID example
            JsonNode amr = schemas.path("AddTeamMemberRequest");
            assertThat(amr.path("required").toString()).contains("userId");
            assertThat(amr.path("properties").path("userId").path("example").asText()).isNotEmpty();

            // TransferTeamLeadershipRequest: userId required, UUID example
            JsonNode ttl = schemas.path("TransferTeamLeadershipRequest");
            assertThat(ttl.path("required").toString()).contains("userId");
            assertThat(ttl.path("properties").path("userId").path("example").asText()).isNotEmpty();
        }
    }

    // ── 5. Response Schemas Properties & Examples ─────────────────────────────

    @Nested
    @DisplayName("5. Response Schema Properties & Examples")
    class ResponseSchemaContractTests {

        @Test
        @DisplayName("TeamResponse schema defines all scalar attributes with examples")
        void teamResponsePropertiesAndExamples() throws Exception {
            JsonNode root = fetchOpenApiSpec();
            JsonNode teamResponse = root.path("components").path("schemas").path("TeamResponse");
            JsonNode props = teamResponse.path("properties");

            assertThat(props.has("id")).isTrue();
            assertThat(props.path("id").path("example").asText()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");

            assertThat(props.has("name")).isTrue();
            assertThat(props.path("name").path("example").asText()).isEqualTo("Core Backend");

            assertThat(props.has("workspaceId")).isTrue();
            assertThat(props.has("organizationId")).isTrue();
            assertThat(props.has("description")).isTrue();
            assertThat(props.has("status")).isTrue();
            assertThat(props.path("status").path("example").asText()).isEqualTo("ACTIVE");

            assertThat(props.has("createdAt")).isTrue();
            assertThat(props.has("updatedAt")).isTrue();
        }

        @Test
        @DisplayName("TeamMemberResponse schema defines role and membership details with examples")
        void teamMemberResponsePropertiesAndExamples() throws Exception {
            JsonNode root = fetchOpenApiSpec();
            JsonNode memberResponse = root.path("components").path("schemas").path("TeamMemberResponse");
            JsonNode props = memberResponse.path("properties");

            assertThat(props.has("id")).isTrue();
            assertThat(props.has("teamId")).isTrue();
            assertThat(props.has("userId")).isTrue();

            assertThat(props.has("role")).isTrue();
            assertThat(props.path("role").path("example").asText()).isEqualTo("MEMBER");

            assertThat(props.has("joinedAt")).isTrue();
            assertThat(props.has("email")).isTrue();
            assertThat(props.path("email").path("example").asText()).isEqualTo("developer@devflow.com");

            assertThat(props.has("username")).isTrue();
            assertThat(props.path("username").path("example").asText()).isEqualTo("developer");
        }

        @Test
        @DisplayName("TeamLeadResponse schema defines lead role and designated member details with examples")
        void teamLeadResponsePropertiesAndExamples() throws Exception {
            JsonNode root = fetchOpenApiSpec();
            JsonNode leadResponse = root.path("components").path("schemas").path("TeamLeadResponse");
            JsonNode props = leadResponse.path("properties");

            assertThat(props.has("id")).isTrue();
            assertThat(props.has("teamId")).isTrue();
            assertThat(props.has("userId")).isTrue();

            assertThat(props.has("role")).isTrue();
            assertThat(props.path("role").path("example").asText()).isEqualTo("LEAD");

            assertThat(props.has("joinedAt")).isTrue();
            assertThat(props.has("email")).isTrue();
            assertThat(props.path("email").path("example").asText()).isEqualTo("lead@devflow.com");

            assertThat(props.has("username")).isTrue();
            assertThat(props.path("username").path("example").asText()).isEqualTo("techlead");
        }
    }

    // ── 6. Parameters & Non-Regression ────────────────────────────────────────

    @Nested
    @DisplayName("6. Parameters & Regression Verification")
    class ParameterAndRegressionTests {

        @Test
        @DisplayName("Path and query parameters are documented with descriptions and examples")
        void parametersAreDocumentedWithExamples() throws Exception {
            JsonNode root = fetchOpenApiSpec();
            JsonNode paths = root.path("paths");

            // Query parameter 'status' on listWorkspaceTeams
            JsonNode listTeams = paths.path("/api/v1/organizations/{organizationId}/workspaces/{workspaceId}/teams").path("get");
            JsonNode params = listTeams.path("parameters");
            boolean foundStatus = false;
            for (JsonNode param : params) {
                if ("status".equals(param.path("name").asText())) {
                    foundStatus = true;
                    assertThat(param.path("in").asText()).isEqualTo("query");
                    assertThat(param.path("required").asBoolean()).isFalse();
                    assertThat(param.path("example").asText()).isEqualTo("ACTIVE");
                }
            }
            assertThat(foundStatus).isTrue();

            // Path parameter 'teamId' on getTeam
            JsonNode getTeam = paths.path("/api/v1/teams/{teamId}").path("get");
            boolean foundTeamId = false;
            for (JsonNode param : getTeam.path("parameters")) {
                if ("teamId".equals(param.path("name").asText())) {
                    foundTeamId = true;
                    assertThat(param.path("in").asText()).isEqualTo("path");
                    assertThat(param.path("required").asBoolean()).isTrue();
                    assertThat(param.path("example").asText()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
                }
            }
            assertThat(foundTeamId).isTrue();
        }

        @Test
        @DisplayName("Existing workspace and organization endpoints are unaffected (no regression)")
        void existingApiEndpointsRemainDocumented() throws Exception {
            JsonNode root = fetchOpenApiSpec();
            JsonNode paths = root.path("paths");

            assertThat(paths.has("/api/v1/organizations")).isTrue();
            assertThat(paths.has("/api/v1/organizations/{organizationId}/workspaces")).isTrue();
            assertThat(paths.has("/api/v1/auth/login")).isTrue();
            assertThat(paths.has("/api/v1/users/me")).isTrue();
        }
    }
}
