package com.example.mapart.baritone;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RealBaritoneFacadeGoalConstructionTest {

    @Test
    void resolvesPreferredBlockPosAndRangeConstructor() throws Exception {
        Constructor<?> constructor = RealBaritoneFacade.resolveGoalNearConstructor(GoalNearWithBlockPosAndRange.class);
        assertArrayEquals(new Class<?>[]{BlockPos.class, int.class}, constructor.getParameterTypes());
    }

    @Test
    void resolvesFourIntConstructorWhenBlockPosConstructorMissing() throws Exception {
        Constructor<?> constructor = RealBaritoneFacade.resolveGoalNearConstructor(GoalNearWithFourInts.class);
        assertArrayEquals(new Class<?>[]{int.class, int.class, int.class, int.class}, constructor.getParameterTypes());
    }

    @Test
    void resolvesThreeIntConstructorAsLastFallback() throws Exception {
        Constructor<?> constructor = RealBaritoneFacade.resolveGoalNearConstructor(GoalNearWithThreeInts.class);
        assertArrayEquals(new Class<?>[]{int.class, int.class, int.class}, constructor.getParameterTypes());
    }

    @Test
    void throwsWhenNoSupportedConstructorExists() {
        assertThrows(NoSuchMethodException.class,
                () -> RealBaritoneFacade.resolveGoalNearConstructor(GoalNearUnsupported.class));
    }

    static class GoalNearWithBlockPosAndRange {
        public GoalNearWithBlockPosAndRange(BlockPos ignored, int range) {
        }
    }

    static class GoalNearWithFourInts {
        public GoalNearWithFourInts(int x, int y, int z, int range) {
        }
    }

    static class GoalNearWithThreeInts {
        public GoalNearWithThreeInts(int x, int y, int z) {
        }
    }

    static class GoalNearUnsupported {
        public GoalNearUnsupported(String unsupported) {
        }
    }
}
