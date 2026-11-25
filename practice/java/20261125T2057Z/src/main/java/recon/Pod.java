package recon;

/** Immutable snapshot of a pod; {@code node} is null while unbound. */
public record Pod(int id, String deployment, String version, int cpu, Phase phase, String node) {
}
