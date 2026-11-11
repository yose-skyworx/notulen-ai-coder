package com.ilkeiapps.skyworx.notulenai.entity;

import java.io.Serial;
import java.io.Serializable;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;


/**
 * The persistent class for the institution database table.
 *
 */
@Entity
@Table(name="institution")
@Setter
@Getter
public class Institution implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

	@Id
	private Long id;

	private String name;

	private String key;

	public Institution() {
	}

}
