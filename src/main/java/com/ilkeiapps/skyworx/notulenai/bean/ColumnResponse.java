package com.ilkeiapps.skyworx.notulenai.bean;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Setter
@Getter
@ToString(includeFieldNames=true)
public class ColumnResponse {

	private String heading;
	private String data;


	public ColumnResponse(String heading, String data) {
		super();
		this.heading = heading;
		this.data = data;
	}


	public ColumnResponse() {
		super();
	}


}
