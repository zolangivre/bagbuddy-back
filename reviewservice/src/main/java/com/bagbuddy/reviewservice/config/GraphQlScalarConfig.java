package com.bagbuddy.reviewservice.config;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * GraphQL ne connait que Int, Float, String, Boolean et ID. Le domaine BagBuddy manipule des
 * dates et surtout de l'argent : serialiser un prix en Float perdrait de la precision, ce que
 * l'API REST ne faisait pas. Ces trois scalaires reprennent exactement le format que Jackson
 * produisait auparavant, pour que la migration ne change pas la valeur transportee.
 */
@Configuration
public class GraphQlScalarConfig {

    /** ISO-8601 local, le format que Jackson serialisait deja pour LocalDateTime. */
    public static final GraphQLScalarType DATE_TIME = GraphQLScalarType.newScalar()
            .name("DateTime")
            .description("Date-heure ISO-8601 locale, ex. 2026-09-09T14:30:00")
            .coercing(new Coercing<LocalDateTime, String>() {

                @Override
                public String serialize(Object input, GraphQLContext context, Locale locale) {
                    if (input instanceof LocalDateTime dateTime) {
                        return dateTime.toString();
                    }
                    throw new CoercingSerializeException(
                            "DateTime attend un LocalDateTime, recu " + typeOf(input));
                }

                @Override
                public LocalDateTime parseValue(Object input, GraphQLContext context, Locale locale) {
                    return parse(String.valueOf(input));
                }

                @Override
                public LocalDateTime parseLiteral(Value<?> value, CoercedVariables variables,
                                                  GraphQLContext context, Locale locale) {
                    if (value instanceof StringValue stringValue) {
                        return parse(stringValue.getValue());
                    }
                    throw new CoercingParseLiteralException("DateTime attend une chaine ISO-8601");
                }

                private LocalDateTime parse(String raw) {
                    try {
                        return LocalDateTime.parse(raw);
                    } catch (DateTimeParseException ex) {
                        try {
                            // Un client qui envoie un offset (…Z, +02:00) reste accepte : on
                            // retombe sur l'heure locale, comme le faisait la deserialisation REST.
                            return OffsetDateTime.parse(raw).toLocalDateTime();
                        } catch (DateTimeParseException ignored) {
                            throw new CoercingParseValueException(
                                    "Date-heure ISO-8601 invalide : " + raw);
                        }
                    }
                }
            })
            .build();

    /** Montants et poids : transportes en decimal exact, jamais en virgule flottante. */
    public static final GraphQLScalarType BIG_DECIMAL = GraphQLScalarType.newScalar()
            .name("BigDecimal")
            .description("Nombre decimal de precision arbitraire (prix, poids)")
            .coercing(new Coercing<BigDecimal, BigDecimal>() {

                @Override
                public BigDecimal serialize(Object input, GraphQLContext context, Locale locale) {
                    if (input instanceof BigDecimal decimal) {
                        return decimal;
                    }
                    if (input instanceof Number number) {
                        return new BigDecimal(number.toString());
                    }
                    throw new CoercingSerializeException(
                            "BigDecimal attend un nombre, recu " + typeOf(input));
                }

                @Override
                public BigDecimal parseValue(Object input, GraphQLContext context, Locale locale) {
                    return parse(String.valueOf(input));
                }

                @Override
                public BigDecimal parseLiteral(Value<?> value, CoercedVariables variables,
                                               GraphQLContext context, Locale locale) {
                    if (value instanceof IntValue intValue) {
                        return new BigDecimal(intValue.getValue());
                    }
                    if (value instanceof FloatValue floatValue) {
                        return floatValue.getValue();
                    }
                    if (value instanceof StringValue stringValue) {
                        return parse(stringValue.getValue());
                    }
                    throw new CoercingParseLiteralException("BigDecimal attend un nombre");
                }

                private BigDecimal parse(String raw) {
                    try {
                        return new BigDecimal(raw);
                    } catch (NumberFormatException ex) {
                        throw new CoercingParseValueException("Nombre decimal invalide : " + raw);
                    }
                }
            })
            .build();

    /** Int GraphQL est un 32 bits signe : trop court pour un compteur ou un montant Stripe. */
    public static final GraphQLScalarType LONG = GraphQLScalarType.newScalar()
            .name("Long")
            .description("Entier 64 bits")
            .coercing(new Coercing<Long, Long>() {

                @Override
                public Long serialize(Object input, GraphQLContext context, Locale locale) {
                    if (input instanceof Number number) {
                        return number.longValue();
                    }
                    throw new CoercingSerializeException(
                            "Long attend un nombre, recu " + typeOf(input));
                }

                @Override
                public Long parseValue(Object input, GraphQLContext context, Locale locale) {
                    return parse(String.valueOf(input));
                }

                @Override
                public Long parseLiteral(Value<?> value, CoercedVariables variables,
                                         GraphQLContext context, Locale locale) {
                    if (value instanceof IntValue intValue) {
                        return intValue.getValue().longValueExact();
                    }
                    if (value instanceof StringValue stringValue) {
                        return parse(stringValue.getValue());
                    }
                    throw new CoercingParseLiteralException("Long attend un entier");
                }

                private Long parse(String raw) {
                    try {
                        return Long.valueOf(raw);
                    } catch (NumberFormatException ex) {
                        throw new CoercingParseValueException("Entier invalide : " + raw);
                    }
                }
            })
            .build();

    private static String typeOf(Object input) {
        return input == null ? "null" : input.getClass().getName();
    }

    @Bean
    public RuntimeWiringConfigurer bagbuddyScalarsConfigurer() {
        return wiring -> wiring.scalar(DATE_TIME).scalar(BIG_DECIMAL).scalar(LONG);
    }
}
