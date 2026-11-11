package com.ilkeiapps.skyworx.notulenai.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ilkeiapps.skyworx.notulenai.bean.ClientRequest;
import com.ilkeiapps.skyworx.notulenai.bean.ClientUpdateRequest;
import com.ilkeiapps.skyworx.notulenai.bean.ProjectRequest;
import com.ilkeiapps.skyworx.notulenai.bean.ProjectUpdateRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.ApiResponse;
import com.ilkeiapps.skyworx.notulenai.bean.web.ClientIdRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.ClientRequestParameter;
import com.ilkeiapps.skyworx.notulenai.bean.web.ProjectIdRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.ProjectRequestParameter;
import com.ilkeiapps.skyworx.notulenai.entityview.client.ClientListView;
import com.ilkeiapps.skyworx.notulenai.entityview.project.ProjectListView;
import com.ilkeiapps.skyworx.notulenai.service.ClientService;
import com.ilkeiapps.skyworx.notulenai.service.ProjectService;

import lombok.RequiredArgsConstructor;

/**
 * Project and client CRUD endpoints, backed by {@link ProjectService} and {@link ClientService}.
 * Project routes sit under {@code /api/project}, client routes under {@code /api/project/client};
 * all sit under {@code /api/**} and therefore require a valid JWT (see {@code SecurityConfiguration}).
 * <p>
 * The list reads return Entity Views; create/update/delete return the service's {@link ApiResponse}
 * directly, which wraps request DTOs (never managed entities, so nothing lazy is serialized).
 */
@RestController
@RequestMapping(path = "/api/project")
@RequiredArgsConstructor
public class ProjectController {

	private final ProjectService projectService;
	private final ClientService clientService;

	@GetMapping(path = "/list", produces = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<ProjectListView> list(ProjectRequestParameter filter) {
		return projectService.listProject(filter);
	}

	@PostMapping(path = "/save",
			produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<ProjectRequest> save(@RequestBody ProjectRequest req) {
		return projectService.saveProject(req);
	}

	@PostMapping(path = "/update",
			produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<ProjectUpdateRequest> update(@RequestBody ProjectUpdateRequest req) {
		return projectService.updateProject(req);
	}

	@PostMapping(path = "/delete",
			produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<ProjectIdRequest> delete(@RequestBody ProjectIdRequest req) {
		return projectService.deleteProject(req);
	}

	@GetMapping(path = "/client/list", produces = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<ClientListView> listClient(ClientRequestParameter filter) {
		return clientService.listClient(filter);
	}

	@PostMapping(path = "/client/save",
			produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<ClientRequest> saveClient(@RequestBody ClientRequest req) {
		return clientService.saveClient(req);
	}

	@PostMapping(path = "/client/update",
			produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<ClientUpdateRequest> updateClient(@RequestBody ClientUpdateRequest req) {
		return clientService.updateClient(req);
	}

	@PostMapping(path = "/client/delete",
			produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<ClientIdRequest> deleteClient(@RequestBody ClientIdRequest req) {
		return clientService.deleteClient(req);
	}
}
