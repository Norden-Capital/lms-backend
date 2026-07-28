package org.example.backend.controller;

import org.example.backend.dto.AuthDto.*;
import org.example.backend.entity.User;
import org.example.backend.repository.UserRepository;
import org.example.backend.service.JwtService;
import org.example.backend.service.TwoFactorAuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TwoFactorAuthService twoFactorAuthService;
    private final JwtService jwtService;

    public AuthController(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          TwoFactorAuthService twoFactorAuthService,
                          JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.twoFactorAuthService = twoFactorAuthService;
        this.jwtService = jwtService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Email is already in use.");
        }

        String mfaSecret = request.isEnable2fa() ? twoFactorAuthService.generateSecret() : null;

        User user = User.builder()
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .phone(request.getPhone())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .mfaEnabled(request.isEnable2fa())
                .mfaSecret(mfaSecret)
                .build();

        userRepository.save(user);

        UserDto userDto = mapToUserDto(user);

        if (request.isEnable2fa()) {
            MfaSetupResponse mfaSetup = new MfaSetupResponse();
            mfaSetup.setSecret(mfaSecret);
            mfaSetup.setQrCodeImage(twoFactorAuthService.generateQrCodeDataUrl(mfaSecret, user.getEmail()));
            return ResponseEntity.ok(mfaSetup);
        }

        String token = jwtService.generateToken(user.getEmail());
        return ResponseEntity.ok(new AuthResponse(token, false, "Registration successful", userDto));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElse(null);

        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid email or password.");
        }

        if (user.isMfaEnabled()) {
            if (request.getTotpCode() == null || request.getTotpCode().isEmpty()) {
                return ResponseEntity.ok(new AuthResponse(null, true, "2FA TOTP code required.", mapToUserDto(user)));
            }
            if (!twoFactorAuthService.isCodeValid(user.getMfaSecret(), request.getTotpCode())) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid 2FA code.");
            }
        }

        String token = jwtService.generateToken(user.getEmail());
        return ResponseEntity.ok(new AuthResponse(token, false, "Login successful", mapToUserDto(user)));
    }

    @PostMapping("/2fa/setup")
    public ResponseEntity<?> setup2fa(@RequestParam String email) {
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found.");
        }

        String secret = twoFactorAuthService.generateSecret();
        user.setMfaSecret(secret);
        userRepository.save(user);

        MfaSetupResponse response = new MfaSetupResponse();
        response.setSecret(secret);
        response.setQrCodeImage(twoFactorAuthService.generateQrCodeDataUrl(secret, user.getEmail()));

        return ResponseEntity.ok(response);
    }

    @PostMapping("/2fa/verify")
    public ResponseEntity<?> verify2fa(@RequestBody Verify2FaRequest request) {
        User user = userRepository.findByEmail(request.getEmail()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found.");
        }

        if (twoFactorAuthService.isCodeValid(user.getMfaSecret(), request.getCode())) {
            user.setMfaEnabled(true);
            userRepository.save(user);
            String token = jwtService.generateToken(user.getEmail());
            return ResponseEntity.ok(new AuthResponse(token, false, "2FA successfully enabled!", mapToUserDto(user)));
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid 2FA verification code.");
    }

    private UserDto mapToUserDto(User user) {
        UserDto dto = new UserDto();
        dto.setId(user.getId());
        dto.setFirstName(user.getFirstName());
        dto.setLastName(user.getLastName());
        dto.setPhone(user.getPhone());
        dto.setEmail(user.getEmail());
        dto.setMfaEnabled(user.isMfaEnabled());
        return dto;
    }
}
