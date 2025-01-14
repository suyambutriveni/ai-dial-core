package com.epam.aidial.core.server.upstream;

import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
public class UpstreamRouteTest {

    @Mock
    private Vertx vertx;

    @Mock
    private Random generator;

    @Test
    void testUpstreamRouteWithRetry() {
        Model model = new Model();
        model.setName("model1");
        model.setUpstreams(List.of(
                new Upstream("endpoint1", null, null, 1, 1),
                new Upstream("endpoint2", null, null, 1, 1),
                new Upstream("endpoint3", null, null, 1, 1),
                new Upstream("endpoint4", null, null, 1, 1)
        ));

        UpstreamRouteProvider upstreamRouteProvider = new UpstreamRouteProvider(vertx, () -> generator);
        UpstreamRoute route = upstreamRouteProvider.get(model);
        assertNotNull(route.next());

        assertTrue(route.available());
        assertNotNull(route.get());
        assertEquals(1, route.getAttemptCount());

        route.fail(HttpStatus.BAD_GATEWAY, -1);
        assertNotNull(route.next());

        assertTrue(route.available());
        assertNotNull(route.get());
        assertEquals(2, route.getAttemptCount());

        route.fail(HttpStatus.BAD_GATEWAY, -1);
        assertNotNull(route.next());

        assertTrue(route.available());
        assertNotNull(route.get());
        assertEquals(3, route.getAttemptCount());

        route.fail(HttpStatus.BAD_GATEWAY, -1);
        route.next();

        assertTrue(route.available());
        assertNotNull(route.get());
        assertEquals(4, route.getAttemptCount());

        route.fail(HttpStatus.BAD_GATEWAY, -1);
        assertThrows(HttpException.class, route::next);

        // verify route reach max attempts
        assertFalse(route.available());
        assertNull(route.get());
        assertEquals(4, route.getAttemptCount());
    }

    @Test
    void testUpstreamRouteWithRetry2() {
        Model model = new Model();
        model.setName("model1");
        model.setUpstreams(List.of(
                new Upstream("endpoint1", null, null, 1, 1),
                new Upstream("endpoint2", null, null, 1, 1)
        ));

        UpstreamRouteProvider upstreamRouteProvider = new UpstreamRouteProvider(vertx, () -> generator);
        UpstreamRoute route = upstreamRouteProvider.get(model);
        assertNotNull(route.next());

        assertTrue(route.available());
        assertNotNull(route.get());
        assertEquals(1, route.getAttemptCount());

        route.fail(HttpStatus.TOO_MANY_REQUESTS, 30);
        assertNotNull(route.next());

        assertTrue(route.available());
        assertNotNull(route.get());
        assertEquals(2, route.getAttemptCount());

        route.fail(HttpStatus.TOO_MANY_REQUESTS, 30);
        assertThrows(HttpException.class, route::next);

        assertFalse(route.available());
        assertNull(route.get());
        assertEquals(2, route.getAttemptCount());
    }
}
