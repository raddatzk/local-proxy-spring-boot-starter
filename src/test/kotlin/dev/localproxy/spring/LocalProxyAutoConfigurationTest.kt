package dev.localproxy.spring

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
            assertTrue(context.containsBean("localProxyRegistrar"))
        }
    }

    @Test
    fun `can be switched off`() {
        runner.withPropertyValues("local.proxy.enabled=false").run { context ->
            assertFalse(context.containsBean("localProxyRegistrar"))
        }
    }

    @Test
    fun `derives the hostname from the application name`() {
        runner.withPropertyValues("spring.application.name=Order Service").run { context ->
            val properties = context.getBean(LocalProxyProperties::class.java)
            assertTrue(properties.host == null)
            assertTrue(properties.tld == "localhost")
        }
    }
}