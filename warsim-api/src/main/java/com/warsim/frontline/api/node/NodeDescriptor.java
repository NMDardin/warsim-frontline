package com.warsim.frontline.api.node;

import java.util.Objects;

/**
 * Immutable identity of a WarSim runtime node.
 */
public record NodeDescriptor(String id, NodeType type) {
    public NodeDescriptor {
        NodeIds.requireValid(id);
        Objects.requireNonNull(type, "type");
    }
}
