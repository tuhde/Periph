package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MCP4XXXPartsTest {

    @Test
    void namedClassesPresetThePart() {
        MCP4XXXFull[] chips = {
            new MCP4531Full(new MockConnection()),
            new MCP4532Full(new MockConnection()),
            new MCP4551Full(new MockConnection()),
            new MCP4552Full(new MockConnection()),
            new MCP4631Full(new MockConnection()),
            new MCP4632Full(new MockConnection()),
            new MCP4651Full(new MockConnection()),
            new MCP4652Full(new MockConnection()),
            new MCP4541Full(new MockConnection()),
            new MCP4542Full(new MockConnection()),
            new MCP4561Full(new MockConnection()),
            new MCP4562Full(new MockConnection()),
            new MCP4641Full(new MockConnection()),
            new MCP4642Full(new MockConnection()),
            new MCP4661Full(new MockConnection()),
            new MCP4662Full(new MockConnection())
        };
        String[] names = {
            "MCP4531",
            "MCP4532",
            "MCP4551",
            "MCP4552",
            "MCP4631",
            "MCP4632",
            "MCP4651",
            "MCP4652",
            "MCP4541",
            "MCP4542",
            "MCP4561",
            "MCP4562",
            "MCP4641",
            "MCP4642",
            "MCP4661",
            "MCP4662"
        };
        for (int i = 0; i < chips.length; i++) {
            MCP4XXXMinimal.PartInfo row = MCP4XXXMinimal.lookup(names[i]);
            assertEquals(row.channels, chips[i].channels(), names[i]);
            assertEquals(row.maxCode + 1, chips[i].steps(), names[i]);
            assertEquals(row.potentiometer, chips[i].isPotentiometer(), names[i]);
            assertEquals(row.nonvolatile, chips[i].isNonvolatile(), names[i]);
        }
        new MCP4532Minimal(new MockConnection());
    }
}
