package com.ragagent.auth.dto;

/** POST /auth/refresh：新签发的一对令牌。 */
public record TokenPairResponse(String token, String refreshToken) {
}
