package com.ilkeiapps.skyworx.notulenai.config;

import java.io.IOException;

import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ilkeiapps.skyworx.notulenai.service.JwtService;
import com.ilkeiapps.skyworx.notulenai.service.UserJwtService;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Authenticates each request from a {@code Authorization: Bearer <jwt>} header. Validates the JWT
 * signature/expiry (stateless) and that the token is still active in the {@code token} table
 * (revocation), then populates the security context.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private final JwtService jwtService;
	private final UserJwtService userJwtService;

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request,
			@NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
			throws ServletException, IOException {

		final String authHeader = request.getHeader("Authorization");
		if (authHeader == null || !authHeader.startsWith("Bearer ")) {
			filterChain.doFilter(request, response);
			return;
		}

		final String jwt = authHeader.substring(7);
		try {
			final String username = jwtService.extractUserName(jwt);
			if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
				UserDetails userDetails = userJwtService.getUser().loadUserByUsername(username);
				if (jwtService.isTokenValid(jwt, userDetails) && userJwtService.isTokenActive(jwt)) {
					var authToken = new UsernamePasswordAuthenticationToken(
							userDetails, null, userDetails.getAuthorities());
					authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
					SecurityContextHolder.getContext().setAuthentication(authToken);
				}
			}
		} catch (JwtException | IllegalArgumentException ex) {
			// Malformed, tampered, or expired token: proceed unauthenticated.
			SecurityContextHolder.clearContext();
		}

		filterChain.doFilter(request, response);
	}
}
