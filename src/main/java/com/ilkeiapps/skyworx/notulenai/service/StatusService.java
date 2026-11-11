package com.ilkeiapps.skyworx.notulenai.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.ilkeiapps.skyworx.notulenai.entity.GeneralStatus;
import com.ilkeiapps.skyworx.notulenai.entity.QGeneralStatus;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * Lookup of {@code status} reference rows by code. The {@code status} table is a single-table
 * hierarchy discriminated by {@code TYPE}; this accessor targets the general subtype, e.g.
 * {@code "AKT"} active, {@code "REM"} removed.
 */
@Service
@RequiredArgsConstructor
public class StatusService {

	private final EntityManager em;
	private final CriteriaBuilderFactory configBuilder;

	@Transactional(readOnly = true)
	public GeneralStatus getGeneralStatusByCode(String code) {
		var qs = new QGeneralStatus("s");
		var rows = configBuilder.create(em, GeneralStatus.class).from(GeneralStatus.class, qs.getMetadata().getName())
				.where(qs.code.toString()).eq(code)
				.setMaxResults(1)
				.getResultList();
		return rows.isEmpty() ? null : rows.getFirst();
	}
}
