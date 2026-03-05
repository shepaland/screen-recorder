package com.prg.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "prg.email")
public class EmailConfig {

    /** From email address */
    private String from = "otp@shepaland.ru";

    /** From display name */
    private String fromName = "Кадеро";
}
