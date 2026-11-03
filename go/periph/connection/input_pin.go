package connection

// Trigger selects which edge direction(s) invoke a handler registered via
// InputPin.OnEdge.
type Trigger int

const (
	Rising Trigger = iota
	Falling
	Change
)

// InputPin delivers edge notifications from a chip's INT line and reads its
// level.
//
// Go function values are not comparable, so there is no way to implement an
// OffEdge(handler) that matches a stored handler against the one passed in —
// the pattern every other language in this repository uses. OnEdge instead
// returns an unsubscribe closure directly, the idiomatic Go equivalent (the
// same shape as context.CancelFunc).
type InputPin interface {
	// OnEdge registers handler to run on each edge matching trigger and
	// returns a function that removes it. Multiple handlers may be
	// registered concurrently — e.g. several chips sharing one INT line.
	OnEdge(trigger Trigger, handler func()) (unsubscribe func())

	// IsHigh reads the electrical level of the line now. It is the raw
	// level — no inversion, no debounce; active-low signals are interpreted
	// by the chip driver — and does not disturb registered edge handlers.
	// Used for level signals a driver samples at a moment of its own choosing
	// (a BUSY output, a data-ready line).
	IsHigh() (bool, error)
}

// edgeHandlerEntry is the bookkeeping record shared by every InputPin
// implementation in this package that has a real edge signal to filter by
// direction (GpioInputPin on both Linux and TinyGo). PollingInputPin has no
// such signal and keeps its own simpler bookkeeping.
type edgeHandlerEntry struct {
	trigger Trigger
	fn      func()
}
