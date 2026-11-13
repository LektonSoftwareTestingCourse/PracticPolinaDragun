package com.processing.service;

import com.processing.config.SwitchProperties;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class RoutingServiceTest {
    private RoutingService routing() {
        return new RoutingService(new SwitchProperties("test", Map.of("400000", "ISS001"),
                null, null, null, null, null, null));
    }


    @Test
    void shouldReturnIssuerWhenPanHasKnownBin() {

        var service = routing();

        var issuer = service.getIssuerIdByPan("4000001234567890");

        assertThat(issuer).isEqualTo("ISS001");
    }


    @Test
    void shouldRejectPanWhenPanIsShorterThanBin() {

        var service = routing();

        var thrown = catchThrowable(() -> service.getIssuerIdByPan("40000"));

        assertThat(thrown).isInstanceOf(UnknownBinException.class);
    }
}
