package com.bagbuddy.transactionservice.config;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.data.method.annotation.support.AnnotatedControllerConfigurer;

/**
 * Garde les resolvers sur le thread de la requete.
 *
 * Avec spring.threads.virtual.enabled, Spring GraphQL reenvoie chaque resolver "bloquant" sur
 * l'executeur applicatif : la requete passe en servlet asynchrone et le SecurityContext doit
 * suivre par propagation de contexte. Le thread de requete de Tomcat etant deja virtuel, ce
 * detour n'apporte rien -- attendre Postgres ou un autre service n'y retient deja aucun thread
 * systeme -- et il cassait MockMvc, qui recevait une reponse vide faute d'asyncDispatch.
 */
@Configuration
public class GraphQlExecutionConfig {

    @Bean
    static BeanPostProcessor synchronousGraphQlResolvers() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                // Avant afterPropertiesSet(), qui est l'etape ou les resolvers sont detectes.
                if (bean instanceof AnnotatedControllerConfigurer configurer) {
                    configurer.setBlockingMethodPredicate(method -> false);
                }
                return bean;
            }
        };
    }
}
