package com.prg.auth.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthCallbackResponse {

    private String status;
    private String accessToken;
    private String tokenType;
    private Integer expiresIn;
    private Object user;
    private String oauthToken;
    private Integer oauthTokenExpiresIn;
    private OAuthUserInfo oauthUser;
    private List<TenantPreview> tenants;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OAuthUserInfo {
        private String email;
        private String name;
        private String avatarUrl;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TenantPreview {
        private UUID id;
        private String name;
        private String slug;
        private String role;
        private Boolean isCurrent;
    }
}
