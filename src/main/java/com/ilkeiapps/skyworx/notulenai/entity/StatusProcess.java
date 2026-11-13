package com.ilkeiapps.skyworx.notulenai.entity;

import java.io.Serial;
import java.io.Serializable;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

import lombok.Getter;
import lombok.Setter;


/**
 * Document-processing status master. A subtype of the single-table {@link Status} hierarchy
 * (discriminator {@code TYPE = 'PROCESS'}), sharing the {@code status} table with
 * {@link StatusGeneral}. Rows are seeded idempotently by {@code InitialMasterService} and resolved
 * by {@code code} via {@code StatusService} (never by hardcoded id).
 * <p>
 * Codes: {@code PENDING}, {@code PROCESSING}, {@code COMPLETED}, {@code FAILED}, {@code CANCELLED}.
 */
@Entity
@DiscriminatorValue(value = "PROCESS")
@Setter
@Getter
public class StatusProcess extends Status implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

}
