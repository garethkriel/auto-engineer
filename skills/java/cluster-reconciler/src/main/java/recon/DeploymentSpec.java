package recon;

/** Declared desired state of one deployment. */
public record DeploymentSpec(String name, int replicas, String version, int cpu, int maxSurge, int maxUnavailable) {

    public DeploymentSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (replicas < 0) {
            throw new IllegalArgumentException("replicas must be >= 0");
        }
        if (cpu <= 0) {
            throw new IllegalArgumentException("cpu must be > 0");
        }
        if (maxSurge < 0 || maxUnavailable < 0) {
            throw new IllegalArgumentException("maxSurge and maxUnavailable must be >= 0");
        }
        if (maxSurge == 0 && maxUnavailable == 0) {
            throw new IllegalArgumentException("maxSurge and maxUnavailable must not both be 0");
        }
    }
}
