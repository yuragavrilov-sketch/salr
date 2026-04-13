package ru.tcb.sal.commands.test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * Test-slice annotation for SAL Command Bus handler tests.
 * Bootstraps a Spring context with InMemoryCommandTransport —
 * no RabbitMQ needed. @CommandHandler beans are auto-discovered.
 *
 * Usage:
 * <pre>
 * {@literal @}SalCommandsTest
 * class MyHandlerTest {
 *     {@literal @}Autowired CommandBus commandBus;
 *
 *     {@literal @}Test void myTest() {
 *         var result = commandBus.execute(new MyCommand(...));
 *         assertThat(result.value()).isEqualTo("expected");
 *     }
 * }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(classes = SalCommandsTestAutoConfiguration.class)
@Import(SalCommandsTestAutoConfiguration.class)
public @interface SalCommandsTest {
}
