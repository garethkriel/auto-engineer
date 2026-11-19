package ae.stream;

/** An event that arrived too late for {@code window} (for sessions: its proto window). */
public record Dropped(Event event, Window window) { }
