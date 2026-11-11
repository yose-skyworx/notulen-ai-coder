package com.ilkeiapps.skyworx.notulenai.bean;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class Menu {

	private String url;
	private String name;
	private String slug;
	private String icon;
	private List<Menu> submenu;

	public Menu() {

	}
	public Menu(String url, String name, String slug, String icon) {
		super();
		this.url = url;
		this.name = name;
		this.slug = slug;
		this.icon = icon;
	}


}
