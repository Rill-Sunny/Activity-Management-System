package com.limitactivitysystem.gift.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import jakarta.validation.constraints.*;

public class AllocateGiftSeatsRequest {
    @NotBlank @Size(max = 64) private String requestId;
    @NotNull @Positive @JsonDeserialize(using = IntegerQuantity.class) private Integer quantity;
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    @JsonAnySetter public void rejectUnknownProperty(String field, Object value) {
        throw new IllegalArgumentException("GIFT allocation accepts only requestId and quantity");
    }
    public static class IntegerQuantity extends JsonDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return context.reportInputMismatch(Integer.class, "quantity must be a JSON integer");
            }
            return parser.getIntValue();
        }
    }
}
