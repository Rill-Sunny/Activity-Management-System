package com.limitactivitysystem.product.dto;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.math.BigDecimal;

public final class ProductRequestFormats {
    private ProductRequestFormats() { }
    public static class IntegerValue extends JsonDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) { return context.reportInputMismatch(Integer.class, "Expected a JSON integer"); }
            return parser.getIntValue();
        }
    }
    public static class LongValue extends JsonDeserializer<Long> {
        @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) { return context.reportInputMismatch(Long.class, "Expected a JSON integer ID"); }
            return parser.getLongValue();
        }
    }
    public static class MoneyValue extends JsonDeserializer<BigDecimal> {
        @Override public BigDecimal deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT) && !parser.hasToken(JsonToken.VALUE_NUMBER_FLOAT)) {
                return context.reportInputMismatch(BigDecimal.class, "Expected a JSON decimal amount");
            }
            return parser.getDecimalValue();
        }
    }
}
