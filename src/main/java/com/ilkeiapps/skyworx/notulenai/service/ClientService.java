package com.ilkeiapps.skyworx.notulenai.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.blazebit.persistence.view.EntityViewManager;
import com.blazebit.persistence.view.EntityViewSetting;
import com.ilkeiapps.ilkeitool.validator.IlKeiValidator;
import com.ilkeiapps.ilkeitool.validator.ProcessResult;
import com.ilkeiapps.skyworx.notulenai.bean.ClientRequest;
import com.ilkeiapps.skyworx.notulenai.bean.ClientUpdateRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.ApiResponse;
import com.ilkeiapps.skyworx.notulenai.bean.web.ClientIdRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.ClientRequestParameter;
import com.ilkeiapps.skyworx.notulenai.entity.Client;
import com.ilkeiapps.skyworx.notulenai.entity.QClient;
import com.ilkeiapps.skyworx.notulenai.entityview.client.ClientListView;
import com.ilkeiapps.skyworx.notulenai.utility.Utility;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Client CRUD, following the com.ilkeiapps house style: Blaze-Persistence queries with QueryDSL path
 * metadata, {@code IlKeiValidator} for input checks, Entity Views for reads, and a soft delete that
 * flips the general status to {@code REM}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClientService {

	private final EntityManager em;
	private final CriteriaBuilderFactory configBuilder;
	private final EntityViewManager viewManager;
	private final StatusService statusService;

	@Transactional
	public ApiResponse<ClientRequest> saveClient(ClientRequest src) {
		var wrap = new ApiResponse<ClientRequest>();
		wrap.setStatus(false);

		ProcessResult pr = IlKeiValidator.builder().withPojo(src)
				.pick(ClientRequest.Fields.code, "Kode").isMandatory().asString().withMinLen(2).pack()
				.pick(ClientRequest.Fields.name, "Nama").isMandatory().asString().withMinLen(2).pack()
				.validate();
		if (!pr.getResult()) {
			wrap.setMessage(pr.getMessage());
			return wrap;
		}

		var qc = new QClient("c");
		var cu = configBuilder.create(em, Client.class).from(Client.class, qc.getMetadata().getName())
				.where(qc.code.toString()).eq(src.getCode())
				.where(qc.status.code.toString()).notEq("REM")
				.getCountQuery().getSingleResult();
		if (cu > 0) {
			wrap.setMessage(Utility.getErrorMessage("client.client.duplicate_item"));
			return wrap;
		}

		var client = new Client();
		client.setCode(src.getCode());
		client.setName(src.getName());
		client.setStatus(statusService.getGeneralStatusByCode("AKT"));

		this.em.persist(client);
		this.em.flush();

		wrap.insertNewData(src);
		wrap.setStatus(true);
		return wrap;
	}

	@Transactional
	public ApiResponse<ClientUpdateRequest> updateClient(ClientUpdateRequest src) {
		var wrap = new ApiResponse<ClientUpdateRequest>();
		wrap.setStatus(false);

		ProcessResult pr = IlKeiValidator.builder().withPojo(src)
				.pick(ClientUpdateRequest.Fields.id, "Id Client").isMandatory().asLong().withMinLen(1).pack()
				.pick(ClientUpdateRequest.Fields.code, "Kode").isMandatory().asString().withMinLen(2).pack()
				.pick(ClientUpdateRequest.Fields.name, "Nama").isMandatory().asString().withMinLen(2).pack()
				.validate();
		if (!pr.getResult()) {
			wrap.setMessage(pr.getMessage());
			return wrap;
		}

		var qc = new QClient("c");
		var cu = configBuilder.create(em, Client.class).from(Client.class, qc.getMetadata().getName())
				.where(qc.code.toString()).eq(src.getCode())
				.where(qc.status.code.toString()).notEq("REM")
				.where(qc.id.toString()).notEq(src.getId())
				.getCountQuery().getSingleResult();
		if (cu > 0) {
			wrap.setMessage(Utility.getErrorMessage("client.client.duplicate_item"));
			return wrap;
		}

		var client = this.em.find(Client.class, src.getId());
		if (client == null) {
			wrap.setMessage(Utility.getErrorMessage("client.client.item_not_found"));
			return wrap;
		}

		client.setCode(src.getCode());
		client.setName(src.getName());

		this.em.merge(client);
		this.em.flush();

		wrap.insertNewData(src);
		wrap.setStatus(true);
		return wrap;
	}

	@Transactional(readOnly = true)
	public ApiResponse<ClientListView> listClient(ClientRequestParameter filter) {
		var wrap = new ApiResponse<ClientListView>();
		wrap.setStatus(false);

		var qc = new QClient("c");
		var query = configBuilder.create(em, Client.class).from(Client.class, qc.getMetadata().getName());
		if (filter.getKeyword() != null && filter.getKeyword().length() > 2) {
			var kw = "%" + filter.getKeyword().toLowerCase() + "%";
			query = query.whereOr()
					.where("LOWER(" + qc.code.toString() + ")").like().value(kw).noEscape()
					.where("LOWER(" + qc.name.toString() + ")").like().value(kw).noEscape()
					.endOr();
		}
		query = query.where(qc.status.code.toString()).notEq("REM");

		var num = Utility.getPerPage(filter.getPerPage());
		var of = Utility.getOffsetPage(filter.getPage(), num);
		query = query.orderByDesc(qc.id.toString());

		var res = viewManager.applySetting(EntityViewSetting.create(ClientListView.class, of, num), query)
				.withKeysetExtraction(true)
				.getResultList();

		wrap.setMaxPage(res.getTotalPages());
		wrap.setData(res.stream().toList());
		wrap.setStatus(true);
		return wrap;
	}

	/**
	 * Soft-deletes a client by setting the general status to {@code REM}. The row is kept so existing
	 * references (e.g. projects pointing at it) remain resolvable; every list query filters {@code REM} out.
	 */
	@Transactional
	public ApiResponse<ClientIdRequest> deleteClient(ClientIdRequest src) {
		var wrap = new ApiResponse<ClientIdRequest>();
		wrap.setStatus(false);

		if (src.getId() == null) {
			wrap.setMessage(Utility.getErrorMessage("client.client.item_not_found"));
			return wrap;
		}

		var client = this.em.find(Client.class, src.getId());
		if (client == null) {
			wrap.setMessage(Utility.getErrorMessage("client.client.item_not_found"));
			return wrap;
		}

		var removed = statusService.getGeneralStatusByCode("REM");
		if (removed == null) {
			wrap.setMessage(Utility.getErrorMessage("client.client.status_missing"));
			return wrap;
		}

		client.setStatus(removed);
		this.em.flush();

		src.setId(client.getId());
		wrap.insertNewData(src);
		wrap.setStatus(true);
		return wrap;
	}
}
