package com.ilkeiapps.skyworx.notulenai.entity;

import java.time.LocalDateTime;
import java.io.Serial;
import java.io.Serializable;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import com.ilkeiapps.skyworx.notulenai.bean.Menu;

import lombok.Getter;
import lombok.Setter;

/**
 * Entity implementation class for Entity: Token
 *
 */
@Entity
@Table(name="token")
@Setter
@Getter
public class Token implements Serializable {


	@Serial
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name="token_seq", sequenceName="token_seq", initialValue=1, allocationSize=1)
	@GeneratedValue(strategy=GenerationType.SEQUENCE, generator="token_seq")
	private Long id;

	private String token;
	private String ip;

	@ManyToOne(fetch = FetchType.LAZY)
	private User user;

	@Column(name="create_date")
	private LocalDateTime createDate;

	@Column(name = "expire_date")
	private LocalDateTime expireDate;

	@Transient
	private List<Menu> menu;

	@Transient
	private String avatar;

	public Token() {
		super();
	}

}
