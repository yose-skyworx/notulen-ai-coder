package com.ilkeiapps.skyworx.notulenai.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.ilkeiapps.skyworx.notulenai.bean.enums.ShowType;
import com.ilkeiapps.skyworx.notulenai.bean.web.ApiResponse;
import com.ilkeiapps.skyworx.notulenai.entity.Gender;
import com.ilkeiapps.skyworx.notulenai.entity.GeneralStatus;
import com.ilkeiapps.skyworx.notulenai.entity.Permission;
import com.ilkeiapps.skyworx.notulenai.entity.Role;
import com.ilkeiapps.skyworx.notulenai.entity.User;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Seeds the user-related master/reference data (the user slice of the legacy
 * {@code InitialMasterService}): general statuses, genders, permissions, roles and the superadmin
 * user. Idempotent at the row level — every {@code persist*} helper first checks whether the row
 * already exists by its natural key ({@code code}, or {@code name}/{@code username}) and inserts only
 * when absent, so the endpoint is safe to re-run.
 * <p>
 * The superadmin password is hashed with the project {@code PasswordEncoder} (BCrypt). The default
 * plaintext is {@value #DEFAULT_SUPERADMIN_PASSWORD} — change it before using in any real environment.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InitialMasterService {

	private static final String DEFAULT_SUPERADMIN_PASSWORD = "123456";
	private static final int STATUS_ACTIVE_ID = 10;

	private final EntityManager em;
	private final CriteriaBuilderFactory configBuilder;
	private final PasswordEncoder passwordEncoder;

	@Transactional
	public ApiResponse<String> init() {
		var wrap = new ApiResponse<String>();
		wrap.setStatus(false);

		var start = System.currentTimeMillis();
		log.info("init user master data: start");

		var statuses = seedStatuses();
		if (statuses > 0) {
			log.info("seeded {} general statuses", statuses);
		}

		var permissions = seedPermissions();
		if (permissions > 0) {
			log.info("seeded {} permissions", permissions);
		}
		// Make the just-persisted permissions visible to the role query below.
		this.em.flush();

		var roles = seedRoles();
		if (roles > 0) {
			log.info("seeded {} roles", roles);
		}

		var genders = seedGenders();
		if (genders > 0) {
			log.info("seeded {} genders", genders);
		}
		// Make the just-persisted roles/genders visible to the superadmin seed.
		this.em.flush();

		if (seedSuperadmin()) {
			log.info("seeded superadmin user");
		}

		log.info("init user master data: done in {} ms", System.currentTimeMillis() - start);
		wrap.insertNewData("User master data checked");
		wrap.setStatus(true);
		return wrap;
	}

	// ---------------------------------------------------------------- statuses

	private int seedStatuses() {
		var n = 0;
		n += persistGeneralStatus(STATUS_ACTIVE_ID, "AKT", "ACTIVE");
		n += persistGeneralStatus(11, "NAK", "NOT ACTIVE");
		n += persistGeneralStatus(12, "REM", "DELETED");
		return n;
	}

	// ---------------------------------------------------------------- permissions / roles / user

	private int seedPermissions() {
		var n = 0;
		n += persistPermission("view_names", "Can view patient names");
		n += persistPermission("manage_patients", "Can add patients");
		n += persistPermission("receive_external_test", "Can receive test requests");
		n += persistPermission("request_test", "Can request new test");
		n += persistPermission("accept_test_specimen", "Can accept test specimen");
		n += persistPermission("reject_test_specimen", "Can reject test specimen");
		n += persistPermission("change_test_specimen", "Can change test specimen");
		n += persistPermission("start_test", "Can start tests");
		n += persistPermission("enter_test_results", "Can enter tests results");
		n += persistPermission("edit_test_results", "Can edit test results");
		n += persistPermission("verify_test_results", "Can verify test results");
		n += persistPermission("send_results_to_external_system", "Can send test results to external systems");
		n += persistPermission("refer_specimens", "Can refer specimens");
		n += persistPermission("manage_users", "Can manage users");
		n += persistPermission("manage_test_catalog", "Can manage test catalog");
		n += persistPermission("manage_lab_configurations", "Can manage lab configurations");
		n += persistPermission("view_reports", "Can view reports");
		n += persistPermission("manage_inventory", "Can manage inventory");
		n += persistPermission("request_topup", "Can request top-up");
		n += persistPermission("manage_qc", "Can manage Quality Control");
		n += persistPermission("verify_test_results_2", "Can verify test result final");
		return n;
	}

	private int seedRoles() {
		var all = allPermissions();
		var exceptAnalis = Arrays.asList("verify_test_results_2", "manage_qc", "request_topup", "manage_inventory",
				"manage_lab_configurations", "manage_test_catalog", "manage_users");
		var exceptSpv = Arrays.asList("manage_lab_configurations", "manage_test_catalog");
		var exceptDoctor = Arrays.asList("manage_qc", "request_topup", "manage_inventory",
				"manage_lab_configurations", "manage_test_catalog", "manage_users");

		var n = 0;
		n += persistRole("SUPERADMIN", all);
		n += persistRole("ANALIS", filterPermissions(all, exceptAnalis));
		n += persistRole("SPV ANALIS", filterPermissions(all, exceptSpv));
		n += persistRole("DOKTER", filterPermissions(all, exceptDoctor));
		return n;
	}

	private int seedGenders() {
		var n = 0;
		n += persistGender(1, "male", "LAKI-LAKI", ShowType.INA);
		n += persistGender(2, "female", "PEREMPUAN", ShowType.INA);
		n += persistGender(3, "other", "LAINNYA", ShowType.INT);
		n += persistGender(4, "unknown", "TIDAK TAHU", ShowType.INT);
		return n;
	}

	private boolean seedSuperadmin() {
		if (existsBy(User.class, "username", "superadmin")) {
			return false;
		}
		var user = new User();
		user.setName("Administrator");
		user.setGender(this.em.find(Gender.class, 1));
		user.setRoles(roleByName("SUPERADMIN"));
		user.setStatus(this.em.find(GeneralStatus.class, STATUS_ACTIVE_ID));
		user.setUsername("superadmin");
		user.setPassword(passwordEncoder.encode(DEFAULT_SUPERADMIN_PASSWORD));
		user.setCreatedAt(LocalDateTime.now());
		this.em.persist(user);
		return true;
	}

	private List<Permission> filterPermissions(List<Permission> all, List<String> excludedNames) {
		var out = new ArrayList<Permission>();
		for (Permission p : all) {
			if (!excludedNames.contains(p.getName())) {
				out.add(p);
			}
		}
		return out;
	}

	// ---------------------------------------------------------------- persist helpers (idempotent)

	private int persistGeneralStatus(int id, String code, String name) {
		if (existsBy(GeneralStatus.class, "code", code)) {
			return 0;
		}
		var s = new GeneralStatus();
		s.setId(id);
		s.setCode(code);
		s.setName(name);
		this.em.persist(s);
		return 1;
	}

	private int persistPermission(String name, String displayName) {
		if (existsBy(Permission.class, "name", name)) {
			return 0;
		}
		var p = new Permission();
		p.setName(name);
		p.setDisplayName(displayName);
		this.em.persist(p);
		return 1;
	}

	private int persistRole(String name, List<Permission> permissions) {
		if (existsBy(Role.class, "name", name)) {
			return 0;
		}
		var r = new Role();
		r.setName(name);
		r.setPermissions(permissions);
		r.setStatus(this.em.find(GeneralStatus.class, STATUS_ACTIVE_ID));
		this.em.persist(r);
		return 1;
	}

	private int persistGender(int id, String code, String name, ShowType show) {
		if (existsBy(Gender.class, "code", code)) {
			return 0;
		}
		var g = new Gender();
		g.setId(id);
		g.setCode(code);
		g.setName(name);
		g.setShow(show);
		this.em.persist(g);
		return 1;
	}

	// ---------------------------------------------------------------- lookups

	/** True when a row of {@code entityClass} already has {@code value} in {@code field}. */
	private boolean existsBy(Class<?> entityClass, String field, Object value) {
		return configBuilder.create(em, entityClass).from(entityClass, "x")
				.where("x." + field).eq(value)
				.getCountQuery().getSingleResult() > 0;
	}

	private List<Permission> allPermissions() {
		return configBuilder.create(em, Permission.class).from(Permission.class, "p").getResultList();
	}

	private Role roleByName(String name) {
		var rows = configBuilder.create(em, Role.class).from(Role.class, "r")
				.where("r.name").eq(name).setMaxResults(1).getResultList();
		return rows.isEmpty() ? null : rows.getFirst();
	}
}
