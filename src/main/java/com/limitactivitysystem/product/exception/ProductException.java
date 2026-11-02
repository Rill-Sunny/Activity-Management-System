package com.limitactivitysystem.product.exception;

public class ProductException extends RuntimeException {
    private final ProductErrorCode code;
    public ProductException(ProductErrorCode code,String message) { super(message);this.code=code; }
    public ProductErrorCode code(){ return code; }
}
