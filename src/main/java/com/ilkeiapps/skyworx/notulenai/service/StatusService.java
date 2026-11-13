package com.ilkeiapps.skyworx.notulenai.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.ilkeiapps.skyworx.notulenai.entity.StatusGeneral;
import com.ilkeiapps.skyworx.notulenai.entity.QStatusGeneral;
import com.ilkeiapps.skyworx.notulenai.entity.QStatusProcess;
import com.ilkeiapps.skyworx.notulenai.entity.StatusProcess;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * Lookup of {@code status} reference rows by code. The {@code status} table is a single-table
 * hierarchy discriminated by {@code TYPE}; these accessors target the {@code GENERAL} subtype (e.g.
 * {@code "AKT"} active, {@code "REM"} removed) and the {@code PROCESS} subtype (document-processing
 * lifecycle: {@code PENDING}, {@code PROCESSING}, {@code COMPLETED}, {@code FAILED}, {@code CANCELLED}).
 */
@Service
@RequiredArgsConstructor
public class StatusService {

	private final EntityManager em;
	private final CriteriaBuilderFactory configBuilder;

	@Transactional(readOnly = true)
	public StatusGeneral getStatusGeneralByCode(String code) {
		var qs = new QStatusGeneral("s");
		var rows = configBuilder.create(em, StatusGeneral.class).from(StatusGeneral.class, qs.getMetadata().getName())
				.where(qs.code.toString()).eq(code)
				.setMaxResults(1)
				.getResultList();
		return rows.isEmpty() ? null : rows.getFirst();
	}

	@Transactional(readOnly = true)
	public StatusProcess getStatusProcessByCode(String code) {
		var qs = new QStatusProcess("s");
		var rows = configBuilder.create(em, StatusProcess.class).from(StatusProcess.class, qs.getMetadata().getName())
				.where(qs.code.toString()).eq(code)
				.setMaxResults(1)
				.getResultList();
		return rows.isEmpty() ? null : rows.getFirst();
	}
}
