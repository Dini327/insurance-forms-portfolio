package com.insuranceforms.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insuranceforms.admin.AdminUserDetailsService;
import com.insuranceforms.agent.AgentUserDetailsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AgentUserDetailsService agentUserDetailsService;
    private final AdminUserDetailsService adminUserDetailsService;
    private final ObjectMapper objectMapper;

    @Value("${app.frontend-origin}")
    private String frontendOrigin;

    public SecurityConfig(AgentUserDetailsService agentUserDetailsService,
                           AdminUserDetailsService adminUserDetailsService,
                           ObjectMapper objectMapper) {
        this.agentUserDetailsService = agentUserDetailsService;
        this.adminUserDetailsService = adminUserDetailsService;
        this.objectMapper = objectMapper;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // Filters built via http.formLogin()/http.securityContext() get this delegating
    // repository wired automatically by Spring Security's own configurers. Since
    // JsonAuthenticationFilter is constructed and added manually (addFilterAt), that
    // wiring never happens and the filter falls back to its class default
    // (RequestAttributeSecurityContextRepository only), which never persists the
    // SecurityContext to the session — so no SESSION cookie is ever issued and every
    // authenticated request after a "successful" login 401s. Set explicitly to match
    // what the DSL would have wired.
    private SecurityContextRepository sessionBackedSecurityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository());
    }

    private AuthenticationManager buildManager(HttpSecurity http, UserDetailsService userDetailsService)
            throws Exception {
        AuthenticationManagerBuilder builder = http.getSharedObject(AuthenticationManagerBuilder.class);
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setPasswordEncoder(passwordEncoder());
        provider.setUserDetailsService(userDetailsService);
        builder.authenticationProvider(provider);
        return builder.build();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain agentChain(HttpSecurity http) throws Exception {
        AuthenticationManager manager = buildManager(http, agentUserDetailsService);
        JsonAuthenticationFilter loginFilter = new JsonAuthenticationFilter(manager, "/api/auth/agent/login", objectMapper);
        loginFilter.setAuthenticationSuccessHandler(new JsonLoginSuccessHandler(objectMapper, "AGENT"));
        loginFilter.setAuthenticationFailureHandler(new JsonLoginFailureHandler(objectMapper));
        loginFilter.setSecurityContextRepository(sessionBackedSecurityContextRepository());

        http.securityMatcher("/api/auth/agent/**", "/api/agent/**")
                .authenticationManager(manager)
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/agent/register").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/agent/login").permitAll()
                        .requestMatchers("/api/agent/**").hasRole("AGENT")
                        .anyRequest().authenticated())
                .addFilterAt(loginFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> res.sendError(401)));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain adminChain(HttpSecurity http) throws Exception {
        AuthenticationManager manager = buildManager(http, adminUserDetailsService);
        JsonAuthenticationFilter loginFilter = new JsonAuthenticationFilter(manager, "/api/auth/admin/login", objectMapper);
        loginFilter.setAuthenticationSuccessHandler(new JsonLoginSuccessHandler(objectMapper, "ADMIN"));
        loginFilter.setAuthenticationFailureHandler(new JsonLoginFailureHandler(objectMapper));
        loginFilter.setSecurityContextRepository(sessionBackedSecurityContextRepository());

        http.securityMatcher("/api/auth/admin/**", "/api/admin/**")
                .authenticationManager(manager)
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/admin/login").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .addFilterAt(loginFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> res.sendError(401)));
        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain logoChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/logos/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(4)
    public SecurityFilterChain csrfBootstrapChain(HttpSecurity http) throws Exception {
        // GET is a CSRF-safe method, so this stays open to everyone; CSRF must stay
        // *active* (not disabled) here, since this endpoint's only job is to make the
        // CsrfFilter materialize a token and write it as a cookie for the client to
        // echo back on subsequent POSTs.
        http.securityMatcher("/api/csrf-token")
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(frontendOrigin));
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
