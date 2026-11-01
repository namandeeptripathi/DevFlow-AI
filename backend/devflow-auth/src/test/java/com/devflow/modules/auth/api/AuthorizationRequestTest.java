package com.devflow.modules.auth.api;

import com.devflow.modules.auth.context.AuthorizationContext;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.RoleScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizationRequestTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final Permission PERMISSION = Permission.of("task.create");

    @Test
    @DisplayName("Create platform authorization request via platform(userId, permission)")
    void shouldCreatePlatformRequest() {
        AuthorizationRequest request = AuthorizationRequest.platform(USER_ID, PERMISSION);

        assertThat(request.userId()).isEqualTo(USER_ID);
        assertThat(request.context().getScope()).isEqualTo(RoleScope.PLATFORM);
        assertThat(request.getScope()).isEqualTo(RoleScope.PLATFORM);
        assertThat(request.getOrganizationId()).isEmpty();
        assertThat(request.getProjectId()).isEmpty();
        assertThat(request.permission()).isEqualTo(PERMISSION);
        assertThat(request.resourceId()).isNull();
        assertThat(request.getResourceId()).isEmpty();
        assertThat(request.attributes()).isEmpty();
    }

    @Test
    @DisplayName("Create organization authorization request via organization(userId, orgId, permission)")
    void shouldCreateOrganizationRequest() {
        AuthorizationRequest request = AuthorizationRequest.organization(USER_ID, ORG_ID, PERMISSION);

        assertThat(request.userId()).isEqualTo(USER_ID);
        assertThat(request.context().getScope()).isEqualTo(RoleScope.ORGANIZATION);
        assertThat(request.getScope()).isEqualTo(RoleScope.ORGANIZATION);
        assertThat(request.getOrganizationId()).contains(ORG_ID);
        assertThat(request.getProjectId()).isEmpty();
        assertThat(request.permission()).isEqualTo(PERMISSION);
        assertThat(request.resourceId()).isNull();
    }

    @Test
    @DisplayName("Create project authorization request via project(userId, orgId, projectId, permission)")
    void shouldCreateProjectRequest() {
        AuthorizationRequest request = AuthorizationRequest.project(USER_ID, ORG_ID, PROJECT_ID, PERMISSION, "task-99");

        assertThat(request.userId()).isEqualTo(USER_ID);
        assertThat(request.context().getScope()).isEqualTo(RoleScope.PROJECT);
        assertThat(request.getScope()).isEqualTo(RoleScope.PROJECT);
        assertThat(request.getOrganizationId()).contains(ORG_ID);
        assertThat(request.getProjectId()).contains(PROJECT_ID);
        assertThat(request.permission()).isEqualTo(PERMISSION);
        assertThat(request.resourceId()).isEqualTo("task-99");
        assertThat(request.getResourceId()).contains("task-99");
    }

    @Test
    @DisplayName("Backward-compatible of(userId, orgId, permission) creates organization request")
    void shouldSupportBackwardCompatibleFactory() {
        AuthorizationRequest request = AuthorizationRequest.of(USER_ID, ORG_ID, PERMISSION);

        assertThat(request.getScope()).isEqualTo(RoleScope.ORGANIZATION);
        assertThat(request.getOrganizationId()).contains(ORG_ID);
        assertThat(request.getProjectId()).isEmpty();
    }

    @Test
    @DisplayName("Create request with typed context via of(userId, context, permission)")
    void shouldSupportGenericTypedContextFactory() {
        AuthorizationContext projectContext = AuthorizationContext.project(ORG_ID, PROJECT_ID);
        AuthorizationRequest request = AuthorizationRequest.of(USER_ID, projectContext, PERMISSION);

        assertThat(request.context()).isEqualTo(projectContext);
        assertThat(request.getScope()).isEqualTo(RoleScope.PROJECT);
        assertThat(request.getOrganizationId()).contains(ORG_ID);
        assertThat(request.getProjectId()).contains(PROJECT_ID);
    }

    @Test
    @DisplayName("Reject null required request parameters")
    void shouldRejectNullRequiredParameters() {
        assertThatThrownBy(() -> AuthorizationRequest.of(null, AuthorizationContext.platform(), PERMISSION))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("userId");

        assertThatThrownBy(() -> AuthorizationRequest.of(USER_ID, (AuthorizationContext) null, PERMISSION))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("context");

        assertThatThrownBy(() -> AuthorizationRequest.of(USER_ID, AuthorizationContext.platform(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("permission");
    }

    @Test
    @DisplayName("Support various immutable scalar and temporal attribute value types")
    void shouldSupportImmutableAttributeTypes() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("isOwner", true);
        attrs.put("count", 42);
        attrs.put("rate", 99.5);
        attrs.put("name", "audit");
        attrs.put("refId", UUID.randomUUID());
        attrs.put("scopeEnum", RoleScope.ORGANIZATION);
        attrs.put("timestamp", Instant.now());
        attrs.put("date", LocalDate.now());
        attrs.put("time", LocalTime.now());
        attrs.put("duration", Duration.ofMinutes(15));

        AuthorizationRequest request = AuthorizationRequest.platform(USER_ID, PERMISSION, attrs);

        assertThat(request.attributes()).hasSize(10);
        assertThat(request.attributes().get("isOwner")).isEqualTo(true);
        assertThat(request.attributes().get("count")).isEqualTo(42);
    }

    @Test
    @DisplayName("Deep defensive copy of nested Map ensures immutability against caller modification")
    void shouldDeepDefensivelyCopyNestedMap() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("tag", "confidential");

        Map<String, Object> root = new HashMap<>();
        root.put("metadata", nested);

        AuthorizationRequest request = AuthorizationRequest.platform(USER_ID, PERMISSION, root);

        // Caller mutates original map
        nested.put("tag", "public");
        nested.put("newKey", "malicious");

        @SuppressWarnings("unchecked")
        Map<String, Object> reqNested = (Map<String, Object>) request.attributes().get("metadata");

        assertThat(reqNested.get("tag")).isEqualTo("confidential");
        assertThat(reqNested).doesNotContainKey("newKey");

        // Attempting to mutate returned map fails
        assertThatThrownBy(() -> reqNested.put("other", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Deep defensive copy of nested Collection ensures immutability against caller modification")
    void shouldDeepDefensivelyCopyNestedCollection() {
        List<String> list = new ArrayList<>();
        list.add("item1");
        list.add("item2");

        Map<String, Object> root = new HashMap<>();
        root.put("tags", list);

        AuthorizationRequest request = AuthorizationRequest.platform(USER_ID, PERMISSION, root);

        // Caller mutates source list
        list.add("item3");

        @SuppressWarnings("unchecked")
        List<Object> reqList = (List<Object>) request.attributes().get("tags");

        assertThat(reqList).containsExactly("item1", "item2");

        // Attempting to mutate returned list fails
        assertThatThrownBy(() -> reqList.add("item4"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Reject null or blank attribute keys")
    void shouldRejectInvalidAttributeKeys() {
        Map<String, Object> nullKeyMap = new HashMap<>();
        nullKeyMap.put(null, "value");

        assertThatThrownBy(() -> AuthorizationRequest.platform(USER_ID, PERMISSION, nullKeyMap))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Attribute key");

        Map<String, Object> blankKeyMap = new HashMap<>();
        blankKeyMap.put("   ", "value");

        assertThatThrownBy(() -> AuthorizationRequest.platform(USER_ID, PERMISSION, blankKeyMap))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Attribute key must not be blank");
    }

    @Test
    @DisplayName("Reject null attribute values")
    void shouldRejectNullAttributeValues() {
        Map<String, Object> nullValueMap = new HashMap<>();
        nullValueMap.put("key", null);

        assertThatThrownBy(() -> AuthorizationRequest.platform(USER_ID, PERMISSION, nullValueMap))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Attribute value for key 'key' must not be null");
    }

    @Test
    @DisplayName("Reject unsupported mutable attribute value types (e.g. java.util.Date)")
    void shouldRejectUnsupportedAttributeTypes() {
        Map<String, Object> unsupportedMap = new HashMap<>();
        unsupportedMap.put("date", new Date());

        assertThatThrownBy(() -> AuthorizationRequest.platform(USER_ID, PERMISSION, unsupportedMap))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported attribute value type");
    }

    @Test
    @DisplayName("Accept all 8 supported immutable numeric types")
    void shouldAcceptAllSupportedImmutableNumericTypes() {
        Map<String, Object> numericMap = new HashMap<>();
        numericMap.put("int", Integer.valueOf(100));
        numericMap.put("long", Long.valueOf(1000L));
        numericMap.put("double", Double.valueOf(3.14159));
        numericMap.put("float", Float.valueOf(2.71f));
        numericMap.put("short", Short.valueOf((short) 10));
        numericMap.put("byte", Byte.valueOf((byte) 1));
        numericMap.put("bigDecimal", new BigDecimal("12345.6789"));
        numericMap.put("bigInteger", new BigInteger("9876543210"));

        AuthorizationRequest request = AuthorizationRequest.platform(USER_ID, PERMISSION, numericMap);

        assertThat(request.attributes()).hasSize(8);
        assertThat(request.attributes().get("int")).isEqualTo(100);
        assertThat(request.attributes().get("long")).isEqualTo(1000L);
        assertThat(request.attributes().get("double")).isEqualTo(3.14159);
        assertThat(request.attributes().get("float")).isEqualTo(2.71f);
        assertThat(request.attributes().get("short")).isEqualTo((short) 10);
        assertThat(request.attributes().get("byte")).isEqualTo((byte) 1);
        assertThat(request.attributes().get("bigDecimal")).isEqualTo(new BigDecimal("12345.6789"));
        assertThat(request.attributes().get("bigInteger")).isEqualTo(new BigInteger("9876543210"));
    }

    @Test
    @DisplayName("Reject mutable numeric types such as AtomicInteger, AtomicLong, LongAdder, and custom Number subclasses")
    void shouldRejectMutableNumericTypes() {
        // AtomicInteger
        Map<String, Object> atomicIntMap = Map.of("atomicInt", new AtomicInteger(42));
        assertThatThrownBy(() -> AuthorizationRequest.platform(USER_ID, PERMISSION, atomicIntMap))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported attribute value type")
                .hasMessageContaining("AtomicInteger");

        // AtomicLong
        Map<String, Object> atomicLongMap = Map.of("atomicLong", new AtomicLong(100L));
        assertThatThrownBy(() -> AuthorizationRequest.platform(USER_ID, PERMISSION, atomicLongMap))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported attribute value type")
                .hasMessageContaining("AtomicLong");

        // LongAdder
        Map<String, Object> longAdderMap = Map.of("longAdder", new LongAdder());
        assertThatThrownBy(() -> AuthorizationRequest.platform(USER_ID, PERMISSION, longAdderMap))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported attribute value type")
                .hasMessageContaining("LongAdder");

        // Custom mutable Number subclass
        Number customNumber = new Number() {
            private int value = 5;
            @Override public int intValue() { return value; }
            @Override public long longValue() { return value; }
            @Override public float floatValue() { return value; }
            @Override public double doubleValue() { return value; }
        };
        Map<String, Object> customNumMap = Map.of("customNum", customNumber);
        assertThatThrownBy(() -> AuthorizationRequest.platform(USER_ID, PERMISSION, customNumMap))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported attribute value type");
    }
}
