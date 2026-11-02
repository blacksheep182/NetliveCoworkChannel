package com.netlivecowork.channel.contract;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class InputAvailabilityTest {
    @Test void explicitInteractionCannotBeAdvertisedAsAnAllowedInput() {
        assertThrows(ChannelContractException.class,()->new InputAvailability(true,"continue","interaction_required"));
        assertThrows(ChannelContractException.class,()->new InputAvailability(false,"blocked","user_turn"));
        assertEquals("deferred",new InputAvailability(true,"deferred","children_pending").mode());
    }
}
