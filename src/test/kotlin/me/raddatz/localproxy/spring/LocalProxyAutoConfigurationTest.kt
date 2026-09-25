package me.raddatz.localproxy.spring

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalProxyAutoConfigurationTest {

    private val runner = WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(LocalProxyAutoConfiguration::class.java))

    @Test
    fun `is active by default in a web application`() {
        runner.run { context ->
            assertThat(context.containsBean("localProxyRegistrar")).isFalse
        }
    }

    @Test
    fun `can be turned on`() {
        runner.withPropertyValues("local.proxy.enabled=true").run { context ->
            assertThat(context.containsBean("localProxyRegistrar")).isTrue
        }
    }

    @Test
    fun `derives the hostname from the application name`() {
        runner.withPropertyValues("spring.application.name=Order Service", "local.proxy.enabled=true").run { context ->
            val properties = context.getBean(LocalProxyProperties::class.java)
            assertThat(properties.host == null).isTrue
            assertThat(properties.tld == "localhost").isTrue
        }
    }
}
