package com.shopcopilot.business.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

class PageQueryTest {

    @Test
    void should_useDefaultPageAndSize() {
        PageQuery query = new PageQuery();

        assertEquals(1, query.getPage());
        assertEquals(20, query.getSize());
        assertEquals(1, query.toPage().getCurrent());
        assertEquals(20, query.toPage().getSize());
    }

    @Test
    void should_rejectPageBelowOne() {
        PageQuery query = new PageQuery();
        query.setPage(0);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertFalse(validator.validate(query).isEmpty());
        }
    }

    @Test
    void should_rejectSizeAboveOneHundred() {
        PageQuery query = new PageQuery();
        query.setSize(101);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertFalse(validator.validate(query).isEmpty());
        }
    }

    @Test
    void should_acceptBoundaryValues() {
        PageQuery query = new PageQuery();
        query.setPage(1);
        query.setSize(100);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertTrue(validator.validate(query).isEmpty());
        }
    }
}
