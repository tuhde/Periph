package connection

// OutputPin drives a chip's hardware enable or power pin.
type OutputPin interface {
	Set(high bool) error
}
