package ae.acl;

/**
 * A protected resource.
 *
 * @param type kind of resource, never null ("" when unknown)
 * @param owner owner id, may be null
 * @param archived whether the resource is archived
 * @param sensitivity 0..3
 */
public record Resource(String type, String owner, boolean archived, int sensitivity) {
    public Resource {
        type = type == null ? "" : type;
    }

    /** True if {@code user} is the (non-null) owner. */
    public boolean ownedBy(String user) {
        return owner != null && owner.equals(user);
    }

    boolean validSensitivity() {
        return sensitivity >= 0 && sensitivity <= 3;
    }
}
