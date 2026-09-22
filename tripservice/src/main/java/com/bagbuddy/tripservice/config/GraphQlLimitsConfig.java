package com.bagbuddy.tripservice.config;

import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Plafonds sur les requetes acceptees. Une API GraphQL publique laisse le client
 * composer sa requete : sans limite, il compose aussi le cout.
 *
 * Ce que chaque plafond protege reellement, ici :
 *
 *  - La complexite est la limite qui sert. Aucun type de nos schemas ne se
 *    reference en boucle, donc l'attaque classique par imbrication infinie est
 *    impossible ; en revanche rien n'empeche de demander cent fois le meme champ
 *    couteux sous cent alias differents, et c'est exactement ce que compte la
 *    complexite.
 *  - La profondeur ne protege de rien aujourd'hui (la requete la plus profonde
 *    du domaine fait 4 niveaux). Elle est la comme filet pour le jour ou le
 *    schema gagnera un champ cyclique, ou elle deviendra la seule barriere.
 */
@Configuration
public class GraphQlLimitsConfig {

    @Bean
    public Instrumentation maxQueryDepthInstrumentation(
            @Value("${bagbuddy.graphql.max-depth:12}") int maxDepth) {
        return new MaxQueryDepthInstrumentation(maxDepth);
    }

    @Bean
    public Instrumentation maxQueryComplexityInstrumentation(
            @Value("${bagbuddy.graphql.max-complexity:500}") int maxComplexity) {
        return new MaxQueryComplexityInstrumentation(maxComplexity);
    }
}
