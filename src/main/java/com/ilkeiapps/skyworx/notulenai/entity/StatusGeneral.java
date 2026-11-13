package com.ilkeiapps.skyworx.notulenai.entity;

import java.io.Serial;
import java.io.Serializable;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

import lombok.Getter;
import lombok.Setter;


/**
 * The persistent class for the test_statuses database table.
 *
 */
@Entity
@DiscriminatorValue(value="GENERAL")
@Setter
@Getter
public class StatusGeneral extends Status implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

}
