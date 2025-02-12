/*
 * Copyright 2013-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.kubernetes.configserver;

import java.util.*;

import io.kubernetes.client.openapi.apis.CoreV1Api;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.environment.PropertySource;
import org.springframework.cloud.config.server.environment.EnvironmentRepository;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.StringUtils;

/**
 * @author Ryan Baxter
 */
public class KubernetesEnvironmentRepository implements EnvironmentRepository {

	private static final Log LOG = LogFactory.getLog(KubernetesEnvironmentRepository.class);

	private final CoreV1Api coreApi;

	private final List<KubernetesPropertySourceSupplier> kubernetesPropertySourceSuppliers;

	private final String namespace;

	@Value("${spring.cloud.addProfile.secrets}")
	private boolean addSecretsProfile;

	public KubernetesEnvironmentRepository(CoreV1Api coreApi,
										   List<KubernetesPropertySourceSupplier> kubernetesPropertySourceSuppliers,
										   String namespace) {
		this.coreApi = coreApi;
		this.kubernetesPropertySourceSuppliers = kubernetesPropertySourceSuppliers;
		this.namespace = namespace;
		LOG.info("Initialized KubernetesEnvironmentRepository with namespace: " + namespace);
	}

	@Override
	public Environment findOne(String application, String profile, String label) {
		return findOne(application, profile, label, true);
	}

	@Override
	public Environment findOne(String application, String profile, String label, boolean includeOrigin) {
		LOG.info("Finding environment for application: " + application + ", profile: " + profile + ", label: " + label);

		if (!StringUtils.hasText(profile)) {
			profile = "default";
			LOG.debug("No profile provided, using default profile");
		}

		List<String> profiles = new ArrayList<>(List.of(StringUtils.commaDelimitedListToStringArray(profile)));
		Collections.reverse(profiles);

		if (!profiles.contains("default")) {
			profiles.add("default");
			LOG.debug("Added 'default' profile to the list of active profiles");
		}

		Environment environment = new Environment(application, profiles.toArray(new String[0]), label, null, null);
		LOG.info("Created Environment with profiles: " + StringUtils.collectionToCommaDelimitedString(profiles));

		for (String activeProfile : profiles) {
			try {
				LOG.debug("Processing profile: " + activeProfile);
				StandardEnvironment springEnv = new KubernetesConfigServerEnvironment(createPropertySources(application));
				springEnv.setActiveProfiles(activeProfile);
				LOG.debug("Set active profile in Spring Environment: " + activeProfile);

				if (!"application".equalsIgnoreCase(application)) {
					LOG.debug("Adding application configuration for: " + application);
					addApplicationConfiguration(environment, springEnv, application);
				}
			} catch (Exception e) {
				LOG.warn("Error processing profile: " + activeProfile, e);
			}
		}

		LOG.debug("Adding default application configuration for 'application'");
		StandardEnvironment springEnv = new KubernetesConfigServerEnvironment(createPropertySources("application"));
		addApplicationConfiguration(environment, springEnv, "application");

		LOG.info("Final Environment: " + environment);
		return environment;
	}

	private MutablePropertySources createPropertySources(String application) {
		LOG.debug("Creating property sources for application: " + application);
		Map<String, Object> applicationProperties = Map.of("spring.application.name", application);
		MapPropertySource propertySource = new MapPropertySource("kubernetes-config-server", applicationProperties);
		MutablePropertySources mutablePropertySources = new MutablePropertySources();
		mutablePropertySources.addFirst(propertySource);
		LOG.debug("Added property source: " + propertySource.getName());
		return mutablePropertySources;
	}

	private void addApplicationConfiguration(Environment environment, StandardEnvironment springEnv,
											 String applicationName) {
		LOG.debug("Adding application configuration for: " + applicationName);

		Set<String> activeProfiles = new HashSet<>(Arrays.asList(springEnv.getActiveProfiles()));
		if (!activeProfiles.contains("secrets") && addSecretsProfile) {
			activeProfiles.add("secrets");
			springEnv.setActiveProfiles(activeProfiles.toArray(new String[0]));
			LOG.info("Automatically added 'secrets' profile since it was missing.");
		}

		kubernetesPropertySourceSuppliers.forEach(supplier -> {
			LOG.debug("Processing property source supplier: " + supplier.getClass().getSimpleName());
			List<MapPropertySource> propertySources = supplier.get(coreApi, applicationName, namespace, springEnv);
			LOG.debug("Found " + propertySources.size() + " property sources for application: " + applicationName);

			propertySources.forEach(propertySource -> {
				if (propertySource.getPropertyNames().length > 0) {
					LOG.debug("Adding PropertySource: " + propertySource.getName());
					LOG.debug("PropertySource Names: "
						+ StringUtils.arrayToCommaDelimitedString(propertySource.getPropertyNames()));
					environment.add(new PropertySource(propertySource.getName(), propertySource.getSource()));
				} else {
					LOG.debug("Skipping empty PropertySource: " + propertySource.getName());
				}
			});
		});
	}
}
