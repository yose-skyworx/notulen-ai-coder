package com.ilkeiapps.skyworx.notulenai.entity;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;


/**
 * The persistent class for the users database table.
 *
 */
@Entity
@Table(name="users")
@Setter
@Getter
public class User implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name="users_id_generator", sequenceName="seq_users", initialValue=1, allocationSize=1)
	@GeneratedValue(strategy=GenerationType.SEQUENCE, generator="users_id_generator")
	private Long id;

	private String address;

	@Column(name="created_at")
	private LocalDateTime createdAt;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "created_by_id", referencedColumnName = "id")
	private User createdBy;

	@Column(name="deleted_at")
	private LocalDateTime deletedAt;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "deleted_by_id", referencedColumnName = "id")
	private User deletedBy;

	private String designation;

	private String email;

	@ManyToOne(fetch = FetchType.LAZY)
	private Gender gender;

	private String image;

	private String signature;

	private String name;

	private String password;

	private String phone;

	@Column(name="external_code")
	private String externalCode;

	@Column(name="remember_token")
	private String rememberToken;

	@Column(name="updated_at")
	private LocalDateTime updatedAt;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "updated_by_id", referencedColumnName = "id")
	private User updatedBy;

	private String username;

	//bi-directional many-to-many association to Role
	@ManyToOne(fetch = FetchType.LAZY)
	private Role roles;

	@ManyToOne(fetch = FetchType.LAZY)
	private Institution institution;

	@ManyToOne(fetch = FetchType.LAZY)
	private GeneralStatus status;

	public User() {
	}

}
