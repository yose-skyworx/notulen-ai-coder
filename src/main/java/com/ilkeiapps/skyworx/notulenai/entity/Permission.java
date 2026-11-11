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
import jakarta.persistence.ManyToMany;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;


/**
 * The persistent class for the permissions database table.
 *
 */
@Entity
@Table(name="permissions")
@Setter
@Getter
public class Permission implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name="permissions_id_generator", sequenceName="seq_permissions", initialValue=1, allocationSize=1)
	@GeneratedValue(strategy=GenerationType.SEQUENCE, generator="permissions_id_generator")
	private Long id;

	@Column(name="created_at")
	private LocalDateTime createdAt;

	private String description;

	@Column(name="display_name")
	private String displayName;

	private String name;

	@Column(name="updated_at")
	private LocalDateTime updatedAt;

	//bi-directional many-to-many association to Role
	@ManyToMany(mappedBy="permissions", fetch = FetchType.LAZY)
	private List<Role> roles;

	public Permission() {
	}

}
