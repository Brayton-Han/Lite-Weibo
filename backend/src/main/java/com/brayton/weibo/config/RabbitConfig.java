package com.brayton.weibo.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "notification.exchange";

    public static final String FOLLOW_QUEUE = "notification.follow.queue";
    public static final String LIKE_QUEUE = "notification.like.queue";
    public static final String COMMENT_QUEUE = "notification.comment.queue";

    @Bean
    public TopicExchange notificationExchange() {
        return new TopicExchange(EXCHANGE);
    }

    @Bean
    public Queue followQueue() {
        return new Queue(FOLLOW_QUEUE, true);
    }

    @Bean
    public Queue likeQueue() {
        return new Queue(LIKE_QUEUE, true);
    }

    @Bean
    public Queue commentQueue() {
        return new Queue(COMMENT_QUEUE, true);
    }

    @Bean
    public Binding bindFollow() {
        return BindingBuilder.bind(followQueue())
                .to(notificationExchange())
                .with("notification.follow");
    }

    @Bean
    public Binding bindLike() {
        return BindingBuilder.bind(likeQueue())
                .to(notificationExchange())
                .with("notification.like");
    }

    @Bean
    public Binding bindComment() {
        return BindingBuilder.bind(commentQueue())
                .to(notificationExchange())
                .with("notification.comment");
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory cf, MessageConverter mc) {
        RabbitTemplate template = new RabbitTemplate(cf);
        template.setMessageConverter(mc);
        return template;
    }
}

