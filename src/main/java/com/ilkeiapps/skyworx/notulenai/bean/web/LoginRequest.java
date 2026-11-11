package com.ilkeiapps.skyworx.notulenai.bean.web;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

@Setter
@Getter
@ToString(includeFieldNames=true)
@FieldNameConstants
public class LoginRequest {

	private String username;
	private String password;
	private String ip;
}
