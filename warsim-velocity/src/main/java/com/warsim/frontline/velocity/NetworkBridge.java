package com.warsim.frontline.velocity;

import com.warsim.frontline.network.NetworkMessage;
import com.velocitypowered.api.proxy.ServerConnection;

interface NetworkBridge {
    boolean send(ServerConnection connection, NetworkMessage message);
}
