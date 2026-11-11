package com.ilkeiapps.skyworx.notulenai.controller;

import java.io.IOException;
import java.util.function.Function;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.ilkeiapps.skyworx.notulenai.bean.web.ApiResponse;
import com.ilkeiapps.skyworx.notulenai.bean.web.GenericRequestParameter;
import com.ilkeiapps.skyworx.notulenai.bean.web.LoginRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.MyPasswordRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.RoleRequestParameter;
import com.ilkeiapps.skyworx.notulenai.bean.web.UserIdRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.UserPasswordRequest;
import com.ilkeiapps.skyworx.notulenai.bean.RoleRequest;
import com.ilkeiapps.skyworx.notulenai.bean.UserRequest;
import com.ilkeiapps.skyworx.notulenai.entityview.role.RoleListView;
import com.ilkeiapps.skyworx.notulenai.entityview.user.UserListView;
import com.ilkeiapps.skyworx.notulenai.entityview.user.UserProfileView;
import com.ilkeiapps.skyworx.notulenai.service.UserService;
import com.ilkeiapps.skyworx.notulenai.utility.Utility;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * User / Role endpoints. Covers the routes backed by {@link UserService}: login, user
 * list/save/update/delete, the admin/self password resets, the avatar/signature uploads, role
 * list/save/update, and the authenticated user's profile ({@code /check}).
 * <p>
 * Reads return Entity Views; create/update return only status + message (the service's managed
 * entity is never serialized, since OSIV is disabled).
 */
@Slf4j
@RestController
@RequestMapping(path = "/api/user")
@RequiredArgsConstructor
public class UserController {

	private final UserService userService;

	@GetMapping(path = "/check", produces = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<UserProfileView> check() {
		return userService.getProfile();
	}

	@PostMapping(path = "/login",
			produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<String> login(@RequestBody LoginRequest req) {
		return userService.login(req);
	}

	@GetMapping(path = "/user/list", produces = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<UserListView> listUser(GenericRequestParameter filter) {
		return userService.listUser(filter);
	}

	@PostMapping(path = "/user/save",
			produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<String> saveUser(@RequestBody UserRequest req) {
		return ack(userService.saveUser(req));
	}

	@PostMapping(path = "/user/update",
			produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<String> updateUser(@RequestBody UserRequest req) {
		return ack(userService.updateUser(req));
	}

	@PostMapping(path = "/user/delete",
			produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<String> deleteUser(@RequestBody UserIdRequest req) {
		return ack(userService.deleteUser(req));
	}

	@PostMapping(path = "/user/resetpassword",
			produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<String> resetPassword(@RequestBody UserPasswordRequest req) {
		return ack(userService.resetPassword(req));
	}

	@PostMapping(path = "/my/resetpassword",
			produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<String> resetMyPassword(@RequestBody MyPasswordRequest req) {
		return ack(userService.changeMyPassword(req));
	}

	/**
	 * Avatar upload. Consumes multipart form data with the file part named {@code data}, matching the
	 * legacy {@code UploadForm.data} field so existing clients keep working.
	 */
	@PostMapping(path = "/my/photo", produces = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<String> uploadMyPhoto(@RequestParam("data") MultipartFile data) {
		return uploadAck(data, userService::uploadMyPhoto);
	}

	/** Signature upload; same multipart contract as {@link #uploadMyPhoto}. */
	@PostMapping(path = "/my/signature", produces = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<String> uploadMySignature(@RequestParam("data") MultipartFile data) {
		return uploadAck(data, userService::uploadMySignature);
	}

	@GetMapping(path = "/role/list", produces = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<RoleListView> listRole(RoleRequestParameter filter) {
		return userService.listRole(filter);
	}

	@PostMapping(path = "/role/save",
			produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<String> saveRole(@RequestBody RoleRequest req) {
		return ack(userService.saveRole(req));
	}

	@PostMapping(path = "/role/update",
			produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<String> updateRole(@RequestBody RoleRequest req) {
		return ack(userService.updateRole(req));
	}

	/** Returns only status + message, so a managed entity in the service response is never serialized. */
	private ApiResponse<String> ack(ApiResponse<?> src) {
		var out = new ApiResponse<String>();
		out.setStatus(src.getStatus());
		out.setMessage(src.getMessage());
		return out;
	}

	/**
	 * Reads the uploaded bytes and delegates to {@code handler}. A missing or unreadable part is
	 * reported through the standard wrapper rather than surfacing as a 500.
	 */
	private ApiResponse<String> uploadAck(MultipartFile data, Function<byte[], ApiResponse<?>> handler) {
		byte[] bytes;
		try {
			bytes = data.getBytes();
		} catch (IOException ex) {
			log.error("failed to read uploaded multipart file", ex);
			var out = new ApiResponse<String>();
			out.setStatus(false);
			out.setMessage(Utility.getErrorMessage("user.user.image_invalid"));
			return out;
		}
		return ack(handler.apply(bytes));
	}
}
