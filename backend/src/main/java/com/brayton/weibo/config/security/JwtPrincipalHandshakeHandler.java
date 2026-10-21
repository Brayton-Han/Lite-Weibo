package com.brayton.weibo.config.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;

@Slf4j
@Component
public class JwtPrincipalHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected Principal determineUser(ServerHttpRequest request,
                                      WebSocketHandler wsHandler,
                                      Map<String, Object> attributes) {

        String userId = (String) attributes.get("userId");
        log.debug("WebSocket 握手 userId={}", userId);
        if (userId != null) {
            return () -> userId; // Principal.getName() 返回 userId
        }
        return null;
    }
}
