package com.netlivecowork.channel.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Channel 装配层入口。三层实现分别位于 channel-l1 / channel-l2 / channel-l3，只经公共契约协作
 * （INV-47）；本模块只做进程装配、管理端 API 与协议入口。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ChannelApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChannelApplication.class, args);
    }
}
