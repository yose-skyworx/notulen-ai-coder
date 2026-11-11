package com.ilkeiapps.skyworx.notulenai.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ilkeiapps.skyworx.notulenai.bean.web.ApiResponse;
import com.ilkeiapps.skyworx.notulenai.service.InitialMasterService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bootstrap endpoint that seeds the user-related master data via {@link InitialMasterService}. The
 * seeder is idempotent (each row is natural-key guarded), so the endpoint is safe to call more than
 * once. This route is permitted without authentication in {@code SecurityConfiguration} so it can be
 * run on a fresh database before any user exists.
 */
@Slf4j
@RestController
@RequestMapping(path = "/api/init")
@RequiredArgsConstructor
public class InitialMasterController {

	private final InitialMasterService initialMasterService;

	@PostMapping(path = "/master", produces = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<String> init() {
		return initialMasterService.init();
	}
}
