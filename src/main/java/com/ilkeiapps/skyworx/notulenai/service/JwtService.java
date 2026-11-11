package com.ilkeiapps.skyworx.notulenai.service;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import javax.crypto.SecretKey;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import com.ilkeiapps.skyworx.notulenai.config.JwtProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

/**
 * Stateless JWT service (house style), using the project jjwt 0.12 API and the HMAC secret from
 * {@link JwtProperties}.
 */
@Slf4j
@Service
public class JwtService {

	private final SecretKey signingKey;
	private final long validityMs;

	public JwtService(JwtProperties properties) {
		byte[] keyBytes = properties.getSecret().getBytes(StandardCharsets.UTF_8);
		if (keyBytes.length < 32) {
			throw new IllegalStateException("security.jwt.secret must be at least 32 bytes for HS256 signing");
		}
		this.signingKey = Keys.hmacShaKeyFor(keyBytes);
		this.validityMs = properties.getExpirationMs();
	}

	public String extractUserName(String token) {
		return extractClaim(token, Claims::getSubject);
	}

	public String generateToken(UserDetails userDetails) {
		return generateToken(new HashMap<>(), userDetails);
	}

	public boolean isTokenValid(String token, UserDetails userDetails) {
		final String userName = extractUserName(token);
		return userName.equals(userDetails.getUsername()) && !isTokenExpired(token);
	}

	private String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
		long now = System.currentTimeMillis();
		return Jwts.builder()
				.claims(extraClaims)
				.subject(userDetails.getUsername())
				.issuedAt(new Date(now))
				.expiration(new Date(now + validityMs))
				.signWith(signingKey)
				.compact();
	}

	private boolean isTokenExpired(String token) {
		return extractClaim(token, Claims::getExpiration).before(new Date());
	}

	private <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
		return claimsResolver.apply(extractAllClaims(token));
	}

	private Claims extractAllClaims(String token) {
		return Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token).getPayload();
	}
}
