// host.greeting is a Spring bean that implements HostBinding.
greeting = host.greeting.text()
// A formula, so the test also proves that formulas compile on a Spring host's classpath.
hello = emptyTable(3).update("N = i + 1", "Text = greeting")
