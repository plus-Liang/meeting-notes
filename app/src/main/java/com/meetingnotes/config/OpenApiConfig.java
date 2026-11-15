package com.meetingnotes.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 契约（T4.6 / US-10 / D-019 第三面）：全局声明 bearer securityScheme，
 * 不给每个 controller 堆注解；/v3/api-docs 与 /swagger-ui.html 由 springdoc starter 提供（匿名可访问）。
 */
@Configuration
public class OpenApiConfig {

    private static final String SCHEME_NAME = "bearer";

    @Bean
    public OpenAPI meetingNotesOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("MeetingNotes API")
                        .description("自托管「录音/网课 → 结构化纪要 + 待办」；/api/v1/** 需 Authorization: Bearer <API_TOKEN>")
                        .version("v1"))
                .components(new Components().addSecuritySchemes(SCHEME_NAME,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME_NAME));
    }
}
