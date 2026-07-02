package com.warsim.frontline.network.redis;

import com.warsim.frontline.api.ModuleState;
import com.warsim.frontline.api.node.NodeType;
import com.warsim.frontline.api.redis.NodeAvailability;
import com.warsim.frontline.api.redis.NodeSnapshot;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class NodeSnapshotMapper {
    private NodeSnapshotMapper() {
    }

    static Map<String, String> toMap(NodeSnapshot snapshot) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("nodeId", snapshot.nodeId());
        fields.put("nodeType", snapshot.nodeType().name());
        fields.put("instanceId", snapshot.instanceId().toString());
        fields.put("lifecycleState", snapshot.lifecycleState().name());
        fields.put("availability", snapshot.availability().name());
        fields.put("onlinePlayers", Integer.toString(snapshot.onlinePlayers()));
        fields.put("maximumPlayers", Integer.toString(snapshot.maximumPlayers()));
        fields.put("reservedPlayers", Integer.toString(snapshot.reservedPlayers()));
        fields.put("acceptingPlayers", Boolean.toString(snapshot.acceptingPlayers()));
        fields.put("startedAt", Long.toString(snapshot.startedAt().toEpochMilli()));
        fields.put("lastHeartbeatAt", Long.toString(snapshot.lastHeartbeatAt().toEpochMilli()));
        fields.put("protocolVersion", Integer.toString(snapshot.protocolVersion()));
        fields.put("buildVersion", snapshot.buildVersion());
        return fields;
    }

    static NodeSnapshot fromMap(Map<String, String> fields) {
        return new NodeSnapshot(
            required(fields, "nodeId"),
            NodeType.valueOf(required(fields, "nodeType")),
            UUID.fromString(required(fields, "instanceId")),
            ModuleState.valueOf(required(fields, "lifecycleState")),
            NodeAvailability.valueOf(required(fields, "availability")),
            Integer.parseInt(required(fields, "onlinePlayers")),
            Integer.parseInt(required(fields, "maximumPlayers")),
            Integer.parseInt(required(fields, "reservedPlayers")),
            Boolean.parseBoolean(required(fields, "acceptingPlayers")),
            Instant.ofEpochMilli(Long.parseLong(required(fields, "startedAt"))),
            Instant.ofEpochMilli(Long.parseLong(required(fields, "lastHeartbeatAt"))),
            Integer.parseInt(required(fields, "protocolVersion")),
            required(fields, "buildVersion")
        );
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing node field " + key);
        }
        return value;
    }
}
