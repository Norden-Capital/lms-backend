package org.example.backend.dto;

import lombok.Data;

public class AuthDto {

    @Data
    public static class RegisterRequest {
        private String firstName;
        private String lastName;
        private String phone; // optional
        private String email;
        private String password;
        private boolean enable2fa; // optional on registration
    }

    @Data
    public static class LoginRequest {
        private String email;
        private String password;
        private String totpCode; // required if 2FA is enabled
    }

    @Data
    public static class Verify2FaRequest {
        private String email;
        private String code;
    }

    @Data
    public static class AuthResponse {
        private String token;
        private boolean mfaRequired;
        private String message;
        private UserDto user;

        public AuthResponse(String token, boolean mfaRequired, String message, UserDto user) {
            this.token = token;
            this.mfaRequired = mfaRequired;
            this.message = message;
            this.user = user;
        }
    }

    @Data
    public static class MfaSetupResponse {
        private String secret;
        private String qrCodeImage; // Base64 Data URL for Google Authenticator QR Code
    }

    @Data
    public static class UserDto {
        private Long id;
        private String firstName;
        private String lastName;
        private String phone;
        private String email;
        private boolean mfaEnabled;
    }
}
