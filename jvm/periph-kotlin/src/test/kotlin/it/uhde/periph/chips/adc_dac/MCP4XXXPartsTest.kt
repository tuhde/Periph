package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MCP4XXXPartsTest {

    @Test
    fun namedClassesPresetThePart() {
        val chips = listOf(
            "MCP4531" to MCP4531Full(MockConnection()),
            "MCP4532" to MCP4532Full(MockConnection()),
            "MCP4551" to MCP4551Full(MockConnection()),
            "MCP4552" to MCP4552Full(MockConnection()),
            "MCP4631" to MCP4631Full(MockConnection()),
            "MCP4632" to MCP4632Full(MockConnection()),
            "MCP4651" to MCP4651Full(MockConnection()),
            "MCP4652" to MCP4652Full(MockConnection()),
            "MCP4541" to MCP4541Full(MockConnection()),
            "MCP4542" to MCP4542Full(MockConnection()),
            "MCP4561" to MCP4561Full(MockConnection()),
            "MCP4562" to MCP4562Full(MockConnection()),
            "MCP4641" to MCP4641Full(MockConnection()),
            "MCP4642" to MCP4642Full(MockConnection()),
            "MCP4661" to MCP4661Full(MockConnection()),
            "MCP4662" to MCP4662Full(MockConnection())
        )
        for ((name, chip) in chips) {
            val row = MCP4XXXMinimal.lookup(name)
            assertEquals(row.channels, chip.channels(), name)
            assertEquals(row.maxCode + 1, chip.steps(), name)
            assertEquals(row.potentiometer, chip.isPotentiometer(), name)
            assertEquals(row.nonvolatile, chip.isNonvolatile(), name)
        }
        MCP4532Minimal(MockConnection())
    }
}
