package com.ilkeiapps.skyworx.notulenai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

import com.blazebit.persistence.Criteria;
import com.blazebit.persistence.CriteriaBuilderFactory;
import com.blazebit.persistence.integration.view.spring.EnableEntityViews;
import com.blazebit.persistence.spi.CriteriaBuilderConfiguration;
import com.blazebit.persistence.view.EntityViewManager;
import com.blazebit.persistence.view.spi.EntityViewConfiguration;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceUnit;

/**
 * Blaze-Persistence wiring (house style): {@code CriteriaBuilderFactory} + {@code EntityViewManager}
 * with entity-view scanning. Also exposes a shared {@link EntityManager} bean so services can
 * constructor-inject {@code EntityManager}. This project has no Spring Data repositories, so Spring
 * Data does not register one automatically — without this bean, startup fails with
 * {@code NoSuchBeanDefinitionException} for {@code EntityManager}.
 */
@Configuration
@EnableEntityViews("com.ilkeiapps.skyworx.notulenai.entityview")
public class BlazePersistenceConfiguration {

	@PersistenceUnit
	private EntityManagerFactory entityManagerFactory;

	@Bean
	public EntityManager entityManager() {
		return SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
	}

	@Bean
	@Lazy(false)
	public CriteriaBuilderFactory createCriteriaBuilderFactory() {
		CriteriaBuilderConfiguration config = Criteria.getDefault();
		return config.createCriteriaBuilderFactory(entityManagerFactory);
	}

	@Bean
	@Lazy(false)
	public EntityViewManager createEntityViewManager(CriteriaBuilderFactory cbf,
			EntityViewConfiguration entityViewConfiguration) {
		return entityViewConfiguration.createEntityViewManager(cbf);
	}
}
