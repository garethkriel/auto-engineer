package ae.proto;

/** One field of a {@link MessageType}. */
public final class Field {
    private final String name;
    private final int number;
    private final Kind kind;
    private final boolean repeated;
    private MessageType message;

    Field(String name, int number, Kind kind, boolean repeated) {
        this.name = name;
        this.number = number;
        this.kind = kind;
        this.repeated = repeated;
    }

    void bind(MessageType target) {
        this.message = target;
    }

    public String name() {
        return name;
    }

    public int number() {
        return number;
    }

    public Kind kind() {
        return kind;
    }

    public boolean repeated() {
        return repeated;
    }

    /** The referenced message type for {@link Kind#MESSAGE} fields, otherwise null. */
    public MessageType message() {
        return message;
    }

    /** Whether values of this field are written packed. */
    boolean packed() {
        return repeated && kind.packable();
    }
}
