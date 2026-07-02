package com.warsim.frontline.api.redis;

import com.warsim.frontline.api.node.NodeType;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface NodeDirectoryService {
    CompletableFuture<Optional<NodeSnapshot>> findNode(String nodeId);
    CompletableFuture<List<NodeSnapshot>> listActiveNodes();
    CompletableFuture<List<NodeSnapshot>> listActiveNodesByType(NodeType type);
    CompletableFuture<Boolean> isJoinable(String nodeId);
    CompletableFuture<List<NodeSnapshot>> findJoinableNodes(NodeType type);
    CompletableFuture<Void> removeNode(String nodeId);
    CompletableFuture<List<NodeSnapshot>> refresh();
}
