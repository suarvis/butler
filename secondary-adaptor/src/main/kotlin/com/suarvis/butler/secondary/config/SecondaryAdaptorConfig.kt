package com.suarvis.butler.secondary.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PersistenceProperties::class, ModelCatalogProperties::class, ModelProviderProperties::class)
class SecondaryAdaptorConfig
