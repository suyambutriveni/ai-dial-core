package com.epam.aidial.core.storage.util;


import com.epam.aidial.core.storage.http.HttpException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class EtagHeaderTest {
    @Test
    void testEtag() {
        EtagHeader etag = EtagHeader.fromHeader("123", null);
        etag.validate("123");
    }

    @Test
    void testEtagWithQuotes() {
        EtagHeader etag = EtagHeader.fromHeader("\"123\"", null);
        etag.validate("123");
    }

    @Test
    void testEtagList() {
        EtagHeader etag = EtagHeader.fromHeader("\"123\",\"234\"", null);
        etag.validate("123");
        etag.validate("234");
    }

    @Test
    void testEtagAny() {
        EtagHeader etag = EtagHeader.fromHeader(EtagHeader.ANY_TAG, null);
        etag.validate("any");
    }

    @Test
    void testMissingEtag() {
        EtagHeader etag = EtagHeader.fromHeader(null, null);
        etag.validate("any");
    }

    @Test
    void testEtagMismatch() {
        EtagHeader etag = EtagHeader.fromHeader("123", null);
        assertThrows(HttpException.class, () -> etag.validate("234"));
    }

    @Test
    void testNoOverwrite() {
        EtagHeader etag = EtagHeader.fromHeader(null, EtagHeader.ANY_TAG);
        assertThrows(HttpException.class, () -> etag.validate("123"));
    }

    @Test
    void testIfMatchAndNoneIfMatch() {
        EtagHeader etag = EtagHeader.fromHeader("299,123", "235,326");
        etag.validate("123");
    }

    @Test
    void testIfMatchAnyAndNoneIfMatch() {
        EtagHeader etag = EtagHeader.fromHeader("*", "235,326");
        etag.validate("123");
    }

    @Test
    void testNoneIfMatchFail() {
        EtagHeader etag = EtagHeader.fromHeader(null, "235,326");
        assertThrows(HttpException.class, () -> etag.validate("326"));
    }

    @Test
    void testNoneIfMatchAnyFail() {
        EtagHeader etag = EtagHeader.fromHeader(null, "*");
        assertThrows(HttpException.class, () -> etag.validate("326"));
    }

    @Test
    void testNoneIfMatchPass() {
        EtagHeader etag = EtagHeader.fromHeader(null, "235,326");
        etag.validate("123");
    }

}