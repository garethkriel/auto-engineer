package ae.swim;

import static ae.swim.Status.ALIVE;
import static ae.swim.Status.DEAD;
import static ae.swim.Status.SUSPECT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class UpdateTest {
    // Literal table: new status/inc, old status/inc, does new override old?
    @ParameterizedTest
    @CsvSource({
        "ALIVE,1,ALIVE,0,true",
        "ALIVE,0,ALIVE,0,false",
        "ALIVE,0,ALIVE,1,false",
        "ALIVE,1,SUSPECT,0,true",
        "ALIVE,1,SUSPECT,1,false",
        "ALIVE,0,SUSPECT,1,false",
        "SUSPECT,0,ALIVE,0,true",
        "SUSPECT,1,ALIVE,0,true",
        "SUSPECT,0,ALIVE,1,false",
        "SUSPECT,1,SUSPECT,0,true",
        "SUSPECT,1,SUSPECT,1,false",
        "SUSPECT,0,SUSPECT,1,false",
        "DEAD,0,ALIVE,5,true",
        "DEAD,0,SUSPECT,5,true",
        "DEAD,0,DEAD,0,false",
        "DEAD,9,DEAD,0,false",
        "ALIVE,99,DEAD,0,false",
        "SUSPECT,99,DEAD,0,false"
    })
    void overrideTable(Status ns, int ni, Status os, int oi, boolean expected) {
        assertEquals(expected, new Update("n", ns, ni).overrides(new Update("n", os, oi)));
    }

    @Test
    void rejectsNegativeIncarnationAndNulls() {
        assertThrows(IllegalArgumentException.class, () -> new Update("n", ALIVE, -1));
        assertThrows(NullPointerException.class, () -> new Update(null, ALIVE, 0));
        assertThrows(NullPointerException.class, () -> new Update("n", null, 0));
    }

    @Test
    void overridingDifferentNodesIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new Update("a", SUSPECT, 1).overrides(new Update("b", ALIVE, 0)));
    }

    @Test
    void configValidation() {
        assertThrows(IllegalArgumentException.class, () -> new Config(4, 4, 1, 10, 3, 2));
        assertThrows(IllegalArgumentException.class, () -> new Config(8, 0, 1, 10, 3, 2));
        assertThrows(IllegalArgumentException.class, () -> new Config(8, 3, -1, 10, 3, 2));
        assertThrows(IllegalArgumentException.class, () -> new Config(8, 3, 1, 0, 3, 2));
        assertThrows(IllegalArgumentException.class, () -> new Config(8, 3, 1, 10, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> new Config(8, 3, 1, 10, 3, 0));
    }
}
