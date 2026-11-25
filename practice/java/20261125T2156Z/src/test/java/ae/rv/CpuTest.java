package ae.rv;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CpuTest {
    private static Cpu boot(String src, int mem) {
        Cpu c = new Cpu(mem);
        c.load(Assembler.assemble(src).words());
        return c;
    }

    private static RunResult run(String src) {
        return boot(src, 4096).run(1_000_000);
    }

    /** Executes `op x3, x1, x2` with the given operands and returns x3. */
    private static int alu(String op, int a, int b) {
        Cpu c = boot("li x1, " + (a & 0xFFFFFFFFL) + "\nli x2, " + (b & 0xFFFFFFFFL) + "\n" + op + " x3, x1, x2\nebreak", 256);
        RunResult r = c.run(100);
        assertEquals(Halt.BREAK, r.halt());
        return c.reg(3);
    }

    private static int bi(BigInteger v) {
        return v.intValue(); // low 32 bits
    }

    @Test
    void exitCodeAndSteps() {
        RunResult r = run("li a0, 42\nli a7, 93\necall");
        assertEquals(Halt.EXIT, r.halt());
        assertEquals(42, r.exitCode());
        assertEquals(3, r.steps());
    }

    @Test
    void printAndPutchar() {
        RunResult r = run("li a0, -7\nli a7, 1\necall\nli a0, 65\nli a7, 11\necall\nli a0, 0x141\necall\nli a7, 93\necall");
        assertEquals("-7\nAA", r.output());
        assertEquals(0x141, r.exitCode());
    }

    @Test
    void x0IsHardwiredToZero() {
        Cpu c = boot("addi x0, x0, 5\nadd x1, x0, x0\nlui x0, 1\njal x0, 4\nebreak", 256);
        c.run(100);
        assertEquals(0, c.reg(0));
        assertEquals(0, c.reg(1));
    }

    @Test
    void spStartsAtTopOfMemory() {
        assertEquals(4096, new Cpu(4096).reg(2));
    }

    @Test
    void arithmeticEdges() {
        assertEquals(Integer.MIN_VALUE, alu("add", Integer.MAX_VALUE, 1));
        assertEquals(Integer.MAX_VALUE, alu("sub", Integer.MIN_VALUE, 1));
        assertEquals(1, alu("slt", -1, 0));
        assertEquals(0, alu("sltu", -1, 0));
        assertEquals(1, alu("sltu", 0, -1));
        assertEquals(0x80000000, alu("sll", 1, 31));
        assertEquals(2, alu("sll", 1, 33)); // shift amount uses low 5 bits
        assertEquals(0x7FFFFFFF, alu("srl", -1, 1));
        assertEquals(-1, alu("sra", -2, 1));
        assertEquals(-1, alu("sra", Integer.MIN_VALUE, 31));
        assertEquals(1, alu("srl", Integer.MIN_VALUE, 31));
        assertEquals(0xF0, alu("xor", 0xFF, 0x0F));
        assertEquals(0xFF, alu("or", 0xF0, 0x0F));
        assertEquals(0x0F, alu("and", 0x1F, 0x0F));
    }

    @Test
    void divisionEdges() {
        assertEquals(-1, alu("div", 5, 0));
        assertEquals(-1, alu("divu", 5, 0));
        assertEquals(5, alu("rem", 5, 0));
        assertEquals(5, alu("remu", 5, 0));
        assertEquals(Integer.MIN_VALUE, alu("div", Integer.MIN_VALUE, -1));
        assertEquals(0, alu("rem", Integer.MIN_VALUE, -1));
        assertEquals(-3, alu("div", -7, 2));   // truncates toward zero
        assertEquals(-1, alu("rem", -7, 2));   // sign follows dividend
        assertEquals(-3, alu("div", 7, -2)); // 7 / -2 = -3.5 truncates to -3
        assertEquals(1, alu("rem", 7, -2));
        assertEquals(0x7FFFFFFF, alu("divu", -1, 2));
        assertEquals(1, alu("remu", -1, 2));
    }

    @Test
    void multiplicationAgainstBigInteger() {
        Random r = new Random(99);
        int[] specials = {0, 1, -1, 2, Integer.MIN_VALUE, Integer.MAX_VALUE, 0x10000, -0x10000};
        for (int n = 0; n < 300; n++) {
            int a = n < 64 ? specials[n % 8] : r.nextInt();
            int b = n < 64 ? specials[n / 8] : r.nextInt();
            BigInteger sa = BigInteger.valueOf(a), sb = BigInteger.valueOf(b);
            BigInteger ua = BigInteger.valueOf(a & 0xFFFFFFFFL), ub = BigInteger.valueOf(b & 0xFFFFFFFFL);
            String ctx = a + "," + b;
            assertEquals(bi(sa.multiply(sb)), alu("mul", a, b), "mul " + ctx);
            assertEquals(bi(sa.multiply(sb).shiftRight(32)), alu("mulh", a, b), "mulh " + ctx);
            assertEquals(bi(sa.multiply(ub).shiftRight(32)), alu("mulhsu", a, b), "mulhsu " + ctx);
            assertEquals(bi(ua.multiply(ub).shiftRight(32)), alu("mulhu", a, b), "mulhu " + ctx);
            if (b != 0) {
                assertEquals(bi(ua.divide(ub)), alu("divu", a, b), "divu " + ctx);
                assertEquals(bi(ua.mod(ub)), alu("remu", a, b), "remu " + ctx);
                if (!(a == Integer.MIN_VALUE && b == -1)) {
                    assertEquals(bi(sa.divide(sb)), alu("div", a, b), "div " + ctx);
                    assertEquals(bi(sa.remainder(sb)), alu("rem", a, b), "rem " + ctx);
                }
            }
        }
    }

    @Test
    void immediateOpsAndLiRoundTrip() {
        long[] values = {0, 1, -1, 2047, 2048, -2048, -2049, 0x7FFFF800L, 0x7FFFFFFFL, 0x80000000L, 0xFFFFFFFFL,
            0x12345FFFL, 0x12345800L, 0xFFFFF800L, 0xFFFFF7FFL};
        for (long v : values) {
            Cpu c = boot("li x1, " + v + "\nebreak", 256);
            c.run(10);
            assertEquals((int) v, c.reg(1), "li " + v);
        }
        Cpu c = boot("li x1, -16\nsrai x2, x1, 2\nsrli x3, x1, 28\nslli x4, x1, 1\nslti x5, x1, -15\n"
                + "sltiu x6, x1, -15\nandi x7, x1, 0x7F0\nori x8, x0, -2048\nxori x9, x1, -1\nebreak", 256);
        c.run(100);
        assertEquals(-4, c.reg(2));
        assertEquals(15, c.reg(3));
        assertEquals(-32, c.reg(4));
        assertEquals(1, c.reg(5));
        assertEquals(1, c.reg(6)); // -16 as unsigned < -15 as unsigned
        assertEquals(0x7F0 & -16, c.reg(7));
        assertEquals(-2048, c.reg(8));
        assertEquals(15, c.reg(9));
    }

    @Test
    void auipcAndJalLink() {
        Cpu c = boot("nop\nauipc x1, 1\njal x2, skip\nnop\nskip: jalr x3, 0(x2)\nebreak", 256);
        // pc: 0 nop; 4 auipc -> 4+0x1000; 8 jal -> x2=12, pc=16; 16 jalr -> x3=20, pc=12 (nop) then 16 again... bound steps
        RunResult r = c.run(8);
        assertEquals(Halt.STEP_LIMIT, r.halt());
        assertEquals(4 + 0x1000, c.reg(1));
        assertEquals(12, c.reg(2));
        assertEquals(20, c.reg(3));
    }

    @Test
    void jalrClearsLowBit() {
        Cpu c = boot("li x1, 13\njalr x0, 0(x1)\nnop\nnop\nebreak", 256); // target 12 -> second nop... li is 1 word
        // addresses: 0 li, 4 jalr -> (13 & ~1) = 12, 8 nop, 12 nop? words: li(0) jalr(4) nop(8) nop(12) ebreak(16)
        RunResult r = c.run(10);
        assertEquals(Halt.BREAK, r.halt());
        assertEquals(4, r.steps()); // li, jalr, nop(12), ebreak
    }

    @Test
    void loadsAndStoresWithSignAndZeroExtension() {
        Cpu c = boot("""
            li x1, 512
            li x2, 0x8081F2F3
            sw x2, 0(x1)
            lb x3, 0(x1)
            lbu x4, 0(x1)
            lh x5, 2(x1)
            lhu x6, 2(x1)
            lw x7, 0(x1)
            lb x8, 3(x1)
            sb x2, 8(x1)
            sh x2, 12(x1)
            lw x9, 8(x1)
            lw x10, 12(x1)
            ebreak
            """, 1024);
        c.run(100);
        assertEquals(0xFFFFFFF3, c.reg(3));
        assertEquals(0xF3, c.reg(4));
        assertEquals(0xFFFF8081, c.reg(5));
        assertEquals(0x8081, c.reg(6));
        assertEquals(0x8081F2F3, c.reg(7));
        assertEquals(0xFFFFFF80, c.reg(8));
        assertEquals(0xF3, c.reg(9));
        assertEquals(0xF2F3, c.reg(10));
        assertEquals((byte) 0xF3, c.memByte(512)); // little endian
        assertEquals((byte) 0x81, c.memByte(514));
    }

    @Test
    void trapsOnMisalignmentRangeAndIllegal() {
        assertThrows(Trap.class, () -> boot("li x1, 2\nlw x2, 0(x1)", 256).run(10));
        assertThrows(Trap.class, () -> boot("li x1, 1\nlh x2, 0(x1)", 256).run(10));
        assertThrows(Trap.class, () -> boot("li x1, 2\nsw x2, 0(x1)", 256).run(10));
        assertThrows(Trap.class, () -> boot("li x1, 256\nlb x2, 0(x1)", 256).run(10));
        assertThrows(Trap.class, () -> boot("li x1, 253\nlw x2, 0(x1)", 256).run(10));
        assertThrows(Trap.class, () -> boot("li x1, -4\nsw x2, 0(x1)", 256).run(10));
        assertThrows(Trap.class, () -> boot(".word 0", 256).run(10));
    }

    @Test
    void trapMessagesNameTheCause() {
        assertTrue(assertThrows(Trap.class, () -> boot(".word 0", 256).run(10)).getMessage().contains("illegal"));
        assertTrue(assertThrows(Trap.class, () -> boot("li x1, 2\nlw x2, 0(x1)", 256).run(10)).getMessage().contains("misaligned"));
        assertTrue(assertThrows(Trap.class, () -> boot("li x1, 256\nlb x2, 0(x1)", 256).run(10)).getMessage().contains("range"));
        assertTrue(assertThrows(Trap.class, () -> boot("li a7, 7\necall", 256).run(10)).getMessage().contains("ecall"));
    }

    @Test
    void fetchOutsideMemoryTraps() {
        // falls off the end of a 16-byte memory
        Trap t = assertThrows(Trap.class, () -> boot("nop\nnop\nnop\nnop", 16).run(100));
        assertTrue(t.getMessage().contains("range"), t.getMessage());
        // control: with an ebreak at the end it halts
        assertEquals(Halt.BREAK, boot("nop\nnop\nnop\nebreak", 16).run(100).halt());
        // target 2 is a misaligned fetch (message check: an illegal-word trap would also be a Trap)
        Trap m = assertThrows(Trap.class, () -> boot("jalr x0, 2(x0)\nnop", 16).run(5));
        assertTrue(m.getMessage().contains("misaligned"), m.getMessage());
    }

    @Test
    void programTooBigIsRejected() {
        Cpu c = new Cpu(8);
        assertThrows(IllegalArgumentException.class, () -> c.load(new int[3]));
        c.load(new int[2]);
    }

    @Test
    void stepLimitOnInfiniteLoop() {
        RunResult r = run("loop: j loop");
        assertEquals(Halt.STEP_LIMIT, boot("loop: j loop", 64).run(500).halt());
        assertEquals(500, boot("loop: j loop", 64).run(500).steps());
        assertEquals(Halt.STEP_LIMIT, r.halt());
    }

    @Test
    void fibonacci() {
        RunResult r = run("""
            li a0, 10        # n
            li t0, 0         # fib(0)
            li t1, 1         # fib(1)
            loop: beqz a0, done
              add t2, t0, t1
              mv t0, t1
              mv t1, t2
              addi a0, a0, -1
              j loop
            done: mv a0, t0
              li a7, 93
              ecall
            """);
        assertEquals(55, r.exitCode());
    }

    @Test
    void gcdByEuclid() {
        RunResult r = run("""
            li a0, 1071
            li a1, 462
            loop: beqz a1, done
              rem t0, a0, a1
              mv a0, a1
              mv a1, t0
              j loop
            done: li a7, 93
              ecall
            """);
        assertEquals(21, r.exitCode());
    }

    @Test
    void sumOneToHundred() {
        RunResult r = run("""
            li t0, 1
            li t1, 0
            li t2, 101
            loop: bge t0, t2, done
              add t1, t1, t0
              addi t0, t0, 1
              j loop
            done: mv a0, t1
              li a7, 93
              ecall
            """);
        assertEquals(5050, r.exitCode());
    }

    @Test
    void sieveCountsTwentyFivePrimesBelowHundred() {
        // flags at address 1024..1123 (1 = composite); count the zeros for 2..99
        RunResult r = boot("""
            li s0, 1024        # base
            li s1, 100         # N
            li t0, 2           # i
            outer: bge t0, s1, count
              add t1, s0, t0
              lbu t2, 0(t1)
              bnez t2, next
              mul t3, t0, t0   # j = i*i
            inner: bge t3, s1, next
              add t4, s0, t3
              li t5, 1
              sb t5, 0(t4)
              add t3, t3, t0
              j inner
            next: addi t0, t0, 1
              j outer
            count: li t0, 2
              li a0, 0
            cl: bge t0, s1, fin
              add t1, s0, t0
              lbu t2, 0(t1)
              bnez t2, skip
              addi a0, a0, 1
            skip: addi t0, t0, 1
              j cl
            fin: li a7, 93
              ecall
            """, 2048).run(1_000_000);
        assertEquals(Halt.EXIT, r.halt());
        assertEquals(25, r.exitCode());
    }

    @Test
    void bubbleSortInMemory() {
        Cpu c = boot("""
            li s0, 512
            li s1, 8
            # store 8 values: 5, -3, 9, 0, 9, -8, 2, 7
            li t0, 5
            sw t0, 0(s0)
            li t0, -3
            sw t0, 4(s0)
            li t0, 9
            sw t0, 8(s0)
            sw x0, 12(s0)
            sw t0, 16(s0)
            li t0, -8
            sw t0, 20(s0)
            li t0, 2
            sw t0, 24(s0)
            li t0, 7
            sw t0, 28(s0)
            li t0, 0            # pass counter
            pass: addi t6, s1, -1
              bge t0, t6, end
              li t1, 0
            cmp: sub t2, t6, t0
              bge t1, t2, nextpass
              slli t3, t1, 2
              add t3, t3, s0
              lw t4, 0(t3)
              lw t5, 4(t3)
              ble t4, t5, noswap
              sw t5, 0(t3)
              sw t4, 4(t3)
            noswap: addi t1, t1, 1
              j cmp
            nextpass: addi t0, t0, 1
              j pass
            end: ebreak
            """, 1024);
        RunResult r = c.run(100_000);
        assertEquals(Halt.BREAK, r.halt());
        int[] expected = {-8, -3, 0, 2, 5, 7, 9, 9};
        for (int k = 0; k < 8; k++) {
            assertEquals(expected[k], c.memWord(512 + 4 * k), "slot " + k);
        }
    }

    @Test
    void recursiveFactorialUsesTheStack() {
        RunResult r = boot("""
            li a0, 10
            jal ra, fact
            li a7, 93
            ecall
            fact: li t0, 2
              blt a0, t0, base
              addi sp, sp, -8
              sw ra, 4(sp)
              sw a0, 0(sp)
              addi a0, a0, -1
              jal ra, fact
              lw t1, 0(sp)
              lw ra, 4(sp)
              addi sp, sp, 8
              mul a0, a0, t1
              ret
            base: li a0, 1
              ret
            """, 512).run(10_000);
        assertEquals(3628800, r.exitCode());
    }

    @Test
    void stepApiMatchesRun() {
        Cpu c = boot("li x1, 3\naddi x1, x1, 4\nebreak", 64);
        assertNull(c.step());
        assertEquals(4, c.pc());
        assertNull(c.step());
        assertEquals(7, c.reg(1));
        assertEquals(Halt.BREAK, c.step());
    }
}
