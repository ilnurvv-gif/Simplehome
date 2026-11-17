package local.headunit.antiradar;

/** Камера фиксации скорости. dir = направление в градусах (0..359) или -1, limit = ограничение км/ч или 0. */
public final class Camera {
    public final double lat, lon;
    public final int limit, dir;
    public final boolean user;

    public Camera(double lat, double lon, int limit, int dir, boolean user) {
        this.lat = lat; this.lon = lon; this.limit = limit; this.dir = dir; this.user = user;
    }
}
