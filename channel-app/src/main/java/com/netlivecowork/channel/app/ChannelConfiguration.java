package com.netlivecowork.channel.app;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 装配：进程内运行时、管理端鉴权过滤器与周期任务。 */
@Configuration
class ChannelConfiguration {

    @Bean(destroyMethod = "close")
    ChannelRuntime channelRuntime(ChannelProperties properties) {
        ChannelRuntime runtime = ChannelRuntime.create(properties);
        runtime.start();
        return runtime;
    }

    @Bean
    FilterRegistrationBean<AdminAuthFilter> adminAuthFilter(ChannelProperties properties) {
        FilterRegistrationBean<AdminAuthFilter> registration = new FilterRegistrationBean<>(
            new AdminAuthFilter(properties.adminToken()));
        registration.addUrlPatterns("/api/v1/channel/*");
        registration.setOrder(1);
        return registration;
    }

    /** 周期任务：把网络调用留在事务之外，由单线程按固定间隔推进（INV-20）。 */
    @Component
    static class ChannelTick {
        private final ChannelRuntime runtime;

        ChannelTick(ChannelRuntime runtime) {
            this.runtime = runtime;
        }

        @Scheduled(fixedDelay = 500)
        void tick() {
            runtime.tick();
        }
    }
}
