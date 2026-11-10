package com.brayton.weibo.config;

import com.brayton.weibo.service.JWTService;
import com.brayton.weibo.config.security.JwtHandshakeInterceptor;
import com.brayton.weibo.config.security.JwtPrincipalHandshakeHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JWTService jwtService;

    /** 与 REST CORS 共用同一份白名单（WebConfig），防止任意站点用用户的 token 建立 WebSocket */
    @Value("${cors.allowed-origins:http://localhost:3000}")
    private String[] allowedOrigins;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")  // 前端连接地址：/ws
                .setHandshakeHandler(new JwtPrincipalHandshakeHandler())
                .addInterceptors(new JwtHandshakeInterceptor(jwtService))
                .setAllowedOrigins(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 客户端订阅这些前缀的消息
        // 群发：/topic/**
        // 指定用户：/queue/**（配合 convertAndSendToUser）
        registry.enableSimpleBroker("/topic", "/queue");

        // 客户端发送消息到服务端的前缀
        registry.setApplicationDestinationPrefixes("/app");
    }
}
