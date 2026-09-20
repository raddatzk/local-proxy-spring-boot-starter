package me.raddatz.localproxy.spring

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment

@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnClass(ObjectMapper::class)
@ConditionalOnProperty(
    prefix = "local.proxy",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(LocalProxyProperties::class)
class LocalProxyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun caddyAdminClient(properties: LocalProxyProperties): CaddyAdminClient =
        CaddyAdminClient(properties.caddy.adminUrl, properties.timeout)

    @Bean
    @ConditionalOnMissingBean
    fun localProxyRegistrar(
        properties: LocalProxyProperties,
        client: CaddyAdminClient,
        environment: Environment,
    ): LocalProxyRegistrar = LocalProxyRegistrar(
        properties,
        client,
        environment.getProperty("spring.application.name"),
    )
}