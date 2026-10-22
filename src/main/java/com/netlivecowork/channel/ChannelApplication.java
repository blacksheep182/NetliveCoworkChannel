package com.netlivecowork.channel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ChannelConfig.class)
public class ChannelApplication {
    public static void main(String[] args) { SpringApplication.run(ChannelApplication.class,args); }
}
