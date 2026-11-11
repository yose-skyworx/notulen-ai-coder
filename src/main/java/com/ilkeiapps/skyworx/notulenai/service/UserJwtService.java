package com.ilkeiapps.skyworx.notulenai.service;

import java.time.LocalDateTime;
import java.util.ArrayList;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.ilkeiapps.skyworx.notulenai.entity.QToken;
import com.ilkeiapps.skyworx.notulenai.entity.QUser;
import com.ilkeiapps.skyworx.notulenai.entity.Token;
import com.ilkeiapps.skyworx.notulenai.entity.User;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * Spring Security integration: resolves a {@link UserDetails} from the {@code User} table, and
 * checks whether a persisted token is still active (revocation support).
 */
@Service
@RequiredArgsConstructor
public class UserJwtService {

	private final EntityManager em;
	private final CriteriaBuilderFactory configBuilder;

	public UserDetailsService getUser() {
		return new UserDetailsService() {
			@Override
			public UserDetails loadUserByUsername(String username) {
				var qu = new QUser("u");
				var users = configBuilder.create(em, User.class).from(User.class, qu.getMetadata().getName())
						.fetch(qu.roles.toString())
						.where(qu.username.toString()).eq(username)
						.where(qu.status.code.toString()).eq("AKT")
						.getResultList();
				if (users.isEmpty()) {
					throw new UsernameNotFoundException("User tidak ditemukan: " + username);
				}

				var u = users.getFirst();
				var authorities = new ArrayList<GrantedAuthority>();
				authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
				if (u.getRoles() != null && u.getRoles().getName() != null) {
					authorities.add(new SimpleGrantedAuthority(u.getRoles().getName()));
				}

				return new org.springframework.security.core.userdetails.User(
						u.getUsername(), u.getPassword(), authorities);
			}
		};
	}

	/**
	 * A persisted JWT is active when a {@code token} row exists and has not expired. Supports
	 * revocation: deleting the row invalidates the token before its own expiry.
	 */
	@Transactional(readOnly = true)
	public boolean isTokenActive(String token) {
		var qt = new QToken("t");
		var rows = configBuilder.create(em, Token.class).from(Token.class, qt.getMetadata().getName())
				.where(qt.token.toString()).eq(token)
				.setMaxResults(1)
				.getResultList();
		if (rows.isEmpty()) {
			return false;
		}
		var expireDate = rows.getFirst().getExpireDate();
		return expireDate == null || expireDate.isAfter(LocalDateTime.now());
	}
}
