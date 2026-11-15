// One ticking table. Every variable assigned here shows up in the web IDE and is readable from
// the host with EmbeddedDeephaven.table(name).

// `host` holds the objects the host application bound. This one is a plain string.
greeting = host.greeting

// A row every second. The formula is compiled by Deephaven at run time, which is the step that
// fails inside a fat jar (finding F6).
hello = timeTable("PT1S").update("Row = ii", "Message = greeting")
