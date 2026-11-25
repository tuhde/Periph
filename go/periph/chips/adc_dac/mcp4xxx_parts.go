package adcdac

import "github.com/tuhde/Periph/go/periph/connection"

// Named constructors: one pair per MCP4XXX family member. They only preset the part and
// behave exactly like NewMCP4XXXMinimal / NewMCP4XXXFull with that part name; the connection
// must be bound to an address in the part's range.

// NewMCP4531Minimal creates an MCP4XXXMinimal for the MCP4531.
func NewMCP4531Minimal(t connection.RegisterConnection, rAB, rWiper float64) *MCP4XXXMinimal {
	d, _ := NewMCP4XXXMinimal(t, "MCP4531", rAB, rWiper)
	return d
}

// NewMCP4531Full creates an MCP4XXXFull for the MCP4531; hvc and hvcSettleMs as in NewMCP4XXXFull.
func NewMCP4531Full(t connection.RegisterConnection, rAB, rWiper float64, hvc connection.OutputPin, hvcSettleMs int) *MCP4XXXFull {
	d, _ := NewMCP4XXXFull(t, "MCP4531", rAB, rWiper, hvc, hvcSettleMs)
	return d
}

// NewMCP4532Minimal creates an MCP4XXXMinimal for the MCP4532.
func NewMCP4532Minimal(t connection.RegisterConnection, rAB, rWiper float64) *MCP4XXXMinimal {
	d, _ := NewMCP4XXXMinimal(t, "MCP4532", rAB, rWiper)
	return d
}

// NewMCP4532Full creates an MCP4XXXFull for the MCP4532; hvc and hvcSettleMs as in NewMCP4XXXFull.
func NewMCP4532Full(t connection.RegisterConnection, rAB, rWiper float64, hvc connection.OutputPin, hvcSettleMs int) *MCP4XXXFull {
	d, _ := NewMCP4XXXFull(t, "MCP4532", rAB, rWiper, hvc, hvcSettleMs)
	return d
}

// NewMCP4551Minimal creates an MCP4XXXMinimal for the MCP4551.
func NewMCP4551Minimal(t connection.RegisterConnection, rAB, rWiper float64) *MCP4XXXMinimal {
	d, _ := NewMCP4XXXMinimal(t, "MCP4551", rAB, rWiper)
	return d
}

// NewMCP4551Full creates an MCP4XXXFull for the MCP4551; hvc and hvcSettleMs as in NewMCP4XXXFull.
func NewMCP4551Full(t connection.RegisterConnection, rAB, rWiper float64, hvc connection.OutputPin, hvcSettleMs int) *MCP4XXXFull {
	d, _ := NewMCP4XXXFull(t, "MCP4551", rAB, rWiper, hvc, hvcSettleMs)
	return d
}

// NewMCP4552Minimal creates an MCP4XXXMinimal for the MCP4552.
func NewMCP4552Minimal(t connection.RegisterConnection, rAB, rWiper float64) *MCP4XXXMinimal {
	d, _ := NewMCP4XXXMinimal(t, "MCP4552", rAB, rWiper)
	return d
}

// NewMCP4552Full creates an MCP4XXXFull for the MCP4552; hvc and hvcSettleMs as in NewMCP4XXXFull.
func NewMCP4552Full(t connection.RegisterConnection, rAB, rWiper float64, hvc connection.OutputPin, hvcSettleMs int) *MCP4XXXFull {
	d, _ := NewMCP4XXXFull(t, "MCP4552", rAB, rWiper, hvc, hvcSettleMs)
	return d
}

// NewMCP4631Minimal creates an MCP4XXXMinimal for the MCP4631.
func NewMCP4631Minimal(t connection.RegisterConnection, rAB, rWiper float64) *MCP4XXXMinimal {
	d, _ := NewMCP4XXXMinimal(t, "MCP4631", rAB, rWiper)
	return d
}

// NewMCP4631Full creates an MCP4XXXFull for the MCP4631; hvc and hvcSettleMs as in NewMCP4XXXFull.
func NewMCP4631Full(t connection.RegisterConnection, rAB, rWiper float64, hvc connection.OutputPin, hvcSettleMs int) *MCP4XXXFull {
	d, _ := NewMCP4XXXFull(t, "MCP4631", rAB, rWiper, hvc, hvcSettleMs)
	return d
}

// NewMCP4632Minimal creates an MCP4XXXMinimal for the MCP4632.
func NewMCP4632Minimal(t connection.RegisterConnection, rAB, rWiper float64) *MCP4XXXMinimal {
	d, _ := NewMCP4XXXMinimal(t, "MCP4632", rAB, rWiper)
	return d
}

// NewMCP4632Full creates an MCP4XXXFull for the MCP4632; hvc and hvcSettleMs as in NewMCP4XXXFull.
func NewMCP4632Full(t connection.RegisterConnection, rAB, rWiper float64, hvc connection.OutputPin, hvcSettleMs int) *MCP4XXXFull {
	d, _ := NewMCP4XXXFull(t, "MCP4632", rAB, rWiper, hvc, hvcSettleMs)
	return d
}

// NewMCP4651Minimal creates an MCP4XXXMinimal for the MCP4651.
func NewMCP4651Minimal(t connection.RegisterConnection, rAB, rWiper float64) *MCP4XXXMinimal {
	d, _ := NewMCP4XXXMinimal(t, "MCP4651", rAB, rWiper)
	return d
}

// NewMCP4651Full creates an MCP4XXXFull for the MCP4651; hvc and hvcSettleMs as in NewMCP4XXXFull.
func NewMCP4651Full(t connection.RegisterConnection, rAB, rWiper float64, hvc connection.OutputPin, hvcSettleMs int) *MCP4XXXFull {
	d, _ := NewMCP4XXXFull(t, "MCP4651", rAB, rWiper, hvc, hvcSettleMs)
	return d
}

// NewMCP4652Minimal creates an MCP4XXXMinimal for the MCP4652.
func NewMCP4652Minimal(t connection.RegisterConnection, rAB, rWiper float64) *MCP4XXXMinimal {
	d, _ := NewMCP4XXXMinimal(t, "MCP4652", rAB, rWiper)
	return d
}

// NewMCP4652Full creates an MCP4XXXFull for the MCP4652; hvc and hvcSettleMs as in NewMCP4XXXFull.
func NewMCP4652Full(t connection.RegisterConnection, rAB, rWiper float64, hvc connection.OutputPin, hvcSettleMs int) *MCP4XXXFull {
	d, _ := NewMCP4XXXFull(t, "MCP4652", rAB, rWiper, hvc, hvcSettleMs)
	return d
}

// NewMCP4541Minimal creates an MCP4XXXMinimal for the MCP4541.
func NewMCP4541Minimal(t connection.RegisterConnection, rAB, rWiper float64) *MCP4XXXMinimal {
	d, _ := NewMCP4XXXMinimal(t, "MCP4541", rAB, rWiper)
	return d
}

// NewMCP4541Full creates an MCP4XXXFull for the MCP4541; hvc and hvcSettleMs as in NewMCP4XXXFull.
func NewMCP4541Full(t connection.RegisterConnection, rAB, rWiper float64, hvc connection.OutputPin, hvcSettleMs int) *MCP4XXXFull {
	d, _ := NewMCP4XXXFull(t, "MCP4541", rAB, rWiper, hvc, hvcSettleMs)
	return d
}

// NewMCP4542Minimal creates an MCP4XXXMinimal for the MCP4542.
func NewMCP4542Minimal(t connection.RegisterConnection, rAB, rWiper float64) *MCP4XXXMinimal {
	d, _ := NewMCP4XXXMinimal(t, "MCP4542", rAB, rWiper)
	return d
}

// NewMCP4542Full creates an MCP4XXXFull for the MCP4542; hvc and hvcSettleMs as in NewMCP4XXXFull.
func NewMCP4542Full(t connection.RegisterConnection, rAB, rWiper float64, hvc connection.OutputPin, hvcSettleMs int) *MCP4XXXFull {
	d, _ := NewMCP4XXXFull(t, "MCP4542", rAB, rWiper, hvc, hvcSettleMs)
	return d
}

// NewMCP4561Minimal creates an MCP4XXXMinimal for the MCP4561.
func NewMCP4561Minimal(t connection.RegisterConnection, rAB, rWiper float64) *MCP4XXXMinimal {
	d, _ := NewMCP4XXXMinimal(t, "MCP4561", rAB, rWiper)
	return d
}

// NewMCP4561Full creates an MCP4XXXFull for the MCP4561; hvc and hvcSettleMs as in NewMCP4XXXFull.
func NewMCP4561Full(t connection.RegisterConnection, rAB, rWiper float64, hvc connection.OutputPin, hvcSettleMs int) *MCP4XXXFull {
	d, _ := NewMCP4XXXFull(t, "MCP4561", rAB, rWiper, hvc, hvcSettleMs)
	return d
}

// NewMCP4562Minimal creates an MCP4XXXMinimal for the MCP4562.
func NewMCP4562Minimal(t connection.RegisterConnection, rAB, rWiper float64) *MCP4XXXMinimal {
	d, _ := NewMCP4XXXMinimal(t, "MCP4562", rAB, rWiper)
	return d
}

// NewMCP4562Full creates an MCP4XXXFull for the MCP4562; hvc and hvcSettleMs as in NewMCP4XXXFull.
func NewMCP4562Full(t connection.RegisterConnection, rAB, rWiper float64, hvc connection.OutputPin, hvcSettleMs int) *MCP4XXXFull {
	d, _ := NewMCP4XXXFull(t, "MCP4562", rAB, rWiper, hvc, hvcSettleMs)
	return d
}

// NewMCP4641Minimal creates an MCP4XXXMinimal for the MCP4641.
func NewMCP4641Minimal(t connection.RegisterConnection, rAB, rWiper float64) *MCP4XXXMinimal {
	d, _ := NewMCP4XXXMinimal(t, "MCP4641", rAB, rWiper)
	return d
}

// NewMCP4641Full creates an MCP4XXXFull for the MCP4641; hvc and hvcSettleMs as in NewMCP4XXXFull.
func NewMCP4641Full(t connection.RegisterConnection, rAB, rWiper float64, hvc connection.OutputPin, hvcSettleMs int) *MCP4XXXFull {
	d, _ := NewMCP4XXXFull(t, "MCP4641", rAB, rWiper, hvc, hvcSettleMs)
	return d
}

// NewMCP4642Minimal creates an MCP4XXXMinimal for the MCP4642.
func NewMCP4642Minimal(t connection.RegisterConnection, rAB, rWiper float64) *MCP4XXXMinimal {
	d, _ := NewMCP4XXXMinimal(t, "MCP4642", rAB, rWiper)
	return d
}

// NewMCP4642Full creates an MCP4XXXFull for the MCP4642; hvc and hvcSettleMs as in NewMCP4XXXFull.
func NewMCP4642Full(t connection.RegisterConnection, rAB, rWiper float64, hvc connection.OutputPin, hvcSettleMs int) *MCP4XXXFull {
	d, _ := NewMCP4XXXFull(t, "MCP4642", rAB, rWiper, hvc, hvcSettleMs)
	return d
}

// NewMCP4661Minimal creates an MCP4XXXMinimal for the MCP4661.
func NewMCP4661Minimal(t connection.RegisterConnection, rAB, rWiper float64) *MCP4XXXMinimal {
	d, _ := NewMCP4XXXMinimal(t, "MCP4661", rAB, rWiper)
	return d
}

// NewMCP4661Full creates an MCP4XXXFull for the MCP4661; hvc and hvcSettleMs as in NewMCP4XXXFull.
func NewMCP4661Full(t connection.RegisterConnection, rAB, rWiper float64, hvc connection.OutputPin, hvcSettleMs int) *MCP4XXXFull {
	d, _ := NewMCP4XXXFull(t, "MCP4661", rAB, rWiper, hvc, hvcSettleMs)
	return d
}

// NewMCP4662Minimal creates an MCP4XXXMinimal for the MCP4662.
func NewMCP4662Minimal(t connection.RegisterConnection, rAB, rWiper float64) *MCP4XXXMinimal {
	d, _ := NewMCP4XXXMinimal(t, "MCP4662", rAB, rWiper)
	return d
}

// NewMCP4662Full creates an MCP4XXXFull for the MCP4662; hvc and hvcSettleMs as in NewMCP4XXXFull.
func NewMCP4662Full(t connection.RegisterConnection, rAB, rWiper float64, hvc connection.OutputPin, hvcSettleMs int) *MCP4XXXFull {
	d, _ := NewMCP4XXXFull(t, "MCP4662", rAB, rWiper, hvc, hvcSettleMs)
	return d
}
