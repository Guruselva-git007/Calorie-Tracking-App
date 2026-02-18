package com.calorietracker.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.calorietracker.dto.AuthRequestCodeRequest;
import com.calorietracker.dto.AuthRequestCodeResponse;
import com.calorietracker.dto.AuthSessionResponse;
import com.calorietracker.dto.AuthVerifyCodeRequest;
import com.calorietracker.entity.AppUser;
import com.calorietracker.entity.AuthVerificationCode;
import com.calorietracker.entity.UserSession;
import com.calorietracker.repository.AppUserRepository;
import com.calorietracker.repository.AuthVerificationCodeRepository;
import com.calorietracker.repository.UserSessionRepository;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String GUEST_EMAIL = "guest@calorietracker.local";

    private final AppUserRepository appUserRepository;
    private final AuthVerificationCodeRepository authVerificationCodeRepository;
    private final UserSessionRepository userSessionRepository;
    private final boolean exposeDevCode;
    private final int codeExpiryMinutes;
    private final int sessionHours;

    public AuthService(
        AppUserRepository appUserRepository,
        AuthVerificationCodeRepository authVerificationCodeRepository,
        UserSessionRepository userSessionRepository,
        @Value("${app.auth.dev-code-visible:true}") boolean exposeDevCode,
        @Value("${app.auth.code-expiry-minutes:10}") int codeExpiryMinutes,
        @Value("${app.auth.session-hours:168}") int sessionHours
    ) {
        this.appUserRepository = appUserRepository;
        this.authVerificationCodeRepository = authVerificationCodeRepository;
        this.userSessionRepository = userSessionRepository;
        this.exposeDevCode = exposeDevCode;
        this.codeExpiryMinutes = Math.max(2, codeExpiryMinutes);
        this.sessionHours = Math.max(24, sessionHours);
    }

    @Transactional
    public AuthRequestCodeResponse requestCode(AuthRequestCodeRequest request) {
        LoginTarget target = resolveTarget(request.getEmail(), request.getPhone());

        if (StringUtils.hasText(request.getEmail())) {
            findOrCreateUserByEmail(
                request.getEmail(),
                request.getName(),
                request.getNickname(),
                StringUtils.hasText(request.getPhone()) ? request.getPhone() : null
            );
        } else {
            findOrCreateUserByPhone(request.getPhone(), request.getName(), request.getNickname());
        }

        String code = generateCode();
        Instant expiresAt = Instant.now().plus(codeExpiryMinutes, ChronoUnit.MINUTES);

        AuthVerificationCode verificationCode = new AuthVerificationCode();
        verificationCode.setTargetType(target.type());
        verificationCode.setTargetValue(target.value());
        verificationCode.setCode(code);
        verificationCode.setExpiresAt(expiresAt);
        verificationCode.setConsumed(false);
        authVerificationCodeRepository.save(verificationCode);

        log.info("verification-code {} -> {} (expiresAt={})", target.type(), target.value(), expiresAt);

        AuthRequestCodeResponse response = new AuthRequestCodeResponse();
        response.setStatus("CODE_SENT");
        response.setChannel(target.type());
        response.setTargetMasked(maskTarget(target.type(), target.value()));
        response.setExpiresAt(expiresAt);
        if (exposeDevCode) {
            response.setDevCode(code);
        }
        return response;
    }

    @Transactional
    public AuthSessionResponse verifyCode(AuthVerifyCodeRequest request) {
        LoginTarget target = resolveTarget(request.getEmail(), request.getPhone());
        String normalizedCode = request.getCode().trim();

        AuthVerificationCode latestCode = authVerificationCodeRepository
            .findFirstByTargetTypeAndTargetValueOrderByCreatedAtDesc(target.type(), target.value())
            .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "Verification code not found."));

        if (Boolean.TRUE.equals(latestCode.getConsumed())) {
            throw new ResponseStatusException(UNAUTHORIZED, "Verification code already used.");
        }
        if (latestCode.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(UNAUTHORIZED, "Verification code expired.");
        }
        if (!Objects.equals(latestCode.getCode(), normalizedCode)) {
            throw new ResponseStatusException(UNAUTHORIZED, "Invalid verification code.");
        }

        latestCode.setConsumed(true);
        authVerificationCodeRepository.save(latestCode);

        AppUser user = StringUtils.hasText(request.getEmail())
            ? findOrCreateUserByEmail(
                request.getEmail(),
                request.getName(),
                request.getNickname(),
                StringUtils.hasText(request.getPhone()) ? request.getPhone() : null
            )
            : findOrCreateUserByPhone(request.getPhone(), request.getName(), request.getNickname());

        if (target.type().equals("EMAIL")) {
            user.setEmailVerified(true);
        } else if (target.type().equals("PHONE")) {
            user.setPhoneVerified(true);
        }
        user = appUserRepository.save(user);

        return createSessionResponse(user);
    }

    @Transactional
    public AuthSessionResponse guestSession(String preferredNickname) {
        String guestNickname = StringUtils.hasText(preferredNickname) ? preferredNickname.trim() : "Guest";

        AppUser user = appUserRepository.findFirstByEmailIgnoreCase(GUEST_EMAIL)
            .map(existing -> {
                boolean changed = false;
                if (!Objects.equals(existing.getNickname(), guestNickname)) {
                    existing.setNickname(guestNickname);
                    changed = true;
                }
                if (!Objects.equals(existing.getName(), "Guest User")) {
                    existing.setName("Guest User");
                    changed = true;
                }
                if (!Boolean.TRUE.equals(existing.getEmailVerified())) {
                    existing.setEmailVerified(true);
                    changed = true;
                }
                if (!StringUtils.hasText(existing.getRegion())) {
                    existing.setRegion("Global");
                    changed = true;
                }
                return changed ? appUserRepository.save(existing) : existing;
            })
            .orElseGet(() -> {
                AppUser guest = new AppUser("Guest User", 2200);
                guest.setNickname(guestNickname);
                guest.setEmail(GUEST_EMAIL);
                guest.setEmailVerified(true);
                guest.setRegion("Global");
                return appUserRepository.save(guest);
            });

        return createSessionResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthSessionResponse me(String token) {
        UserSession session = getSessionOrThrow(token);
        AuthSessionResponse response = new AuthSessionResponse();
        response.setToken(session.getSessionToken());
        response.setExpiresAt(session.getExpiresAt());
        response.setUser(session.getUser());
        return response;
    }

    @Transactional
    public void logout(String token) {
        if (!StringUtils.hasText(token)) {
            return;
        }
        userSessionRepository.findFirstBySessionTokenAndRevokedFalse(token.trim())
            .ifPresent(session -> {
                session.setRevoked(true);
                userSessionRepository.save(session);
            });
    }

    @Transactional(readOnly = true)
    public AppUser userFromTokenOrThrow(String token) {
        return getSessionOrThrow(token).getUser();
    }

    private UserSession getSessionOrThrow(String token) {
        if (!StringUtils.hasText(token)) {
            throw new ResponseStatusException(UNAUTHORIZED, "Session token required.");
        }
        UserSession session = userSessionRepository.findFirstBySessionTokenAndRevokedFalse(token.trim())
            .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "Session not found."));
        if (session.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(UNAUTHORIZED, "Session expired.");
        }
        return session;
    }

    private AppUser findOrCreateUserByEmail(String email, String name, String nickname, String phone) {
        String normalizedEmail = normalizeEmail(email);
        if (!StringUtils.hasText(normalizedEmail)) {
            throw new ResponseStatusException(BAD_REQUEST, "Email is required.");
        }

        return appUserRepository.findFirstByEmailIgnoreCase(normalizedEmail)
            .map(existing -> enrichExistingUser(existing, name, nickname, phone))
            .orElseGet(() -> createNewUser(name, nickname, normalizedEmail, phone));
    }

    private AppUser findOrCreateUserByPhone(String phone, String name, String nickname) {
        String normalizedPhone = normalizePhone(phone);
        if (!StringUtils.hasText(normalizedPhone)) {
            throw new ResponseStatusException(BAD_REQUEST, "Phone is required.");
        }

        return appUserRepository.findFirstByPhone(normalizedPhone)
            .map(existing -> enrichExistingUser(existing, name, nickname, null))
            .orElseGet(() -> createNewUser(name, nickname, null, normalizedPhone));
    }

    private AppUser enrichExistingUser(AppUser existing, String name, String nickname, String phone) {
        boolean changed = false;
        if (StringUtils.hasText(name) && !Objects.equals(existing.getName(), name.trim())) {
            existing.setName(name.trim());
            changed = true;
        }
        if (StringUtils.hasText(nickname) && !Objects.equals(existing.getNickname(), nickname.trim())) {
            existing.setNickname(nickname.trim());
            changed = true;
        }
        if (StringUtils.hasText(phone) && !Objects.equals(existing.getPhone(), normalizePhone(phone))) {
            existing.setPhone(normalizePhone(phone));
            changed = true;
        }
        if (!changed) {
            return existing;
        }
        return appUserRepository.save(existing);
    }

    private AppUser createNewUser(String name, String nickname, String email, String phone) {
        String safeName = StringUtils.hasText(name) ? name.trim() : "New User";
        AppUser user = new AppUser(safeName, 2200);
        user.setNickname(StringUtils.hasText(nickname) ? nickname.trim() : safeName);
        user.setEmail(email);
        user.setPhone(phone);
        user.setRegion("Global");
        return appUserRepository.save(user);
    }

    private LoginTarget resolveTarget(String email, String phone) {
        String normalizedEmail = normalizeEmail(email);
        String normalizedPhone = normalizePhone(phone);

        if (!StringUtils.hasText(normalizedEmail) && !StringUtils.hasText(normalizedPhone)) {
            throw new ResponseStatusException(BAD_REQUEST, "Provide email or phone.");
        }

        if (StringUtils.hasText(normalizedPhone)) {
            return new LoginTarget("PHONE", normalizedPhone);
        }
        return new LoginTarget("EMAIL", normalizedEmail);
    }

    private String generateCode() {
        int value = RANDOM.nextInt(900000) + 100000;
        return String.valueOf(value);
    }

    private AuthSessionResponse createSessionResponse(AppUser user) {
        UserSession session = new UserSession();
        session.setUser(user);
        session.setSessionToken(generateSessionToken());
        session.setExpiresAt(Instant.now().plus(sessionHours, ChronoUnit.HOURS));
        session.setRevoked(false);
        userSessionRepository.save(session);

        AuthSessionResponse response = new AuthSessionResponse();
        response.setToken(session.getSessionToken());
        response.setExpiresAt(session.getExpiresAt());
        response.setUser(user);
        return response;
    }

    private String generateSessionToken() {
        return "sess_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String maskTarget(String type, String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        if ("PHONE".equals(type)) {
            String digits = value.replaceAll("[^0-9]", "");
            if (digits.length() <= 4) {
                return "****";
            }
            return "******" + digits.substring(digits.length() - 4);
        }

        int at = value.indexOf("@");
        if (at <= 1) {
            return "***" + value.substring(Math.max(0, at));
        }
        return value.charAt(0) + "***" + value.substring(at);
    }

    private String normalizeEmail(String email) {
        if (!StringUtils.hasText(email)) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizePhone(String phone) {
        if (!StringUtils.hasText(phone)) {
            return null;
        }
        String cleaned = phone.trim().replaceAll("[^0-9+]", "");
        return cleaned.isBlank() ? null : cleaned;
    }

    private record LoginTarget(String type, String value) {
    }
}
