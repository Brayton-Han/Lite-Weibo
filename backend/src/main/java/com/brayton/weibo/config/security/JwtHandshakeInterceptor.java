package com.brayton.weibo.config.security;

import lombok.RequiredArgsConstructor;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private final JWTService jwtService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {

        String token = null;

        // 1. Try to get from Header (keeping your original logic just in case)
        List<String> authHeaders = request.getHeaders().get("Authorization");
        if (authHeaders != null && !authHeaders.isEmpty()) {
            token = authHeaders.get(0).replace("Bearer ", "");
        }

        // 2. If header is empty, try to get from Query Parameter (The Fix)
        if (token == null && request instanceof ServletServerHttpRequest) {
            ServletServerHttpRequest servletRequest = (ServletServerHttpRequest) request;
            String query = servletRequest.getServletRequest().getQueryString();
            if (query != null && query.contains("token=")) {
                // Parse "token=xyz" manually or use a utility
                // Simple parsing example:
                for (String param : query.split("&")) {
                    if (param.startsWith("token=")) {
                        token = param.substring(6);
                        break;
                    }
                }
            }
        }

        if (token != null) {
            try {
                Long userId = jwtService.getUserIdFromToken(token);
                if (userId != null) {
                    attributes.put("userId", userId.toString());
                    return true;
                }
            } catch (Exception e) {
                System.out.println("WebSocket Auth Failed: " + e.getMessage());
            }
        }

        // Return false to reject the connection if no valid token found
        // Or return true allowing anonymous connections (but notifyUser... won't work)
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {}
}