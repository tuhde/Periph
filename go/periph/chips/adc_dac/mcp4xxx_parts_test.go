package adcdac

import (
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

func TestMCP4XXXNamedConstructorsPresetThePart(t *testing.T) {
	ctors := map[string]func(connection.RegisterConnection) *MCP4XXXFull{
		"MCP4531": func(c connection.RegisterConnection) *MCP4XXXFull { return NewMCP4531Full(c, 10000, 75, nil, 1) },
		"MCP4532": func(c connection.RegisterConnection) *MCP4XXXFull { return NewMCP4532Full(c, 10000, 75, nil, 1) },
		"MCP4551": func(c connection.RegisterConnection) *MCP4XXXFull { return NewMCP4551Full(c, 10000, 75, nil, 1) },
		"MCP4552": func(c connection.RegisterConnection) *MCP4XXXFull { return NewMCP4552Full(c, 10000, 75, nil, 1) },
		"MCP4631": func(c connection.RegisterConnection) *MCP4XXXFull { return NewMCP4631Full(c, 10000, 75, nil, 1) },
		"MCP4632": func(c connection.RegisterConnection) *MCP4XXXFull { return NewMCP4632Full(c, 10000, 75, nil, 1) },
		"MCP4651": func(c connection.RegisterConnection) *MCP4XXXFull { return NewMCP4651Full(c, 10000, 75, nil, 1) },
		"MCP4652": func(c connection.RegisterConnection) *MCP4XXXFull { return NewMCP4652Full(c, 10000, 75, nil, 1) },
		"MCP4541": func(c connection.RegisterConnection) *MCP4XXXFull { return NewMCP4541Full(c, 10000, 75, nil, 1) },
		"MCP4542": func(c connection.RegisterConnection) *MCP4XXXFull { return NewMCP4542Full(c, 10000, 75, nil, 1) },
		"MCP4561": func(c connection.RegisterConnection) *MCP4XXXFull { return NewMCP4561Full(c, 10000, 75, nil, 1) },
		"MCP4562": func(c connection.RegisterConnection) *MCP4XXXFull { return NewMCP4562Full(c, 10000, 75, nil, 1) },
		"MCP4641": func(c connection.RegisterConnection) *MCP4XXXFull { return NewMCP4641Full(c, 10000, 75, nil, 1) },
		"MCP4642": func(c connection.RegisterConnection) *MCP4XXXFull { return NewMCP4642Full(c, 10000, 75, nil, 1) },
		"MCP4661": func(c connection.RegisterConnection) *MCP4XXXFull { return NewMCP4661Full(c, 10000, 75, nil, 1) },
		"MCP4662": func(c connection.RegisterConnection) *MCP4XXXFull { return NewMCP4662Full(c, 10000, 75, nil, 1) },
	}
	for _, row := range MCP4XXXParts() {
		ctor, ok := ctors[row.Name]
		if !ok {
			t.Errorf("no named constructor for %s", row.Name)
			continue
		}
		chip := ctor(newMCP4XXXMock(row.MaxCode))
		if chip.Channels() != row.Channels || chip.Steps() != row.MaxCode+1 ||
			chip.IsPotentiometer() != row.Potentiometer || chip.IsNonvolatile() != row.Nonvolatile {
			t.Errorf("%s: constructor does not preset the part", row.Name)
		}
		if chip.desc.Name != row.Name {
			t.Errorf("%s: desc = %s", row.Name, chip.desc.Name)
		}
	}
	if m := NewMCP4532Minimal(newMCP4XXXMock(128), 10000, 75); m.desc.Name != "MCP4532" {
		t.Errorf("minimal constructor desc = %s", m.desc.Name)
	}
}
