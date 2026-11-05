package com.limitactivitysystem.product.controller;

import com.limitactivitysystem.product.exception.ProductException;
import com.limitactivitysystem.product.exception.ProductOrderOutOfStockException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.*;

@RestControllerAdvice(basePackages="com.limitactivitysystem.product.controller")
public class ProductRequestExceptionHandler {
    private static final Logger LOG=LoggerFactory.getLogger(ProductRequestExceptionHandler.class);
    @ExceptionHandler(ProductException.class)
    public ProblemDetail rejected(ProductException failure){return problem(failure.code().status(),failure.code().name(),failure.getMessage());}
    @ExceptionHandler(ProductOrderOutOfStockException.class)
    public ProblemDetail outOfStock(ProductOrderOutOfStockException failure){
        var value=problem(HttpStatus.CONFLICT,"PRODUCT_ORDER_OUT_OF_STOCK",failure.getMessage());
        value.setProperty("orderId",failure.orderId());
        value.setProperty("unavailableItems",failure.unavailableItems());
        value.setProperty("inventorySnapshot", "Observed during this payment attempt; stock is not reserved");
        return value;
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentNotValidException.class,ConstraintViolationException.class,
            HandlerMethodValidationException.class,MethodArgumentTypeMismatchException.class,IllegalArgumentException.class})
    public ProblemDetail invalid(){return problem(HttpStatus.BAD_REQUEST,"INVALID_PRODUCT_REQUEST","Invalid product request");}
    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail technical(DataAccessException failure){
        LOG.error("Product database operation failed type={}",failure.getClass().getSimpleName());
        return problem(failure instanceof TransientDataAccessException || failure instanceof DataAccessResourceFailureException ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.INTERNAL_SERVER_ERROR,
                "PRODUCT_TECHNICAL_FAILURE","Product operation encountered a database failure");
    }
    private ProblemDetail problem(HttpStatus status,String code,String message){var value=ProblemDetail.forStatusAndDetail(status,message);value.setProperty("code",code);return value;}
}
