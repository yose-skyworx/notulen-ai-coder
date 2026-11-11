package com.ilkeiapps.skyworx.notulenai.entity;

import java.io.Serial;
import java.io.Serializable;

import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * Entity implementation class for Entity: Status
 *
 */
@Entity
@Table(name="status")
@Inheritance(strategy=InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name="TYPE")
@Setter
@Getter
public abstract class Status implements Serializable {


	@Serial
	private static final long serialVersionUID = 1L;

	@Id
	private Integer id;

	private String code;

	private String name;

	public Status() {
		super();
	}

}
