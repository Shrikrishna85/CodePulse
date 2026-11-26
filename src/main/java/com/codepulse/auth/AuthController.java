package com.codepulse.auth;

import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

    public AuthController(
            AuthService authService,
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy) {
        this.authService = authService;
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
    }

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken csrfToken) {
        return new CsrfResponse(csrfToken.getToken());
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@RequestBody RegistrationRequest request) {
        AuthService.RegisteredUser user =
                authService.register(request.username(), request.email(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new UserResponse(user.id(), user.username(), user.role()));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
            @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            request.username() == null ? "" : request.username().trim().toLowerCase(Locale.ROOT),
                            request.password() == null ? "" : request.password()));
        } catch (AuthenticationException exception) {
            if (exception instanceof BadCredentialsException) {
                authService.recordFailedLogin(
                        request.username() == null ? "" : request.username().trim().toLowerCase(Locale.ROOT));
            }
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ApiError("Invalid username or password"));
        }

        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        authService.recordSuccessfulLogin(principal.username());
        sessionAuthenticationStrategy.onAuthentication(authentication, servletRequest, servletResponse);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, servletRequest, servletResponse);

        return ResponseEntity.ok(new UserResponse(principal.id(), principal.username(), principal.role()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        logoutHandler.logout(request, response, SecurityContextHolder.getContext().getAuthentication());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public UserResponse currentUser(Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        return new UserResponse(principal.id(), principal.username(), principal.role());
    }

    public record RegistrationRequest(String username, String email, String password) {}

    public record LoginRequest(String username, String password) {}

    public record UserResponse(long id, String username, String role) {}

    public record CsrfResponse(String token) {}

    public record ApiError(String error) {}
}
