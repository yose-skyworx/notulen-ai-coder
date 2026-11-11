package com.ilkeiapps.skyworx.notulenai.entity;

import java.io.Serial;
import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;


/**
 * The persistent class for the setting database table.
 *
 */
@Entity
@Table(name="setting")
@Setter
@Getter
public class Setting implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

	@Id
	private Long id;

	@Column(name="hospital_code")
	private String hospitalCode;

	@Column(name="hospital_name")
	private String hospitalName;

	@Column(name="hospital_address")
	private String hospitalAddress;

	@Column(name="head_lab")
	private String headOfLab;

	@Column(name="head_lab_no")
	private String headOfLabNumber;

	@Column(name="doc_lab")
	private String doctorOfLab;

	@Column(name="doc_lab_no")
	private String doctorOfLabNumber;

	@Column(name="spv_lab")
	private String spvOfLab;

	@Column(name="spv_lab_no")
	private String spvOfLabNumber;

	@Column(name="loc")
	private String location;

	@Column(name="logo_pos")
	private Integer logoPosition;

	@Column(name="media_folder")
	private String mediaFolder;

	@Column(name="media_url")
	private String mediaUrl;

	@Column(name="barcode_data", columnDefinition="TEXT")
	private String barcodeData;

	public Setting() {
	}


}
