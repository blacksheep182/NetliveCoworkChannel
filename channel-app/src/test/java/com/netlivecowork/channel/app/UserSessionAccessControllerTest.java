package com.netlivecowork.channel.app;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
/** An external page credential must not grant configuration authority. */
class UserSessionAccessControllerTest {
    @Test void formerExternalFacadeAlwaysRejectsConfiguration() {
        var response=new UserSessionAccessController().closed();
        assertEquals(403,response.getStatusCode().value());
        assertEquals("PERMISSION_DENIED",response.getBody().path("error").path("code").asText());
        assertTrue(response.getBody().path("error").path("message").asText().contains("CoWork"));
    }
}
