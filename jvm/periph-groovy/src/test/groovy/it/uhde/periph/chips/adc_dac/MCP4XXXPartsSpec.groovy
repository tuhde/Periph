package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class MCP4XXXPartsSpec extends Specification {

    def "named classes preset the part"() {
        expect:
        def chip = chipFactory(new MockConnection())
        def row = MCP4XXXMinimal.lookup(name)
        chip.channels() == row.channels
        chip.steps() == row.maxCode + 1
        chip.isPotentiometer() == row.potentiometer
        chip.isNonvolatile() == row.nonvolatile

        where:
        name << ['MCP4531', 'MCP4532', 'MCP4551', 'MCP4552', 'MCP4631', 'MCP4632', 'MCP4651', 'MCP4652', 'MCP4541', 'MCP4542', 'MCP4561', 'MCP4562', 'MCP4641', 'MCP4642', 'MCP4661', 'MCP4662']
        chipFactory << [
                { c -> new MCP4531Full(c) },
                { c -> new MCP4532Full(c) },
                { c -> new MCP4551Full(c) },
                { c -> new MCP4552Full(c) },
                { c -> new MCP4631Full(c) },
                { c -> new MCP4632Full(c) },
                { c -> new MCP4651Full(c) },
                { c -> new MCP4652Full(c) },
                { c -> new MCP4541Full(c) },
                { c -> new MCP4542Full(c) },
                { c -> new MCP4561Full(c) },
                { c -> new MCP4562Full(c) },
                { c -> new MCP4641Full(c) },
                { c -> new MCP4642Full(c) },
                { c -> new MCP4661Full(c) },
                { c -> new MCP4662Full(c) }
        ]
    }

    def "minimal alias constructs"() {
        expect:
        new MCP4532Minimal(new MockConnection()) != null
    }
}
