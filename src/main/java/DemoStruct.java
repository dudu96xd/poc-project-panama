import java.lang.foreign.*;
import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.JAVA_INT;

static final StructLayout POINT = MemoryLayout.structLayout(
        JAVA_INT.withName("x"),
        JAVA_INT.withName("y")
);
static final long X_OFF = POINT.byteOffset(groupElement("x"));
static final long Y_OFF = POINT.byteOffset(groupElement("y"));

void main() {
    try (Arena arena = Arena.ofConfined()) {
        MemorySegment p = arena.allocate(POINT);

        p.set(JAVA_INT, X_OFF, 10);
        p.set(JAVA_INT, Y_OFF, 20);

        int x = p.get(JAVA_INT, X_OFF);
        int y = p.get(JAVA_INT, Y_OFF);

        IO.println("Point = (" + x + ", " + y + ")");
    }
}
