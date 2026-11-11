package com.ilkeiapps.skyworx.notulenai.service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.blazebit.persistence.view.EntityViewManager;
import com.blazebit.persistence.view.EntityViewSetting;
import com.ilkeiapps.ilkeitool.validator.IlKeiValidator;
import com.ilkeiapps.ilkeitool.validator.ProcessResult;
import com.ilkeiapps.ilkeitoolentityblazebit.validator.IlKeiEntityValidator;
import com.ilkeiapps.skyworx.notulenai.bean.RoleRequest;
import com.ilkeiapps.skyworx.notulenai.bean.UserRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.ApiResponse;
import com.ilkeiapps.skyworx.notulenai.bean.web.GenericRequestParameter;
import com.ilkeiapps.skyworx.notulenai.bean.web.LoginRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.MyPasswordRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.RoleRequestParameter;
import com.ilkeiapps.skyworx.notulenai.bean.web.UserIdRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.UserPasswordRequest;
import com.ilkeiapps.skyworx.notulenai.config.JwtProperties;
import com.ilkeiapps.skyworx.notulenai.entity.Gender;
import com.ilkeiapps.skyworx.notulenai.entity.Permission;
import com.ilkeiapps.skyworx.notulenai.entity.QGender;
import com.ilkeiapps.skyworx.notulenai.entity.QPermission;
import com.ilkeiapps.skyworx.notulenai.entity.QRole;
import com.ilkeiapps.skyworx.notulenai.entity.QUser;
import com.ilkeiapps.skyworx.notulenai.entity.Role;
import com.ilkeiapps.skyworx.notulenai.entity.Setting;
import com.ilkeiapps.skyworx.notulenai.entity.Token;
import com.ilkeiapps.skyworx.notulenai.entity.User;
import com.ilkeiapps.skyworx.notulenai.entityview.gender.GenderListView;
import com.ilkeiapps.skyworx.notulenai.entityview.permission.PermissionListView;
import com.ilkeiapps.skyworx.notulenai.entityview.role.RoleListView;
import com.ilkeiapps.skyworx.notulenai.entityview.user.UserListView;
import com.ilkeiapps.skyworx.notulenai.entityview.user.UserProfileView;
import com.ilkeiapps.skyworx.notulenai.utility.ImageResizer;
import com.ilkeiapps.skyworx.notulenai.utility.Utility;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * User / Role / Permission management and login, following the com.ilkeiapps house style:
 * Blaze-Persistence queries with QueryDSL path metadata, {@code IlKeiValidator} /
 * {@code IlKeiEntityValidator}, Entity Views for reads, BCrypt passwords, and stateless JWT login
 * with the issued token persisted in the {@code token} table for revocation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

	private static final String INVALID_CREDENTIALS = "Username atau password salah";

	private final EntityManager em;
	private final CriteriaBuilderFactory configBuilder;
	private final EntityViewManager viewManager;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final JwtProperties jwtProperties;
	private final StatusService statusService;

	@Transactional
	public ApiResponse<String> login(LoginRequest req) {
		var res = new ApiResponse<String>();
		res.setStatus(false);

		ProcessResult pr = IlKeiValidator.builder().withPojo(req)
				.pick(LoginRequest.Fields.username, "Username").isMandatory().asString().withMinLen(2).pack()
				.pick(LoginRequest.Fields.password, "Password").isMandatory().asString().withMinLen(2).pack()
				.validate();
		if (!pr.getResult()) {
			res.setMessage(pr.getMessage());
			return res;
		}

		var qu = new QUser("u");
		var users = configBuilder.create(em, User.class).from(User.class, qu.getMetadata().getName())
				.where(qu.username.toString()).eq(req.getUsername())
				.where(qu.status.code.toString()).eq("AKT")
				.getResultList();
		if (users.isEmpty()) {
			res.setMessage(INVALID_CREDENTIALS);
			return res;
		}

		var user = users.getFirst();
		if (user.getPassword() == null || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
			res.setMessage(INVALID_CREDENTIALS);
			return res;
		}

		var authorities = new ArrayList<GrantedAuthority>();
		authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
		if (user.getRoles() != null && user.getRoles().getName() != null) {
			authorities.add(new SimpleGrantedAuthority(user.getRoles().getName()));
		}
		var userDetails = new org.springframework.security.core.userdetails.User(
				user.getUsername(), user.getPassword(), authorities);
		String token = jwtService.generateToken(userDetails);

		var now = LocalDateTime.now();
		var tkn = new Token();
		tkn.setToken(token);
		tkn.setUser(user);
		tkn.setIp(req.getIp());
		tkn.setCreateDate(now);
		tkn.setExpireDate(now.plus(Duration.ofMillis(jwtProperties.getExpirationMs())));
		this.em.persist(tkn);
		this.em.flush();

		res.insertNewData(token);
		res.setStatus(true);
		return res;
	}

	@Transactional
	public ApiResponse<UserRequest> saveUser(UserRequest src) {
		var wrap = new ApiResponse<UserRequest>();
		wrap.setStatus(false);

		ProcessResult pr = IlKeiValidator.builder().withPojo(src)
				.pick(UserRequest.Fields.name, "Nama").isMandatory().asString().withMinLen(2).pack()
				.pick(UserRequest.Fields.username, "Username").isMandatory().asString().withMinLen(2).pack()
				.pick(UserRequest.Fields.password, "Password").isMandatory().asString().withMinLen(3).pack()
				.validate();
		if (!pr.getResult()) {
			wrap.setMessage(pr.getMessage());
			return wrap;
		}

		if (src.getGenderId() == null) {
			wrap.setMessage(Utility.getErrorMessage("user.user.gender_not_found"));
			return wrap;
		}
		if (src.getRoleId() == null) {
			wrap.setMessage(Utility.getErrorMessage("user.user.role_not_found"));
			return wrap;
		}

		var gpr = IlKeiEntityValidator.builder().withCriteriaBuilderFactory(em, configBuilder)
				.pick().withEntity(Gender.class, "g").whereCriteriaEqual("g.id", src.getGenderId())
				.withErrorMessage(Utility.getErrorMessage("user.user.gender_not_found")).pack()
				.validate();
		if (!gpr.getResult()) {
			wrap.setMessage(gpr.getMessage());
			return wrap;
		}

		var rpr = IlKeiEntityValidator.builder().withCriteriaBuilderFactory(em, configBuilder)
				.pick().withEntity(Role.class, "r").whereCriteriaEqual("r.id", src.getRoleId())
				.withErrorMessage(Utility.getErrorMessage("user.user.role_not_found")).pack()
				.validate();
		if (!rpr.getResult()) {
			wrap.setMessage(rpr.getMessage());
			return wrap;
		}

		var qu = new QUser("u");
		var cu = configBuilder.create(em, User.class).from(User.class, qu.getMetadata().getName())
				.where(qu.username.toString()).eq(src.getUsername())
				.getCountQuery().getSingleResult();
		if (cu > 0) {
			wrap.setMessage(Utility.getErrorMessage("user.user.duplicate_item"));
			return wrap;
		}

		var user = new User();
		user.setName(src.getName());
		user.setUsername(src.getUsername());
		user.setEmail(src.getEmail());
		user.setPhone(src.getPhone());
		user.setDesignation(src.getDesignation());
		user.setPassword(passwordEncoder.encode(src.getPassword()));
		user.setGender(this.em.find(Gender.class, src.getGenderId().intValue()));
		user.setRoles(this.em.find(Role.class, src.getRoleId()));
		user.setStatus(statusService.getGeneralStatusByCode("AKT"));
		user.setCreatedAt(LocalDateTime.now());
		user.setCreatedBy(currentUser());

		this.em.persist(user);
		this.em.flush();

		src.setId(user.getId());
		src.setPassword(null);
		wrap.insertNewData(src);
		wrap.setStatus(true);
		return wrap;
	}

	@Transactional
	public ApiResponse<UserRequest> updateUser(UserRequest src) {
		var wrap = new ApiResponse<UserRequest>();
		wrap.setStatus(false);

		ProcessResult pr = IlKeiValidator.builder().withPojo(src)
				.pick(UserRequest.Fields.name, "Nama").isMandatory().asString().withMinLen(2).pack()
				.pick(UserRequest.Fields.username, "Username").isMandatory().asString().withMinLen(2).pack()
				.pick(UserRequest.Fields.id, "Id User").isMandatory().asLong().withMinLen(1).pack()
				.validate();
		if (!pr.getResult()) {
			wrap.setMessage(pr.getMessage());
			return wrap;
		}

		if (src.getGenderId() == null) {
			wrap.setMessage(Utility.getErrorMessage("user.user.gender_not_found"));
			return wrap;
		}
		if (src.getRoleId() == null) {
			wrap.setMessage(Utility.getErrorMessage("user.user.role_not_found"));
			return wrap;
		}

		var qu = new QUser("u");
		var cu = configBuilder.create(em, User.class).from(User.class, qu.getMetadata().getName())
				.where(qu.username.toString()).eq(src.getUsername())
				.where(qu.id.toString()).notEq(src.getId())
				.getCountQuery().getSingleResult();
		if (cu > 0) {
			wrap.setMessage(Utility.getErrorMessage("user.user.duplicate_item"));
			return wrap;
		}

		var user = this.em.find(User.class, src.getId());
		if (user == null) {
			wrap.setMessage(Utility.getErrorMessage("user.user.item_not_found"));
			return wrap;
		}

		var gen = this.em.find(Gender.class, src.getGenderId().intValue());
		if (gen == null) {
			wrap.setMessage(Utility.getErrorMessage("user.user.gender_not_found"));
			return wrap;
		}
		var role = this.em.find(Role.class, src.getRoleId());
		if (role == null) {
			wrap.setMessage(Utility.getErrorMessage("user.user.role_not_found"));
			return wrap;
		}

		user.setName(src.getName());
		user.setUsername(src.getUsername());
		user.setEmail(src.getEmail());
		user.setPhone(src.getPhone());
		user.setDesignation(src.getDesignation());
		user.setGender(gen);
		user.setRoles(role);
		user.setUpdatedAt(LocalDateTime.now());

		this.em.merge(user);
		this.em.flush();

		wrap.insertNewData(src);
		wrap.setStatus(true);
		return wrap;
	}

	public ApiResponse<UserListView> listUser(GenericRequestParameter filter) {
		var wrap = new ApiResponse<UserListView>();
		wrap.setStatus(false);

		var qp = new QUser("u");
		var query = configBuilder.create(em, User.class).from(User.class, qp.getMetadata().getName());
		if (filter.getKeyword() != null && filter.getKeyword().length() > 2) {
			var kw = "%" + filter.getKeyword().toLowerCase() + "%";
			query = query.whereOr()
					.where("LOWER(" + qp.name.toString() + ")").like().value(kw).noEscape()
					.where("LOWER(" + qp.username.toString() + ")").like().value(kw).noEscape()
					.endOr();
		}
		query = query.where(qp.status.code.toString()).notEq("REM");

		var num = Utility.getPerPage(filter.getPerPage());
		var of = Utility.getOffsetPage(filter.getPage(), num);
		query = query.orderByDesc(qp.id.toString());

		var res = viewManager.applySetting(EntityViewSetting.create(UserListView.class, of, num), query)
				.withKeysetExtraction(true)
				.getResultList();

		wrap.setMaxPage(res.getTotalPages());
		wrap.setData(res.stream().toList());
		wrap.setStatus(true);
		return wrap;
	}

	@Transactional
	public ApiResponse<RoleRequest> saveRole(RoleRequest src) {
		var wrap = new ApiResponse<RoleRequest>();
		wrap.setStatus(false);

		ProcessResult pr = IlKeiValidator.builder().withPojo(src)
				.pick(RoleRequest.Fields.name, "Nama").isMandatory().asString().withMinLen(2).pack()
				.validate();
		if (!pr.getResult()) {
			wrap.setMessage(pr.getMessage());
			return wrap;
		}

		if (src.getName().equalsIgnoreCase("SUPERADMIN")) {
			wrap.setMessage(Utility.getErrorMessage("user.role.restricted_name"));
			return wrap;
		}

		var qr = new QRole("r");
		var cr = configBuilder.create(em, Role.class).from(Role.class, qr.getMetadata().getName())
				.where(qr.name.toString()).eq(src.getName())
				.getCountQuery().getSingleResult();
		if (cr > 0) {
			wrap.setMessage(Utility.getErrorMessage("user.role.duplicate_item"));
			return wrap;
		}

		var role = new Role();
		role.setName(src.getName());
		role.setDisplayName(src.getDisplayName());
		role.setDescription(src.getDescription());
		role.setStatus(statusService.getGeneralStatusByCode("AKT"));
		role.setCreatedAt(LocalDateTime.now());

		this.em.persist(role);
		this.em.flush();

		src.setId(role.getId());
		wrap.insertNewData(src);
		wrap.setStatus(true);
		return wrap;
	}

	@Transactional
	public ApiResponse<RoleRequest> updateRole(RoleRequest src) {
		var wrap = new ApiResponse<RoleRequest>();
		wrap.setStatus(false);

		ProcessResult pr = IlKeiValidator.builder().withPojo(src)
				.pick("name", "Nama").isMandatory().asString().withMinLen(2).pack()
				.pick("id", "Id Role").isMandatory().asLong().withMinLen(1).pack()
				.validate();
		if (!pr.getResult()) {
			wrap.setMessage(pr.getMessage());
			return wrap;
		}

		if (src.getName().equalsIgnoreCase("SUPERADMIN")) {
			wrap.setMessage(Utility.getErrorMessage("user.role.restricted_name"));
			return wrap;
		}

		var qr = new QRole("r");
		var cr = configBuilder.create(em, Role.class).from(Role.class, qr.getMetadata().getName())
				.where(qr.name.toString()).eq(src.getName())
				.where(qr.id.toString()).notEq(src.getId())
				.getCountQuery().getSingleResult();
		if (cr > 0) {
			wrap.setMessage(Utility.getErrorMessage("user.role.duplicate_item"));
			return wrap;
		}

		var role = this.em.find(Role.class, src.getId());
		if (role == null) {
			wrap.setMessage(Utility.getErrorMessage("user.role.item_not_found"));
			return wrap;
		}

		role.setName(src.getName());
		role.setDisplayName(src.getDisplayName());
		role.setDescription(src.getDescription());
		role.setUpdatedAt(LocalDateTime.now());

		this.em.merge(role);
		this.em.flush();

		wrap.insertNewData(src);
		wrap.setStatus(true);
		return wrap;
	}

	@Transactional
	public ApiResponse<Role> updateRolePermission(Long id, List<Permission> permissions) {
		var wrap = new ApiResponse<Role>();
		wrap.setStatus(false);

		if (id == null || permissions == null || permissions.isEmpty()) {
			wrap.setMessage(Utility.getErrorMessage("user.role.permission_not_found"));
			return wrap;
		}

		var role = this.em.find(Role.class, id);
		if (role == null) {
			wrap.setMessage(Utility.getErrorMessage("user.role.item_not_found"));
			return wrap;
		}

		role.setPermissions(null);
		this.em.flush();

		var nperm = new ArrayList<Permission>();
		for (Permission permission : permissions) {
			var p = this.em.find(Permission.class, permission.getId());
			if (p != null) {
				nperm.add(p);
			}
		}

		role.setPermissions(nperm);
		this.em.merge(role);
		this.em.flush();

		wrap.insertNewData(role);
		wrap.setStatus(true);
		return wrap;
	}

	public ApiResponse<RoleListView> listRole(RoleRequestParameter filter) {
		var wrap = new ApiResponse<RoleListView>();
		wrap.setStatus(false);

		var qp = new QRole("r");
		var query = configBuilder.create(em, Role.class).from(Role.class, qp.getMetadata().getName());
		if (filter.getKeyword() != null && filter.getKeyword().length() > 2) {
			var kw = "%" + filter.getKeyword().toLowerCase() + "%";
			query = query.whereOr()
					.where("LOWER(" + qp.name.toString() + ")").like().value(kw).noEscape()
					.where("LOWER(" + qp.displayName.toString() + ")").like().value(kw).noEscape()
					.where("LOWER(" + qp.description.toString() + ")").like().value(kw).noEscape()
					.endOr();
		}
		query = query.where(qp.status.code.toString()).notEq("REM");

		var num = Utility.getPerPage(filter.getPerPage());
		var of = Utility.getOffsetPage(filter.getPage(), num);
		query = query.orderByDesc(qp.id.toString());

		var res = viewManager.applySetting(EntityViewSetting.create(RoleListView.class, of, num), query)
				.withKeysetExtraction(true)
				.getResultList();

		wrap.setMaxPage(res.getTotalPages());
		wrap.setData(res.stream().toList());
		wrap.setStatus(true);
		return wrap;
	}

	public ApiResponse<GenderListView> listGender(GenericRequestParameter filter) {
		var wrap = new ApiResponse<GenderListView>();
		wrap.setStatus(false);

		var qp = new QGender("g");
		var query = configBuilder.create(em, Gender.class).from(Gender.class, qp.getMetadata().getName());
		if (filter.getKeyword() != null && filter.getKeyword().length() > 2) {
			query = query.where("LOWER(" + qp.name.toString() + ")")
					.like().value("%" + filter.getKeyword().toLowerCase() + "%").noEscape();
		}
		if (filter.getCode() != null) {
			query = query.where(qp.code.toString()).eq(filter.getCode());
		}

		var num = Utility.getPerPage(filter.getPerPage());
		var of = Utility.getOffsetPage(filter.getPage(), num);
		query = query.orderByDesc(qp.id.toString());

		var res = viewManager.applySetting(EntityViewSetting.create(GenderListView.class, of, num), query)
				.withKeysetExtraction(true)
				.getResultList();

		wrap.setMaxPage(res.getTotalPages());
		wrap.setData(res.stream().toList());
		wrap.setStatus(true);
		return wrap;
	}

	public ApiResponse<PermissionListView> listPermission(GenericRequestParameter filter) {
		var wrap = new ApiResponse<PermissionListView>();
		wrap.setStatus(false);

		var qp = new QPermission("p");
		var query = configBuilder.create(em, Permission.class).from(Permission.class, qp.getMetadata().getName());
		if (filter.getKeyword() != null && filter.getKeyword().length() > 2) {
			query = query.where("LOWER(" + qp.name.toString() + ")")
					.like().value("%" + filter.getKeyword().toLowerCase() + "%").noEscape();
		}

		var num = Utility.getPerPage(filter.getPerPage());
		var of = Utility.getOffsetPage(filter.getPage(), num);
		query = query.orderByDesc(qp.id.toString());

		var res = viewManager.applySetting(EntityViewSetting.create(PermissionListView.class, of, num), query)
				.withKeysetExtraction(true)
				.getResultList();

		wrap.setMaxPage(res.getTotalPages());
		wrap.setData(res.stream().toList());
		wrap.setStatus(true);
		return wrap;
	}

	/**
	 * Returns the profile of the currently authenticated user as an Entity View. The endpoint is
	 * behind {@code .anyRequest().authenticated()}, so an authentication is always present here; the
	 * not-found branch guards only against a stale token whose user row was since removed.
	 */
	@Transactional(readOnly = true)
	public ApiResponse<UserProfileView> getProfile() {
		var wrap = new ApiResponse<UserProfileView>();
		wrap.setStatus(false);

		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !auth.isAuthenticated() || auth.getName() == null
				|| "anonymousUser".equals(auth.getName())) {
			wrap.setMessage(Utility.getErrorMessage("user.user.item_not_found"));
			return wrap;
		}

		var qu = new QUser("u");
		var query = configBuilder.create(em, User.class).from(User.class, qu.getMetadata().getName())
				.where(qu.username.toString()).eq(auth.getName())
				.where(qu.status.code.toString()).notEq("REM");

		var res = viewManager.applySetting(EntityViewSetting.create(UserProfileView.class), query)
				.getResultList();
		if (res.isEmpty()) {
			wrap.setMessage(Utility.getErrorMessage("user.user.item_not_found"));
			return wrap;
		}

		wrap.insertNewData(res.getFirst());
		wrap.setMaxPage(1);
		wrap.setStatus(true);
		return wrap;
	}

	/**
	 * Soft-deletes a user by setting the general status to {@code REM} and stamping the deletion
	 * audit columns. The row is kept so that existing references remain resolvable; every list/get
	 * query filters {@code REM} out.
	 */
	@Transactional
	public ApiResponse<UserIdRequest> deleteUser(UserIdRequest src) {
		var wrap = new ApiResponse<UserIdRequest>();
		wrap.setStatus(false);

		if (src.getId() == null) {
			wrap.setMessage(Utility.getErrorMessage("user.user.item_not_found"));
			return wrap;
		}

		var user = this.em.find(User.class, src.getId());
		if (user == null) {
			wrap.setMessage(Utility.getErrorMessage("user.user.item_not_found"));
			return wrap;
		}

		var removed = statusService.getGeneralStatusByCode("REM");
		if (removed == null) {
			wrap.setMessage(Utility.getErrorMessage("user.user.status_missing"));
			return wrap;
		}

		user.setStatus(removed);
		user.setDeletedAt(LocalDateTime.now());
		user.setDeletedBy(currentUser());
		this.em.flush();

		src.setId(user.getId());
		wrap.insertNewData(src);
		wrap.setStatus(true);
		return wrap;
	}

	/**
	 * Admin password reset for another user. Hashes with BCrypt, matching {@link #login}.
	 */
	@Transactional
	public ApiResponse<UserPasswordRequest> resetPassword(UserPasswordRequest src) {
		var wrap = new ApiResponse<UserPasswordRequest>();
		wrap.setStatus(false);

		ProcessResult pr = IlKeiValidator.builder().withPojo(src)
				.pick(UserPasswordRequest.Fields.password, "Password").isMandatory().asString().withMinLen(2).pack()
				.pick(UserPasswordRequest.Fields.id, "Id User").isMandatory().asLong().withMinLen(1).pack()
				.validate();
		if (!pr.getResult()) {
			wrap.setMessage(pr.getMessage());
			return wrap;
		}

		var user = loadActiveUser(src.getId(), wrap);
		if (user == null) {
			return wrap;
		}

		user.setPassword(passwordEncoder.encode(src.getPassword()));
		user.setUpdatedAt(LocalDateTime.now());
		user.setUpdatedBy(currentUser());
		this.em.flush();

		src.setPassword(null);
		wrap.insertNewData(src);
		wrap.setStatus(true);
		return wrap;
	}

	/**
	 * Changes the authenticated user's own password. The target is taken from the security context,
	 * never from the request body, so a caller cannot change another user's password.
	 */
	@Transactional
	public ApiResponse<MyPasswordRequest> changeMyPassword(MyPasswordRequest src) {
		var wrap = new ApiResponse<MyPasswordRequest>();
		wrap.setStatus(false);

		ProcessResult pr = IlKeiValidator.builder().withPojo(src)
				.pick(MyPasswordRequest.Fields.pass1, "Password Baru").isMandatory().asString().withMinLen(2).pack()
				.pick(MyPasswordRequest.Fields.pass2, "Konfirmasi Password").isMandatory().asString().withMinLen(2).pack()
				.validate();
		if (!pr.getResult()) {
			wrap.setMessage(pr.getMessage());
			return wrap;
		}

		if (!src.getPass1().equals(src.getPass2())) {
			wrap.setMessage(Utility.getErrorMessage("user.user.password_mismatch"));
			return wrap;
		}

		var me = currentUser();
		if (me == null) {
			wrap.setMessage(Utility.getErrorMessage("user.user.item_not_found"));
			return wrap;
		}

		var user = loadActiveUser(me.getId(), wrap);
		if (user == null) {
			return wrap;
		}

		user.setPassword(passwordEncoder.encode(src.getPass1()));
		user.setUpdatedAt(LocalDateTime.now());
		user.setUpdatedBy(me);
		this.em.flush();

		src.setPass1(null);
		src.setPass2(null);
		wrap.insertNewData(src);
		wrap.setStatus(true);
		return wrap;
	}

	/**
	 * Stores the authenticated user's avatar under the configured media folder and records the
	 * relative path in {@code users.image}.
	 */
	@Transactional
	public ApiResponse<String> uploadMyPhoto(byte[] bytes) {
		return uploadMyImage(bytes, "avatar", "user.user.image_invalid");
	}

	/**
	 * Stores the authenticated user's signature; identical to {@link #uploadMyPhoto} but writes to
	 * {@code <mediaFolder>/signature/} and records {@code users.signature}.
	 */
	@Transactional
	public ApiResponse<String> uploadMySignature(byte[] bytes) {
		return uploadMyImage(bytes, "signature", "user.user.image_invalid");
	}

	/**
	 * Shared avatar/signature upload. Validates the media folder setting and the image bytes before
	 * touching the filesystem, writes and resizes the file, then persists the relative path. Returns
	 * the stored relative path (never the managed {@code User}, which carries the password hash).
	 */
	private ApiResponse<String> uploadMyImage(byte[] bytes, String subFolder, String invalidImageKey) {
		var wrap = new ApiResponse<String>();
		wrap.setStatus(false);

		var me = currentUser();
		if (me == null) {
			wrap.setMessage(Utility.getErrorMessage("user.user.item_not_found"));
			return wrap;
		}

		var setting = this.em.find(Setting.class, 1L);
		if (setting == null || setting.getMediaFolder() == null || setting.getMediaUrl() == null) {
			wrap.setMessage(Utility.getErrorMessage("user.user.media_setting_invalid"));
			return wrap;
		}

		if (bytes == null || bytes.length == 0) {
			wrap.setMessage(Utility.getErrorMessage(invalidImageKey));
			return wrap;
		}

		var user = loadActiveUser(me.getId(), wrap);
		if (user == null) {
			return wrap;
		}

		String relativeDir = "/" + subFolder + "/";
		String fullDir = setting.getMediaFolder() + relativeDir;
		String originalPath = fullDir + user.getId() + ".png";
		String resizedPath = fullDir + user.getId() + "_r.png";

		try {
			var dir = new File(fullDir);
			if (!dir.exists() && !dir.mkdirs()) {
				wrap.setMessage(Utility.getErrorMessage("user.user.media_setting_invalid"));
				return wrap;
			}

			var original = new File(originalPath);
			try (var out = new FileOutputStream(original)) {
				out.write(bytes);
			}

			ImageResizer.resize(originalPath, resizedPath, 640, 480);
			original.delete();

			String storedPath = relativeDir + user.getId() + "_r.png";
			if ("avatar".equals(subFolder)) {
				user.setImage(storedPath);
			} else {
				user.setSignature(storedPath);
			}
			user.setUpdatedAt(LocalDateTime.now());
			this.em.flush();

			wrap.insertNewData(storedPath);
			wrap.setStatus(true);
			return wrap;
		} catch (IOException ex) {
			log.error("failed to store {} for user {}", subFolder, user.getId(), ex);
			wrap.setMessage(Utility.getErrorMessage(invalidImageKey));
			return wrap;
		}
	}

	/**
	 * Loads a non-removed user by id, populating {@code wrap}'s message and returning {@code null}
	 * when it is missing or already soft-deleted.
	 */
	private User loadActiveUser(Long id, ApiResponse<?> wrap) {
		var user = this.em.find(User.class, id);
		if (user == null) {
			wrap.setMessage(Utility.getErrorMessage("user.user.item_not_found"));
			return null;
		}
		if (user.getStatus() == null || "REM".equals(user.getStatus().getCode())) {
			wrap.setMessage(Utility.getErrorMessage("user.user.item_not_found"));
			return null;
		}
		return user;
	}

	public User getUserByUsername(String userName) {
		var qu = new QUser("u");
		var users = configBuilder.create(em, User.class).from(User.class, qu.getMetadata().getName())
				.where(qu.username.toString()).eq(userName)
				.setMaxResults(1)
				.getResultList();
		return users.isEmpty() ? null : users.getFirst();
	}

	public User currentUser() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !auth.isAuthenticated() || auth.getName() == null
				|| "anonymousUser".equals(auth.getName())) {
			return null;
		}
		return getUserByUsername(auth.getName());
	}
}
