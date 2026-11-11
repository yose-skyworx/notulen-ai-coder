package com.ilkeiapps.skyworx.notulenai.entity;

import java.io.Serial;
import java.io.Serializable;

import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.ilkeiapps.skyworx.notulenai.bean.enums.ShowType;

import lombok.Getter;
import lombok.Setter;


/**
 * The persistent class for the gender database table.
 *
 */
@Entity
@Table(name="gender")
@Setter
@Getter
public class Gender implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

	@Id
	private Integer id;

	private String code;
	private String name;

	@Column(name="show")
	@Enumerated(EnumType.STRING)
	private ShowType show;

	public Gender() {
	}


}
