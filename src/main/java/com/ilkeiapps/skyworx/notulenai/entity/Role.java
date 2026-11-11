package com.ilkeiapps.skyworx.notulenai.entity;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;


/**
 * The persistent class for the roles database table.
 *
 */
@Entity
@Table(name="roles")
@Setter
@Getter
public class Role implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name="roles_id_generator", sequenceName="seq_roles", initialValue=1, allocationSize=1)
	@GeneratedValue(strategy=GenerationType.SEQUENCE, generator="roles_id_generator")
	private Long id;

	@Column(name="created_at")
	private LocalDateTime createdAt;

	private String description;

	@Column(name="display_name")
	private String displayName;

	private String name;

	@Column(name="updated_at")
	private LocalDateTime updatedAt;

	//bi-directional many-to-many association to Permission
	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(
		name="permission_role"
		, joinColumns={
			@JoinColumn(name="role_id")
			}
		, inverseJoinColumns={
			@JoinColumn(name="permission_id")
			}
		)
	private List<Permission> permissions;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name="status_id")
	private GeneralStatus status;


	public Role() {
	}

}
