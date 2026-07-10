package com.wallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The single entry point that boots the entire backend. Running this
 * class's main() method starts an embedded web server and the whole
 * Spring application context.
 *
 * @SpringBootApplication is shorthand for three annotations combined:
 *
 *   1. @Configuration           - this class is itself allowed to declare
 *                                 Spring @Bean methods.
 *   2. @EnableAutoConfiguration - Spring Boot looks at what's on the
 *                                 classpath and configures sensible
 *                                 defaults automatically. For example:
 *                                   - seeing spring-boot-starter-web       -> auto-configure an embedded Tomcat + Spring MVC
 *                                   - seeing spring-boot-starter-data-jpa
 *                                     + a MySQL driver                    -> auto-configure a DataSource + Hibernate
 *   3. @ComponentScan           - automatically find and register every
 *                                 @Component / @Service / @Repository /
 *                                 @RestController class in this package
 *                                 (com.wallet) or any sub-package. This is
 *                                 exactly why every layer from Section 11's
 *                                 folder structure (controller/, service/,
 *                                 repository/, etc.) lives UNDER com.wallet
 *                                 rather than beside it - if they didn't,
 *                                 Spring would never find them.
 */
@SpringBootApplication
@EnableScheduling
public class LedgerPayApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerPayApplication.class, args);
    }
}
